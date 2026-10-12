package k8s

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	networkingv1 "k8s.io/api/networking/v1"
	"k8s.io/apimachinery/pkg/util/strategicpatch"
	sigsyaml "sigs.k8s.io/yaml"
)

// DeploymentBuilder 业务 Deployment 构造门面：内置纯函数构造（Deployment，平台演进而变）
// 为基线，可选 YAML overlay（平台环境策略，如 PVC 名/调度约束/镜像拉取密钥）经
// Strategic Merge Patch 按 K8s 原生语义合并（同 name 的 container/volume/env 子合并）。
//
// 配置方式：环境变量 DEPLOYMENT_TEMPLATE 指向 overlay 文件；缺省不设置即纯内置构造，
// 行为与历史版本完全一致。overlay 变更在下次 apply（发布/重新发布/上下线）时生效。
type DeploymentBuilder struct {
	// overlay 为 Strategic Merge Patch 原文（Deployment JSON/YAML），启动时加载。
	overlay []byte
	// hasOverlay 标记是否配置了 overlay：空文件与未配置均视为无 overlay。
	hasOverlay bool
}

// NewDeploymentBuilder 读取 overlay 文件构建 builder；path 为空返回默认 builder
// （纯内置构造）。文件读取/解析延迟到首次 Build 时报错——本函数仅在打开文件失败
// （不存在/无权限）时立即失败，方便启动 fail-fast。
func NewDeploymentBuilder(path string) (*DeploymentBuilder, error) {
	if path == "" {
		return &DeploymentBuilder{}, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read deployment overlay %s: %w", path, err)
	}
	b := &DeploymentBuilder{overlay: data, hasOverlay: true}
	// 启动期以哑参数试渲染一次，语法/结构错误 fail-fast（与 MYSQL_DSN 缺失同策略）。
	// 试渲染用哑参数 oaf-template-probe/default：overlay 不得硬编码
	// metadata.name/namespace（每服务发布期才确定），报错提示
	// "must stay default/oaf-template-probe" 即属此类误用。
	if _, err := b.Build(ObjectParams{K8sName: Prefix + "template-probe", Namespace: "default"}); err != nil {
		return nil, fmt.Errorf("deployment overlay %s invalid: %w", path, err)
	}
	return b, nil
}

// Build 构造业务 Deployment：内置构造 → overlay 合并 → 不变量校验。
func (b *DeploymentBuilder) Build(p ObjectParams) (*appsv1.Deployment, error) {
	base := Deployment(p)
	if !b.hasOverlay {
		return base, nil
	}
	patchedVal, err := applySMPOverlay(*base, b.overlay, appsv1.Deployment{})
	if err != nil {
		return nil, err
	}
	patched := &patchedVal
	if err := validateDeployment(p, patched); err != nil {
		return nil, fmt.Errorf("overlay violates deployment invariants: %w", err)
	}
	return patched, nil
}

// applySMPOverlay 对已注册 K8s 类型对象做 Strategic Merge Patch：overlay 为 YAML 裸
// 对象（非 list），先转 JSON 再与 base 合并（patch 元数据取自 proto 零值类型），
// 结果反序列化返回。Deployment/Ingress 两种 builder 共用。
func applySMPOverlay[T any](base T, overlay []byte, proto T) (T, error) {
	var zero T
	overlayJSON, err := sigsyaml.YAMLToJSON(overlay)
	if err != nil {
		return zero, fmt.Errorf("overlay yaml: %w", err)
	}
	baseJSON, err := json.Marshal(base)
	if err != nil {
		return zero, fmt.Errorf("marshal base object: %w", err)
	}
	patchedJSON, err := strategicpatch.StrategicMergePatch(baseJSON, overlayJSON, proto)
	if err != nil {
		return zero, fmt.Errorf("strategic merge patch: %w", err)
	}
	var out T
	if err := json.Unmarshal(patchedJSON, &out); err != nil {
		return zero, fmt.Errorf("unmarshal patched object: %w", err)
	}
	return out, nil
}

