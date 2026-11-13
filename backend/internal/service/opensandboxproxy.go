// OpenSandbox 管理面代理（sandbox-pool-opensandbox-design §5）：
//   - 配置事实源 = platform_config（GetPlatformDefaults 读 OPENSANDBOX_SERVER_URL / OPENSANDBOX_API_KEY），
//     任一缺失 → ErrSandboxNotConfigured（handler 映射 503，提示先在 /settings 配置）；
//   - 无状态代理：不做缓存、不做后台同步、无 ticker（控制面红线）——对 opensandbox 的写
//     仅来自显式 admin API（§5.4 合规：无新表、无后台任务、无对业务 K8s 资源的写操作）；
//   - HTTP do-helper 参照 modelconn.go httpGetJSON/httpJSON 的纪律（1MB 限读、显式超时、
//     评审 d12：两 helper 把非 2xx 折叠为字符串且不回数值状态码、GET/PUT/DELETE 携带
//     OPEN-SANDBOX-API-KEY 无现成通路，故自写返回 (status, body) 的小 helper）；
//     超时纪律：GET 5s、管理操作 10s（§5.1）；
//   - 上游错误映射（按 HTTP 状态码判定，评审 d19：401 的 INVALID_API_KEY 字面量在
//     release-1.1.0 spec/schema 中未出现）：501 → 503（部署形态提示）、401 → 502（配置
//     错误指向 /settings）、429/5xx/其余非 2xx 原样透传（body 摘录透出）、上游不可达/超时 → 502。
package service

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	"k8s.io/apimachinery/pkg/util/validation"

	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/store"
)

// ErrSandboxNotConfigured opensandbox 管理面未配置（缺 URL / API Key）→ handler 503。
var ErrSandboxNotConfigured = errors.New("opensandbox is not configured (set OPENSANDBOX_SERVER_URL / OPENSANDBOX_API_KEY in /settings)")

// SandboxUpstreamError 上游错误映射结果：Status 为响应给前端的 HTTP 状态码，Msg 用户可读。
type SandboxUpstreamError struct {
	Status int
	Msg    string
}

func (e *SandboxUpstreamError) Error() string { return e.Msg }

// 池 Pod 预设模板常量（§5.2：模板由后端预设生成，不接收自由 PodTemplateSpec JSON，
// 避免任意 Pod spec 注入面）。逐字段复刻上游官方 Pool sample
// （opensandbox kubernetes/config/samples/sandbox_v1alpha1_pool.yaml，评审 d18：
// command/env/volumes/initContainers 全量，image 参数只替换主容器镜像）；
// installer 镜像 tag 与 §8 deploy-opensandbox.sh 载入 kind 的 release-1.1.0 BOM 同源。
const (
	sandboxPresetTaskExecutorImage = "docker.io/opensandbox/task-executor:release-1.1.0"
	sandboxPresetExecdImage        = "docker.io/opensandbox/execd:release-1.1.0"
	// 主容器镜像兜底：platform_config 未配置 SANDBOX_IMAGE 时的缺省（模板 placeholder 同值）
	sandboxPresetImageFallback = "opensandbox/code-interpreter:v1.1.0"
	// 池主容器名与 task-executor 监听地址（官方 sample 原样；SANDBOX_MAIN_CONTAINER 指向它）
	sandboxPresetMainContainer = "sandbox"
	sandboxPresetExecdPort     = "0.0.0.0:5758"
	// commandWrapper 固定脚本名（skills-syncd §5.2：镜像仅提供二进制与 launcher 载体，
	// 池化路径启动点由 preset 注入）
	sandboxPresetLauncherPath = "/usr/local/bin/skillsync-launcher.sh"
	// 池名与服务绑定后缀（C-1/C-2：池名必须为 {服务 k8s 名}-pool 且服务存在）
	sandboxPoolServiceSuffix = "-pool"
	// extraEnv 数量上限（skills-syncd §4.2 守护 env 键集 ×2 余量）
	sandboxPoolExtraEnvMax = 8
)

// 技能同步缺省注入三键（PR-4a，skills-syncd §5.2 示例模板）：BASE_URL 按 {k8s 名}-svc
// 集群内地址派生（与 docs/deployment.md「技能同步」egress 放行 FQDN 同形，dns+nft 策略
// 按守护实际拨号域名匹配）；TOKEN secretKeyRef 引用 per-service Secret（k8s.SkillsyncTokenName，
// 消费方落点 ns k8s.SandboxWorkloadNamespace）；INTERVAL 取守护缺省 1m。
const (
	skillsyncEnvBaseURL  = "SKILLSYNC_BASE_URL"
	skillsyncEnvInterval = "SKILLSYNC_INTERVAL"
	skillsyncInterval    = "1m"
)

