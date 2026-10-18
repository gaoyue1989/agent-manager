package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"
	"unicode/utf8"

	"gorm.io/gorm"
	networkingv1 "k8s.io/api/networking/v1"

	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/store"
)

// Core 业务门面：REST 与 MCP 共用。
type Core struct {
	DB  *gorm.DB
	FS  *store.FS
	K8s k8s.Client
	Cfg ConfigView

	Packages *PackageService

	// JWTSecret 登录 token 校验密钥（introspect 数据面用；签发在 handler 层）
	JWTSecret string

	// OnServiceRunning 服务转 running 后的回调（M5 模型下发挂载点；nil 跳过）。
	// 在 transition 内 goroutine 触发，失败不影响状态机推进。
	OnServiceRunning func(serviceID uint)
}

// ConfigView 发布所需的最小配置集（避免直接依赖 config.Config，便于测试）。
// ImageOption 可选镜像项（MCP list_images 对外暴露；镜像名按环境而异，不写死）。
type ImageOption struct {
	Image string `json:"image"`
	Label string `json:"label,omitempty"`
}

type ConfigView struct {
	Namespace string
	// PlatformPVCName 业务 Pod 挂载的平台数据卷 PVC 名（空 = k8s.PVCName 默认常量）
	PlatformPVCName string
	IngressClass    string
	IngressHost     string
	IngressPort     int
	// IngressHostSuffix 业务路由模式开关：空=router 模式（无 per-service Ingress，展示
	// 地址纯配置拼装），非空=host 模式（历史行为）
	IngressHostSuffix              string
	DefaultImage                   string
	ImageOptions                   []ImageOption
	ImageAllowed                   func(string) bool
	ResCPU, ResMem, LimCPU, LimMem string
	RegisterTimeout                time.Duration
	RegisterRetry                  int
	// PackageDownloadBase create_oaf_zip 返回 download_url 的基地址（空则取 DefaultPackageDownloadBase）
	PackageDownloadBase string
	// DeployBuilder 业务 Deployment 构造门面（内置构造 + 可选环境 overlay）；
	// nil 视为纯内置构造，模式同 ImageAllowed 函数字段便于测试注入。
	DeployBuilder *k8s.DeploymentBuilder
	// IngressBuilder 业务 Ingress 构造门面（内置构造 + 可选环境 overlay）；nil 同上。
	IngressBuilder *k8s.IngressBuilder
}

// DefaultPackageDownloadBase 集群内默认下载基地址（业务 Agent Pod 同 namespace 可达）。
const DefaultPackageDownloadBase = "http://platform-backend.agent-platform.svc.cluster.local:8080"

// PackageDownloadURL 拼接整包下载 URL（create_oaf_zip 交付给 present_url 代理下载）。
func (c *Core) PackageDownloadURL(packageID uint) string {
	base := strings.TrimRight(c.Cfg.PackageDownloadBase, "/")
	if base == "" {
		base = DefaultPackageDownloadBase
	}
	return fmt.Sprintf("%s/api/v1/packages/%d/download", base, packageID)
}

// deployBuilder 兜底取 builder：未注入时退回纯内置构造（历史行为）。
func (c *Core) deployBuilder() *k8s.DeploymentBuilder {
	if c.Cfg.DeployBuilder != nil {
		return c.Cfg.DeployBuilder
	}
	return &k8s.DeploymentBuilder{}
}

// ingressBuilder 兜底取 builder：语义同 deployBuilder。
func (c *Core) ingressBuilder() *k8s.IngressBuilder {
	if c.Cfg.IngressBuilder != nil {
		return c.Cfg.IngressBuilder
	}
	return &k8s.IngressBuilder{}
}

var (
	ErrNotFound        = errors.New("not found")
	ErrImageNotAllowed = errors.New("image not in AVAILABLE_IMAGES")
	ErrBadState        = errors.New("action not allowed in current status")
)