// validateDeployment overlay 合并后强校验平台不变量，违规拒绝发布（发布期反馈，
// 而非让业务 Pod 带病上线）。selector/labels、envFrom、/config 挂载、保留键 env
// 是 status 查询与 OAF 加载的核心契约。
func validateDeployment(p ObjectParams, d *appsv1.Deployment) error {
	if d.Name != p.K8sName || d.Namespace != p.Namespace {
		return fmt.Errorf("metadata.name/namespace must stay %s/%s", p.Namespace, p.K8sName)
	}
	if d.Spec.Replicas == nil || *d.Spec.Replicas != orDefaultReplicas(p.Replicas) {
		return fmt.Errorf("spec.replicas must stay %d (per-publish request)", orDefaultReplicas(p.Replicas))
	}
	wantSel := map[string]string{LabelKey: p.K8sName}
	// selector 为 nil（overlay 置空/$patch: delete）同样违规；matchExpressions 追加
	// 会让 selector 匹配不到任何 Pod（模板 labels 仅 LabelKey），以 deploy_failed
	// 超时延迟暴露——一并拒绝。
	if d.Spec.Selector == nil || d.Spec.Selector.MatchLabels[LabelKey] != p.K8sName ||
		len(d.Spec.Selector.MatchLabels) != len(wantSel) || len(d.Spec.Selector.MatchExpressions) != 0 {
		return fmt.Errorf("spec.selector must stay %v (deployment selector immutable)", wantSel)
	}
	if d.Spec.Template.ObjectMeta.Labels[LabelKey] != p.K8sName {
		return fmt.Errorf("template labels %s must stay %s", LabelKey, p.K8sName)
	}
	// 主容器按 name 定位：SMP 合并后 overlay 新增的 sidecar 可能排在列表前面，
	// 不能假设 Containers[0] 是 agent（回归时踩过：sidecar 合并后 index 位移）。
	agent := -1
	for i, c := range d.Spec.Template.Spec.Containers {
		if c.Name == "agent" {
			agent = i
			break
		}
	}
	if agent < 0 {
		return fmt.Errorf("container %q missing (must not be renamed/dropped)", "agent")
	}
	cs := d.Spec.Template.Spec.Containers[agent]
	// envFrom 必含两源引用：服务 Secret（敏感 env）+ 服务 CM（非敏感 env）。
	// 平台默认配置不经 envFrom 注入（R3 修订），不校验平台对象。
	wantEnvFrom := map[string]bool{
		EnvSecretName(p.K8sName): false,
		p.K8sName + "-env":       false,
	}
	for _, ef := range cs.EnvFrom {
		name := ""
		switch {
		case ef.ConfigMapRef != nil:
			name = ef.ConfigMapRef.Name
		case ef.SecretRef != nil:
			name = ef.SecretRef.Name
		}
		if _, ok := wantEnvFrom[name]; ok {
			wantEnvFrom[name] = true
		}
	}
	for name, found := range wantEnvFrom {
		if !found {
			return fmt.Errorf("envFrom %s missing", name)
		}
	}
	// /config 只读 subPath 挂载（OAF 包加载核心机制）+ 平台功能挂载存在性：
	// volumeMounts 按 mountPath 合并，overlay 可用 $patch: delete 精确移除单条，
	// 静默删 /data/files（文件上传）、/applog（日志采集）、/workspace（工作区）
	// 会让 Pod 照常 Running 而平台功能失效——发布期一并拦下。
	mounts := map[string]corev1.VolumeMount{}
	for _, vm := range cs.VolumeMounts {
		mounts[vm.MountPath] = vm
	}
	cfgMount, ok := mounts["/config"]
	if !ok || !cfgMount.ReadOnly || cfgMount.SubPath != p.SubPath {
		return fmt.Errorf("/config read-only subPath mount (%s) missing", p.SubPath)
	}
	for path, want := range map[string]struct{ subPath string }{
		FilesMountPath:     {FilesSubPath},
		ApplogMountPath:    {""},
		WorkspaceMountPath: {""},
	} {
		vm, ok := mounts[path]
		if !ok || vm.ReadOnly || vm.SubPath != want.subPath {
			return fmt.Errorf("writable mount %s missing or altered", path)
		}
	}
	// 保留键 env 必须存在（平台契约）
	fixed := map[string]string{}
	for _, e := range cs.Env {
		fixed[e.Name] = e.Value
	}
	for k, want := range map[string]string{
		"AGENT_CONFIG_DIR": "/config", "AGENT_WORKSPACE_DIR": "/workspace",
		"SERVER_HOST": "0.0.0.0", "SERVER_PORT": fmt.Sprintf("%d", AgentPort),
	} {
		if fixed[k] != want {
			return fmt.Errorf("reserved env %s must stay %s", k, want)
		}
	}
	// HOST_NAME 经 downward API 注入
	foundHostName := false
	for _, e := range cs.Env {
		if e.Name == "HOST_NAME" && e.ValueFrom != nil && e.ValueFrom.FieldRef != nil &&
			e.ValueFrom.FieldRef.FieldPath == "metadata.name" {
			foundHostName = true
			break
		}
	}
	if !foundHostName {
		return fmt.Errorf("HOST_NAME env (fieldRef metadata.name) missing")
	}
	// 容器内 mount 引用的 volume 必须存在（防 overlay 悬空引用）
	vols := map[string]bool{}
	for _, v := range d.Spec.Template.Spec.Volumes {
		vols[v.Name] = true
	}
	for _, c := range d.Spec.Template.Spec.Containers {
		for _, vm := range c.VolumeMounts {
			if !vols[vm.Name] {
				return fmt.Errorf("container %q mounts unknown volume %q", c.Name, vm.Name)
			}
		}
	}
	return nil
}

