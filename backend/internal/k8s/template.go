package k8s

import (
	"encoding/json"
	"fmt"
	"os"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
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
	patched, err := applyOverlay(base, b.overlay)
	if err != nil {
		return nil, err
	}
	if err := validateDeployment(p, patched); err != nil {
		return nil, fmt.Errorf("overlay violates deployment invariants: %w", err)
	}
	return patched, nil
}

// applyOverlay 对 Deployment 做 Strategic Merge Patch。
// overlay 为裸 Deployment 对象（非 list），YAML 先转 JSON 再走 apimachinery。
func applyOverlay(base *appsv1.Deployment, overlay []byte) (*appsv1.Deployment, error) {
	overlayJSON, err := sigsyaml.YAMLToJSON(overlay)
	if err != nil {
		return nil, fmt.Errorf("overlay yaml: %w", err)
	}
	baseJSON, err := json.Marshal(base)
	if err != nil {
		return nil, fmt.Errorf("marshal base deployment: %w", err)
	}
	patchedJSON, err := strategicpatch.StrategicMergePatch(baseJSON, overlayJSON, appsv1.Deployment{})
	if err != nil {
		return nil, fmt.Errorf("strategic merge patch: %w", err)
	}
	out := &appsv1.Deployment{}
	if err := json.Unmarshal(patchedJSON, out); err != nil {
		return nil, fmt.Errorf("unmarshal patched deployment: %w", err)
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
	// envFrom 必含 {name}-env ConfigMap
	foundEnvFrom := false
	for _, ef := range cs.EnvFrom {
		if ef.ConfigMapRef != nil && ef.ConfigMapRef.Name == p.K8sName+"-env" {
			foundEnvFrom = true
			break
		}
	}
	if !foundEnvFrom {
		return fmt.Errorf("envFrom %s-env ConfigMap missing", p.K8sName)
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
