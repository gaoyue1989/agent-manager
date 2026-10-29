// imagebuilder 单元测试（契约 §3，不依赖网络）：
//   - tar 层构建正确性：路径前缀 / 权限 / 符号链接跳过 / 内容一致性 / 确定性；
//   - tag 命名规则：TargetRef 拼装与可解析性；
//   - labels 注入与 digest 返回：经 remoteOps 假实现离线走通 Build 组装链；
//   - 输入校验与错误透传。remote 真实推送链路靠 E2E（契约 §8）验证。
package imagebuilder

import (
	"archive/tar"
	"context"
	"errors"
	"io"
	"math/rand"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/google/go-containerregistry/pkg/authn"
	"github.com/google/go-containerregistry/pkg/name"
	v1 "github.com/google/go-containerregistry/pkg/v1"
	"github.com/google/go-containerregistry/pkg/v1/random"
)

// writeTestPackage 生成最小 OAF 包目录：根级 AGENTS.md + 嵌套目录/文件，
// 覆盖权限抬升（0600→0644）、可执行位保留（0755）与符号链接跳过三类场景。
func writeTestPackage(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	files := map[string]struct {
		content string
		mode    os.FileMode
	}{
		"AGENTS.md":            {"# e2e export agent\n", 0o644},
		"mcp-configs/mcp.json": {"{\"mcpServers\":{}}\n", 0o644},
		"skills/demo/SKILL.md": {"---\nname: demo\ndescription: demo\n---\n", 0o600}, // 故意低于 0644 下限
		"bin/tool.sh":          {"#!/bin/sh\necho hi\n", 0o755},                      // 可执行位应保留
	}
	for rel, f := range files {
		p := filepath.Join(dir, rel)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(f.content), 0o644); err != nil {
			t.Fatal(err)
		}
		// 显式 Chmod 规避 umask 对 WriteFile 权限的掩蔽，保证用例确定
		if err := os.Chmod(p, f.mode); err != nil {
			t.Fatal(err)
		}
	}
	// 符号链接（指向包外）：必须整条跳过，绝不能进层（防 zip-slip 同类逃逸）
	if err := os.Symlink("/etc/passwd", filepath.Join(dir, "escape.link")); err != nil {
		t.Fatal(err)
	}
	return dir
}

// readLayerEntries 解开层的未压缩 tar 流，按条目名归集 header 与内容。
func readLayerEntries(t *testing.T, layer v1.Layer) (map[string]*tar.Header, map[string]string) {
	t.Helper()
	rc, err := layer.Uncompressed()
	if err != nil {
		t.Fatalf("layer.Uncompressed: %v", err)
	}
	defer rc.Close()
	headers := map[string]*tar.Header{}
	contents := map[string]string{}
	tr := tar.NewReader(rc)
	for {
		hdr, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			t.Fatalf("read tar entry: %v", err)
		}
		headers[hdr.Name] = hdr
		b, err := io.ReadAll(tr)
		if err != nil {
			t.Fatalf("read tar entry body: %v", err)
		}
		contents[hdr.Name] = string(b)
	}
	return headers, contents
}

// mustDigest 兼容 v1.Image 与 v1.Layer（两者都有 Digest()）。
func mustDigest(t *testing.T, d interface{ Digest() (v1.Hash, error) }) v1.Hash {
	t.Helper()
	h, err := d.Digest()
	if err != nil {
		t.Fatalf("Digest: %v", err)
	}
	return h
}

// buildTestLabels 审计标签四件套（设计 §4/§5）。
func buildTestLabels() map[string]string {
	return map[string]string{
		"oaf.platform/slug":      "agentmanager/release-agent",
		"oaf.platform/version":   "1.2.0",
		"oaf.platform/checksum":  "sha256:abc123",
		"oaf.platform/export-id": "EXP-20261029-0001",
	}
}