// PublishRequest POST /services 入参。
type PublishRequest struct {
	PackageID uint   `json:"packageId"`
	Name      string `json:"name"`
	Image     string `json:"image"`
	// Env 用户环境变量（全量覆盖语义）；模板敏感键自动路由进服务 Secret，绝不落 CM/env_json
	Env map[string]string `json:"env"`
	// SecretKeys 强制按敏感处理的任意键（如 MCP token），与模板敏感键一并路由进服务 Secret
	SecretKeys []string `json:"secretKeys"`
	Replicas   int32    `json:"replicas"`

	// ---- 控制面拆分扩展（M1/M3）：存量调用不传即零影响 ----
	// WorkspaceID 归属工作空间（0=未归属）；CreatedBy 发布人用户 id
	WorkspaceID uint `json:"workspaceId"`
	CreatedBy   uint `json:"createdBy"`
	// Spec 资源规格档（"" | small | medium | large，决议 4；与 DEPLOYMENT_TEMPLATE
	// overlay 的 resources 配置互斥校验）
	Spec string `json:"spec"`
	// K8sNameBase k8s 名派生源覆盖（M3 决议 5 同源派生）：非空时 k8s 名从它派生而非
	// req.Name——console 创建链路传 agentKey（纯中文显示名 sanitize 后为空会撞
	// "svc" 兜底名，部署实测）；存量调用不传即零影响
	K8sNameBase string `json:"k8sNameBase,omitempty"`
	// PublicEndpoint 是否发布对外访问入口；nil=true（存量默认）
	PublicEndpoint *bool `json:"publicEndpoint"`
}

// spec 档 → Deployment resources 直译映射（决议 4）。
var specProfiles = map[string][2]string{ // [requestsCPU, requestsMem]（limits 同值直译）
	// micro：最低配置档，limits=256Mi 放不下托管 Java 运行时（OOMKilled CrashLoop，
	// issue #136）——仅极轻量自备镜像可用，创建页默认档是 small 且对 micro 显式警示
	"micro":  {"250m", "256Mi"},
	"small":  {"1", "2Gi"},
	"medium": {"2", "4Gi"},
	"large":  {"4", "8Gi"},
}

// NewCore 组装业务层。
func NewCore(db *gorm.DB, fs *store.FS, kc k8s.Client, cv ConfigView) *Core {
	return &Core{DB: db, FS: fs, K8s: kc, Cfg: cv,
		Packages: &PackageService{DB: db, FS: fs}}
}

// validateSpec 规格档校验 + 与 DEPLOYMENT_TEMPLATE overlay resources 互斥（决议 4）。
func (c *Core) validateSpec(spec string) error {
	if spec == "" {
		return nil
	}
	if _, ok := specProfiles[spec]; !ok {
		return fmt.Errorf("%w: unknown spec %q (micro|small|medium|large)", ErrInvalidForm, spec)
	}
	if c.deployBuilder().OverridesResources() {
		return fmt.Errorf("%w: DEPLOYMENT_TEMPLATE already configures resources; spec is not allowed", ErrInvalidForm)
	}
	return nil
}

