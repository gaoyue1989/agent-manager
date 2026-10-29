// Package imagebuilder：导出 OAF 配置镜像构建器（设计 §5）。
// busybox 基底 + 包目录 tar 层（挂载点 /oaf），go-containerregistry remote.Write 直推
// 指定镜像仓库——纯数据镜像（KB~MB 级）、无 docker 依赖、无特权、同 tag 内容寻址幂等重推。
//
// 接口与类型（BuildInput/BuildOutput/Builder，契约 §3.1）为 U2 服务层注入点：
// 服务层以接口注入便于单测替换 fake；RemoteBuilder 直推实现（契约 §3.2 实现要点）
// 见本文件 Build，包目录 tar 层见 tarlayer.go。集成阶段在 cmd/server/main.go 装配
// core.Images = imagebuilder.NewRemoteBuilder(...)，未装配时服务层对创建导出单
// 返回 400（不降级假成功）。
package imagebuilder

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/google/go-containerregistry/pkg/authn"
	"github.com/google/go-containerregistry/pkg/name"
	v1 "github.com/google/go-containerregistry/pkg/v1"
	"github.com/google/go-containerregistry/pkg/v1/mutate"
	"github.com/google/go-containerregistry/pkg/v1/remote"
)

// BuildInput 构建输入。
type BuildInput struct {
	PackageDir string            // OAF 包目录绝对路径（{DATA_ROOT}/packages/{id}，backend 本地文件系统）
	TargetRef  string            // 目标镜像完整引用，如 172.20.0.1:5001/oaf-e2e-export-agent:1.2.0-e1
	Labels     map[string]string // 审计标签：oaf.platform/slug、/version、/checksum、/export-id（设计 §4/§5）
}

// BuildOutput 构建输出。
type BuildOutput struct {
	Digest string // sha256:...（内容寻址；remote.Write 成功后取 img.Digest()，与仓库内 digest 一致）
}

// Builder 导出配置镜像构建器（服务层以接口注入，便于单测替换 fake）。
type Builder interface {
	Build(ctx context.Context, in BuildInput) (BuildOutput, error)
}

// defaultBaseImage 基底镜像缺省值（设计 §5 busybox:1.36）：config.Load 已兜底
// EXPORT_BASE_IMAGE，此处对空串再兜一层；离线/内网部署必须显式覆盖（契约 §9-D8）。
const defaultBaseImage = "busybox:1.36"

// oafRepoPrefix OAF 配置镜像仓库名前缀（设计 §5 tag 规则 oaf-{short}；
// short = k8s.ShortName 去 oaf- 前缀，k8s 名已带 oaf- 前缀故不重复拼接，契约 §9-D12）。
const oafRepoPrefix = "oaf-"

// remoteOps 网络缝隙：抽象「拉基底 / 推镜像」两个 remote 动作，单测注入假实现即可
// 离线验证输入校验、tar 层构建、labels 注入与 digest 返回；生产由 realRemoteOps 走
// go-containerregistry（remote 推送链路本身靠 E2E 契约 §8 验证）。
type remoteOps interface {
	pullBase(ctx context.Context, baseImage string, auth authn.Authenticator) (v1.Image, error)
	write(ctx context.Context, targetRef string, img v1.Image, auth authn.Authenticator) error
}

// realRemoteOps 生产实现（契约 §3.2 步骤 3/5）。
type realRemoteOps struct{}

// pullBase 拉取基底镜像。引用统一按 name.Insecure 解析：transport 层对 insecure
// registry 先试 https、失败并行回退 plain-http（remote/transport/ping.go 双 scheme 试探）——
// 导出直推目标多为内网 http 仓库（如 172.20.0.1:5001），https 仓库行为不变。
func (realRemoteOps) pullBase(ctx context.Context, baseImage string, auth authn.Authenticator) (v1.Image, error) {
	ref, err := name.ParseReference(baseImage, name.Insecure)
	if err != nil {
		return nil, fmt.Errorf("parse base image reference %q: %w", baseImage, err)
	}
	return remote.Image(ref, remote.WithContext(ctx), remote.WithAuth(auth))
}

// write 直推目标仓库。remote.Write 按 manifest/layer digest 分层上传：
// 同 tag 同内容重推时 blob/manifest 已存在即 no-op——内容寻址幂等（设计 §5）。
func (realRemoteOps) write(ctx context.Context, targetRef string, img v1.Image, auth authn.Authenticator) error {
	ref, err := name.ParseReference(targetRef, name.Insecure)
	if err != nil {
		return fmt.Errorf("parse target reference %q: %w", targetRef, err)
	}
	return remote.Write(ref, img, remote.WithContext(ctx), remote.WithAuth(auth))
}

// RemoteBuilder 直推实现（Builder 的生产实现）。
type RemoteBuilder struct {
	baseImage string
	auth      authn.Authenticator
	ops       remoteOps
}

