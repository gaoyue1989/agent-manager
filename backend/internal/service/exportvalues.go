// 导出 values/manifest 渲染（跨集群导出设计 §7，2026-10）。
// 文件级契约见 docs/implementation/cross-cluster-export-contract.md §5。
//
// 四层合并（§5.1，优先级低→高）：L0 chart 默认（helm 侧生效，平台不渲染）<
// L1 工作空间运行时默认（workspace_runtime_defaults）< L2 用户导出覆盖（仅新增，
// 撞 L1 已配置键/L3 保留键 400）< L3 平台生成（保留键 + 派生键，不可覆盖）。
// 敏感路由（§5.2）：platformconfig.SensitiveKeys() ∪ 请求 secretKeys → values secretEnv
// 段（chart 渲染为服务 Secret），其余 → env 段；空值键剔除（dropEmptyEnv 同语义）。
package service

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"time"

	"gopkg.in/yaml.v3"

	"agent-manager/backend/internal/imagebuilder"
	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/service/platformconfig"
	"agent-manager/backend/internal/store"
)

// exportMetaKeys 渲染期消费的导出参数键（runtimeExportKeys 去掉 OTEL_EXPORTER_OTLP_ENDPOINT）：
// 只驱动 image ref / imagePullSecrets / ingress / mysql.createDatabase 等结构化输出，
// 不作为 env 注入业务 Pod（与设计 §7.3 示例一致——env 段无 REGISTRY_ADDRESS/ROUTING_*）。
var exportMetaKeys = map[string]bool{
	"CHECKPOINT_JDBC_BASE": true, "REGISTRY_ADDRESS": true, "REGISTRY_NAMESPACE": true,
	"IMAGE_PULL_SECRETS": true, "ROUTING_MODE": true, "ROUTING_HOST_SUFFIX": true,
	"BASE_IMAGE_WHITELIST": true,
}

// defaultExportChartVersion chartVersion 缺省常量（契约 §4.3）。
const defaultExportChartVersion = "0.1.0"

// parseBaseImageWhitelist BASE_IMAGE_WHITELIST 解析（口径同 config.parseImages："ref|label"
// 逗号分隔）；L1 PUT 时已校验格式，此处解析失败按 400 兜底。
func parseBaseImageWhitelist(raw string) ([]ImageOption, error) {
	var out []ImageOption
	for _, part := range strings.Split(raw, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		img, label := part, ""
		if i := strings.Index(part, "|"); i >= 0 {
			img, label = strings.TrimSpace(part[:i]), strings.TrimSpace(part[i+1:])
		}
		if img == "" {
			return nil, fmt.Errorf("%w: invalid BASE_IMAGE_WHITELIST entry: %q", ErrInvalidForm, part)
		}
		out = append(out, ImageOption{Image: img, Label: label})
	}
	return out, nil
}

// buildOafImageRef OAF 配置镜像完整引用（契约 §5.4 tag 规则）：薄封装复用
// imagebuilder.TargetRef（U3 定义的单一事实源，§9-D12 `+`→`-` 同规则），
// 仅在边界做 registry 尾斜杠归一。
func buildOafImageRef(registryAddr, namespace, short, marker string) string {
	return imagebuilder.TargetRef(strings.TrimRight(registryAddr, "/"), namespace, short, marker)
}

