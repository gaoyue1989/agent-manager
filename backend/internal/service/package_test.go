package service

import (
	"errors"
	"strings"
	"testing"
)

// 被服务引用的包禁止删除（REST DELETE /packages/:id 依赖 ErrPackageInUse → 400）。
func TestPackageDeleteInUse(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")

	if err := core.Packages.Delete(pkg.ID); !errors.Is(err, ErrPackageInUse) {
		t.Fatalf("expect ErrPackageInUse, got %v", err)
	}
	// 引用释放后可删除（目录已随最后一个服务清理，RemoveAll 幂等）
	if err := core.Delete(svc.ID); err != nil {
		t.Fatal(err)
	}
	if err := core.Packages.Delete(pkg.ID); err != nil {
		t.Fatalf("delete after last ref released: %v", err)
	}
	if _, err := core.Packages.Get(pkg.ID); err == nil {
		t.Fatal("package row should be gone")
	}
}

func TestPackageListKeywordFilter(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	uploadTestPkg(t, core, "")
	uploadTestPkg(t, core, "2")

	all, err := core.Packages.List("")
	if err != nil || len(all) != 2 {
		t.Fatalf("list all: %v n=%d", err, len(all))
	}
	// slug 后缀 2 的包 slug=acme/demo2，按关键字过滤应命中 1 条
	hit, err := core.Packages.List("demo2")
	if err != nil || len(hit) != 1 {
		t.Fatalf("filter acme2: %v n=%d", err, len(hit))
	}
	miss, err := core.Packages.List("no-such-keyword")
	if err != nil || len(miss) != 0 {
		t.Fatalf("filter miss: %v n=%d", err, len(miss))
	}
}

// 上传时 AGENTS.md frontmatter 嵌套 model.name 不得覆盖顶层 name
// （与 agent-framework 侧 33f1aa1 修复保持语义一致）。
func TestPackageUploadNestedModelNameParity(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	md := strings.Replace(testAgentsMD,
		"version: 1.0.0",
		"version: 1.0.0\nmodel:\n  provider: openai\n  name: gpt-x", 1)
	rec, err := core.Packages.Upload("demo.zip", bytesReader(buildTestZip(map[string]string{"AGENTS.md": md})))
	if err != nil {
		t.Fatalf("upload: %v", err)
	}
	if rec.Name != "Demo" {
		t.Fatalf("nested model.name must not override top-level name, got %q", rec.Name)
	}
}