func TestPackageTarLayer(t *testing.T) {
	pkgDir := writeTestPackage(t)
	layer, err := packageTarLayer(pkgDir)
	if err != nil {
		t.Fatalf("packageTarLayer: %v", err)
	}
	headers, contents := readLayerEntries(t, layer)
	if len(headers) == 0 {
		t.Fatal("tar layer is empty")
	}

	// 路径前缀：所有条目收敛在 oaf/ 根下（容器内挂载点 /oaf），且无 .. 逃逸
	for name := range headers {
		if !strings.HasPrefix(name, oafLayerRoot+"/") {
			t.Errorf("entry %q: not under %s/ prefix", name, oafLayerRoot)
		}
		if strings.Contains(name, "..") {
			t.Errorf("entry %q: contains ..", name)
		}
	}

	// 根目录条目（oaf/）存在且为目录
	root, ok := headers[oafLayerRoot+"/"]
	if !ok || root.Typeflag != tar.TypeDir {
		t.Fatalf("missing root dir entry %s/", oafLayerRoot)
	}

	// 根级 AGENTS.md：内容一致、普通文件类型
	agents := "oaf/AGENTS.md"
	if got := contents[agents]; got != "# e2e export agent\n" {
		t.Errorf("%s content = %q, want %q", agents, got, "# e2e export agent\n")
	}
	if h := headers[agents]; h.Typeflag != tar.TypeReg {
		t.Errorf("%s typeflag = %c, want regular file", agents, h.Typeflag)
	}

	// 嵌套目录与文件齐全
	for _, name := range []string{
		"oaf/mcp-configs/", "oaf/skills/", "oaf/skills/demo/", "oaf/bin/",
		"oaf/mcp-configs/mcp.json", "oaf/skills/demo/SKILL.md", "oaf/bin/tool.sh",
	} {
		if _, ok := headers[name]; !ok {
			t.Errorf("missing entry %q", name)
		}
	}

	// 权限：目录恒 0755（非 root 遍历需 x 位）；文件 ≥0644——低权限抬升到下限、可执行位保留
	if m := os.FileMode(root.Mode).Perm(); m != oafDirMode {
		t.Errorf("root dir mode = %o, want %o", m, oafDirMode)
	}
	if m := os.FileMode(headers["oaf/skills/demo/"].Mode).Perm(); m != oafDirMode {
		t.Errorf("nested dir mode = %o, want %o", m, oafDirMode)
	}
	if m := os.FileMode(headers["oaf/skills/demo/SKILL.md"].Mode).Perm(); m != oafFileMode {
		t.Errorf("0600 file mode = %o, want lifted to %o", m, oafFileMode)
	}
	if m := os.FileMode(headers["oaf/bin/tool.sh"].Mode).Perm(); m != 0o755 {
		t.Errorf("executable file mode = %o, want %o (exec bit preserved)", m, 0o755)
	}
	if m := os.FileMode(headers[agents].Mode).Perm(); m != oafFileMode {
		t.Errorf("AGENTS.md mode = %o, want %o", m, oafFileMode)
	}

	// 符号链接整条跳过
	if _, ok := headers["oaf/escape.link"]; ok {
		t.Error("symlink escape.link must be skipped")
	}
}

// TestPackageTarLayerDeterministic：同输入两次构建层 digest/diffID 一致——
// 「同 tag 内容寻址幂等重推」（设计 §5）的层侧前提。
func TestPackageTarLayerDeterministic(t *testing.T) {
	pkgDir := writeTestPackage(t)
	l1, err := packageTarLayer(pkgDir)
	if err != nil {
		t.Fatalf("packageTarLayer: %v", err)
	}
	l2, err := packageTarLayer(pkgDir)
	if err != nil {
		t.Fatalf("packageTarLayer: %v", err)
	}
	if d1, d2 := mustDigest(t, l1), mustDigest(t, l2); d1 != d2 {
		t.Errorf("layer digest not deterministic: %s vs %s", d1, d2)
	}
	did1, err := l1.DiffID()
	if err != nil {
		t.Fatal(err)
	}
	did2, err := l2.DiffID()
	if err != nil {
		t.Fatal(err)
	}
	if did1 != did2 {
		t.Errorf("layer diffID not deterministic: %s vs %s", did1, did2)
	}
}

// fakeRemoteOps 假 remote 实现（remoteOps 缝隙）：记录拉取/推送参数并捕获推送镜像，
// 使 Build 组装逻辑可离线验证。
type fakeRemoteOps struct {
	base        v1.Image
	pulledRef   string
	writtenRef  string
	writtenImg  v1.Image
	writtenAuth authn.Authenticator
	writeErr    error
}

func (f *fakeRemoteOps) pullBase(ctx context.Context, baseImage string, auth authn.Authenticator) (v1.Image, error) {
	f.pulledRef = baseImage
	return f.base, nil
}

func (f *fakeRemoteOps) write(ctx context.Context, targetRef string, img v1.Image, auth authn.Authenticator) error {
	f.writtenRef, f.writtenImg, f.writtenAuth = targetRef, img, auth
	return f.writeErr
}

// deterministicBaseImage 生成确定性基底镜像（固定随机源，无网络）：
// 同参数调用必然同 digest，支撑「同输入同镜像 digest」的幂等前提用例。
func deterministicBaseImage(t *testing.T) v1.Image {
	t.Helper()
	img, err := random.Image(1024, 1, random.WithSource(rand.New(rand.NewSource(42))))
	if err != nil {
		t.Fatalf("random.Image: %v", err)
	}
	return img
}