// Publish 创建全套 K8s 资源并异步等待就绪+注册。
func (c *Core) Publish(req PublishRequest) (*store.ServiceEntity, error) {
	if err := ValidateEnv(req.Env); err != nil {
		return nil, err
	}
	if err := validateSecretKeys(req.SecretKeys); err != nil {
		return nil, err
	}
	if err := c.validateSpec(req.Spec); err != nil {
		return nil, err
	}
	// 展示名列容量校验（issue #149：display_name varchar(128)，超长曾以 500 直出）
	if utf8.RuneCountInString(strings.TrimSpace(req.Name)) > 128 {
		return nil, fmt.Errorf("%w: name too long (max 128)", ErrInvalidForm)
	}
	image := req.Image
	if image == "" {
		image = c.Cfg.DefaultImage
	}
	if !c.Cfg.ImageAllowed(image) {
		return nil, ErrImageNotAllowed
	}
	pkg, err := c.Packages.Get(req.PackageID)
	if err != nil {
		return nil, fmt.Errorf("package %d: %w", req.PackageID, err)
	}

	k8sName := k8s.DeriveK8sName(orStr(req.K8sNameBase, req.Name), pkg.Slug)
	// 子对象名预算前置校验（issue #149 复测残留）：Service "{name}-svc"、Secret
	// "{name}-env-secret" 同为 DNS-1123 ≤63——超预算必须 400 拒绝，否则先落库再被
	// K8s API 拒绝（500 invalid metadata.name），残留 deploying/error 记录
	if n := len(k8sName + "-env-secret"); n > 63 {
		return nil, fmt.Errorf("%w: name too long (derived k8s name %q exceeds object-name budget)", ErrInvalidForm, k8sName)
	}
	k8sName, err = c.uniqName(k8sName)
	if err != nil {
		return nil, err
	}
	public := req.PublicEndpoint == nil || *req.PublicEndpoint

	// 敏感路由：模板敏感键 + secretKeys 进 EnvSecret（服务 Secret），其余进 Env（CM）
	plain, secret := splitUserEnv(req.Env, req.SecretKeys)
	// 空值键不落 CM/Secret（设计 §3.3，同 k8s.EnvSecret 注释）：发布没有「空串=删除」三态
	// 语义，空值是无意义输入（向导预填的默认值已由 GetPlatformDefaults 过滤，剩余空串来自
	// 用户手工清空输入框）；空串 env 会让 Spring 占位符 ${VAR:default} 不回落默认值。
	// 「空串=显式删除」的 PATCH 三态语义由 resolveEnvMerge 承担；三条写路径
	// （Publish/UpdateEnv/Republish）出口另有 dropEmptyEnv 兜底，空值永不进入 CM/Secret。
	dropEmptyEnv(plain)
	dropEmptyEnv(secret)
	// 会话隔离派生（issue #142）：k8sName 定型后按服务派生独立 checkpoint 库名与
	// Redis key 前缀——仅 env 未显式配置时生效，用户显式值优先
	c.applyIsolationEnv(k8sName, plain, secret)
	params := applySpec(c.params(k8sName, image, plain, int32Or(req.Replicas), pkg.DirPath), req.Spec)
	params.EnvSecret = secret
	// 展示 endpoint 派生 + Ingress overlay 纯函数前置（host 模式）：非法 overlay 直接
	// 拒绝且不落库（与 image 校验同级，模板违规属平台配置错误，不产生 deploying/error
	// 垃圾记录）；模板可改 host/TLS，公式随合并后 Ingress 走。router 模式无
	// per-service Ingress，纯配置拼装（RouterEndpoint）。
	endpoint, err := c.deriveEndpoint(params)
	if err != nil {
		return nil, err
	}
	if !public {
		// M3 §2.4：不发布对外入口 —— host 模式跳过 Ingress 创建；router 模式
		// （统一 router 域名为静态泛化）仅置空展示地址（页面标注「仅集群内」）
		endpoint = ""
	}
	svc := &store.ServiceEntity{
		K8sName:       k8sName,
		DisplayName:   orStr(req.Name, pkg.Name),
		PackageID:     pkg.ID,
		Image:         image,
		EnvJSON:       mustJSON(plain),
		EnvSecretJSON: mustJSON(secret),
		Replicas:      int(int32Or(req.Replicas)),
		Status:        store.StatusCreated,
		AgentCardJSON: "{}",
		SkillsJSON:    "[]",
		Endpoint:      endpoint,
		ClusterURL:    fmt.Sprintf("http://%s-svc.%s.svc.cluster.local:%d", k8sName, c.Cfg.Namespace, k8s.AgentPort),
		ShortName:     k8s.ShortName(k8sName),

		WorkspaceID:    req.WorkspaceID,
		CreatedBy:      req.CreatedBy,
		Spec:           req.Spec,
		PublicEndpoint: req.PublicEndpoint,
	}

	svc.Status = store.StatusDeploying // 落库即为 deploying（事件保留 created→deploying 审计）
	err = c.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(svc).Error; err != nil {
			// 唯一键冲突（并发同名发布竞态）等存储错误翻译为业务哨兵（issue #151：
			// 并发重复提交曾以 502 Duplicate entry 直出）
			return translateStorageError(err)
		}
		if err := tx.Model(&store.OafPackage{}).Where("id = ?", pkg.ID).
			UpdateColumn("ref_count", gorm.Expr("ref_count + 1")).Error; err != nil {
			return err
		}
		return recordEvent(tx, svc.ID, store.StatusCreated, store.StatusDeploying, "publish requested")
	})
	if err != nil {
		return nil, err
	}

	if _, err := c.applyAllPublic(params, public); err != nil {
		c.transition(svc, store.StatusError, "apply failed: "+err.Error())
		return nil, err
	}
	c.asyncWaitAndRegister(svc.ID)
	return svc, nil
}

// applyAllPublic 公共入口开关包装：public=false 时 host 模式跳过 Ingress 创建
// （M3 §2.4：不发布对外访问入口 = 仅集群内可达）；router 模式行为不变（本就无 per-service
// Ingress，收敛旧对象语义保持）。
func (c *Core) applyAllPublic(p k8s.ObjectParams, public bool) (*networkingv1.Ingress, error) {
	if public || c.Cfg.IngressHostSuffix == "" {
		return c.applyAll(p)
	}
	// host 模式私有发布：跳过 Ingress 构建/写入，并清理历史同名 Ingress（如有）
	if err := c.K8s.DeleteIngress(p.Namespace, p.K8sName); err != nil {
		return nil, fmt.Errorf("ingress cleanup: %w", err)
	}
	if err := c.applyServiceEnvObjects(p); err != nil {
		return nil, err
	}
	if err := c.K8s.EnsureService(k8s.Service(p)); err != nil {
		return nil, fmt.Errorf("service: %w", err)
	}
	dep, err := c.deployBuilder().Build(p)
	if err != nil {
		return nil, fmt.Errorf("deployment: %w", err)
	}
	if err := c.K8s.EnsureDeployment(dep); err != nil {
		return nil, fmt.Errorf("deployment: %w", err)
	}
	return nil, nil
}

