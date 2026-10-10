// Package k8s：K8s 对象构造（纯函数，可单测）与集群客户端。
package k8s

import (
	"fmt"
	"regexp"
	"strings"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	networkingv1 "k8s.io/api/networking/v1"
	resource "k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"
)

// 平台保留键：用户 env 出现同名键则拒绝（见 REDESIGN §5.2）。
var ReservedEnvKeys = map[string]bool{
	"AGENT_CONFIG_DIR": true, "AGENT_WORKSPACE_DIR": true, "SERVER_HOST": true, "SERVER_PORT": true,
	// 日志规范注入键：HOST_NAME=Pod Name，日志路径/内容依赖
	"HOST_NAME": true,
}

var dnsNameRe = regexp.MustCompile(`[^a-z0-9-]+`)

const (
	AgentPort           = 8100
	Prefix              = "oaf-"
	LabelKey            = "app.kubernetes.io/name"
	DataVolumeName      = "oaf-config"
	WorkspaceVolumeName = "agent-workspace"
	WorkspaceMountPath  = "/workspace"
	ManagedByLabel      = "app.kubernetes.io/managed-by"
	ManagedByValue      = "oaf-platform"
	PVCName             = "platform-data"
	// 文件存储可写挂载（file-upload-download-plan §13 P1 跨模块）：
	// platform-data PVC subPath "files/" 挂 /data/files，业务 agent 文件上传/产出落此处。
	FilesVolumeName = "agent-files"
	FilesSubPath    = "files"
	FilesMountPath  = "/data/files"
	// 日志规范挂载：/applog/${HOST_NAME}/trace.log 为容器云日志采集唯一来源，logback 写入该目录
	ApplogVolumeName = "applog"
	ApplogMountPath  = "/applog"
)

// SanitizeK8sName 任意输入转 DNS-1123 label：小写字母数字 '-'，≤63 字符。
func SanitizeK8sName(in string) string {
	s := strings.ToLower(in)
	s = dnsNameRe.ReplaceAllString(s, "-")
	s = strings.Trim(s, "-")
	if s == "" {
		s = "svc"
	}
	if len(s) > 63 {
		s = strings.TrimRight(s[:63], "-")
	}
	return s
}

// DeriveK8sName slug("vendor/agent") 或自定义名 → "oaf-{sanitized}"。
func DeriveK8sName(displayName, slug string) string {
	base := displayName
	if base == "" {
		base = strings.ReplaceAll(slug, "/", "-")
	}
	return Prefix + SanitizeK8sName(base)
}

// ShortName 去掉 oaf- 前缀，用于 ingress path /agent/{short}。
func ShortName(k8sName string) string {
	return strings.TrimPrefix(k8sName, Prefix)
}

// EnvSecretName 服务级敏感 env Secret 名。
func EnvSecretName(k8sName string) string { return k8sName + "-env-secret" }

type ObjectParams struct {
	K8sName   string
	Namespace string
	Image     string
	Env       map[string]string // 用户非敏感 env（已校验，不含保留键；模板敏感键已路由至 EnvSecret）
	// EnvSecret 服务敏感 env（模板 Sensitive 键 + secretKeys 指定键；与 Env 按模板分区互斥）
	EnvSecret map[string]string
	Replicas  int32
	SubPath   string // packages/{packageId}
	// PVCName 业务 Pod 挂载的平台数据卷 PVC 名（环境侧预先提供，平台不创建）。
	// 空 = 默认 PVCName 常量（历史行为）；Helm/清单部署可指向环境已有 PVC。
	PVCName string

	IngressClass string
	IngressHost  string
	IngressPort  int
	// IngressHostSuffix 域名后缀（见 config.IngressHostSuffix）：空=router 模式
	// （不构造 per-service Ingress，子路径路由由集群内 platform-router 承担，
	// 见 RouterServiceName），非空=host 模式（host={K8sName}{suffix}、根路径直出）。
	// 内置构造与 overlay 不变量校验（template.go validateIngress）共用此值判定模式。
	IngressHostSuffix string

	RequestsCPU, RequestsMem, LimitsCPU, LimitsMem string
}

func labels(k8sName string) map[string]string {
	return map[string]string{LabelKey: k8sName, ManagedByLabel: ManagedByValue}
}

// EnvConfigMap 构造 env ConfigMap（oaf-{name}-env，非敏感 env）。
func EnvConfigMap(p ObjectParams) *corev1.ConfigMap {
	return &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: p.K8sName + "-env", Namespace: p.Namespace, Labels: labels(p.K8sName)},
		Data:       p.Env,
	}
}

// EnvSecret 构造服务级敏感 env Secret（{k8sName}-env-secret；无敏感键时为空对象，
// 保持 Deployment envFrom 引用统一）。空值不进入：避免 Spring 占位符 ${VAR:default}
// 遇空串 env 不回落默认值。
func EnvSecret(p ObjectParams) *corev1.Secret {
	return &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{Name: EnvSecretName(p.K8sName), Namespace: p.Namespace, Labels: labels(p.K8sName)},
		Data:       stringMapToBytes(p.EnvSecret),
	}
}