// TestBuildInjectsLabelsAndReturnsDigest：离线走通完整 Build 组装链（假 remote），
// 验证基底引用 / 推送引用 / labels 注入 / digest 返回 / 凭证透传。
func TestBuildInjectsLabelsAndReturnsDigest(t *testing.T) {
	pkgDir := writeTestPackage(t)
	base := deterministicBaseImage(t)
	fake := &fakeRemoteOps{base: base}
	b := &RemoteBuilder{baseImage: "registry.internal/base:9", auth: authenticatorFor("pusher", "secret"), ops: fake}
	labels := buildTestLabels()
	out, err := b.Build(context.Background(), BuildInput{
		PackageDir: pkgDir,
		TargetRef:  "172.20.0.1:5001/oaf-e2e-export-agent:1.2.0-e1",
		Labels:     labels,
	})
	if err != nil {
		t.Fatalf("Build: %v", err)
	}

	// 基底引用原样交给 pullBase
	if fake.pulledRef != "registry.internal/base:9" {
		t.Errorf("pulled base = %q, want %q", fake.pulledRef, "registry.internal/base:9")
	}
	// 推送引用 = 输入 TargetRef 原样（tag 命名规则由 TargetRef 拼装，见 TestTargetRef）
	if fake.writtenRef != "172.20.0.1:5001/oaf-e2e-export-agent:1.2.0-e1" {
		t.Errorf("written ref = %q", fake.writtenRef)
	}
	// labels 全量注入推送镜像的 config
	cf, err := fake.writtenImg.ConfigFile()
	if err != nil {
		t.Fatalf("ConfigFile: %v", err)
	}
	if !reflect.DeepEqual(cf.Config.Labels, labels) {
		t.Errorf("labels = %v, want %v", cf.Config.Labels, labels)
	}
	// digest = 推送镜像内容摘要（sha256:...），与仓库内 digest 一致（内容寻址）
	if want := mustDigest(t, fake.writtenImg); out.Digest != want.String() {
		t.Errorf("output digest = %q, want %q", out.Digest, want.String())
	}
	if !strings.HasPrefix(out.Digest, "sha256:") {
		t.Errorf("digest %q lacks sha256: prefix", out.Digest)
	}
	// 推送凭证透传（Basic）
	ba, ok := fake.writtenAuth.(*authn.Basic)
	if !ok || ba.Username != "pusher" || ba.Password != "secret" {
		t.Errorf("push auth = %#v, want Basic{pusher,secret}", fake.writtenAuth)
	}
}

// TestBuildDigestStableForSameInput：同输入两次 Build 得到同镜像 digest——
// remote.Write 对同 tag 同 digest 重推为 no-op 的前提；真实仓库 no-op 行为由 E2E §8.7 校验 6 验证。
func TestBuildDigestStableForSameInput(t *testing.T) {
	pkgDir := writeTestPackage(t)
	build := func() (string, error) {
		base := deterministicBaseImage(t)
		fake := &fakeRemoteOps{base: base}
		b := &RemoteBuilder{baseImage: "busybox:1.36", auth: authn.Anonymous, ops: fake}
		out, err := b.Build(context.Background(), BuildInput{
			PackageDir: pkgDir,
			TargetRef:  "172.20.0.1:5001/ns/oaf-demo:1.0.0-e1",
			Labels:     buildTestLabels(),
		})
		if err != nil {
			return "", err
		}
		return out.Digest, nil
	}
	d1, err := build()
	if err != nil {
		t.Fatalf("first build: %v", err)
	}
	d2, err := build()
	if err != nil {
		t.Fatalf("second build: %v", err)
	}
	if d1 != d2 {
		t.Errorf("digest not stable for same input: %s vs %s", d1, d2)
	}
}