// applyAll 幂等创建/更新服务 env 两级对象+Deployment+Service，并返回已落集群的
// Ingress 对象（host 模式，供调用方派生展示地址，避免二次 Build；router 模式恒 nil）。
// Deployment/Ingress 均经 builder（内置构造 + 可选环境 overlay 合并 + 不变量校验），
// overlay 违规视为 apply 失败，服务转 error 状态。Build 纯函数零成本前置：非法
// overlay 时任何对象不落半套资源。
//
// router 模式（IngressHostSuffix 空）：不构造 per-service Ingress（子路径路由由集群内
// platform-router 承担），并对同名 Ingress「存在即删除」（NotFound 容忍）——存量
// path 模式对象与共享 /agent Ingress 路径重叠，经显式发布 API 收敛（不做后台批量迁移）。
func (c *Core) applyAll(p k8s.ObjectParams) (*networkingv1.Ingress, error) {
	dep, err := c.deployBuilder().Build(p)
	if err != nil {
		return nil, fmt.Errorf("deployment: %w", err)
	}
	var ing *networkingv1.Ingress
	if c.Cfg.IngressHostSuffix == "" {
		if err := c.K8s.DeleteIngress(p.Namespace, p.K8sName); err != nil {
			return nil, fmt.Errorf("ingress cleanup: %w", err)
		}
	} else {
		ing, err = c.ingressBuilder().Build(p)
		if err != nil {
			return nil, fmt.Errorf("ingress: %w", err)
		}
		if err := c.K8s.EnsureIngress(ing); err != nil {
			return nil, fmt.Errorf("ingress: %w", err)
		}
	}
	if err := c.applyServiceEnvObjects(p); err != nil {
		return nil, err
	}
	if err := c.K8s.EnsureService(k8s.Service(p)); err != nil {
		return nil, fmt.Errorf("service: %w", err)
	}
	if err := c.K8s.EnsureDeployment(dep); err != nil {
		return nil, fmt.Errorf("deployment: %w", err)
	}
	return ing, nil
}

// applyServiceEnvObjects 幂等写入服务 env 两级对象（CM 非敏感 + Secret 敏感）。
func (c *Core) applyServiceEnvObjects(p k8s.ObjectParams) error {
	if err := c.K8s.EnsureConfigMap(k8s.EnvConfigMap(p)); err != nil {
		return fmt.Errorf("configmap: %w", err)
	}
	if err := c.K8s.EnsureSecret(k8s.EnvSecret(p)); err != nil {
		return fmt.Errorf("secret: %w", err)
	}
	return nil
}

// asyncWaitAndRegister 后台推进：WaitReady → Register → running / deploy_failed / register_failed。
func (c *Core) asyncWaitAndRegister(id uint) {
	go func() {
		defer func() { _ = recover() }() // 防御 goroutine panic 拖垮进程
		ctx, cancel := context.WithTimeout(context.Background(), c.Cfg.RegisterTimeout)
		defer cancel()

		var svc store.ServiceEntity
		if err := c.DB.First(&svc, id).Error; err != nil {
			return
		}
		if err := c.K8s.WaitReady(ctx, c.Cfg.Namespace, svc.K8sName, c.Cfg.RegisterTimeout); err != nil {
			c.transition(&svc, store.StatusDeployFailed, "ready wait: "+err.Error())
			return
		}
		// 就绪后重读最新记录（等待期间记录可能已被更新）
		var fresh store.ServiceEntity
		if err := c.DB.First(&fresh, id).Error; err == nil {
			svc = fresh
		}
		reason := c.Register(&svc)
		if reason == "" {
			c.transition(&svc, store.StatusRunning, "registered via a2a agent-card")
		} else {
			c.transition(&svc, store.StatusRegisterFailed, reason)
		}
	}()
}

