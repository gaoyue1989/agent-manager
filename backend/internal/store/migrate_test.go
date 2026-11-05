package store

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
)

// newMigrateDB 内存库 + Backfill 触及的表迁移（store.Open AutoMigrate 列表的子集：
// 本文件用例涉及的实体，不含未回填的控制台域等表）。
func newMigrateDB(t *testing.T) *gorm.DB {
	t.Helper()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if err := db.AutoMigrate(&OafPackage{}, &ServiceEntity{}, &ServiceEvent{},
		&PlatformConfigEntity{}, &PlatformConfigEvent{},
		&User{}, &Workspace{}, &WorkspaceMember{}, &ApiKey{},
		&Skill{}, &SkillVersion{}, &SkillRelease{}, &SkillMarketSource{}, &SkillMarketItem{},
		&ExportOrderEntity{}, &ExportPackageMarkEntity{}, &WorkspaceRuntimeDefaultEntity{}); err != nil {
		t.Fatal(err)
	}
	return db
}

// TestBackfillSeedIdempotent 种子/回填幂等：跑两遍，第二遍零写入。
func TestBackfillSeedIdempotent(t *testing.T) {
	db := newMigrateDB(t)
	if err := Backfill(db, "admin", "init-pass"); err != nil {
		t.Fatalf("first backfill: %v", err)
	}
	var users []User
	if err := db.Find(&users).Error; err != nil {
		t.Fatal(err)
	}
	if len(users) != 1 || users[0].Username != "admin" || users[0].Role != "admin" {
		t.Fatalf("seed admin wrong: %+v", users)
	}
	if !CheckPassword(users[0].PasswordHash, "init-pass") {
		t.Fatal("bcrypt hash mismatch")
	}
	var ws Workspace
	if err := db.Where("slug = ?", "default").First(&ws).Error; err != nil {
		t.Fatalf("default workspace missing: %v", err)
	}
	if ws.OwnerUserID != users[0].ID {
		t.Fatalf("owner not seeded admin: %+v", ws)
	}
	var members int64
	db.Model(&WorkspaceMember{}).Where("workspace_id = ?", ws.ID).Count(&members)
	if members != 1 {
		t.Fatalf("owner membership missing: %d", members)
	}

	// 二次启动：零新增写入
	countAll := func() (int64, int64, int64) {
		var u, w, m int64
		db.Model(&User{}).Count(&u)
		db.Model(&Workspace{}).Count(&w)
		db.Model(&WorkspaceMember{}).Count(&m)
		return u, w, m
	}
	u0, w0, m0 := countAll()
	if err := Backfill(db, "admin", "init-pass"); err != nil {
		t.Fatalf("second backfill: %v", err)
	}
	u1, w1, m1 := countAll()
	if u0 != u1 || w0 != w1 || m0 != m1 {
		t.Fatalf("second backfill wrote rows: users %d->%d ws %d->%d members %d->%d",
			u0, u1, w0, w1, m0, m1)
	}
}

// TestBackfillSkipsWhenEnvEmpty ADMIN_INIT_* 缺失 → 不种子（warn 不阻断）。
func TestBackfillSkipsWhenEnvEmpty(t *testing.T) {
	db := newMigrateDB(t)
	if err := Backfill(db, "", ""); err != nil {
		t.Fatalf("backfill without env: %v", err)
	}
	var users int64
	db.Model(&User{}).Count(&users)
	if users != 0 {
		t.Fatal("no admin env should seed nothing")
	}
	// default 工作空间仍创建（owner=0）
	var ws int64
	db.Model(&Workspace{}).Where("slug = ?", "default").Count(&ws)
	if ws != 1 {
		t.Fatal("default workspace should exist")
	}
}

// TestBackfillAssignsDefaultWorkspace 未归属服务回填 default 空间（决议 2）。
func TestBackfillAssignsDefaultWorkspace(t *testing.T) {
	db := newMigrateDB(t)
	orphan := ServiceEntity{K8sName: "oaf-legacy", DisplayName: "legacy"}
	if err := db.Create(&orphan).Error; err != nil {
		t.Fatal(err)
	}
	if err := Backfill(db, "admin", "x"); err != nil {
		t.Fatal(err)
	}
	var ws Workspace
	db.Where("slug = ?", "default").First(&ws)
	var got ServiceEntity
	db.First(&got, orphan.ID)
	if got.WorkspaceID != ws.ID {
		t.Fatalf("orphan not assigned: %+v (ws=%d)", got, ws.ID)
	}
	// 公共包不回填（决议 7）
	pkg := OafPackage{Name: "p", Slug: "a/b", Version: "1.0.0"}
	db.Create(&pkg)
	_ = Backfill(db, "admin", "x")
	var gotPkg OafPackage
	db.First(&gotPkg, pkg.ID)
	if gotPkg.WorkspaceID != 0 {
		t.Fatal("public packages must keep workspace_id=0")
	}
}