// TestBuildInputValidation：契约 §3.2 步骤 1 的输入校验，失败时不得触网。
func TestBuildInputValidation(t *testing.T) {
	pkgDir := writeTestPackage(t)
	emptyDir := t.TempDir() // 合法目录但无根级 AGENTS.md
	base := deterministicBaseImage(t)
	fake := &fakeRemoteOps{base: base}
	b := &RemoteBuilder{baseImage: "busybox:1.36", auth: authn.Anonymous, ops: fake}
	okRef := "172.20.0.1:5001/oaf-demo:1.0.0-e1"

	cases := []struct {
		name    string
		in      BuildInput
		wantErr string
	}{
		{"package dir missing", BuildInput{PackageDir: filepath.Join(pkgDir, "nope"), TargetRef: okRef}, "package dir"},
		{"package dir is a file", BuildInput{PackageDir: filepath.Join(pkgDir, "AGENTS.md"), TargetRef: okRef}, "not a directory"},
		{"no root AGENTS.md", BuildInput{PackageDir: emptyDir, TargetRef: okRef}, "AGENTS.md"},
		{"empty target ref", BuildInput{PackageDir: pkgDir}, "target ref"},
		{"empty label key", BuildInput{PackageDir: pkgDir, TargetRef: okRef, Labels: map[string]string{"": "v"}}, "empty key"},
		{"empty label value", BuildInput{PackageDir: pkgDir, TargetRef: okRef, Labels: map[string]string{"oaf.platform/slug": ""}}, "empty value"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := b.Build(context.Background(), tc.in); err == nil || !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("Build error = %v, want containing %q", err, tc.wantErr)
			}
		})
	}
	// 校验失败不得触网（不拉基底、不推送）
	if fake.pulledRef != "" || fake.writtenRef != "" {
		t.Error("remote ops must not run when validation fails")
	}
}

// TestBuildPushErrorPropagates：直推失败时错误带目标引用透出、不返回 digest。
func TestBuildPushErrorPropagates(t *testing.T) {
	pkgDir := writeTestPackage(t)
	base := deterministicBaseImage(t)
	fake := &fakeRemoteOps{base: base, writeErr: errors.New("registry unavailable")}
	b := &RemoteBuilder{baseImage: "busybox:1.36", auth: authn.Anonymous, ops: fake}
	out, err := b.Build(context.Background(), BuildInput{
		PackageDir: pkgDir,
		TargetRef:  "172.20.0.1:5001/oaf-demo:1.0.0-e1",
		Labels:     map[string]string{"oaf.platform/export-id": "EXP-1"},
	})
	if err == nil || !strings.Contains(err.Error(), "registry unavailable") || !strings.Contains(err.Error(), "push image") {
		t.Fatalf("Build error = %v, want push failure wrapping registry unavailable", err)
	}
	if out.Digest != "" {
		t.Errorf("digest %q must be empty on failure", out.Digest)
	}
}

// TestTargetRef：tag 命名规则（设计 §5 + 契约 §9-D12）——{registry}/{ns}/oaf-{short}:{tag}、
// ns 空省略、语义版本 + 替换为 -；拼出的引用必须可被 name.ParseReference 接受。
func TestTargetRef(t *testing.T) {
	cases := []struct {
		name      string
		registry  string
		namespace string
		short     string
		marker    string
		want      string
	}{
		{"no namespace", "172.20.0.1:5001", "", "e2e-export-agent", "1.2.0-e1",
			"172.20.0.1:5001/oaf-e2e-export-agent:1.2.0-e1"},
		{"with namespace", "reg.example.com:5000", "prod", "travel-agent", "0.9.0-e12",
			"reg.example.com:5000/prod/oaf-travel-agent:0.9.0-e12"},
		{"plus in version replaced with dash", "172.20.0.1:5001", "ns", "demo-agent", "1.0.0+build-e2",
			"172.20.0.1:5001/ns/oaf-demo-agent:1.0.0-build-e2"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := TargetRef(tc.registry, tc.namespace, tc.short, tc.marker)
			if got != tc.want {
				t.Fatalf("TargetRef = %q, want %q", got, tc.want)
			}
			if _, err := name.ParseReference(got, name.Insecure); err != nil {
				t.Fatalf("composed ref %q does not parse: %v", got, err)
			}
		})
	}
}

// TestNewRemoteBuilder：空基底回落缺省 busybox:1.36；空凭证=匿名、非空=Basic；生产实现装配 realRemoteOps。
func TestNewRemoteBuilder(t *testing.T) {
	b := NewRemoteBuilder("", "", "")
	if b.baseImage != defaultBaseImage {
		t.Errorf("baseImage = %q, want default %q", b.baseImage, defaultBaseImage)
	}
	if b.auth != authn.Anonymous {
		t.Errorf("empty credentials must map to anonymous, got %#v", b.auth)
	}
	b2 := NewRemoteBuilder("reg.internal/base:1", "u", "p")
	if b2.baseImage != "reg.internal/base:1" {
		t.Errorf("baseImage = %q, want %q", b2.baseImage, "reg.internal/base:1")
	}
	ba, ok := b2.auth.(*authn.Basic)
	if !ok || ba.Username != "u" || ba.Password != "p" {
		t.Errorf("auth = %#v, want Basic{u,p}", b2.auth)
	}
	if _, ok := b2.ops.(realRemoteOps); !ok {
		t.Errorf("ops = %#v, want realRemoteOps", b2.ops)
	}
}