// sandboxPoolExtraEnvKeyRe extraEnv 键白名单：仅 SKILLSYNC_ 前缀（§5.2 受控参数化，
// 不接受任意 env 注入面）。
var sandboxPoolExtraEnvKeyRe = regexp.MustCompile(`^SKILLSYNC_[A-Z0-9_]+$`)

// SandboxCapacitySpec 池容量四键（上游 PoolCapacitySpec，int32 全必填）。
type SandboxCapacitySpec struct {
	BufferMin int32 `json:"bufferMin"`
	BufferMax int32 `json:"bufferMax"`
	PoolMin   int32 `json:"poolMin"`
	PoolMax   int32 `json:"poolMax"`
}

// validate 校验链：0 ≤ poolMin ≤ bufferMin ≤ bufferMax ≤ poolMax（§5.2）。
func (s SandboxCapacitySpec) validate() error {
	if s.PoolMin < 0 || s.BufferMin < 0 || s.BufferMax < 0 || s.PoolMax < 0 {
		return fmt.Errorf("%w: capacitySpec 值不可为负", ErrInvalidForm)
	}
	if !(s.PoolMin <= s.BufferMin && s.BufferMin <= s.BufferMax && s.BufferMax <= s.PoolMax) {
		return fmt.Errorf("%w: capacitySpec 必须满足 0 ≤ poolMin ≤ bufferMin ≤ bufferMax ≤ poolMax（got poolMin=%d bufferMin=%d bufferMax=%d poolMax=%d）",
			ErrInvalidForm, s.PoolMin, s.BufferMin, s.BufferMax, s.PoolMax)
	}
	return nil
}

// SandboxPoolEnvSecretKeyRef extraEnv 的 Secret 引用形态（K8s EnvVar valueFrom 同形）。
type SandboxPoolEnvSecretKeyRef struct {
	Name string `json:"name"`
	Key  string `json:"key"`
}

// SandboxPoolExtraEnv 池主容器注入的受控 env 项：value 或 secretKeyRef 二选一。
type SandboxPoolExtraEnv struct {
	Name         string                      `json:"name"`
	Value        string                      `json:"value,omitempty"`
	SecretKeyRef *SandboxPoolEnvSecretKeyRef `json:"secretKeyRef,omitempty"`
}

// CreateSandboxPoolInput 创建池入参。评审 d7：recycleStrategy 不是上游 POST /v1/pools 的
// 请求字段（CreatePoolRequest 仅 name/template/capacitySpec，release-1.1.0 schema.py
// 实证），收了会被 FastAPI 静默忽略——契约不收，回收策略取 CRD 默认 Delete（V4 语义不变）。
// 但设计 §5.2 承诺枚举校验（黑盒第 1 轮实锤：recycleStrategy:"Bogus" → 201 静默丢弃），
// 故入口侧仍做白名单校验（合法值接受但不透传，非法值 400 拒绝）。
// 技能同步默认开启（PR-4a，skills-syncd §11）：绑定了服务的新建池缺省注入 SKILLSYNC_*
// extraEnv + commandWrapper（D-1 内联守卫，偏斜退化为无守护旧行为）；DisableSyncd 为
// 显式关闭入参（灰度/排障/回滚 runbook「池模板参数摘除」的替代）——true = 模板逐字段与
// 历史形态一致（旧代码 dormant 保留，拨开关可完整回退）。ExtraEnv 为 C-2 手动通道：
// 键级覆盖缺省派生值（同名后者胜）或追加其他 SKILLSYNC_* 键；不接受自由 PodSpec。
type CreateSandboxPoolInput struct {
	Name            string                `json:"name"`
	Image           string                `json:"image,omitempty"`
	CapacitySpec    SandboxCapacitySpec   `json:"capacitySpec"`
	RecycleStrategy string                `json:"recycleStrategy,omitempty"`
	ExtraEnv        []SandboxPoolExtraEnv `json:"extraEnv,omitempty"`
	DisableSyncd    bool                  `json:"disableSyncd,omitempty"`
}