// TestBackfillExportOrderMarkers issue #234：export_orders.marker 独立小列存量回填——
// 合法快照行回填 marker；坏 JSON 快照行容忍留空（不报错不落写）；连续两次 Backfill
// 第二次零写入（幂等，对写副作用严格成立）。
func TestBackfillExportOrderMarkers(t *testing.T) {
	db := newMigrateDB(t)
	good := ExportOrderEntity{ServiceID: 1, WorkspaceID: 1,
		Status: "built", RequestJSON: `{"marker":"1.0.0-e1","baseImage":"busybox:1.36"}`}
	bad := ExportOrderEntity{ServiceID: 1, WorkspaceID: 1,
		Status: "built", RequestJSON: `{"marker": 123, broken`}
	empty := ExportOrderEntity{ServiceID: 1, WorkspaceID: 1,
		Status: "built", RequestJSON: `{"baseImage":"busybox:1.36"}`}
	filled := ExportOrderEntity{ServiceID: 1, WorkspaceID: 1,
		Status: "built", Marker: "2.0.0-e2", RequestJSON: `{"marker":"2.0.0-e2"}`}
	for _, o := range []*ExportOrderEntity{&good, &bad, &empty, &filled} {
		if err := db.Create(o).Error; err != nil {
			t.Fatal(err)
		}
	}
	if err := Backfill(db, "", ""); err != nil {
		t.Fatalf("backfill: %v", err)
	}
	var gotGood, gotBad, gotEmpty, gotFilled ExportOrderEntity
	db.First(&gotGood, good.ID)
	db.First(&gotBad, bad.ID)
	db.First(&gotEmpty, empty.ID)
	db.First(&gotFilled, filled.ID)
	if gotGood.Marker != "1.0.0-e1" {
		t.Fatalf("good row marker = %q, want 1.0.0-e1", gotGood.Marker)
	}
	if gotBad.Marker != "" {
		t.Fatalf("bad snapshot row must tolerate empty marker, got %q", gotBad.Marker)
	}
	if gotEmpty.Marker != "" {
		t.Fatalf("empty-marker snapshot row must stay empty, got %q", gotEmpty.Marker)
	}
	if gotFilled.Marker != "2.0.0-e2" {
		t.Fatalf("already-filled row must keep its marker, got %q", gotFilled.Marker)
	}

	// 二跑幂等：捕获写 marker 列的 UPDATE 语句，坏快照行允许重扫但必须零 UPDATE
	// （已回填行不命中 Where，坏行解析失败不落写——幂等对写副作用严格成立）。
	// 断言按 marker 列过滤：Backfill 第 8 步归属对齐（issue #235）每轮仍会对
	// export_orders 发一条零命中的 workspace_id UPDATE（行内容不变，非本步写
	// 副作用，同既有步骤 3/4 的无条件 UPDATE 约定），不纳入本用例计数
	var markerUpdates int
	db.Callback().Update().After("gorm:update").Register("test_count_export_marker_updates",
		func(tx *gorm.DB) {
			sql := tx.Statement.SQL.String()
			if strings.Contains(sql, "export_orders") && strings.Contains(sql, "marker") {
				markerUpdates++
			}
		})
	if err := Backfill(db, "", ""); err != nil {
		t.Fatalf("second backfill: %v", err)
	}
	if markerUpdates != 0 {
		t.Fatalf("second backfill must issue zero export_orders UPDATE, got %d", markerUpdates)
	}
	var again ExportOrderEntity
	db.First(&again, good.ID)
	if again.Marker != "1.0.0-e1" {
		t.Fatalf("second backfill changed marker: %q", again.Marker)
	}
}

