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
// go-containerregistry（remote 推送链路本身靠 E2E 契约 §8 验证）。两动作均以
// authn.Keychain 取凭证——凭证按 registry 分发（issue #227）。
type remoteOps interface {
	pullBase(ctx context.Context, baseImage string, kc authn.Keychain) (v1.Image, error)
	write(ctx context.Context, targetRef string, img v1.Image, kc authn.Keychain) error
}

// realRemoteOps 生产实现（契约 §3.2 步骤 3/5）。
type realRemoteOps struct{}

// pullBase 拉取基底镜像。引用统一按 name.Insecure 解析：transport 层对 insecure
// registry 先试 https、失败并行回退 plain-http（remote/transport/ping.go 双 scheme 试探）——
// 导出直推目标多为内网 http 仓库（如 172.20.0.1:5001），https 仓库行为不变。
// 凭证经 keychain 按 registry 分发（issue #227）：非推送 registry（如缺省基底所在的
// docker.io）一律匿名拉取——坏凭证送达第三方 token 端点会被 401（匿名拉公共镜像反而失败）。
func (realRemoteOps) pullBase(ctx context.Context, baseImage string, kc authn.Keychain) (v1.Image, error) {
	ref, err := name.ParseReference(baseImage, name.Insecure)
	if err != nil {
		return nil, fmt.Errorf("parse base image reference %q: %w", baseImage, err)
	}
	return remote.Image(ref, remote.WithContext(ctx), remote.WithAuthFromKeychain(kc))
}

// write 直推目标仓库。remote.Write 按 manifest/layer digest 分层上传：
// 同 tag 同内容重推时 blob/manifest 已存在即 no-op——内容寻址幂等（设计 §5）。
// 凭证经 keychain 按 registry 分发（issue #227）：本动作唯一 registry 即推送目标，
// keychain 必命中推送凭证，与原 remote.WithAuth(Basic) 行为等价。
func (realRemoteOps) write(ctx context.Context, targetRef string, img v1.Image, kc authn.Keychain) error {
	ref, err := name.ParseReference(targetRef, name.Insecure)
	if err != nil {
		return fmt.Errorf("parse target reference %q: %w", targetRef, err)
	}
	return remote.Write(ref, img, remote.WithContext(ctx), remote.WithAuthFromKeychain(kc))
}

// pushScopedKeychain 按 registry 分发凭证的 keychain（issue #227）：仅推送目标 registry
// 拿到推送凭证，其余 registry（含基底镜像仓库）一律匿名——消除推送凭证送达第三方
// registry token 端点的缺口；私有基底需 EXPORT_BASE_IMAGE 指向与推送同 registry（可复用
// 推送凭证）或匿名可达的仓库（契约 §9-D8）。
type pushScopedKeychain struct {
	pushRegistry string              // 推送目标 registry（name 包规范化口径，docker.io → index.docker.io）
	pushAuth     authn.Authenticator // 推送凭证（可空装配时已兜底为 authn.Anonymous）
}

// Resolve 实现 authn.Keychain：资源 registry 与推送目标一致时返回推送凭证，否则匿名。
func (k *pushScopedKeychain) Resolve(resource authn.Resource) (authn.Authenticator, error) {
	if resource.RegistryStr() == k.pushRegistry {
		return k.pushAuth, nil
	}
	return authn.Anonymous, nil
}

// RemoteBuilder 直推实现（Builder 的生产实现）。
type RemoteBuilder struct {
	baseImage string
	pushAuth  authn.Authenticator // 推送凭证，仅作用于推送目标 registry（issue #227）；基底拉取对非推送 registry 一律匿名
	ops       remoteOps
}

// NewRemoteBuilder 直推实现。baseImage 为基底镜像引用（config EXPORT_BASE_IMAGE，缺省
// busybox:1.36）；username/password 为推送凭证（可空=匿名；生产经平台 Secret 注入 backend
// 进程 env，设计 §12.3，不进任何导出产物），仅作用于推送目标 registry，基底拉取对非
// 推送 registry 一律匿名（issue #227）。
func NewRemoteBuilder(baseImage, username, password string) *RemoteBuilder {
	if strings.TrimSpace(baseImage) == "" {
		baseImage = defaultBaseImage
	}
	return &RemoteBuilder{
		baseImage: baseImage,
		pushAuth:  authenticatorFor(username, password),
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
// 输入校验 → 包目录 tar 层 → 解析推送 registry（构造按 registry 分发的 keychain）→
// 拉基底 → 注入 labels + 追加配置层 → 直推 → 返回 digest。
func (b *RemoteBuilder) Build(ctx context.Context, in BuildInput) (BuildOutput, error) {
	if err := validateInput(in); err != nil {
		return BuildOutput{}, err
	}
	layer, err := packageTarLayer(in.PackageDir)
	if err != nil {
		return BuildOutput{}, fmt.Errorf("build package tar layer: %w", err)
	}
	// 前置解析推送 registry 构造 keychain（issue #227）：TargetRef 不可解析时在 Build
	// 早期失败（不触网），比 realRemoteOps.write 内的解析更早暴露；两侧均出自同一 name
	// 包解析，registry 规范化口径一致（docker.io → index.docker.io），无归一化偏差。
	pushRef, err := name.ParseReference(in.TargetRef, name.Insecure)
	if err != nil {
		return BuildOutput{}, fmt.Errorf("parse target reference %q: %w", in.TargetRef, err)
	}
	kc := &pushScopedKeychain{
		pushRegistry: pushRef.Context().RegistryStr(),
		pushAuth:     b.pushAuth,
	}
	base, err := b.ops.pullBase(ctx, b.baseImage, kc)
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
	if err := b.ops.write(ctx, in.TargetRef, img, kc); err != nil {
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