// validateSandboxExtraEnv 受控 extraEnv 校验：键白名单 + 形态二选一 + 引用完整性 +
// 重复键 + 数量上限；违规一律 ErrInvalidForm（handler 400）。
func validateSandboxExtraEnv(extraEnv []SandboxPoolExtraEnv) error {
	if len(extraEnv) > sandboxPoolExtraEnvMax {
		return fmt.Errorf("%w: extraEnv 数量超上限 %d（got %d）", ErrInvalidForm, sandboxPoolExtraEnvMax, len(extraEnv))
	}
	seen := make(map[string]bool, len(extraEnv))
	for i, e := range extraEnv {
		if !sandboxPoolExtraEnvKeyRe.MatchString(e.Name) {
			return fmt.Errorf("%w: extraEnv[%d] 键 %q 不在白名单（^SKILLSYNC_[A-Z0-9_]+$）", ErrInvalidForm, i, e.Name)
		}
		if seen[e.Name] {
			return fmt.Errorf("%w: extraEnv 键重复 %q", ErrInvalidForm, e.Name)
		}
		seen[e.Name] = true
		switch {
		case e.SecretKeyRef == nil:
			if e.Value == "" {
				return fmt.Errorf("%w: extraEnv[%d] %q 须携带 value 或 secretKeyRef 之一", ErrInvalidForm, i, e.Name)
			}
		case e.Value != "":
			return fmt.Errorf("%w: extraEnv[%d] %q 的 value 与 secretKeyRef 互斥", ErrInvalidForm, i, e.Name)
		case e.SecretKeyRef.Name == "" || e.SecretKeyRef.Key == "":
			return fmt.Errorf("%w: extraEnv[%d] %q 的 secretKeyRef 须含 name 与 key", ErrInvalidForm, i, e.Name)
		}
	}
	return nil
}

// validateSandboxPoolBinding 池名/服务绑定校验（C-1 校验预建池 + C-2 手动通道同端点，
// G1 池与服务一一对应）：池名必须为 {服务 k8s 名}-pool 且该服务存在
// （ServiceEntity.K8sName uniqueIndex 保证绑定唯一）；池名全局唯一由上游同名创建 409 兜底。
// 返回解析出的服务 k8s 名（技能同步缺省注入按其派生 BASE_URL/Secret 名）。
func (c *Core) validateSandboxPoolBinding(name string) (string, error) {
	k8sName, ok := strings.CutSuffix(name, sandboxPoolServiceSuffix)
	if !ok || k8sName == "" {
		return "", fmt.Errorf("%w: pool 名必须为 {服务 k8s 名}-pool（got %q）", ErrInvalidForm, name)
	}
	var cnt int64
	if err := c.DB.Model(&store.ServiceEntity{}).Where("k8s_name = ?", k8sName).Count(&cnt).Error; err != nil {
		return "", err
	}
	if cnt == 0 {
		return "", fmt.Errorf("%w: pool %q 未绑定已存在的服务（k8s 名 %q 不存在）", ErrInvalidForm, name, k8sName)
	}
	return k8sName, nil
}

// validateRecycleStrategy 设计 §5.2 枚举校验：空（缺省）或 Delete/Restart/Noop 合法，
// 其余 400。字段不透传上游（d7），上游按 CRD 默认 Delete。
func validateRecycleStrategy(v string) error {
	switch v {
	case "", "Delete", "Restart", "Noop":
		return nil
	default:
		return fmt.Errorf("%w: recycleStrategy 非法值 %q（合法：Delete / Restart / Noop）", ErrInvalidForm, v)
	}
}

// sandboxConfig 代理出所需的平台配置切片。
type sandboxConfig struct{ base, apiKey string }

// sandboxProxyConfig 读取配置事实源；任一键缺失返回 ErrSandboxNotConfigured。
// URL 允许省略 scheme（placeholder 与平台配置均为 host:port 形态）→ 统一补 http://。
func (c *Core) sandboxProxyConfig() (sandboxConfig, error) {
	values, err := c.GetPlatformDefaults()
	if err != nil {
		return sandboxConfig{}, fmt.Errorf("read platform config: %w", err)
	}
	base := strings.TrimSpace(values["OPENSANDBOX_SERVER_URL"])
	key := strings.TrimSpace(values["OPENSANDBOX_API_KEY"])
	if base == "" || key == "" {
		return sandboxConfig{}, ErrSandboxNotConfigured
	}
	if !strings.Contains(base, "://") {
		base = "http://" + base
	}
	return sandboxConfig{base: strings.TrimRight(base, "/"), apiKey: key}, nil
}

// sandboxDo 参照 modelconn.go httpGetJSON/httpJSON 纪律的小 do-helper（d12）：
// 1MB 限读、显式超时、注入 OPEN-SANDBOX-API-KEY，返回 (status, body) 不折叠非 2xx。
func (p sandboxConfig) do(method, path string, body []byte, timeout time.Duration) (int, []byte, error) {
	var rd io.Reader
	if body != nil {
		rd = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, p.base+path, rd)
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("OPEN-SANDBOX-API-KEY", p.apiKey)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	client := &http.Client{Timeout: timeout}
	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return resp.StatusCode, nil, err
	}
	return resp.StatusCode, data, nil
}