// UpdateEnv 全量替换 env 并滚动重启。
// env 为非敏感部分的全量覆盖；模板敏感键或 secretKeys 指定键路由进服务 Secret，
// 三态语义：出现且非空=设置、出现且空串=删除（回落平台默认）、未出现=sticky 保持不变。
// 非敏感份空串=不设置（全量覆盖下「不写即删除」，空串无意义），出口 dropEmptyEnv
// 兜底保证空值不落 CM/env_json/Secret（设计 §3.3）。
// 旧 env_json 中的存量敏感键自动迁入服务 Secret（设计 §3.6 防丢失规则）。
func (c *Core) UpdateEnv(id uint, env map[string]string, secretKeys []string) (*store.ServiceEntity, error) {
	if err := ValidateEnv(env); err != nil {
		return nil, err
	}
	if err := validateSecretKeys(secretKeys); err != nil {
		return nil, err
	}
	svc, err := c.Get(id)
	if err != nil {
		return nil, err
	}
	switch svc.Status {
	case store.StatusStopped:
		return nil, fmt.Errorf("%w: service is stopped", ErrBadState)
	case store.StatusDeploying:
		return nil, fmt.Errorf("%w: service is deploying", ErrBadState)
	}
	oldPlain, oldSecret, err := c.partitionServiceEnv(svc)
	if err != nil {
		return nil, err
	}
	plain, secret := resolveEnvMerge(oldPlain, oldSecret, env, secretKeys)
	// 出口兜底：空值永不进入 CM/env_json/env_secret_json（设计 §3.3，与 Publish 同语义）
	dropEmptyEnv(plain)
	dropEmptyEnv(secret)
	if len(plain)+len(secret) > MaxEnvKeys {
		return nil, fmt.Errorf("too many env keys: %d (max %d)", len(plain)+len(secret), MaxEnvKeys)
	}
	params := c.params(svc.K8sName, svc.Image, plain, int32(svc.Replicas), c.pkgDir(svc.PackageID))
	params.EnvSecret = secret
	if err := c.applyServiceEnvObjects(params); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := c.K8s.RestartDeployment(ctx, c.Cfg.Namespace, svc.K8sName); err != nil {
		return nil, err
	}
	// DB 先于 transition：若顺序颠倒，DB 写失败会留下 deploying 且无 goroutine 推进，
	// 而 deploying 拒绝再次 UpdateEnv，服务将卡死（原实现即 DB 在前，保持该失败语义）
	svc.EnvJSON = mustJSON(plain)
	svc.EnvSecretJSON = mustJSON(secret)
	if err := c.DB.Model(svc).Updates(map[string]interface{}{
		"env_json": svc.EnvJSON, "env_secret_json": svc.EnvSecretJSON,
	}).Error; err != nil {
		return nil, err
	}
	c.transition(svc, store.StatusDeploying, "env updated, rolling restart")
	c.asyncWaitAndRegister(svc.ID)
	return svc, nil
}

// RepublishOptions 可选变更字段；nil/0 表示沿用现状。
type RepublishOptions struct {
	PackageID  *uint
	Image      *string
	Replicas   *int32
	Env        map[string]string // 非 nil 时全量替换（非敏感部分；敏感键路由语义同 UpdateEnv）
	SecretKeys []string          // Env 非 nil 时生效：强制按敏感处理的任意键
	Spec       *string           // 规格档（决议 4；空串=清除回平台默认）
	PublicEndpoint *bool         // 对外入口开关
}

// applySpec 将规格档直译进 ObjectParams resources（limits 同值，决议 4）。
func applySpec(p k8s.ObjectParams, spec string) k8s.ObjectParams {
	if prof, ok := specProfiles[spec]; ok {
		p.RequestsCPU, p.RequestsMem = prof[0], prof[1]
		p.LimitsCPU, p.LimitsMem = prof[0], prof[1]
	}
	return p
}