// derivedExportEnv L3 平台生成 env（契约 §5.4 表，全部不可被 L2 覆盖）：
//   - 平台保留键四件（objects.go:18-19 + AgentPort 8100）；
//   - CHECKPOINT_JDBC_URL：L1 CHECKPOINT_JDBC_BASE 派生 `{基库}_{short}`（复用
//     publishisolation.go deriveCheckpointURL/checkpointDBNameFor，传 short 名，不复制逻辑）；
//     L1 未配置基库时不派生（chart mysql.createDatabase 同步为 false，建库交运维）；
//   - AGENT_REDIS_PREFIX：`{short}:`（复用 redisPrefixFor）。
func derivedExportEnv(merged map[string]string, short string) (map[string]string, error) {
	out := map[string]string{
		"AGENT_CONFIG_DIR":    "/config",
		"AGENT_WORKSPACE_DIR": "/workspace",
		"SERVER_HOST":         "0.0.0.0",
		"SERVER_PORT":         strconv.Itoa(k8s.AgentPort),
		"AGENT_REDIS_PREFIX":  redisPrefixFor(short),
	}
	if base := merged["CHECKPOINT_JDBC_BASE"]; base != "" {
		url, ok := deriveCheckpointURL(base, short)
		if !ok {
			return nil, fmt.Errorf("%w: CHECKPOINT_JDBC_BASE %q is not a derivable jdbc URL", ErrInvalidForm, base)
		}
		out["CHECKPOINT_JDBC_URL"] = url
	}
	return out, nil
}

// resolveExportLayers 创建导出单前的 L1/L2 裁决（L3 键空间对 L1/L2 结构性不可持有，
// 契约 §1.4/§5.4）：返回 L1 事实源、L1∪L2 合并结果（空值剔除）与镜像白名单。
// 校验：L2 仅新增（撞 L1 已配置键 400，锁定语义设计 §2.1/§10）、L2 撞导出参数键
// （exportMetaKeys——推送目标/路由/checkpoint 基库等基础设施输出仅取 L1，契约 §5.5）
// 与 L3 派生键均 400、baseImage 命中白名单（缺省取首项，模式同 config.go:122-124）、
// REGISTRY_ADDRESS 必配、host 模式 ROUTING_HOST_SUFFIX 必配且合法。
func (c *Core) resolveExportLayers(wsID uint, req *ExportOrderRequest) (l1, merged map[string]string, whitelist []ImageOption, err error) {
	l1, err = c.loadRuntimeDefaultValues(wsID)
	if err != nil {
		return nil, nil, nil, err
	}
	// L2 撞键裁决（§5.1）：env 键与 secretKeys 同口径（secretKeys 只是敏感标记，键本身同样不允许撞）
	l2Keys := make(map[string]bool, len(req.Env)+len(req.SecretKeys))
	for k := range req.Env {
		l2Keys[k] = true
	}
	for _, k := range req.SecretKeys {
		l2Keys[k] = true
	}
	for k := range l2Keys {
		if v, ok := l1[k]; ok && v != "" {
			return nil, nil, nil, fmt.Errorf("%w: env key %q overrides workspace runtime default (L1 is locked for export; only new keys may be added)", ErrInvalidForm, k)
		}
		// 导出参数键一律取 L1（契约 §5.5：imagePullSecrets/ingress/mysql.createDatabase 等
		// 顶层键来源为 L1）：L2 携带即使 L1 未配置也 400——否则成员可经请求 env 绕过运维
		// 锁定静默改写镜像推送目标、生产 ingress host、checkpoint 派生库（评审修复）
		if exportMetaKeys[k] {
			return nil, nil, nil, fmt.Errorf("%w: env key %q is a workspace export parameter (L1, ops-managed via workspace config; not settable per-export)", ErrInvalidForm, k)
		}
		if l3DerivedKeys[k] {
			return nil, nil, nil, fmt.Errorf("%w: env key %q is platform-derived at export time (L3, non-overridable)", ErrInvalidForm, k)
		}
	}
	merged = map[string]string{}
	for k, v := range l1 {
		if v != "" {
			merged[k] = v
		}
	}
	for k, v := range req.Env {
		if v != "" {
			merged[k] = v
		}
	}
	dropEmptyEnv(merged)

	whitelist, err = parseBaseImageWhitelist(l1["BASE_IMAGE_WHITELIST"])
	if err != nil {
		return nil, nil, nil, err
	}
	if req.BaseImage == "" {
		if len(whitelist) == 0 {
			return nil, nil, nil, fmt.Errorf("%w: baseImage required: workspace BASE_IMAGE_WHITELIST is not configured", ErrInvalidForm)
		}
		req.BaseImage = whitelist[0].Image
	} else {
		allowed := false
		for _, o := range whitelist {
			if o.Image == req.BaseImage {
				allowed = true
				break
			}
		}
		if !allowed {
			return nil, nil, nil, fmt.Errorf("%w: baseImage %q not in workspace BASE_IMAGE_WHITELIST", ErrImageNotAllowed, req.BaseImage)
		}
	}
	// 推送目标仓库（backend 直推指定镜像仓库，设计 §5）：L1/L2 均未配置则无法构建
	if merged["REGISTRY_ADDRESS"] == "" {
		return nil, nil, nil, fmt.Errorf("%w: REGISTRY_ADDRESS not configured in workspace runtime defaults", ErrInvalidForm)
	}
	// 路由（ingress 段）：mode 缺省 host；host 模式必须已配后缀（chart validate 同要求，
	// 缺陷上移到创建期拒绝，避免产出不可部署的 values）
	mode := merged["ROUTING_MODE"]
	if mode == "" {
		mode = "host"
	}
	if mode != "host" && mode != "router" {
		return nil, nil, nil, fmt.Errorf("%w: ROUTING_MODE must be host or router, got %q", ErrInvalidForm, mode)
	}
	if mode == "host" {
		suffix := merged["ROUTING_HOST_SUFFIX"]
		if suffix == "" {
			return nil, nil, nil, fmt.Errorf("%w: ROUTING_HOST_SUFFIX required for host ingress mode", ErrInvalidForm)
		}
		if err := validateRoutingHostSuffix(suffix); err != nil {
			return nil, nil, nil, err
		}
	}
	return l1, merged, whitelist, nil
}

