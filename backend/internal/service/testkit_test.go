package service

import (
	"os"
	"testing"
	"time"

	"agent-manager/backend/internal/k8s/k8sfake"
	"github.com/glebarez/sqlite"
	"gorm.io/gorm"

	"agent-manager/backend/internal/store"
)

// newTestCore 组装 sqlite 内存库 + fake k8s + 临时目录的业务层。
func newTestCore(t *testing.T) (*Core, *k8sfake.FakeK8s, func()) {
	t.Helper()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if err := db.AutoMigrate(&store.OafPackage{}, &store.ServiceEntity{}, &store.ServiceEvent{}); err != nil {
		t.Fatal(err)
	}
	sqlDB, _ := db.DB()
	sqlDB.SetMaxOpenConns(1) // :memory: + 单连接：所有查询走同一连接，规避并发写问题
	dir, _ := os.MkdirTemp("", "oaffs-*")
	fk := k8sfake.New()
	core := &Core{
		DB: db, FS: &store.FS{Root: dir}, K8s: fk,
		Cfg: ConfigView{
			Namespace: "test", IngressClass: "nginx", IngressHost: "1.2.3.4", IngressPort: 30080,
			DefaultImage: "agent-framework:latest",
			ImageAllowed: func(img string) bool { return img == "agent-framework:latest" },
			ResCPU:       "250m", ResMem: "256Mi", LimCPU: "1", LimMem: "1Gi",
			RegisterTimeout: 400 * time.Millisecond,
			RegisterRetry:   0,
		},
	}
	core.Packages = &PackageService{DB: db, FS: core.FS}
	cleanup := func() { os.RemoveAll(dir) }
	return core, fk, cleanup
}

// waitForStatus 轮询等待服务到达目标状态（异步 goroutine 推进）。
func waitForStatus(t *testing.T, c *Core, id uint, want string) store.ServiceEntity {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		svc, err := c.Get(id)
		if err != nil {
			t.Fatal(err)
		}
		if svc.Status == want {
			return *svc
		}
		if time.Now().After(deadline) {
			t.Fatalf("status not %q in time, got %q", want, svc.Status)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

const testAgentsMD = `---
name: Demo
vendorKey: acme
agentKey: demo
version: 1.0.0
slug: acme/demo
description: demo agent package
author: "@acme"
license: MIT
mcpServers:
  - vendor: block
    server: fs
    version: 1.0.0
    configDir: mcp-configs/fs
---
body`

// uploadTestPkg 通过真实 zip 流程上传一个测试包。
func uploadTestPkg(t *testing.T, c *Core, slugSuffix string) *store.OafPackage {
	t.Helper()
	md := testAgentsMD
	if slugSuffix != "" {
		md = replaceSlug(testAgentsMD, slugSuffix)
	}
	zipBytes := buildTestZip(map[string]string{
		"AGENTS.md":                  md,
		"mcp-configs/fs/config.yaml": "connection:\n  type: sse\n",
	})
	rec, err := c.Packages.Upload("demo.zip", bytesReader(zipBytes))
	if err != nil {
		t.Fatalf("upload pkg: %v", err)
	}
	return rec
}