// Republish 幂等重建全套资源（可换包/镜像/env），并滚动重启重新注册。
func (c *Core) Republish(id uint, opt RepublishOptions) (*store.ServiceEntity, error) {
	svc, err := c.Get(id)
	if err != nil {
		return nil, err
	}
	oldPlain, oldSecret, err := c.partitionServiceEnv(svc)
	if err != nil {
		return nil, err
	}
	env := oldPlain
	var secretKeys []string
	if opt.Env != nil {
		if err := ValidateEnv(opt.Env); err != nil {
			return nil, err
		}
		if err := validateSecretKeys(opt.SecretKeys); err != nil {
			return nil, err
		}
		env = opt.Env
		secretKeys = opt.SecretKeys
	}
	plain, secret := resolveEnvMerge(oldPlain, oldSecret, env, secretKeys)
	// 出口兜底：空值永不进入 CM/env_json/env_secret_json（设计 §3.3，与 Publish 同语义）
	dropEmptyEnv(plain)
	dropEmptyEnv(secret)
	if len(plain)+len(secret) > MaxEnvKeys {
		return nil, fmt.Errorf("too many env keys: %d (max %d)", len(plain)+len(secret), MaxEnvKeys)
	}

	newPkgID := svc.PackageID
	if opt.PackageID != nil && *opt.PackageID != svc.PackageID {
		if _, err := c.Packages.Get(*opt.PackageID); err != nil {
			return nil, err
		}
		newPkgID = *opt.PackageID
	}
	image := svc.Image
	if opt.Image != nil {
		if !c.Cfg.ImageAllowed(*opt.Image) {
			return nil, ErrImageNotAllowed
		}
		image = *opt.Image
	}
	replicas := int32(svc.Replicas)
	if opt.Replicas != nil && *opt.Replicas > 0 {
		replicas = *opt.Replicas
	}
	spec := svc.Spec
	if opt.Spec != nil {
		spec = *opt.Spec
	}
	public := svc.EndpointPublic()
	if opt.PublicEndpoint != nil {
		public = *opt.PublicEndpoint
	}

	// Ingress overlay 纯函数前置（事务前，host 模式）：非法 overlay 直接拒绝，库与 K8s
	// 均保持原状；同时派生展示 endpoint 随事务回写（模板/配置变更后展示地址跟随合并
	// 结果）。router 模式纯配置拼装（RouterEndpoint）。
	params := applySpec(c.params(svc.K8sName, image, plain, replicas, c.pkgDir(newPkgID)), spec)
	params.EnvSecret = secret
	endpoint, err := c.deriveEndpoint(params)
	if err != nil {
		return nil, err
	}
	if !public {
		endpoint = ""
	}

	err = c.DB.Transaction(func(tx *gorm.DB) error {
		if newPkgID != svc.PackageID {
			tx.Model(&store.OafPackage{}).Where("id = ?", svc.PackageID).
				UpdateColumn("ref_count", gorm.Expr("ref_count - 1"))
			tx.Model(&store.OafPackage{}).Where("id = ?", newPkgID).
				UpdateColumn("ref_count", gorm.Expr("ref_count + 1"))
			if err := tx.Model(svc).Update("package_id", newPkgID).Error; err != nil {
				return err
			}
		}
		updates := map[string]interface{}{
			"image": image, "replicas": replicas, "endpoint": endpoint,
			"env_json": mustJSON(plain), "env_secret_json": mustJSON(secret),
			"spec": spec, "public_endpoint": public,
		}
		return tx.Model(svc).Updates(updates).Error
	})
	if err != nil {
		return nil, err
	}
	svc.PackageID, svc.Image, svc.Replicas = newPkgID, image, int(replicas)
	svc.EnvJSON, svc.EnvSecretJSON = mustJSON(plain), mustJSON(secret)
	svc.Endpoint, svc.Spec = endpoint, spec
	svc.PublicEndpoint = &public

	if _, err := c.applyAllPublic(params, public); err != nil {
		c.transition(svc, store.StatusError, "republish apply failed: "+err.Error())
		return nil, err
	}
	c.transition(svc, store.StatusDeploying, "republished")
	c.asyncWaitAndRegister(svc.ID)
	return svc, nil
}

// StartAgain stopped/error/deploy_failed/register_failed → 重新上线。
func (c *Core) StartAgain(id uint) (*store.ServiceEntity, error) {
	svc, err := c.Get(id)
	if err != nil {
		return nil, err
	}
	switch svc.Status {
	case store.StatusStopped, store.StatusError, store.StatusDeployFailed, store.StatusRegisterFailed:
	default:
		return nil, fmt.Errorf("%w: cannot start from %s", ErrBadState, svc.Status)
	}
	plain, secret, err := c.partitionServiceEnv(svc)
	if err != nil {
		return nil, err
	}
	params := applySpec(c.params(svc.K8sName, svc.Image, plain, int32(svc.Replicas), c.pkgDir(svc.PackageID)), svc.Spec)
	params.EnvSecret = secret
	ing, err := c.applyAllPublic(params, svc.EndpointPublic())
	if err != nil {
		c.transition(svc, store.StatusError, "start again apply failed: "+err.Error())
		return nil, err
	}
	// 重新上线即重建全部对象，Ingress 已按当前配置形态刷新（path/host 模式切换的存量
	// 服务在此收敛），故同步重算展示地址落库；回写失败只记日志不阻断（apply 已成功，
	// 状态机应继续推进，避免因展示字段写入失败把服务卡在 error）。
	if svc.EndpointPublic() {
		c.refreshEndpoint(svc, ing)
	}
	c.transition(svc, store.StatusDeploying, "started again")
	c.asyncWaitAndRegister(svc.ID)
	return svc, nil
}