// TestBackfillAlignsExportAssetsToServiceWorkspace issue #235 存量死路回填：历史纳管只迁
// services.workspace_id，在 export_package_marks / export_orders 留下旧空间残留——Backfill
// 按 service_id 对齐为服务当前归属；二次调用零写入（幂等）。
func TestBackfillAlignsExportAssetsToServiceWorkspace(t *testing.T) {
	db := newMigrateDB(t)
	if err := db.AutoMigrate(&ExportPackageMarkEntity{}, &ExportOrderEntity{}); err != nil {
		t.Fatal(err)
	}
	if err := Backfill(db, "admin", "x"); err != nil {
		t.Fatal(err)
	}
	var def Workspace
	db.Where("slug = ?", "default").First(&def)
	// 业务空间（模拟已纳管目标）+ 服务 + 修复前残留：marks/orders 仍指 default
	biz := Workspace{Slug: "ws-biz0999", Name: "业务空间", Status: "active", QuotaJSON: "{}"}
	if err := db.Create(&biz).Error; err != nil {
		t.Fatal(err)
	}
	svc := ServiceEntity{K8sName: "oaf-exp-align", DisplayName: "exp-align", WorkspaceID: biz.ID}
	if err := db.Create(&svc).Error; err != nil {
		t.Fatal(err)
	}
	if err := db.Create(&ExportPackageMarkEntity{ServiceID: svc.ID, WorkspaceID: def.ID,
		Marker: "1.0.0-e1", PackageVersion: "1.0.0", Status: "frozen", FilesJSON: "[]"}).Error; err != nil {
		t.Fatal(err)
	}
	if err := db.Create(&ExportOrderEntity{ServiceID: svc.ID, WorkspaceID: def.ID,
		Status: "built", ManifestJSON: "{}"}).Error; err != nil {
		t.Fatal(err)
	}
	if err := Backfill(db, "admin", "x"); err != nil {
		t.Fatal(err)
	}
	var mark ExportPackageMarkEntity
	db.Where("service_id = ?", svc.ID).First(&mark)
	if mark.WorkspaceID != biz.ID {
		t.Fatalf("stale mark not aligned: workspace_id=%d want %d", mark.WorkspaceID, biz.ID)
	}
	var order ExportOrderEntity
	db.Where("service_id = ?", svc.ID).First(&order)
	if order.WorkspaceID != biz.ID {
		t.Fatalf("stale order not aligned: workspace_id=%d want %d", order.WorkspaceID, biz.ID)
	}
	// 幂等：再次 Backfill 零写入（行内容逐字段一致）
	snapshot := func() string {
		var ms []ExportPackageMarkEntity
		var os []ExportOrderEntity
		db.Order("id").Find(&ms)
		db.Order("id").Find(&os)
		b1, _ := json.Marshal(ms)
		b2, _ := json.Marshal(os)
		return string(b1) + "|" + string(b2)
	}
	before := snapshot()
	if err := Backfill(db, "admin", "x"); err != nil {
		t.Fatal(err)
	}
	if after := snapshot(); after != before {
		t.Fatalf("second backfill must be a no-op: before=%s after=%s", before, after)
	}
}