// mapSandboxUpstream 上游结果 → 统一错误面（§5.1 映射矩阵，见文件头注释）。
func mapSandboxUpstream(status int, body []byte, err error) error {
	if err != nil {
		return &SandboxUpstreamError{Status: http.StatusBadGateway,
			Msg: fmt.Sprintf("opensandbox 不可达: %v", err)}
	}
	if status >= 200 && status < 300 {
		return nil
	}
	switch status {
	case http.StatusNotImplemented: // 上游 K8S_POOL_NOT_SUPPORTED：非 kubernetes runtime 部署
		return &SandboxUpstreamError{Status: http.StatusServiceUnavailable,
			Msg: "opensandbox 需以 kubernetes runtime 部署（当前 runtime 不支持池管理 API）"}
	case http.StatusUnauthorized:
		return &SandboxUpstreamError{Status: http.StatusBadGateway,
			Msg: "opensandbox API Key 校验失败（配置错误，请在 /settings 修正 OPENSANDBOX_API_KEY）"}
	}
	// 429/5xx/其余 4xx 原样透传（body 摘录透出）
	return &SandboxUpstreamError{Status: status,
		Msg: fmt.Sprintf("opensandbox upstream HTTP %d: %s", status, truncateBytes(body, 200))}
}

// validateSandboxName 池名 / 沙箱 id 的路径段校验：DNS-1123 label（§5.2 name 校验；
// 兼防 :id 路径注入——上游沙箱 id 为 uuid/域名安全字符集）。
func validateSandboxName(name string) error {
	if name == "" {
		return fmt.Errorf("%w: name is required", ErrInvalidForm)
	}
	if errs := validation.IsDNS1123Label(name); len(errs) > 0 {
		return fmt.Errorf("%w: invalid name %q: %s", ErrInvalidForm, name, strings.Join(errs, "; "))
	}
	return nil
}

const (
	sandboxGetTimeout = 5 * time.Second  // 只读拉取
	sandboxOpTimeout  = 10 * time.Second // 管理操作
)

// ---- admin 端点业务层（§5.2 契约）----

// AdminSandboxPools GET /api/v1/admin/sandboxes/pools → GET /v1/pools（原样透传）。
func (c *Core) AdminSandboxPools() (json.RawMessage, error) {
	cfg, err := c.sandboxProxyConfig()
	if err != nil {
		return nil, err
	}
	status, body, err := cfg.do(http.MethodGet, "/v1/pools", nil, sandboxGetTimeout)
	if err := mapSandboxUpstream(status, body, err); err != nil {
		return nil, err
	}
	return json.RawMessage(body), nil
}

// AdminGetSandboxPool GET /api/v1/admin/sandboxes/pools/:name → GET /v1/pools/{name}。
func (c *Core) AdminGetSandboxPool(name string) (json.RawMessage, error) {
	if err := validateSandboxName(name); err != nil {
		return nil, err
	}
	cfg, err := c.sandboxProxyConfig()
	if err != nil {
		return nil, err
	}
	status, body, err := cfg.do(http.MethodGet, "/v1/pools/"+name, nil, sandboxGetTimeout)
	if err := mapSandboxUpstream(status, body, err); err != nil {
		return nil, err
	}
	return json.RawMessage(body), nil
}