// exportRenderInput 渲染输入（values 与 manifest 共用）。
type exportRenderInput struct {
	OrderNo      string
	Svc          *store.ServiceEntity
	Workspace    *store.Workspace
	Pkg          *store.OafPackage
	Mark         *store.ExportPackageMarkEntity
	L1           map[string]string   // L1 事实源（摘要计数用）
	Merged       map[string]string   // L1∪L2 已裁决合并结果（空值已剔除）
	Req          *ExportOrderRequest // L2 原始请求（EnvKeysAdded 计数 / replicas / baseImage）
	CreatedBy    string              // 导出发起人 username（manifest audit）
	CreatedAt    time.Time           // 导出单创建时间（manifest audit.createdAt；issue #214——
	// 首渲传落库回填的 order.CreatedAt，构建后重渲染传 DB 行同字段，重渲染保持不变）
	OafImageRef  string
	OafDigest    string // 构建回填前为空
	ChartVersion string
}

// splitExportEnv 合并结果 + L3 → env/secretEnv 两段（契约 §5.2/§5.3）：
// 敏感集合 = platformconfig.SensitiveKeys() ∪ 请求 secretKeys（与发布链路 splitUserEnv
// 同口径，直接复用 platformconfig.Split）；渲染取值为 L1∪L2∪L3 并集——模板键与
// L2 新增键（如 MCP_E2E_TOKEN）都进入对应段；导出参数键（exportMetaKeys）剔除。
func splitExportEnv(in *exportRenderInput) (plain, secret map[string]string, err error) {
	short := k8s.ShortName(in.Svc.K8sName)
	l3, err := derivedExportEnv(in.Merged, short)
	if err != nil {
		return nil, nil, err
	}
	full := make(map[string]string, len(in.Merged)+len(l3))
	for k, v := range in.Merged {
		if !exportMetaKeys[k] { // 导出参数键只驱动结构化输出，不注入 Pod env
			full[k] = v
		}
	}
	for k, v := range l3 {
		full[k] = v
	}
	force := make(map[string]bool, len(in.Req.SecretKeys))
	for _, k := range in.Req.SecretKeys {
		force[k] = true
	}
	plain, secret = platformconfig.Split(full, force)
	dropEmptyEnv(plain)
	dropEmptyEnv(secret)
	return plain, secret, nil
}