// TestBackfillAlignsExportAssetsNullWorkspace marks/orders 的 NULL 形态（AutoMigrate 对既有
// 表加列旧行填 NULL，同 services 坑，migrate.go 步骤 3 注释）也必须对齐为服务当前归属。
// 裸库自建 legacy 表（newMigrateDB 已含导出全表，无法复现「先建行后加列」的 NULL 形态）。
func TestBackfillAlignsExportAssetsNullWorkspace(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	svc := ServiceEntity{K8sName: "oaf-exp-null", DisplayName: "exp-null"}
	if err := db.AutoMigrate(&ServiceEntity{}); err != nil {
		t.Fatal(err)
	}
	if err := db.Create(&svc).Error; err != nil {
		t.Fatal(err)
	}
	// 模拟存量库：先建旧 schema 行（无 workspace_id 列）再补列（旧行 → NULL）
	type legacyMark struct {
		ID        uint   `gorm:"primaryKey"`
		ServiceID uint   `gorm:"index"`
		Marker    string `gorm:"size:64"`
		Status    string `gorm:"size:16"`
		CreatedAt time.Time
	}
	if err := db.Table("export_package_marks").AutoMigrate(&legacyMark{}); err != nil {
		t.Fatal(err)
	}
	type legacyOrder struct {
		ID        uint   `gorm:"primaryKey"`
		ServiceID uint   `gorm:"index"`
		Status    string `gorm:"size:24"`
		CreatedAt time.Time
		UpdatedAt time.Time
	}
	if err := db.Table("export_orders").AutoMigrate(&legacyOrder{}); err != nil {
		t.Fatal(err)
	}
	if err := db.Exec("INSERT INTO export_package_marks (id, service_id, marker, status, created_at) VALUES (1, ?, '1.0.0-e1', 'frozen', CURRENT_TIMESTAMP)", svc.ID).Error; err != nil {
		t.Fatal(err)
	}
	if err := db.Exec("INSERT INTO export_orders (id, service_id, status, created_at, updated_at) VALUES (1, ?, 'built', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", svc.ID).Error; err != nil {
		t.Fatal(err)
	}
	if err := db.AutoMigrate(&ExportPackageMarkEntity{}, &ExportOrderEntity{}, // 加列，旧行 workspace_id=NULL
		&User{}, &Workspace{}, &WorkspaceMember{}, &OafPackage{}, &SkillMarketSource{}); err != nil {
		t.Fatal(err)
	}
	var nullMarks, nullOrders int64
	db.Table("export_package_marks").Where("workspace_id IS NULL").Count(&nullMarks)
	db.Table("export_orders").Where("workspace_id IS NULL").Count(&nullOrders)
	if nullMarks != 1 || nullOrders != 1 {
		t.Fatalf("precondition: expect 1 NULL row per table, got marks=%d orders=%d", nullMarks, nullOrders)
	}
	if err := Backfill(db, "admin", "x"); err != nil {
		t.Fatal(err)
	}
	var def Workspace
	db.Where("slug = ?", "default").First(&def)
	var mark ExportPackageMarkEntity
	db.First(&mark, 1)
	if mark.WorkspaceID != def.ID {
		t.Fatalf("NULL mark not aligned: workspace_id=%d want %d", mark.WorkspaceID, def.ID)
	}
	var order ExportOrderEntity
	db.First(&order, 1)
	if order.WorkspaceID != def.ID {
		t.Fatalf("NULL order not aligned: workspace_id=%d want %d", order.WorkspaceID, def.ID)
	}
	// 二跑幂等
	if err := Backfill(db, "admin", "x"); err != nil {
		t.Fatal(err)
	}
}

func TestNewSlugShape(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 20; i++ {
		s := NewSlug()
		if len(s) != 11 || s[:3] != "ws-" {
			t.Fatalf("bad slug %q", s)
		}
		seen[s] = true
	}
	if len(seen) < 15 {
		t.Fatal("slug should be random")
	}
}

// TestBackfillHandlesNullWorkspaceColumn AutoMigrate 对既有表加列时旧行为 NULL（非 0），
// 回填必须同时覆盖两种形态（部署实测发现的缺口）。
func TestBackfillHandlesNullWorkspaceColumn(t *testing.T) {
	db := newMigrateDB(t)
	// 模拟存量库：先建旧 schema 行，再加列（旧行 → NULL）
	type legacy struct {
		ID          uint   `gorm:"primaryKey"`
		K8sName     string `gorm:"size:63;uniqueIndex"`
		DisplayName string `gorm:"size:128"`
	}
	if err := db.Table("services").AutoMigrate(&legacy{}); err != nil {
		t.Fatal(err)
	}
	if err := db.Exec("INSERT INTO services (id, k8s_name, display_name, created_at, updated_at) VALUES (1,'oaf-old','old',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").Error; err != nil {
		t.Fatal(err)
	}
	if err := db.AutoMigrate(&ServiceEntity{}); err != nil { // 加新列，旧行 workspace_id=NULL
		t.Fatal(err)
	}
	var nullCnt int64
	db.Table("services").Where("workspace_id IS NULL").Count(&nullCnt)
	if nullCnt != 1 {
		t.Fatalf("precondition: expect 1 NULL row, got %d", nullCnt)
	}
	if err := Backfill(db, "admin", "x"); err != nil {
		t.Fatalf("backfill: %v", err)
	}
	var ws Workspace
	db.Where("slug = ?", "default").First(&ws)
	var got ServiceEntity
	db.First(&got, 1)
	if got.WorkspaceID != ws.ID {
		t.Fatalf("NULL row not backfilled: workspace_id=%d want %d", got.WorkspaceID, ws.ID)
	}
	// 二跑幂等
	if err := Backfill(db, "admin", "x"); err != nil {
		t.Fatal(err)
	}
}