// AdminCreateSandboxPool POST /api/v1/admin/sandboxes/pools → POST /v1/pools
// （Pod 模板由后端预设常量生成；image 缺省取平台默认 SANDBOX_IMAGE）。
// 技能同步缺省注入（PR-4a）：DisableSyncd=false 且 per-service token Secret 已在池
// workload ns（k8s.SandboxWorkloadNamespace；§9.1 平台下发/管理员预建两路径统一以对象
// 存在为准）→ 注入三键 + commandWrapper（D-1 内联守卫，模板/镜像偏斜退化无守护旧行为）。
// Secret 缺失 = 服务未接入 token（agent-framework 端点空 token 即关闭）→ 按旧行为建池，
// 不让 secretKeyRef 引用不存在对象把 warm pod 卡死 CreateContainerConfigError
// （platformconfig/template.go SKILLSYNC_TOKEN 键注释同口径）；隐式按服务灰度，补配 token
// 后重建池即默认开启。DisableSyncd=true = 显式关闭（灰度/排障/回滚 runbook「池模板参数
// 摘除」的替代），与 extraEnv 互斥（关同步又携同步 env 是矛盾语义）。
func (c *Core) AdminCreateSandboxPool(in CreateSandboxPoolInput, actor uint) (json.RawMessage, error) {
	if err := validateSandboxName(in.Name); err != nil {
		return nil, err
	}
	if err := in.CapacitySpec.validate(); err != nil {
		return nil, err
	}
	if err := validateRecycleStrategy(in.RecycleStrategy); err != nil {
		return nil, err
	}
	if err := validateSandboxExtraEnv(in.ExtraEnv); err != nil {
		return nil, err
	}
	if in.DisableSyncd && len(in.ExtraEnv) > 0 {
		return nil, fmt.Errorf("%w: disableSyncd 与 extraEnv 互斥（显式关闭 = 完全旧行为）", ErrInvalidForm)
	}
	k8sName, err := c.validateSandboxPoolBinding(in.Name)
	if err != nil {
		return nil, err
	}
	image := strings.TrimSpace(in.Image)
	if image == "" {
		image = sandboxPresetImageFallback
		if defaults, err := c.GetPlatformDefaults(); err == nil {
			if v := strings.TrimSpace(defaults["SANDBOX_IMAGE"]); v != "" {
				image = v
			}
		}
	}
	extraEnv, wrapper, syncdMode := in.ExtraEnv, false, "disabled"
	if !in.DisableSyncd {
		exists, err := c.K8s.SecretExists(k8s.SandboxWorkloadNamespace, k8s.SkillsyncTokenName(k8sName))
		if err != nil {
			return nil, fmt.Errorf("skillsync token secret check: %w", err)
		}
		if exists {
			extraEnv, wrapper, syncdMode = withSkillsyncDefaults(k8sName, c.Cfg.Namespace, in.ExtraEnv), true, "default"
		} else {
			syncdMode = "no-token"
		}
	}
	cfg, err := c.sandboxProxyConfig()
	if err != nil {
		return nil, err
	}
	payload, err := json.Marshal(map[string]interface{}{
		"name":         in.Name,
		"template":     sandboxPresetPodTemplate(image, extraEnv, wrapper),
		"capacitySpec": in.CapacitySpec,
	})
	if err != nil {
		return nil, err
	}
	status, body, err := cfg.do(http.MethodPost, "/v1/pools", payload, sandboxOpTimeout)
	if err := mapSandboxUpstream(status, body, err); err != nil {
		return nil, err
	}
	// 管理操作审计（PlatformConfigEvent 同款先例：只记动作与对象，不记值；§10）；
	// syncd 记缺省注入三态（default/no-token/disabled），灰度观测按 D-5 result 维度口径区分
	c.recordAudit("sandbox.pool.create", in.Name, actor, map[string]interface{}{
		"capacitySpec": in.CapacitySpec, "image": image, "syncd": syncdMode,
	})
	return json.RawMessage(body), nil
}

// withSkillsyncDefaults 技能同步缺省三键与手动 extraEnv 的键级合并（PR-4a，skills-syncd
// §5.2 示例模板）：缺省 BASE_URL（{k8s 名}-svc 集群内地址，与 docs/deployment.md「技能
// 同步」egress 放行 FQDN 同形）/ TOKEN（secretKeyRef 引用 per-service Secret）/ INTERVAL
// 在前，manual 同名键覆盖缺省值（C-2 灰度/特殊服务通道），其余键按入参顺序追加。
func withSkillsyncDefaults(k8sName, namespace string, manual []SandboxPoolExtraEnv) []SandboxPoolExtraEnv {
	merged := []SandboxPoolExtraEnv{
		{Name: skillsyncEnvBaseURL, Value: fmt.Sprintf("http://%s-svc.%s.svc:%d", k8sName, namespace, k8s.AgentPort)},
		{Name: k8s.SkillsyncTokenEnvKey, SecretKeyRef: &SandboxPoolEnvSecretKeyRef{Name: k8s.SkillsyncTokenName(k8sName), Key: k8s.SkillsyncTokenDataKey}},
		{Name: skillsyncEnvInterval, Value: skillsyncInterval},
	}
	for _, m := range manual {
		replaced := false
		for i := range merged {
			if merged[i].Name == m.Name {
				merged[i] = m
				replaced = true
				break
			}
		}
		if !replaced {
			merged = append(merged, m)
		}
	}
	return merged
}

