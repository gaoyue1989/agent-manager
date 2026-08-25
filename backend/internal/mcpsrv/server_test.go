package mcpsrv

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/glebarez/sqlite"
	"github.com/modelcontextprotocol/go-sdk/mcp"
	"gorm.io/gorm"

	k8sfake "agent-manager/backend/internal/k8s/k8sfake"
	"agent-manager/backend/internal/service"
	"agent-manager/backend/internal/store"
)

const testAgentsMD = `---
name: Demo
vendorKey: acme
agentKey: demo
version: 1.0.0
slug: acme/demo
description: demo agent package
author: "@acme"
license: MIT
---
body`

func newMCPClient(t *testing.T) (*mcp.ClientSession, *service.Core, func()) {
	t.Helper()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if sqlDB, e := db.DB(); e == nil {
		sqlDB.SetMaxOpenConns(1)
	}
	if err := db.AutoMigrate(&store.OafPackage{}, &store.ServiceEntity{}, &store.ServiceEvent{}); err != nil {
		t.Fatal(err)
	}
	dir, _ := os.MkdirTemp("", "oafmcp-*")
	core := service.NewCore(db, &store.FS{Root: dir}, k8sfake.New(), service.ConfigView{
		Namespace: "test", IngressClass: "nginx", IngressHost: "1.2.3.4", IngressPort: 30080,
		DefaultImage:  "agent-framework:latest",
		ImageAllowed:  func(img string) bool { return img == "agent-framework:latest" },
		RegisterRetry: 0,
	})

	ctx := context.Background()
	st, ct := mcp.NewInMemoryTransports()
	server := mcp.NewServer(&mcp.Implementation{Name: "oaf-platform", Version: "v1"}, nil)
	registerTools(server, core)
	if _, err := server.Connect(ctx, st, nil); err != nil {
		t.Fatal(err)
	}
	client := mcp.NewClient(&mcp.Implementation{Name: "test", Version: "0"}, nil)
	cs, err := client.Connect(ctx, ct, nil)
	if err != nil {
		t.Fatal(err)
	}
	return cs, core, func() { os.RemoveAll(dir) }
}

// call 调工具并解析文本内容为 map。
func call(t *testing.T, cs *mcp.ClientSession, name string, args map[string]any) (isError bool, out map[string]any, raw string) {
	t.Helper()
	res, err := cs.CallTool(context.Background(), &mcp.CallToolParams{Name: name, Arguments: args})
	if err != nil {
		t.Fatalf("%s protocol error: %v", name, err)
	}
	if len(res.Content) == 0 {
		t.Fatalf("%s empty content", name)
	}
	text := res.Content[0].(*mcp.TextContent).Text
	raw = text
	if res.IsError {
		return true, nil, text
	}
	var m map[string]any
	if err := json.Unmarshal([]byte(text), &m); err != nil {
		// 数组输出场景
		var list []map[string]any
		_ = json.Unmarshal([]byte(text), &list)
		m = map[string]any{"items": list}
	}
	return false, m, text
}

func TestMCPUploadAndListPackages(t *testing.T) {
	cs, _, done := newMCPClient(t)
	defer done()

	zipBytes := buildTestZip(map[string]string{"AGENTS.md": testAgentsMD})
	isErr, out, _ := call(t, cs, "upload_package", map[string]any{
		"filename":       "demo.zip",
		"content_base64": base64.StdEncoding.EncodeToString(zipBytes),
	})
	if isErr || out["packageId"] == nil || out["slug"] != "acme/demo" {
		t.Fatalf("upload_package: isErr=%v out=%v", isErr, out)
	}

	isErr, out, _ = call(t, cs, "list_packages", map[string]any{})
	if isErr || len(out["items"].([]map[string]any)) != 1 {
		t.Fatalf("list_packages: %v", out)
	}

	pkgID := uint(out["items"].([]map[string]any)[0]["packageId"].(float64))
	isErr, out, _ = call(t, cs, "get_package_detail", map[string]any{"packageId": pkgID})
	if isErr || !strings.Contains(out["agentsMd"].(string), "slug: acme/demo") {
		t.Fatalf("get_package_detail: %v", out["agentsMd"])
	}
}

