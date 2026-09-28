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
	// 平台默认配置对象（namespace 单例）：非敏感键走 CM、敏感键走 Secret。
	// 命名不得以 "-env"/"-env-secret" 结尾——服务 CM/Secret 名恒为 {k8sName}-env(-secret)
	// 且 k8sName 恒以 oaf- 开头，否则特定服务名（如 "agent-default"）会同类型同名碰撞。
	DefaultConfigCMName = "oaf-platform-default-config"
	DefaultSecretName   = "oaf-platform-default-secret"
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

	IngressClass string
	IngressHost  string
	IngressPort  int

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

// PlatformDefaultConfigMap 构造平台默认配置 CM（模板 Sensitive=false 且有值的键）。
func PlatformDefaultConfigMap(ns string, data map[string]string) *corev1.ConfigMap {
	return &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: DefaultConfigCMName, Namespace: ns, Labels: platformLabels()},
		Data:       data,
	}
}

// PlatformDefaultSecret 构造平台默认配置 Secret（模板 Sensitive=true 且有值的键）。
func PlatformDefaultSecret(ns string, data map[string]string) *corev1.Secret {
	return &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{Name: DefaultSecretName, Namespace: ns, Labels: platformLabels()},
		Data:       stringMapToBytes(data),
	}
}

func platformLabels() map[string]string {
	return map[string]string{ManagedByLabel: ManagedByValue}
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
						// envFrom 四源（K8s 多源同键后引用者覆盖先引用者，K8s 1.32 实测）：
						// 平台默认（非敏感 CM + 敏感 Secret）在前作兜底，服务级（CM + Secret）在后可覆盖
						EnvFrom: []corev1.EnvFromSource{
							{ConfigMapRef: &corev1.ConfigMapEnvSource{LocalObjectReference: corev1.LocalObjectReference{Name: DefaultConfigCMName}}},
							{SecretRef: &corev1.SecretEnvSource{LocalObjectReference: corev1.LocalObjectReference{Name: DefaultSecretName}}},
							{ConfigMapRef: &corev1.ConfigMapEnvSource{LocalObjectReference: corev1.LocalObjectReference{Name: p.K8sName + "-env"}}},
							{SecretRef: &corev1.SecretEnvSource{LocalObjectReference: corev1.LocalObjectReference{Name: EnvSecretName(p.K8sName)}}},
						},
						Env:             fixedEnv,
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
								PersistentVolumeClaim: &corev1.PersistentVolumeClaimVolumeSource{ClaimName: PVCName},
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

// Ingress 构造 nginx Ingress：path /agent/{short}(/|$)(.*) → rewrite /$2。
func Ingress(p ObjectParams) *networkingv1.Ingress {
	short := ShortName(p.K8sName)
	pt := networkingv1.PathTypeImplementationSpecific
	return &networkingv1.Ingress{
		ObjectMeta: metav1.ObjectMeta{
			Name: p.K8sName, Namespace: p.Namespace, Labels: labels(p.K8sName),
			Annotations: map[string]string{
				"nginx.ingress.kubernetes.io/rewrite-target": "/$2",
				"nginx.ingress.kubernetes.io/use-regex":      "true",
				"nginx.ingress.kubernetes.io/ssl-redirect":   "false",
				// A2A blocking 请求与 SSE 流式场景需要长超时
				"nginx.ingress.kubernetes.io/proxy-read-timeout": "3600",
				"nginx.ingress.kubernetes.io/proxy-send-timeout": "3600",
				// 向后端透传外部前缀（Debug Console 尾斜杠重定向等场景）
				"nginx.ingress.kubernetes.io/x-forwarded-prefix": fmt.Sprintf("/agent/%s", short),
			},
		},
		Spec: networkingv1.IngressSpec{
			IngressClassName: &p.IngressClass,
			Rules: []networkingv1.IngressRule{{
				IngressRuleValue: networkingv1.IngressRuleValue{HTTP: &networkingv1.HTTPIngressRuleValue{
					Paths: []networkingv1.HTTPIngressPath{{
						Path:     fmt.Sprintf("/agent/%s(/|$)(.*)", short),
						PathType: &pt,
						Backend: networkingv1.IngressBackend{Service: &networkingv1.IngressServiceBackend{
							Name: p.K8sName + "-svc",
							Port: networkingv1.ServiceBackendPort{Number: AgentPort},
						}},
					}},
				}},
			}},
		},
	}
}