// TestBackfillNormalizesPackageNullWorkspace 存量包 workspace_id NULL → 0（公共包语义），
// 否则版本链/包列表查不到旧包（部署实测）。
func TestBackfillNormalizesPackageNullWorkspace(t *testing.T) {
	db := newMigrateDB(t)
	// 模拟存量：先建旧 schema 行再补列（旧行 NULL）
	type legacyPkg struct {
		ID   uint   `gorm:"primaryKey"`
		Slug string `gorm:"size:200"`
	}
	if err := db.Table("oaf_packages").AutoMigrate(&legacyPkg{}); err != nil {
		t.Fatal(err)
	}
	if err := db.Exec("INSERT INTO oaf_packages (id, slug, created_at) VALUES (1,'a/b',CURRENT_TIMESTAMP)").Error; err != nil {
		t.Fatal(err)
	}
	if err := db.AutoMigrate(&OafPackage{}, &SkillMarketSource{}); err != nil {
		t.Fatal(err)
	}
	if err := Backfill(db, "admin", "x"); err != nil {
		t.Fatal(err)
	}
	var got OafPackage
	db.First(&got, 1)
	if got.WorkspaceID != 0 {
		t.Fatalf("NULL package workspace should normalize to 0, got %d", got.WorkspaceID)
	}
}

// TestDedupPackageVersions issue #172：(slug, version) 重复行归一——保留最早一行，
// 后续行加 .fix{N} 后缀；幂等（二跑零变更）；归一后 AutoMigrate 唯一索引可建。
func TestDedupPackageVersions(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	// 模拟存量库：无唯一索引的旧表 + 重复行（历史黑盒测试产物）
	type legacyPkg struct {
		ID      uint      `gorm:"primaryKey"`
		Slug    string    `gorm:"size:200"`
		Version string    `gorm:"size:32"`
		Created time.Time `gorm:"column:created_at;autoCreateTime"`
	}
	if err := db.Table("oaf_packages").AutoMigrate(&legacyPkg{}); err != nil {
		t.Fatal(err)
	}
	rows := []struct {
		id  uint
		ver string
	}{
		{1, "1.0.0"}, {2, "1.0.1"}, {3, "1.0.1"}, {4, "1.0.1"}, {5, "2.0.0"}, {6, "2.0.0"},
	}
	for _, r := range rows {
		if err := db.Exec("INSERT INTO oaf_packages (id, slug, version, created_at) VALUES (?, 'a/b', ?, CURRENT_TIMESTAMP)",
			r.id, r.ver).Error; err != nil {
			t.Fatal(err)
		}
	}
	if err := dedupPackageVersions(db); err != nil {
		t.Fatalf("dedup: %v", err)
	}
	want := map[uint]string{1: "1.0.0", 2: "1.0.1", 3: "1.0.1.fix1", 4: "1.0.1.fix2", 5: "2.0.0", 6: "2.0.0.fix1"}
	for id, ver := range want {
		var got OafPackage
		if err := db.First(&got, id).Error; err != nil {
			t.Fatalf("row %d missing: %v", id, err)
		}
		if got.Version != ver {
			t.Errorf("row %d version=%q want %q", id, got.Version, ver)
		}
	}
	// 幂等：二跑结果不变
	if err := dedupPackageVersions(db); err != nil {
		t.Fatal(err)
	}
	for id, ver := range want {
		var got OafPackage
		db.First(&got, id)
		if got.Version != ver {
			t.Errorf("second pass row %d version=%q want %q", id, got.Version, ver)
		}
	}
	// 归一后 AutoMigrate 唯一索引可建（模拟 Open 启动顺序）
	if err := db.AutoMigrate(&OafPackage{}); err != nil {
		t.Fatalf("unique index should apply after dedup: %v", err)
	}
}

// TestDedupPackageVersionsFreshDB 全新库（表尚未创建）不得报错：
// CI / 新环境首次启动时 dedup 先于 AutoMigrate 执行，必须跳过而非 SQL 报错。
func TestDedupPackageVersionsFreshDB(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if err := dedupPackageVersions(db); err != nil {
		t.Fatalf("fresh db dedup should be a no-op: %v", err)
	}
	if err := db.AutoMigrate(&OafPackage{}); err != nil {
		t.Fatalf("auto migrate after fresh dedup: %v", err)
	}
}