// AdminUpdateSandboxPool PUT /api/v1/admin/sandboxes/pools/:name → PUT /v1/pools/{name}
// （仅容量：上游 UpdatePoolRequest 只收 capacitySpec，改模板须删除重建）。
func (c *Core) AdminUpdateSandboxPool(name string, spec SandboxCapacitySpec, actor uint) (json.RawMessage, error) {
	if err := validateSandboxName(name); err != nil {
		return nil, err
	}
	if err := spec.validate(); err != nil {
		return nil, err
	}
	cfg, err := c.sandboxProxyConfig()
	if err != nil {
		return nil, err
	}
	payload, err := json.Marshal(map[string]interface{}{"capacitySpec": spec})
	if err != nil {
		return nil, err
	}
	status, body, err := cfg.do(http.MethodPut, "/v1/pools/"+name, payload, sandboxOpTimeout)
	if err := mapSandboxUpstream(status, body, err); err != nil {
		return nil, err
	}
	c.recordAudit("sandbox.pool.update", name, actor, map[string]interface{}{"capacitySpec": spec})
	return json.RawMessage(body), nil
}

// AdminDeleteSandboxPool DELETE /api/v1/admin/sandboxes/pools/:name → DELETE /v1/pools/{name}
// （上游 204；池管理的 warm Pod 由 controller 终止——前端删除确认提示据此）。
func (c *Core) AdminDeleteSandboxPool(name string, actor uint) error {
	if err := validateSandboxName(name); err != nil {
		return err
	}
	cfg, err := c.sandboxProxyConfig()
	if err != nil {
		return err
	}
	status, body, err := cfg.do(http.MethodDelete, "/v1/pools/"+name, nil, sandboxOpTimeout)
	if err := mapSandboxUpstream(status, body, err); err != nil {
		return err
	}
	c.recordAudit("sandbox.pool.delete", name, actor, map[string]interface{}{})
	return nil
}

// AdminListSandboxes GET /api/v1/admin/sandboxes?state=&page=&pageSize= → GET /v1/sandboxes
// （评审 d15：上游分页语法为 page/pageSize（≤200），设计表格的 limit/offset 不存在——
// 契约按 page/pageSize 透传；state 为上游状态过滤（单值））。
func (c *Core) AdminListSandboxes(state string, page, pageSize int) (json.RawMessage, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 200 {
		pageSize = 20
	}
	cfg, err := c.sandboxProxyConfig()
	if err != nil {
		return nil, err
	}
	q := fmt.Sprintf("/v1/sandboxes?page=%d&pageSize=%d", page, pageSize)
	if state != "" {
		// state 来自客户端 query，必须 QueryEscape 再拼上游串（评审遗留：含 &/空格的值
		// 原样透出会注入额外过滤参数或产生畸形请求行）
		q += "&state=" + url.QueryEscape(state)
	}
	status, body, err := cfg.do(http.MethodGet, q, nil, sandboxGetTimeout)
	if err := mapSandboxUpstream(status, body, err); err != nil {
		return nil, err
	}
	return json.RawMessage(body), nil
}

// AdminDeleteSandbox DELETE /api/v1/admin/sandboxes/:id → DELETE /v1/sandboxes/{id}（上游 204）。
func (c *Core) AdminDeleteSandbox(id string, actor uint) error {
	if err := validateSandboxName(id); err != nil {
		return err
	}
	cfg, err := c.sandboxProxyConfig()
	if err != nil {
		return err
	}
	status, body, err := cfg.do(http.MethodDelete, "/v1/sandboxes/"+id, nil, sandboxOpTimeout)
	if err := mapSandboxUpstream(status, body, err); err != nil {
		return err
	}
	c.recordAudit("sandbox.sandbox.delete", id, actor, map[string]interface{}{})
	return nil
}

// ---- 聚合与用户侧只读（§5.2 summary / §5.3 console）----

// SandboxSummaryView GET /api/v1/admin/sandboxes/summary 响应（§6.1 顶部 StatCard 数据源）。
type SandboxSummaryView struct {
	Pools            int `json:"pools"`            // 池总数
	WarmAvailable    int `json:"warmAvailable"`    // Σ status.available（未 reconcile 的池计 0，d19：status 可缺失）
	Allocated        int `json:"allocated"`        // Σ status.allocated
	RunningSandboxes int `json:"runningSandboxes"` // Running 状态沙箱数（上游 pagination.totalItems）
}

// sandboxPoolList 上游 GET /v1/pools 响应（仅取聚合所需字段）。
type sandboxPoolList struct {
	Items []struct {
		Status *struct {
			Available *int `json:"available"`
			Allocated *int `json:"allocated"`
		} `json:"status"`
	} `json:"items"`
}