// headerLineTerminators sanitizeHeaderLine 专用替换表：yaml.v3（v3.0.1）认定的全部行
// 终止符 → 空格。评审复核（独立 harness 实测）：只拦 CR/LF 不够——扫描器把 NEL(U+0085)/
// LS(U+2028)/PS(U+2029) 同样当行终止符（终止注释行、随行内容照常解析），可经 DisplayName
// 复活注入顶出任意顶层键；C0 控制字符则被 yaml 直接拒绝（"control characters are not
// allowed"，fail-closed 不构成注入面），故剥离范围精确到五终止符。
var headerLineTerminators = strings.NewReplacer(
	"\n", " ", "\r", " ",
	"\u0085", " ", "\u2028", " ", "\u2029", " ",
)

// sanitizeHeaderLine values 注释头插值单行化（issue #226 兜底）：把 yaml.v3 认定的全部
// 行终止符 LF/CR/NEL/LS/PS 替换为空格，注入内容只能留在注释行内。插值字段中 DisplayName
// 是活向量：发布仅「非空 + ≤128 rune」校验（agentgen.go:692-696、publish.go:176-179，
// 多字节终止符是合法 rune 可过），经 publish.go:234 orStr(req.Name, pkg.Name) 落
// services.display_name 后由 renderExportValues 直入注释头——完整链路：LS 进 DisplayName
// → 创建导出单（chartVersion 合法）→ values 注释头顶出 resources → charts/oaf-agent
// 模板消费生效。其余插值字段均为受限形态（marker={包版本}-e{N}、pkg/ws slug kebab、
// pkg.version 走 oaf semverRe oaf.go:108、BaseImage 走 L1 白名单、OrderNo 平台生成、
// chartVersion 走创建期 chartVersionRe），但 rebuildExportArtifacts 重渲染读 DB 存量
// order.ChartVersion（创建后无法再校验），仍需此处兜底。values 本体经 yaml.Marshal
// 序列化天然安全，无需处理。
func sanitizeHeaderLine(s string) string {
	return headerLineTerminators.Replace(s)
}

// renderExportValues values.yaml 产物（契约 §5.5 形状；map 键序为 yaml.v3 字典序，可接受）。
func renderExportValues(in *exportRenderInput) (string, error) {
	short := k8s.ShortName(in.Svc.K8sName)
	plain, secret, err := splitExportEnv(in)
	if err != nil {
		return "", err
	}
	mode := in.Merged["ROUTING_MODE"]
	if mode == "" {
		mode = "host"
	}
	ingress := map[string]interface{}{"mode": mode, "tls": false}
	if mode == "host" {
		ingress["hostSuffix"] = in.Merged["ROUTING_HOST_SUFFIX"]
	} else {
		// router 模式：chart 以 pathPrefix 挂 ingress-nginx rewrite 注解（§6.3/§9-D9）
		ingress["pathPrefix"] = "/agent/" + short
	}
	values := map[string]interface{}{
		"fullnameOverride": short,
		"image": map[string]string{
			"base":      in.Req.BaseImage,
			"oaf":       in.OafImageRef,
			"oafDigest": in.OafDigest,
		},
		"imagePullSecrets": splitCSVEnv(in.Merged["IMAGE_PULL_SECRETS"]),
		"env":              plain,
		"secretEnv":        secret,
		"configInjection":  map[string]string{"mode": "initContainer", "dir": "/config"},
		"ingress":          ingress,
		// L1 配置了 CHECKPOINT_JDBC_BASE 即启用 pre-install hook 幂等建库（双保险，§5.4）
		"mysql": map[string]interface{}{"createDatabase": in.Merged["CHECKPOINT_JDBC_BASE"] != ""},
		"podAnnotations": map[string]string{
			"oaf.platform/export-id":        in.OrderNo,
			"oaf.platform/package-checksum": "sha256:" + in.Mark.Checksum,
		},
	}
	if in.Req.Replicas > 0 {
		values["replicas"] = in.Req.Replicas
	}
	raw, err := yaml.Marshal(values)
	if err != nil {
		return "", err
	}
	// 注释头部（拼接字符串，契约 §5.5；敏感值明文警示行照设计 §7.3）。
	// 全部插值参数逐一套 sanitizeHeaderLine（issue #226）：yaml 行终止符全集
	// （LF/CR/NEL/LS/PS）替换为空格，杜绝注入内容顶成独立 YAML 行
	header := fmt.Sprintf(`# oaf-agent values.yaml — 由导出单 %s 自动生成
# 服务: %s %s · 工作空间 %s
# 源包: %s@%s → 发布标记 %s
# chart: oaf-agent-%s · 基础镜像 %s
# 敏感值明文写入本文件（文件代入）：随 ITSM 工单按密级流转
`,
		sanitizeHeaderLine(in.OrderNo), sanitizeHeaderLine(in.Svc.DisplayName), sanitizeHeaderLine(short),
		sanitizeHeaderLine(in.Workspace.Slug), sanitizeHeaderLine(in.Pkg.Slug), sanitizeHeaderLine(in.Pkg.Version),
		sanitizeHeaderLine(in.Mark.Marker), sanitizeHeaderLine(in.ChartVersion), sanitizeHeaderLine(in.Req.BaseImage))
	return header + string(raw), nil
}

