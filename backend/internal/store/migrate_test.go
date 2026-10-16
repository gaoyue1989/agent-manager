package store

import (
	"testing"

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