// Unpublish 下线：删 Ingress/Service/Deployment，保留 CM 与数据。
func (c *Core) Unpublish(id uint) (*store.ServiceEntity, error) {
	svc, err := c.Get(id)
	if err != nil {
		return nil, err
	}
	ns := c.Cfg.Namespace
	_ = c.K8s.DeleteIngress(ns, svc.K8sName)
	_ = c.K8s.DeleteService(ns, svc.K8sName+"-svc")
	_ = c.K8s.DeleteDeployment(ns, svc.K8sName)
	c.transition(svc, store.StatusStopped, "unpublished")
	return svc, nil
}

// Delete 删除服务及全部关联资源（CM、引用计数、PVC 目录、DB 记录）。
func (c *Core) Delete(id uint) error {
	svc, err := c.Get(id)
	if err != nil {
		return err
	}
	ns := c.Cfg.Namespace
	_ = c.K8s.DeleteIngress(ns, svc.K8sName)
	_ = c.K8s.DeleteService(ns, svc.K8sName+"-svc")
	_ = c.K8s.DeleteDeployment(ns, svc.K8sName)
	_ = c.K8s.DeleteConfigMap(ns, svc.K8sName+"-env")
	_ = c.K8s.DeleteSecret(ns, k8s.EnvSecretName(svc.K8sName))
	// 引用计数原子递减（CASE WHEN 防负数，MySQL 8 / SQLite 兼容）。
	// 旧实现为"事务内 First 读出 refCount 再应用侧减 1 判断归零"，
	// 并发发布/删除时存在读-改-写竞态：误判归零触发 RemovePackage，
	// 清掉仍被其他服务引用的包目录（业务 Pod /config 变空 → AGENTS.md not found CrashLoop）。
	err = c.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&store.OafPackage{}).Where("id = ?", svc.PackageID).
			UpdateColumn("ref_count", gorm.Expr("CASE WHEN ref_count > 0 THEN ref_count - 1 ELSE 0 END")).Error; err != nil {
			return err
		}
		if err := tx.Where("service_id = ?", svc.ID).Delete(&store.ServiceEvent{}).Error; err != nil {
			return err
		}
		return tx.Delete(svc).Error
	})
	if err != nil {
		return err
	}

	// 包目录不再随服务删除清理（issue #140）：旧逻辑 ref_count 归零即 RemovePackage，
	// 但 oaf_packages 记录保留，产生「记录在、文件没了」的悬空包（预览/下载/再发布全
	// 404）。包是独立资产（可再发布、版本链可回溯），与显式删包 API（Packages.Delete，
	// 记录+目录同删）保持一致：服务删除只递减引用计数，文件保留。
	// 存量悬空包（目录已清、记录在）无法自动识别，需要管理员经删包 API 手工清理。
	return nil
}

// ---- 内部工具 ----

func (c *Core) params(k8sName, image string, env map[string]string, replicas int32, subPath string) k8s.ObjectParams {
	return k8s.ObjectParams{
		K8sName: k8sName, Namespace: c.Cfg.Namespace, Image: image,
		Env: env, Replicas: replicas, SubPath: subPath, PVCName: c.Cfg.PlatformPVCName,
		IngressClass: c.Cfg.IngressClass, IngressHost: c.Cfg.IngressHost, IngressPort: c.Cfg.IngressPort,
		IngressHostSuffix: c.Cfg.IngressHostSuffix,
		RequestsCPU:       c.Cfg.ResCPU, RequestsMem: c.Cfg.ResMem, LimitsCPU: c.Cfg.LimCPU, LimitsMem: c.Cfg.LimMem,
	}
}

// refreshEndpoint 重算展示地址并落库。供 StartAgain 调用：该路径会重新 apply 全部
// 对象，配置（如 INGRESS_HOST_SUFFIX）变更后展示地址需跟随新形态收敛（host→router
// 模式切换的存量服务在此收敛；router 模式 ing 恒 nil，纯配置拼装）。写库失败只记
// 日志不返回错误（同 transition 风格：非核心字段不阻断状态机推进）。
func (c *Core) refreshEndpoint(svc *store.ServiceEntity, ing *networkingv1.Ingress) {
	endpoint := k8s.RouterEndpoint(c.Cfg.IngressHost, c.Cfg.IngressPort, svc.K8sName)
	if ing != nil {
		endpoint = k8s.IngressEndpoint(ing, c.Cfg.IngressHost)
	}
	if err := c.DB.Model(svc).Update("endpoint", endpoint).Error; err != nil {
		log.Printf("[endpoint] update svc=%d failed: %v", svc.ID, err)
		return
	}
	svc.Endpoint = endpoint
}