// orDefaultReplicas 与 Deployment 构造的 replicas 兜底逻辑保持一致。
func orDefaultReplicas(r int32) int32 {
	if r < 1 {
		return 1
	}
	return r
}

// IngressBuilder 业务 Ingress 构造门面：内置纯函数构造（Ingress，路由形状是平台契约）
// 为基线，可选 YAML overlay（环境策略，如自定义域名 TLS/WAF 类注解）经 Strategic
// Merge Patch 合并。注意 rules/tls 为普通列表（无 patch 策略），overlay 写了即整体
// 替换；annotations 为 map 按 key 合并。仅 host 模式（IngressHostSuffix 非空）使用：
// host 恒为 {K8sName}{suffix} 不可偏离（详见 validateIngress）；router 模式（suffix 空）
// 不构造 per-service Ingress，Build 直接报错（config.Load 对 suffix 空 + 模板非空
// 同样启动即拒）。
//
// 配置方式：环境变量 INGRESS_TEMPLATE 指向 overlay 文件；缺省不设置即纯内置构造，
// 行为与历史版本完全一致。overlay 变更在下次 apply（发布/重新发布/重新上线）时生效。
type IngressBuilder struct {
	// overlay 为 Strategic Merge Patch 原文（Ingress JSON/YAML），启动时加载。
	overlay []byte
	// hasOverlay 标记是否配置了 overlay：空文件与未配置均视为无 overlay。
	hasOverlay bool
}

// NewIngressBuilder 语义同 NewDeploymentBuilder：文件缺失/语法错误/哑参数试渲染
// 违规均启动即失败（fail-fast）。ingressClass 传运行配置值（cfg.IngressClass），
// 保证探针与发布期同参——overlay 显式写 class 只允许等于配置值，否则试渲染即拒绝。
// ingressHostSuffix 同理传配置值（cfg.IngressHostSuffix）：它决定校验走 path 还是 host
// 模式不变量，探针与发布期必须同模式，否则 host 模式的 overlay 会在启动期被误拒。
// 试渲染用哑参数 oaf-template-probe/default——overlay 不得硬编码
// metadata.name/namespace（每服务发布期才确定）。
func NewIngressBuilder(path, ingressClass, ingressHostSuffix string) (*IngressBuilder, error) {
	if path == "" {
		return &IngressBuilder{}, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read ingress overlay %s: %w", path, err)
	}
	b := &IngressBuilder{overlay: data, hasOverlay: true}
	if _, err := b.Build(ObjectParams{K8sName: Prefix + "template-probe", Namespace: "default",
		IngressClass: ingressClass, IngressHostSuffix: ingressHostSuffix}); err != nil {
		return nil, fmt.Errorf("ingress overlay %s invalid: %w", path, err)
	}
	return b, nil
}

// Build 构造业务 Ingress：内置构造 → overlay 合并（占位符按服务替换）→ 不变量校验。
// router 模式（IngressHostSuffix 空）没有 per-service Ingress，调用即编程错误——
// 发布流（publish.go applyAll/deriveEndpoint）在 router 模式下不会走到这里。
func (b *IngressBuilder) Build(p ObjectParams) (*networkingv1.Ingress, error) {
	if p.IngressHostSuffix == "" {
		return nil, fmt.Errorf("router mode (empty INGRESS_HOST_SUFFIX) builds no per-service ingress")
	}
	base := Ingress(p)
	if !b.hasOverlay {
		return base, nil
	}
	// 占位符按服务替换：rules 整体替换语义下 backend/path 必须引用每服务才确定的
	// 名称（与"不得硬编码 metadata.name/namespace"同理，保证单文件模板服务无关，
	// 启动探针也以哑参数走同一替换路径）。
	overlay := []byte(ingressOverlayVars(p).Replace(string(b.overlay)))
	patchedVal, err := applySMPOverlay(*base, overlay, networkingv1.Ingress{})
	if err != nil {
		return nil, err
	}
	patched := &patchedVal
	if err := validateIngress(p, patched); err != nil {
		return nil, fmt.Errorf("overlay violates ingress invariants: %w", err)
	}
	return patched, nil
}