func stringMapToBytes(m map[string]string) map[string][]byte {
	out := make(map[string][]byte, len(m))
	for k, v := range m {
		out[k] = []byte(v)
	}
	return out
}

// Deployment 构造业务 Deployment（固定注入保留键 + PVC subPath 挂载 /config）。
func Deployment(p ObjectParams) *appsv1.Deployment {
	replicas := p.Replicas
	if replicas < 1 {
		replicas = 1
	}
	fixedEnv := []corev1.EnvVar{
		{Name: "AGENT_CONFIG_DIR", Value: "/config"},
		{Name: "AGENT_WORKSPACE_DIR", Value: "/workspace"},
		{Name: "SERVER_HOST", Value: "0.0.0.0"},
		{Name: "SERVER_PORT", Value: fmt.Sprintf("%d", AgentPort)},
		// 日志规范：HOST_NAME=Pod Name（downward API），logback 据此拼日志路径 /applog/${HOST_NAME}
		// 与日志内容 [HOST_NAME] 字段；APP_NAME 不注入，由业务 env 可选指定，缺省 agent-framework
		{Name: "HOST_NAME", ValueFrom: &corev1.EnvVarSource{FieldRef: &corev1.ObjectFieldSelector{FieldPath: "metadata.name"}}},
	}
	probe := &corev1.Probe{
		ProbeHandler: corev1.ProbeHandler{HTTPGet: &corev1.HTTPGetAction{
			Path: "/health", Port: intstr.FromInt(AgentPort),
		}},
	}
	return &appsv1.Deployment{
		ObjectMeta: metav1.ObjectMeta{Name: p.K8sName, Namespace: p.Namespace, Labels: labels(p.K8sName)},
		Spec: appsv1.DeploymentSpec{
			Replicas: &replicas,
			Selector: &metav1.LabelSelector{MatchLabels: map[string]string{LabelKey: p.K8sName}},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: labels(p.K8sName)},
				Spec: corev1.PodSpec{
					Containers: []corev1.Container{{
						Name:            "agent",
						Image:           p.Image,
						ImagePullPolicy: corev1.PullIfNotPresent,
						Ports:           []corev1.ContainerPort{{ContainerPort: AgentPort}},
						// envFrom 两源（K8s 多源同键后引用者覆盖先引用者）：服务敏感 Secret 在前，
						// 服务 CM 在后可覆盖同名键。平台默认配置不经 envFrom 注入——仅作为发布/
						// 编辑 env 时的表单默认填入（R3 修订），服务只携带自己显式配置的 env
						EnvFrom: []corev1.EnvFromSource{
							{SecretRef: &corev1.SecretEnvSource{LocalObjectReference: corev1.LocalObjectReference{Name: EnvSecretName(p.K8sName)}}},
							{ConfigMapRef: &corev1.ConfigMapEnvSource{LocalObjectReference: corev1.LocalObjectReference{Name: p.K8sName + "-env"}}},
						},
						Env: fixedEnv,
						VolumeMounts: []corev1.VolumeMount{
							// OAF 包只读挂载到 /config（同一可写卷的只读 subPath）；工作区为独立可写空目录
							{Name: FilesVolumeName, MountPath: "/config", SubPath: p.SubPath, ReadOnly: true},
							{Name: WorkspaceVolumeName, MountPath: WorkspaceMountPath},
							// 文件存储可写挂载：platform-data subPath files/ → /data/files（FILE_STORAGE_LOCAL_DIR）
							// 与 /config 共用同一卷（agent-files）——避免同 PVC 双 volume 引用
							{Name: FilesVolumeName, MountPath: FilesMountPath, SubPath: FilesSubPath},
							// 日志规范：/applog/${HOST_NAME}/trace.log 为容器云日志采集唯一来源
							{Name: ApplogVolumeName, MountPath: ApplogMountPath},
						},
						ReadinessProbe: withDelay(probe, 15, 5),
						LivenessProbe:  withDelay(probe, 60, 15),
						Resources: corev1.ResourceRequirements{
							Requests: corev1.ResourceList{
								corev1.ResourceCPU:    resource.MustParse(orDefault(p.RequestsCPU, "250m")),
								corev1.ResourceMemory: resource.MustParse(orDefault(p.RequestsMem, "256Mi")),
							},
							Limits: corev1.ResourceList{
								corev1.ResourceCPU:    resource.MustParse(orDefault(p.LimitsCPU, "1")),
								corev1.ResourceMemory: resource.MustParse(orDefault(p.LimitsMem, "1Gi")),
							},
						},
					}},
					Volumes: []corev1.Volume{
						{
							// 单一可写卷（agent-files）：/config 与 /data/files 均挂载其上
							// （/config 为只读 subPath，/data/files 为可写 subPath）。
							// 不设 ForceReadOnly——同一 PVC 不可同时以 ro/rw 双 volume 引用
							Name: FilesVolumeName,
							VolumeSource: corev1.VolumeSource{
								PersistentVolumeClaim: &corev1.PersistentVolumeClaimVolumeSource{ClaimName: orDefault(p.PVCName, PVCName)},
							},
						},
						{Name: WorkspaceVolumeName, VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
						// 日志目录卷（emptyDir，同 workspace 模式，非 root appuser 可写）
						{Name: ApplogVolumeName, VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
					},
				},
			},
		},
	}
}

