package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"

	"gorm.io/gorm"

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
}

// ConfigView 发布所需的最小配置集（避免直接依赖 config.Config，便于测试）。
// ImageOption 可选镜像项（MCP list_images 对外暴露；镜像名按环境而异，不写死）。
type ImageOption struct {
	Image string `json:"image"`
	Label string `json:"label,omitempty"`
}

type ConfigView struct {
	Namespace                      string
	IngressClass                   string
	IngressHost                    string
	IngressPort                    int
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
}

// NewCore 组装业务层。
func NewCore(db *gorm.DB, fs *store.FS, kc k8s.Client, cv ConfigView) *Core {
	return &Core{DB: db, FS: fs, K8s: kc, Cfg: cv,
		Packages: &PackageService{DB: db, FS: fs}}
}

// Publish 创建全套 K8s 资源并异步等待就绪+注册。
func (c *Core) Publish(req PublishRequest) (*store.ServiceEntity, error) {
	if err := ValidateEnv(req.Env); err != nil {
		return nil, err
	}
	if err := validateSecretKeys(req.SecretKeys); err != nil {
		return nil, err
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

	k8sName := k8s.DeriveK8sName(req.Name, pkg.Slug)
	k8sName, err = c.uniqName(k8sName)
	if err != nil {
		return nil, err
	}

	// 敏感路由：模板敏感键 + secretKeys 进 EnvSecret（服务 Secret），其余进 Env（CM）
	plain, secret := splitUserEnv(req.Env, req.SecretKeys)
	params := c.params(k8sName, image, plain, int32Or(req.Replicas), pkg.DirPath)
	params.EnvSecret = secret
	// Ingress overlay 纯函数前置：非法 overlay 直接拒绝且不落库（与 image 校验同级，
	// 模板违规属平台配置错误，不产生 deploying/error 垃圾记录）；同时派生展示
	// endpoint——模板可改 host/path，公式随合并后 Ingress 走（无 overlay 时与旧公式一致）。
	ing, err := c.ingressBuilder().Build(params)
	if err != nil {
		return nil, fmt.Errorf("ingress: %w", err)
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
		Endpoint:      k8s.IngressEndpoint(ing, c.Cfg.IngressHost, c.Cfg.IngressPort),
		ClusterURL:    fmt.Sprintf("http://%s-svc.%s.svc.cluster.local:%d", k8sName, c.Cfg.Namespace, k8s.AgentPort),
		ShortName:     k8s.ShortName(k8sName),
	}

	svc.Status = store.StatusDeploying // 落库即为 deploying（事件保留 created→deploying 审计）
	err = c.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(svc).Error; err != nil {
			return err
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

	if err := c.applyAll(params); err != nil {
		c.transition(svc, store.StatusError, "apply failed: "+err.Error())
		return nil, err
	}
	c.asyncWaitAndRegister(svc.ID)
	return svc, nil
}

// applyAll 幂等创建/更新服务 env 两级对象+Deployment+Service+Ingress。
// Deployment/Ingress 均经 builder（内置构造 + 可选环境 overlay 合并 + 不变量校验），
// overlay 违规视为 apply 失败，服务转 error 状态。Build 纯函数零成本前置：
// 非法 overlay 时任何对象不落半套资源。
func (c *Core) applyAll(p k8s.ObjectParams) error {
	dep, err := c.deployBuilder().Build(p)
	if err != nil {
		return fmt.Errorf("deployment: %w", err)
	}
	ing, err := c.ingressBuilder().Build(p)
	if err != nil {
		return fmt.Errorf("ingress: %w", err)
	}
	if err := c.applyServiceEnvObjects(p); err != nil {
		return err
	}
	if err := c.K8s.EnsureService(k8s.Service(p)); err != nil {
		return fmt.Errorf("service: %w", err)
	}
	if err := c.K8s.EnsureIngress(ing); err != nil {
		return fmt.Errorf("ingress: %w", err)
	}
	if err := c.K8s.EnsureDeployment(dep); err != nil {
		return fmt.Errorf("deployment: %w", err)
	}
	return nil
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

	// Ingress overlay 纯函数前置（事务前）：非法 overlay 直接拒绝，库与 K8s 均保持
	// 原状；同时派生展示 endpoint 随事务回写（模板/配置变更后展示地址跟随合并结果）。
	params := c.params(svc.K8sName, image, plain, replicas, c.pkgDir(newPkgID))
	params.EnvSecret = secret
	ing, err := c.ingressBuilder().Build(params)
	if err != nil {
		return nil, fmt.Errorf("ingress: %w", err)
	}
	endpoint := k8s.IngressEndpoint(ing, c.Cfg.IngressHost, c.Cfg.IngressPort)

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
		}
		return tx.Model(svc).Updates(updates).Error
	})
	if err != nil {
		return nil, err
	}
	svc.PackageID, svc.Image, svc.Replicas = newPkgID, image, int(replicas)
	svc.EnvJSON, svc.EnvSecretJSON = mustJSON(plain), mustJSON(secret)
	svc.Endpoint = endpoint

	if err := c.applyAll(params); err != nil {
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
	params := c.params(svc.K8sName, svc.Image, plain, int32(svc.Replicas), c.pkgDir(svc.PackageID))
	params.EnvSecret = secret
	if err := c.applyAll(params); err != nil {
		c.transition(svc, store.StatusError, "start again apply failed: "+err.Error())
		return nil, err
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

	// 目录清理移出事务：按事务提交后的最终引用计数判定，
	// 与并发发布（ref_count+1）天然互斥，只有真正归零才清理 PVC 目录。
	var pkg store.OafPackage
	if err := c.DB.First(&pkg, svc.PackageID).Error; err == nil && pkg.RefCount <= 0 {
		if rmErr := c.FS.RemovePackage(pkg.DirPath); rmErr != nil {
			log.Printf("[delete] remove package dir %s failed: %v", pkg.DirPath, rmErr)
		} else {
			log.Printf("[delete] package %d dir %s removed (ref_count=0)", pkg.ID, pkg.DirPath)
		}
	}
	return nil
}

// ---- 内部工具 ----

func (c *Core) params(k8sName, image string, env map[string]string, replicas int32, subPath string) k8s.ObjectParams {
	return k8s.ObjectParams{
		K8sName: k8sName, Namespace: c.Cfg.Namespace, Image: image,
		Env: env, Replicas: replicas, SubPath: subPath,
		IngressClass: c.Cfg.IngressClass, IngressHost: c.Cfg.IngressHost, IngressPort: c.Cfg.IngressPort,
		RequestsCPU: c.Cfg.ResCPU, RequestsMem: c.Cfg.ResMem, LimitsCPU: c.Cfg.LimCPU, LimitsMem: c.Cfg.LimMem,
	}
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
		name = fmt.Sprintf("%s-%d", base, i)
		if len(name) > 63 {
			return "", errors.New("cannot derive unique k8s name within 63 chars")
		}
	}
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