// deriveEndpoint 按路由模式派生展示地址：host 模式经 IngressBuilder Build（合并后
// overlay 决定 host/TLS，非法模板在此报错实现发布期 fail-fast）；router 模式纯配置
// 拼装（RouterEndpoint，形状与原 path 模式一致，前端/DB/MCP 契约零变化）。
func (c *Core) deriveEndpoint(p k8s.ObjectParams) (string, error) {
	if c.Cfg.IngressHostSuffix == "" {
		return k8s.RouterEndpoint(c.Cfg.IngressHost, c.Cfg.IngressPort, p.K8sName), nil
	}
	ing, err := c.ingressBuilder().Build(p)
	if err != nil {
		return "", fmt.Errorf("ingress: %w", err)
	}
	return k8s.IngressEndpoint(ing, c.Cfg.IngressHost), nil
}

// uniqName 冲突时追加 -2/-3… 后缀（查库去重，最多 20 次）。
func (c *Core) uniqName(base string) (string, error) {
	name := base
	for i := 2; ; i++ {
		var cnt int64
		if err := c.DB.Model(&store.ServiceEntity{}).Where("k8s_name = ?", name).Count(&cnt).Error; err != nil {
			return "", err
		}
		if cnt == 0 {
			return name, nil
		}
		if i > 20 {
			return "", errors.New("too many name collisions")
		}
		suffix := fmt.Sprintf("-%d", i)
		name = fmt.Sprintf("%s-%d", base, i)
		if len(name) > 63 {
			// base 接近 63 上限时收缩 base 预算给后缀（issue #149：直接报错会卡死发布）
			trimmed := strings.TrimRight(base[:63-len(suffix)], "-")
			name = trimmed + suffix
		}
	}
}

// translateStorageError 将 MySQL 驱动层错误翻译为业务哨兵（部署环境 driver 未开
// TranslateError，duplicate/超长不会映射为 gorm.ErrDuplicatedKey 等，裸错误会经
// mapError/mapCreateError 以 500/502 直出并泄漏列名——issue #149/#151）。
func translateStorageError(err error) error {
	if err == nil {
		return nil
	}
	msg := err.Error()
	lower := strings.ToLower(msg)
	// mysql 1062 / sqlite "UNIQUE constraint failed"（单测内存库）统一归 ErrConflict
	if strings.Contains(lower, "duplicate") || strings.Contains(msg, "1062") ||
		strings.Contains(lower, "unique constraint") {
		return fmt.Errorf("%w: duplicate_name (unique key conflict)", ErrConflict)
	}
	if strings.Contains(msg, "1406") || strings.Contains(lower, "data too long") {
		return fmt.Errorf("%w: input too long for storage", ErrInvalidForm)
	}
	return err
}

func (c *Core) pkgDir(pkgID uint) string {
	return fmt.Sprintf("packages/%d", pkgID)
}

// transition 状态流转 + 落事件 + 回写实体。
func (c *Core) transition(svc *store.ServiceEntity, to, reason string) {
	from := svc.Status
	err := c.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).
			Updates(map[string]interface{}{"status": to}).Error; err != nil {
			return err
		}
		return tx.Create(&store.ServiceEvent{ServiceID: svc.ID, FromStatus: from, ToStatus: to, Reason: reason}).Error
	})
	if err != nil {
		log.Printf("[transition] db error svc=%d: %v", svc.ID, err)
	}
	svc.Status = to
	log.Printf("[transition] svc=%d %s -> %s (%s)", svc.ID, from, to, reason)
	// M5 模型下发挂载点：服务转 running 后触发（异步，失败不影响状态机）
	if to == store.StatusRunning && c.OnServiceRunning != nil {
		go func(id uint) {
			defer func() { _ = recover() }()
			c.OnServiceRunning(id)
		}(svc.ID)
	}
}

func recordEvent(tx *gorm.DB, svcID uint, from, to, reason string) error {
	return tx.Create(&store.ServiceEvent{ServiceID: svcID, FromStatus: from, ToStatus: to, Reason: reason}).Error
}

func int32Or(v int32) int32 {
	if v < 1 {
		return 1
	}
	return v
}

// dropEmptyEnv 就地剔除空值键（发布路径的空值过滤，设计 §3.3「空值永不进入 CM/Secret」）。
func dropEmptyEnv(m map[string]string) {
	for k, v := range m {
		if v == "" {
			delete(m, k)
		}
	}
}

func orStr(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

func mustJSON(v interface{}) string {
	if v == nil {
		return "{}"
	}
	b, err := json.Marshal(v)
	if err != nil {
		return "{}"
	}
	return string(b)
}