// ingressOverlayVars overlay 占位符 → 每服务取值。{{K8S_NAME}}=oaf-{short}（backend
// 指向 {K8S_NAME}-svc），{{SHORT_NAME}}=去前缀短名（path/x-forwarded-prefix 用），
// {{HOST_SUFFIX}}=INGRESS_HOST_SUFFIX 原值——host 模式 overlay 的 TLS hosts/rule host
// 与后缀联动（环境无关模板，换环境零改动），path 模式（后缀为空）替换为空串。
func ingressOverlayVars(p ObjectParams) *strings.Replacer {
	return strings.NewReplacer(
		"{{K8S_NAME}}", p.K8sName,
		"{{SHORT_NAME}}", ShortName(p.K8sName),
		"{{HOST_SUFFIX}}", p.IngressHostSuffix,
	)
}

// 已删除的 path 模式注解键（仅校验用）：内置构造不再生成，但存量 path 模式 overlay
// 照抄来的残留值有真实下游后果（x-forwarded-prefix → DebugController 302 错前缀），
// validateIngress 据此拒绝非空值。
const (
	annRewriteTarget    = "nginx.ingress.kubernetes.io/rewrite-target"
	annUseRegex         = "nginx.ingress.kubernetes.io/use-regex"
	annXForwardedPrefix = "nginx.ingress.kubernetes.io/x-forwarded-prefix"
)

// validateIngress overlay 合并后强校验平台不变量，违规拒绝发布（发布期反馈，而非
// 让坏流量规则上线）。host 模式不变量：对象归属唯一（backend 全部指向本服务）、
// pathType 必填、SSE 长超时保留、defaultBackend 恒空、host 恒为 {K8sName}{suffix}
// （根路径直出）、rewrite 三项注解必须为空（子路径路由已收敛 router 模式，残留的
// path 模式模板注解有真实后果——x-forwarded-prefix 会让 agent-framework DebugController
// 302 到错误前缀 404）。
func validateIngress(p ObjectParams, ing *networkingv1.Ingress) error {
	if ing.Name != p.K8sName || ing.Namespace != p.Namespace {
		return fmt.Errorf("metadata.name/namespace must stay %s/%s", p.Namespace, p.K8sName)
	}
	for k, want := range labels(p.K8sName) {
		if ing.Labels[k] != want {
			return fmt.Errorf("label %s must stay %s", k, want)
		}
	}
	if ing.Spec.IngressClassName != nil && *ing.Spec.IngressClassName != p.IngressClass {
		return fmt.Errorf("spec.ingressClassName must stay %s", p.IngressClass)
	}
	// defaultBackend 是 rules 之外的 catch-all 路由入口：不拦会绕过归属唯一
	// （未匹配本服务 host 的流量打到其他服务），内置构造恒为 nil
	if ing.Spec.DefaultBackend != nil {
		return fmt.Errorf("spec.defaultBackend must stay empty (per-service object)")
	}
	ann := ing.Annotations
	// host 模式无前缀可剥：rewrite 三项必须为空（不存在或空串），不是"不校验"。
	// 放行非空错值有真实后果——x-forwarded-prefix 有明确下游消费者：agent-framework
	// DebugController 读 X-Forwarded-Prefix 拼 302 的 Location（X-Forwarded-Prefix
	// 非空即跳 {该值}/debug/，从 path 模式模板照抄写出的错前缀会让 /debug 直接 404）；
	// rewrite-target 则可能被 ingress-nginx 用于改写整条 location。
	// 内置构造三项一个都不生成，收紧不影响任何合法 host 模式 overlay。
	for _, k := range []string{annRewriteTarget, annUseRegex, annXForwardedPrefix} {
		if ann[k] != "" {
			return fmt.Errorf("annotation %s must stay empty (host mode has no path prefix to rewrite or strip)", k)
		}
	}
	if ann[annProxyReadTimeout] == "" || ann[annProxySendTimeout] == "" {
		return fmt.Errorf("annotations %s/%s must stay (SSE long connection)",
			annProxyReadTimeout, annProxySendTimeout)
	}
	// TLS 覆盖校验（issue #97）：spec.tls 存在时其 hosts 必须覆盖全部 rule host。
	// 展示 Endpoint 的 scheme 只看 len(spec.tls)>0（见 IngressEndpoint）——TLS 不覆盖
	// 的域名会得到 https 展示地址 + 无证书死链（换环境照抄硬编码后缀的示例即触发）。
	if len(ing.Spec.TLS) > 0 {
		tlsHosts := map[string]bool{}
		for _, t := range ing.Spec.TLS {
			for _, h := range t.Hosts {
				tlsHosts[h] = true
			}
		}
		for _, rule := range ing.Spec.Rules {
			if rule.Host != "" && !tlsHosts[rule.Host] {
				return fmt.Errorf("spec.tls hosts must cover rule host %q (endpoint derives https from tls presence)", rule.Host)
			}
		}
	}
	svcName := p.K8sName + "-svc"
	found := false
	for _, rule := range ing.Spec.Rules {
		// 域名即服务身份，overlay 不得偏离（空 host 会退化成共享入口 IP 承载根路径，
		// 流量串服务且无法定位）。遍历全部 rules，不放过无 http 的规则
		if rule.Host != p.K8sName+p.IngressHostSuffix {
			return fmt.Errorf("rule host must stay %s (per-service domain)", p.K8sName+p.IngressHostSuffix)
		}
		if rule.HTTP == nil {
			continue
		}
		for _, path := range rule.HTTP.Paths {
			// 归属唯一：Ingress 对象与服务一一对应，backend 必须指向本服务
			if path.Backend.Service == nil || path.Backend.Service.Name != svcName ||
				path.Backend.Service.Port.Number != AgentPort {
				return fmt.Errorf("backend must stay %s:%d (per-service object)", svcName, AgentPort)
			}
			// pathType 为 v1 必填：漏写会被 apiserver 拒绝，提前在此反馈
			if path.PathType == nil {
				return fmt.Errorf("path %q must set pathType", path.Path)
			}
			// host 模式根路径直出：path 是不变量（Endpoint 固定派生为
			// {scheme}://{K8sName}{suffix}/，若放开 path 会让落库地址与实际服务
			// 路由错位、前端链接 404）
			if path.Path != hostModeRootPath {
				return fmt.Errorf("path %q must stay %s (host mode serves the root path)",
					path.Path, hostModeRootPath)
			}
			found = true
		}
	}
	if !found {
		return fmt.Errorf("spec.rules must keep at least one http path")
	}
	return nil
}