// NewRemoteBuilder 直推实现。baseImage 为基底镜像引用（config EXPORT_BASE_IMAGE，缺省
// busybox:1.36）；username/password 为推送凭证（可空=匿名；生产经平台 Secret 注入 backend
// 进程 env，设计 §12.3，不进任何导出产物）。
func NewRemoteBuilder(baseImage, username, password string) *RemoteBuilder {
	if strings.TrimSpace(baseImage) == "" {
		baseImage = defaultBaseImage
	}
	return &RemoteBuilder{
		baseImage: baseImage,
		auth:      authenticatorFor(username, password),
		ops:       realRemoteOps{},
	}
}

// authenticatorFor 凭证装配：用户名与密码均空 = 匿名推送。
func authenticatorFor(username, password string) authn.Authenticator {
	if username == "" && password == "" {
		return authn.Anonymous
	}
	return &authn.Basic{Username: username, Password: password}
}

// Build 构建并直推 OAF 配置镜像（契约 §3.2 实现要点）：
// 输入校验 → 包目录 tar 层 → 拉基底 → 注入 labels + 追加配置层 → 直推 → 返回 digest。
func (b *RemoteBuilder) Build(ctx context.Context, in BuildInput) (BuildOutput, error) {
	if err := validateInput(in); err != nil {
		return BuildOutput{}, err
	}
	layer, err := packageTarLayer(in.PackageDir)
	if err != nil {
		return BuildOutput{}, fmt.Errorf("build package tar layer: %w", err)
	}
	base, err := b.ops.pullBase(ctx, b.baseImage, b.auth)
	if err != nil {
		return BuildOutput{}, fmt.Errorf("pull base image %q: %w", b.baseImage, err)
	}
	// mutate.Config 整体替换基底 Config（v0.22.1 mutate.go cf.Config = cfg）：
	// 本镜像只作为 initContainer 显式 command（sh -c "cp -a /oaf/. /config/"）的数据源，
	// 基底 Env/Cmd 被清空对功能无影响（契约 §3.2 步骤 4 的指定调用）。
	img, err := mutate.Config(base, v1.Config{Labels: in.Labels})
	if err != nil {
		return BuildOutput{}, fmt.Errorf("inject labels: %w", err)
	}
	if img, err = mutate.AppendLayers(img, layer); err != nil {
		return BuildOutput{}, fmt.Errorf("append package layer: %w", err)
	}
	if err := b.ops.write(ctx, in.TargetRef, img, b.auth); err != nil {
		return BuildOutput{}, fmt.Errorf("push image %q: %w", in.TargetRef, err)
	}
	digest, err := img.Digest()
	if err != nil {
		return BuildOutput{}, fmt.Errorf("resolve image digest: %w", err)
	}
	return BuildOutput{Digest: digest.String()}, nil
}

// validateInput 输入校验（契约 §3.2 步骤 1）：包目录存在且含根级 AGENTS.md
// （构建输入必须是合法 OAF 包）、TargetRef 非空、Labels 键值非空。
func validateInput(in BuildInput) error {
	if strings.TrimSpace(in.PackageDir) == "" {
		return errors.New("package dir is required")
	}
	info, err := os.Stat(in.PackageDir)
	if err != nil {
		return fmt.Errorf("package dir %q: %w", in.PackageDir, err)
	}
	if !info.IsDir() {
		return fmt.Errorf("package dir %q is not a directory", in.PackageDir)
	}
	if _, err := os.Stat(filepath.Join(in.PackageDir, "AGENTS.md")); err != nil {
		return fmt.Errorf("package dir %q is not a valid OAF package (missing root AGENTS.md): %w", in.PackageDir, err)
	}
	if strings.TrimSpace(in.TargetRef) == "" {
		return errors.New("target ref is required")
	}
	for k, v := range in.Labels {
		if strings.TrimSpace(k) == "" {
			return errors.New("labels contain an empty key")
		}
		if strings.TrimSpace(v) == "" {
			return fmt.Errorf("label %q has an empty value", k)
		}
	}
	return nil
}

// TargetRef 拼装 OAF 配置镜像目标引用（设计 §5 tag 规则 + 契约 §9-D12）：
// {registry}/{namespace}/oaf-{short}:{tag}，tag = marker（{包版本}-e{导出序号}）；
// 包版本语义化含 + 时替换为 -（docker tag 字符集限制）。namespace 为空时不拼命名空间段。
// short 传 k8s.ShortName 的返回值（不带 oaf- 前缀），本函数统一补 oaf- 前缀。
func TargetRef(registry, namespace, short, marker string) string {
	tag := strings.ReplaceAll(marker, "+", "-")
	repo := oafRepoPrefix + short
	if namespace != "" {
		repo = namespace + "/" + repo
	}
	return registry + "/" + repo + ":" + tag
}
