package store

import (
	"testing"
	"time"

	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
)

// newMigrateDB 内存库 + 全表迁移（与 store.Open 相同的 AutoMigrate 列表）。
func newMigrateDB(t *testing.T) *gorm.DB {
	t.Helper()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if err := db.AutoMigrate(&OafPackage{}, &ServiceEntity{}, &ServiceEvent{},
		&PlatformConfigEntity{}, &PlatformConfigEvent{},
		&User{}, &Workspace{}, &WorkspaceMember{}, &ApiKey{}); err != nil {
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
	if err := db.AutoMigrate(&OafPackage{}); err != nil {
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