// sandboxSandboxPage 上游 GET /v1/sandboxes 响应（仅取分页元数据）。
type sandboxSandboxPage struct {
	Pagination struct {
		TotalItems int  `json:"totalItems"`
		HasNext    bool `json:"hasNextPage"`
	} `json:"pagination"`
}

// AdminSandboxSummary GET /api/v1/admin/sandboxes/summary：GET /v1/pools +
// GET /v1/sandboxes?state=Running 聚合（d15：Running 数取分页元数据 totalItems，
// pageSize=1 不拉全量——设计的 ?limit= 在上游不存在）。
func (c *Core) AdminSandboxSummary() (*SandboxSummaryView, error) {
	cfg, err := c.sandboxProxyConfig()
	if err != nil {
		return nil, err
	}
	out := &SandboxSummaryView{}
	status, body, err := cfg.do(http.MethodGet, "/v1/pools", nil, sandboxGetTimeout)
	if err := mapSandboxUpstream(status, body, err); err != nil {
		return nil, err
	}
	var pools sandboxPoolList
	if err := json.Unmarshal(body, &pools); err != nil {
		return nil, fmt.Errorf("parse pools response: %w", err)
	}
	out.Pools = len(pools.Items)
	for _, p := range pools.Items {
		if p.Status == nil {
			continue
		}
		if p.Status.Available != nil {
			out.WarmAvailable += int(*p.Status.Available)
		}
		if p.Status.Allocated != nil {
			out.Allocated += int(*p.Status.Allocated)
		}
	}
	status, body, err = cfg.do(http.MethodGet, "/v1/sandboxes?state=Running&page=1&pageSize=1", nil, sandboxGetTimeout)
	if err := mapSandboxUpstream(status, body, err); err != nil {
		return nil, err
	}
	var page sandboxSandboxPage
	if err := json.Unmarshal(body, &page); err != nil {
		return nil, fmt.Errorf("parse sandboxes response: %w", err)
	}
	out.RunningSandboxes = page.Pagination.TotalItems
	return out, nil
}

// SandboxInstanceItem console 只读视图（§5.3：id / state / metadata.service /
// metadata.session_id / expiresAt / createdAt，无任何操作端点）。
type SandboxInstanceItem struct {
	ID        string `json:"id"`
	State     string `json:"state"`
	Service   string `json:"service,omitempty"`
	SessionID string `json:"sessionId,omitempty"`
	ExpiresAt string `json:"expiresAt,omitempty"`
	CreatedAt string `json:"createdAt,omitempty"`
}

// ConsoleSandboxInstances GET /api/v1/console/workspaces/:ws/sandboxes：
// 取该 ws 全部服务 k8s 名集合，转发 GET /v1/sandboxes 后按 metadata.service ∈ 集合过滤。
// 口径注记（评审 d4）：过滤集合 = ServiceEntity.K8sName（含 oaf- 前缀，objects.go Prefix
// 派生；ShortName 跨空间可撞名，不能入集合）——框架侧 create 注入 metadata.service
// 必须同口径（PR-D §4.2 ③），否则过滤恒空。POC 规模内存过滤（§12-4），逐页拉取封顶 10 页。
func (c *Core) ConsoleSandboxInstances(wsID uint) ([]SandboxInstanceItem, error) {
	var rows []store.ServiceEntity
	if err := c.DB.Select("k8s_name").Where("workspace_id = ?", wsID).Find(&rows).Error; err != nil {
		return nil, err
	}
	services := make(map[string]bool, len(rows))
	for _, r := range rows {
		services[r.K8sName] = true
	}
	cfg, err := c.sandboxProxyConfig()
	if err != nil {
		return nil, err
	}
	out := []SandboxInstanceItem{}
	page := 1
	for {
		status, body, err := cfg.do(http.MethodGet,
			fmt.Sprintf("/v1/sandboxes?page=%d&pageSize=200", page), nil, sandboxGetTimeout)
		if err := mapSandboxUpstream(status, body, err); err != nil {
			return nil, err
		}
		var pageResp struct {
			Items []struct {
				ID     string `json:"id"`
				Status struct {
					State string `json:"state"`
				} `json:"status"`
				Metadata  map[string]string `json:"metadata"`
				ExpiresAt string            `json:"expiresAt"`
				CreatedAt string            `json:"createdAt"`
			} `json:"items"`
			Pagination struct {
				HasNext bool `json:"hasNextPage"`
			} `json:"pagination"`
		}
		if err := json.Unmarshal(body, &pageResp); err != nil {
			return nil, fmt.Errorf("parse sandboxes response: %w", err)
		}
		for _, it := range pageResp.Items {
			if !services[it.Metadata["service"]] {
				continue
			}
			out = append(out, SandboxInstanceItem{
				ID: it.ID, State: it.Status.State,
				Service: it.Metadata["service"], SessionID: it.Metadata["session_id"],
				ExpiresAt: it.ExpiresAt, CreatedAt: it.CreatedAt,
			})
		}
		if !pageResp.Pagination.HasNext || page >= 10 {
			break
		}
		page++
	}
	return out, nil
}