func TestMCPPublishFlowAndReservedKey(t *testing.T) {
	cs, core, done := newMCPClient(t)
	defer done()
	pkgID := uploadViaMCP(t, cs)

	// 保留键 → IsError 文本（与 REST 行为一致）
	isErr, _, raw := call(t, cs, "publish_service", map[string]any{
		"packageId": pkgID,
		"env":       map[string]any{"AGENT_CONFIG_DIR": "/hack"},
	})
	if !isErr || !strings.Contains(raw, "reserved") {
		t.Fatalf("reserved key should be tool error: %v %s", isErr, raw)
	}
	// 镜像不在列表
	isErr, _, raw = call(t, cs, "publish_service", map[string]any{
		"packageId": pkgID, "image": "evil:x"})
	if !isErr || !strings.Contains(raw, "AVAILABLE_IMAGES") {
		t.Fatalf("image check: %v %s", isErr, raw)
	}

	// 正常发布：立即返回 deploying（长操作语义）
	isErr, out, _ := call(t, cs, "publish_service", map[string]any{"packageId": pkgID})
	if isErr || out["status"] != "deploying" || out["serviceId"] == nil {
		t.Fatalf("publish_service: %v", out)
	}
	svcID := uint(out["serviceId"].(float64))

	isErr, out, _ = call(t, cs, "get_service_status", map[string]any{"k8sName": "oaf-acme-demo"})
	if isErr || out["status"] == "" {
		t.Fatalf("get_service_status by k8sName: %v", out)
	}
	isErr, _, raw = call(t, cs, "get_service_status", map[string]any{})
	if !isErr || !strings.Contains(raw, "serviceId or k8sName is required") {
		t.Fatalf("missing selector should error: isErr=%v %s", isErr, raw)
	}

	isErr, out, _ = call(t, cs, "update_service_env", map[string]any{
		"serviceId": svcID, "env": map[string]any{"LOG_LEVEL": "warn"}})
	if isErr || out["status"] != "deploying" {
		t.Fatalf("update_service_env: %v", out)
	}

	// 换不存在的包允许报错（验证参数通路），错误文本应来自本调用而非残留变量
	isErr, _, rawRepublish := call(t, cs, "republish_service", map[string]any{
		"serviceId": svcID, "packageId": pkgID + 100})
	if isErr && !strings.Contains(rawRepublish, "not found") {
		t.Fatalf("republish unexpected: %s", rawRepublish)
	}

	isErr, out, _ = call(t, cs, "unpublish_service", map[string]any{"serviceId": svcID})
	if isErr || out["status"] != "stopped" {
		t.Fatalf("unpublish_service: %v", out)
	}
	// 未带确认名 → IsError 要求确认
	isErr, _, rawDel := call(t, cs, "delete_service", map[string]any{"serviceId": svcID})
	if !isErr || !strings.Contains(rawDel, "confirmation required") {
		t.Fatalf("delete without confirm should require confirmation: %v %s", isErr, rawDel)
	}
	// 带确认名 → 删除成功
	isErr, out, _ = call(t, cs, "delete_service",
		map[string]any{"serviceId": svcID, "confirm_k8s_name": "oaf-acme-demo"})
	if isErr || out["deleted"] != true {
		t.Fatalf("delete_service: %v", out)
	}
	_ = core
}

func TestMCPListServicesFilter(t *testing.T) {
	cs, _, done := newMCPClient(t)
	defer done()
	pkgID := uploadViaMCP(t, cs)
	call(t, cs, "publish_service", map[string]any{"packageId": pkgID})

	// 等待异步注册推进到终态（fake 集群不可达 → deploy_failed）
	deadline := 50
	for i := 0; i < deadline; i++ {
		_, out, _ := call(t, cs, "list_services", map[string]any{"status": "deploy_failed"})
		if items, _ := out["items"].([]map[string]any); len(items) == 1 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	isErr, out, _ := call(t, cs, "list_services", map[string]any{"status": "deploy_failed"})
	if isErr || len(out["items"].([]map[string]any)) != 1 {
		t.Fatalf("filter deploy_failed: %v", out)
	}
	isErr, out, _ = call(t, cs, "list_services", map[string]any{"status": "running"})
	if isErr || len(out["items"].([]map[string]any)) != 0 {
		t.Fatalf("filter running should be empty: %v", out)
	}
}

// ---- helpers ----

func uploadViaMCP(t *testing.T, cs *mcp.ClientSession) uint {
	t.Helper()
	zipBytes := buildTestZip(map[string]string{"AGENTS.md": testAgentsMD})
	_, out, _ := call(t, cs, "upload_package", map[string]any{
		"filename": "demo.zip", "content_base64": base64.StdEncoding.EncodeToString(zipBytes)})
	return uint(out["packageId"].(float64))
}

func buildTestZip(files map[string]string) []byte {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range files {
		w, _ := zw.Create(name)
		w.Write([]byte(content))
	}
	zw.Close()
	return buf.Bytes()
}