// splitCSVEnv 逗号分隔串 → 列表（imagePullSecrets 拆分；空串 → 空列表，chart 默认同形）。
func splitCSVEnv(raw string) []string {
	var out []string
	for _, part := range strings.Split(raw, ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	if out == nil {
		out = []string{}
	}
	return out
}

// ---- manifest.json（契约 §5.7，设计 §7.3 尾段）----

// ExportManifest manifest.json 结构：导出单号 ↔ 包 checksum ↔ 镜像 digest ↔ ITSM 工单号
// 四方关联（审计与生产侧一致性校验）；下载审计经 Audit.DownloadedBy 追加（§5.8）。
type ExportManifest struct {
	ExportOrder string `json:"exportOrder"`
	Service     struct {
		ID          uint   `json:"id"`
		DisplayName string `json:"displayName"`
		K8sName     string `json:"k8sName"`
		ShortName   string `json:"shortName"`
	} `json:"service"`
	Workspace struct {
		ID   uint   `json:"id"`
		Slug string `json:"slug"`
	} `json:"workspace"`
	Package struct {
		SourcePackageID uint   `json:"sourcePackageId"`
		Slug            string `json:"slug"`
		SourceVersion   string `json:"sourceVersion"`
		MarkChecksum    string `json:"markChecksum"` // sha256:...
		Marker          string `json:"marker"`
	} `json:"package"`
	Bundle struct {
		Chart          string `json:"chart"`
		BaseImage      string `json:"baseImage"`
		OafImageRef    string `json:"oafImageRef"`
		OafImageDigest string `json:"oafImageDigest"`
		Values         string `json:"values"`
	} `json:"bundle"`
	OverrideSummary struct {
		EnvKeysAdded           int `json:"envKeysAdded"`
		SecretKeys             int `json:"secretKeys"`
		McpServersRewritten    int `json:"mcpServersRewritten"`
		RuntimeDefaultsApplied int `json:"runtimeDefaultsApplied"`
	} `json:"overrideSummary"`
	Itsm struct {
		TicketID   string     `json:"ticketId"`
		AdvancedBy string     `json:"advancedBy"`
		AdvancedAt *time.Time `json:"advancedAt"`
	} `json:"itsm"`
	Audit struct {
		CreatedBy    string                `json:"createdBy"`
		CreatedAt    time.Time             `json:"createdAt"`
		DownloadedBy []ExportDownloadEvent `json:"downloadedBy"`
	} `json:"audit"`
}

// ExportDownloadEvent 下载审计条目（§5.8：操作人 + 时间；独立审计表留 v2，§9-D5）。
type ExportDownloadEvent struct {
	User string    `json:"user"`
	At   time.Time `json:"at"`
}

// renderExportManifest 组装 manifest（不含 itsm 推进与下载审计——由后续动作就地更新）。
func renderExportManifest(in *exportRenderInput, secretKeysCount int) *ExportManifest {
	short := k8s.ShortName(in.Svc.K8sName)
	m := &ExportManifest{ExportOrder: in.OrderNo}
	m.Service.ID, m.Service.DisplayName, m.Service.K8sName, m.Service.ShortName =
		in.Svc.ID, in.Svc.DisplayName, in.Svc.K8sName, short
	m.Workspace.ID, m.Workspace.Slug = in.Workspace.ID, in.Workspace.Slug
	m.Package.SourcePackageID = in.Pkg.ID
	m.Package.Slug = in.Pkg.Slug
	m.Package.SourceVersion = in.Pkg.Version
	m.Package.MarkChecksum = "sha256:" + in.Mark.Checksum
	m.Package.Marker = in.Mark.Marker
	m.Bundle.Chart = "oaf-agent-" + in.ChartVersion
	m.Bundle.BaseImage = in.Req.BaseImage
	m.Bundle.OafImageRef = in.OafImageRef
	m.Bundle.OafImageDigest = in.OafDigest
	m.Bundle.Values = "values.yaml（本 manifest 配套附件）"
	m.OverrideSummary.EnvKeysAdded = len(in.Req.Env) // L2 仅新增语义：env 键数即新增数
	m.OverrideSummary.SecretKeys = secretKeysCount   // 渲染后 secretEnv 段敏感键数
	// McpServersRewritten：v1 快照式导出，MCP 改写在包快照内无结构化跟踪，恒 0（契约 §5.7 示例）
	m.OverrideSummary.McpServersRewritten = 0
	// RuntimeDefaultsApplied：实际被渲染消费的非空 L1 工作空间默认键数
	for _, v := range in.L1 {
		if v != "" {
			m.OverrideSummary.RuntimeDefaultsApplied++
		}
	}
	m.Audit.CreatedBy = in.CreatedBy
	m.Audit.CreatedAt = in.CreatedAt // 审计链时间锚点（§12.5 四方关联，issue #214：不得为零值）
	return m
}

// renderExportArtifacts 渲染 values.yaml 与 manifest.json（splitExportEnv 单次求值共用）。
func renderExportArtifacts(in *exportRenderInput) (values, manifest string, err error) {
	_, secret, err := splitExportEnv(in)
	if err != nil {
		return "", "", err
	}
	if values, err = renderExportValues(in); err != nil {
		return "", "", err
	}
	if manifest, err = marshalExportManifest(renderExportManifest(in, len(secret))); err != nil {
		return "", "", err
	}
	return values, manifest, nil
}

// marshalExportManifest manifest 落库形态（缩进 JSON，随 ITSM 流转的可读交付物）。
func marshalExportManifest(m *ExportManifest) (string, error) {
	raw, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

// parseExportManifest 反序列化已落库 manifest（空串按零值处理，容错历史行）。
func parseExportManifest(raw string) (*ExportManifest, error) {
	m := &ExportManifest{}
	if raw == "" {
		return m, nil
	}
	if err := json.Unmarshal([]byte(raw), m); err != nil {
		return nil, fmt.Errorf("corrupt export manifest json: %w", err)
	}
	return m, nil
}

// manifestWithItsm 人工推进状态时同步更新 manifest itsm 段（操作人与工单号，设计 §9）。
func manifestWithItsm(raw, ticketID, advancedBy string, at time.Time) (string, error) {
	m, err := parseExportManifest(raw)
	if err != nil {
		return "", err
	}
	m.Itsm.TicketID = ticketID
	m.Itsm.AdvancedBy = advancedBy
	m.Itsm.AdvancedAt = &at
	return marshalExportManifest(m)
}

// manifestAppendDownload 下载审计：manifest audit.downloadedBy 追加（§5.8）。
func manifestAppendDownload(raw, username string, at time.Time) (string, error) {
	m, err := parseExportManifest(raw)
	if err != nil {
		return "", err
	}
	m.Audit.DownloadedBy = append(m.Audit.DownloadedBy, ExportDownloadEvent{User: username, At: at})
	return marshalExportManifest(m)
}