func orDefault(v, d string) string {
	if v == "" {
		return d
	}
	return v
}

func withDelay(pr *corev1.Probe, initial, period int32) *corev1.Probe {
	c := pr.DeepCopy()
	c.InitialDelaySeconds = initial
	c.PeriodSeconds = period
	return c
}

// Service 构造 ClusterIP Service（oaf-{name}-svc）。
func Service(p ObjectParams) *corev1.Service {
	return &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{Name: p.K8sName + "-svc", Namespace: p.Namespace, Labels: labels(p.K8sName)},
		Spec: corev1.ServiceSpec{
			Type:     corev1.ServiceTypeClusterIP,
			Selector: map[string]string{LabelKey: p.K8sName},
			Ports:    []corev1.ServicePort{{Port: AgentPort, TargetPort: intstr.FromInt(AgentPort)}},
		},
	}
}

// Ingress 注解常量（host 模式）：内置构造与 overlay 校验（template.go validateIngress）共用。
// 注：子路径路由已收敛为 router 模式（集群内 platform-router 原生 rewrite，见
// manifests/platform-router.yaml 与 subpath-routing-design），平台不再生成任何
// ingress-nginx rewrite 注解（rewrite-target/use-regex/x-forwarded-prefix）。
const (
	annSSLRedirect      = "nginx.ingress.kubernetes.io/ssl-redirect"
	annProxyReadTimeout = "nginx.ingress.kubernetes.io/proxy-read-timeout"
	annProxySendTimeout = "nginx.ingress.kubernetes.io/proxy-send-timeout"
	// hostModeRootPath host 模式的对外路径：根路径直出，overlay 不可改（Endpoint 固定
	// 派生为 {scheme}://{K8sName}{suffix}/，放开 path 会让展示地址与实际路由错位）。
	hostModeRootPath = "/"
	// RouterServiceName router 模式的集群内子路径路由器 Service
	// （manifests/platform-router.yaml 一次性自举；启动自检见 RequireRouter）。
	RouterServiceName = "platform-router-svc"
)

// pathTypePtr 取 PathType 指针（networkingv1.PathType 为值类型，v1 要求非空）。
func pathTypePtr(t networkingv1.PathType) *networkingv1.PathType { return &t }

// ingressBackend 业务 backend 引用：{K8sName}-svc:8100（Ingress 对象与服务一一对应，
// 两种模式与 overlay 合并后的不变量校验共用）。
func ingressBackend(p ObjectParams) networkingv1.IngressBackend {
	return networkingv1.IngressBackend{Service: &networkingv1.IngressServiceBackend{
		Name: p.K8sName + "-svc",
		Port: networkingv1.ServiceBackendPort{Number: AgentPort},
	}}
}

// Ingress 构造 host 模式 nginx Ingress（host={K8sName}{suffix}、path 根路径 /、
// pathType Prefix，仅 ssl-redirect 与 SSE 长超时注解，无 rewrite 相关注解）。
//
// 契约：IngressHostSuffix 必须非空——suffix 空 = router 模式，平台不构造任何
// per-service Ingress（IngressBuilder.Build 对此 fail-fast），子路径路由由集群内
// platform-router 承担（见 RouterServiceName）。
func Ingress(p ObjectParams) *networkingv1.Ingress {
	return &networkingv1.Ingress{
		ObjectMeta: metav1.ObjectMeta{
			Name: p.K8sName, Namespace: p.Namespace, Labels: labels(p.K8sName),
			Annotations: map[string]string{
				annSSLRedirect: "false",
				// A2A blocking 请求与 SSE 流式场景需要长超时
				annProxyReadTimeout: "3600",
				annProxySendTimeout: "3600",
			},
		},
		Spec: networkingv1.IngressSpec{
			IngressClassName: &p.IngressClass,
			Rules: []networkingv1.IngressRule{{
				Host: p.K8sName + p.IngressHostSuffix,
				IngressRuleValue: networkingv1.IngressRuleValue{HTTP: &networkingv1.HTTPIngressRuleValue{
					Paths: []networkingv1.HTTPIngressPath{{
						Path:     hostModeRootPath,
						PathType: pathTypePtr(networkingv1.PathTypePrefix),
						Backend:  ingressBackend(p),
					}},
				}},
			}},
		},
	}
}
