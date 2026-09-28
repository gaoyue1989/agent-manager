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
// 为基线，可选 YAML overlay（环境策略，如自定义域名 host/对外前缀 path/TLS/WAF 类
// 注解）经 Strategic Merge Patch 合并。注意 rules/tls 为普通列表（无 patch 策略），
// overlay 写了即整体替换；annotations 为 map 按 key 合并。
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
// 试渲染用哑参数 oaf-template-probe/default——overlay 不得硬编码
// metadata.name/namespace（每服务发布期才确定）。
func NewIngressBuilder(path, ingressClass string) (*IngressBuilder, error) {
	if path == "" {
		return &IngressBuilder{}, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read ingress overlay %s: %w", path, err)
	}
	b := &IngressBuilder{overlay: data, hasOverlay: true}
	if _, err := b.Build(ObjectParams{K8sName: Prefix + "template-probe", Namespace: "default", IngressClass: ingressClass}); err != nil {
		return nil, fmt.Errorf("ingress overlay %s invalid: %w", path, err)
	}
	return b, nil
}

// Build 构造业务 Ingress：内置构造 → overlay 合并（占位符按服务替换）→ 不变量校验。
func (b *IngressBuilder) Build(p ObjectParams) (*networkingv1.Ingress, error) {
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
// 指向 {K8S_NAME}-svc），{{SHORT_NAME}}=去前缀短名（path/x-forwarded-prefix 用）。
func ingressOverlayVars(p ObjectParams) *strings.Replacer {
	return strings.NewReplacer(
		"{{K8S_NAME}}", p.K8sName,
		"{{SHORT_NAME}}", ShortName(p.K8sName),
	)
}

// validateIngress overlay 合并后强校验平台不变量，违规拒绝发布（发布期反馈，而非
// 让坏流量规则上线）。核心契约：对象归属唯一（backend 全部指向本服务）、rewrite
// 路由形状完整（path 尾缀捕获组 + use-regex）、x-forwarded-prefix 与对外前缀一致
// （agent-framework Debug Console/外链依赖）、SSE 长超时保留。
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
	// （未匹配 /agent/{short} 的流量打到其他服务），内置构造恒为 nil
	if ing.Spec.DefaultBackend != nil {
		return fmt.Errorf("spec.defaultBackend must stay empty (per-service object)")
	}
	ann := ing.Annotations
	for k, want := range map[string]string{
		annRewriteTarget: rewriteTargetValue,
		annUseRegex:      "true",
	} {
		if ann[k] != want {
			return fmt.Errorf("annotation %s must stay %s", k, want)
		}
	}
	if ann[annProxyReadTimeout] == "" || ann[annProxySendTimeout] == "" {
		return fmt.Errorf("annotations %s/%s must stay (SSE long connection)",
			annProxyReadTimeout, annProxySendTimeout)
	}
	svcName := p.K8sName + "-svc"
	found, prefix := false, ""
	for _, rule := range ing.Spec.Rules {
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
			if !strings.HasSuffix(path.Path, ingressPathSuffix) {
				return fmt.Errorf("path %q must keep suffix %s (rewrite-target %s relies on it)",
					path.Path, ingressPathSuffix, rewriteTargetValue)
			}
			// 单一对外前缀：x-forwarded-prefix 为单值注解，多前缀无法与之一致
			cur := strings.TrimSuffix(path.Path, ingressPathSuffix)
			if !found {
				found, prefix = true, cur
			} else if prefix != cur {
				return fmt.Errorf("paths must share one public prefix: %q vs %q", prefix, cur)
			}
		}
	}
	if !found {
		return fmt.Errorf("spec.rules must keep at least one http path")
	}
	if ann[annXForwardedPrefix] != prefix {
		return fmt.Errorf("annotation %s must match public path prefix %q (change both together)",
			annXForwardedPrefix, prefix)
	}
	return nil
}

// IngressEndpoint 从（合并后）Ingress 派生对外展示地址：host 取第一条带尾缀 path 的
// 规则 Host（空回落 fallbackHost；host 已含 ":" 视为自带端口不重复拼接），path 剥掉
// 固定尾缀得对外前缀（剥空即根路径），scheme 按 TLS 有无，端口取入参。仅用于展示
// 与记录；模板改 host 后的 DNS/端口可达性由环境自行保证。
func IngressEndpoint(ing *networkingv1.Ingress, fallbackHost string, port int) string {
	scheme, host := "http", fallbackHost
	if len(ing.Spec.TLS) > 0 {
		scheme = "https"
	}
	found, prefix := false, ""
	for _, rule := range ing.Spec.Rules {
		if rule.HTTP == nil {
			continue
		}
		for _, path := range rule.HTTP.Paths {
			if strings.HasSuffix(path.Path, ingressPathSuffix) {
				if rule.Host != "" {
					host = rule.Host
				}
				prefix = strings.TrimSuffix(path.Path, ingressPathSuffix)
				found = true
				break
			}
		}
		if found {
			break
		}
	}
	// found=false（无尾缀 path，防御派生）与根路径（剥尾缀后为空）统一回落根 URL
	return fmt.Sprintf("%s://%s%s/", scheme, hostWithPort(host, port), prefix)
}

// hostWithPort 拼 host:port；host 已含端口（如 172.20.0.2:30080）时不重复拼接。
func hostWithPort(host string, port int) string {
	if strings.Contains(host, ":") {
		return host
	}
	return fmt.Sprintf("%s:%d", host, port)
}