// sandboxPresetPodTemplate 池 Pod 模板预设（官方 sample sandbox_v1alpha1_pool.yaml
// 逐字段复刻，d18：image 只替换主容器镜像）。extraEnv 追加主容器 env（secretKeyRef
// 形态经 valueFrom）；commandWrapper 开启时 args 换为 D-1 内联守卫字面（launcher 脚本
// 缺失 → 未加引号的 $W 展开为空 → 原命令原样执行，偏斜退化为无守护运行而非 crashloop）；
// 两参数缺省 = 模板逐字段与历史形态一致。
func sandboxPresetPodTemplate(mainImage string, extraEnv []SandboxPoolExtraEnv, commandWrapper bool) map[string]interface{} {
	taskExecutorCmd := fmt.Sprintf("/opt/opensandbox/task-executor -listen-addr=%s -log-dir=/tmp", sandboxPresetExecdPort)
	args := taskExecutorCmd
	if commandWrapper {
		args = fmt.Sprintf("W=%s; [ -x \"$W\" ] || W=\"\"; exec $W %s", sandboxPresetLauncherPath, taskExecutorCmd)
	}
	env := []map[string]interface{}{
		{"name": "SANDBOX_MAIN_CONTAINER", "value": "main"},
		{"name": "EXECD_ENVS", "value": "/opt/opensandbox/.env"},
		{"name": "EXECD", "value": "/opt/opensandbox/execd"},
	}
	for _, e := range extraEnv {
		m := map[string]interface{}{"name": e.Name}
		if e.SecretKeyRef != nil {
			m["valueFrom"] = map[string]interface{}{
				"secretKeyRef": map[string]interface{}{"name": e.SecretKeyRef.Name, "key": e.SecretKeyRef.Key},
			}
		} else {
			m["value"] = e.Value
		}
		env = append(env, m)
	}
	return map[string]interface{}{
		"metadata": map[string]interface{}{
			"labels": map[string]string{"app.kubernetes.io/name": "opensandbox"},
		},
		"spec": map[string]interface{}{
			"volumes": []map[string]interface{}{
				{"name": "sandbox-storage", "emptyDir": map[string]interface{}{}},
				{"name": "opensandbox-bin", "emptyDir": map[string]interface{}{}},
				{"name": "sandbox-logs", "emptyDir": map[string]interface{}{}},
			},
			"initContainers": []map[string]interface{}{
				{
					"name":    "task-executor-installer",
					"image":   sandboxPresetTaskExecutorImage,
					"command": []string{"/bin/sh", "-c"},
					"args":    []string{"cp /workspace/server /opt/opensandbox/task-executor && chmod +x /opt/opensandbox/task-executor"},
					"volumeMounts": []map[string]interface{}{
						{"name": "opensandbox-bin", "mountPath": "/opt/opensandbox"},
					},
				},
				{
					"name":    "execd-installer",
					"image":   sandboxPresetExecdImage,
					"command": []string{"/bin/sh", "-c"},
					"args":    []string{"cp ./execd /opt/opensandbox/execd && cp ./bootstrap.sh /opt/opensandbox/bootstrap.sh && chmod +x /opt/opensandbox/execd && chmod +x /opt/opensandbox/bootstrap.sh"},
					"volumeMounts": []map[string]interface{}{
						{"name": "opensandbox-bin", "mountPath": "/opt/opensandbox"},
					},
				},
			},
			"containers": []map[string]interface{}{
				{
					"name":    sandboxPresetMainContainer,
					"image":   mainImage,
					"command": []string{"/bin/sh", "-c"},
					"args":    []string{args},
					"env":     env,
					"volumeMounts": []map[string]interface{}{
						{"name": "sandbox-storage", "mountPath": "/var/lib/sandbox"},
						{"name": "opensandbox-bin", "mountPath": "/opt/opensandbox"},
						{"name": "sandbox-logs", "mountPath": "/workspace/logs"},
					},
				},
			},
			"tolerations": []map[string]interface{}{{"operator": "Exists"}},
		},
	}
}