// IngressEndpoint 从（合并后）Ingress 派生对外展示地址（host 模式）：取首条规则的
// Host（空则回落 fallbackHost 作防御），根路径直出，端口 80/443 由域名默认承载故不拼
// port：{scheme}://{host}/。scheme 按 TLS 有无。仅用于展示与记录；模板改 host 后的
// DNS/端口可达性由环境自行保证。（router 模式不走本函数，见 RouterEndpoint。）
func IngressEndpoint(ing *networkingv1.Ingress, fallbackHost string) string {
	scheme, host := "http", fallbackHost
	if len(ing.Spec.TLS) > 0 {
		scheme = "https"
	}
	for _, rule := range ing.Spec.Rules {
		if rule.Host != "" {
			host = rule.Host
			break
		}
	}
	return fmt.Sprintf("%s://%s/", scheme, host)
}

// RouterEndpoint router 模式（INGRESS_HOST_SUFFIX 空）的对外展示地址：统一入口 +
// /agent/{short}/ 前缀，形状与原 path 模式输出一致（前端/DB/MCP 契约零变化）。
// 前缀剥离由集群内 platform-router 承担（manifests/platform-router.yaml），backend
// 不构造任何 per-service Ingress。
func RouterEndpoint(host string, port int, k8sName string) string {
	return fmt.Sprintf("http://%s/agent/%s/", hostWithPort(host, port), ShortName(k8sName))
}

// hostWithPort 拼 host:port；host 已含端口（如 172.20.0.2:30080）时不重复拼接。
func hostWithPort(host string, port int) string {
	if strings.Contains(host, ":") {
		return host
	}
	return fmt.Sprintf("%s:%d", host, port)
}

// RequireRouter router 模式（suffix 空）启动自检（subpath-routing-design §4.1）：
// 集群内 platform-router 的 Service 必须已存在（只查对象，不要求 Ready）。缺失即
// fail-fast，与 INGRESS_TEMPLATE 探针同语义——否则发布成功但 /agent/* 流量全部 404。
func RequireRouter(c Client, ns string) error {
	exists, err := c.ServiceExists(ns, RouterServiceName)
	if err != nil {
		return fmt.Errorf("check router service %s/%s: %w", ns, RouterServiceName, err)
	}
	if !exists {
		return fmt.Errorf("service %s/%s not found: router mode (INGRESS_HOST_SUFFIX empty) routes business traffic through it, apply manifests/platform-router.yaml first", ns, RouterServiceName)
	}
	return nil
}
