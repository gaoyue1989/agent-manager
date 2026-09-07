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
}

var dnsNameRe = regexp.MustCompile(`[^a-z0-9-]+`)

const (
	AgentPort           = 8100
	Prefix              = "oaf-"
	LabelKey            = "app.kubernetes.io/name"
	DataVolumeName      = "oaf-config"
	WorkspaceVolumeName = "agent-workspace"
	ManagedByLabel      = "app.kubernetes.io/managed-by"
	ManagedByValue      = "oaf-platform"
	PVCName             = "platform-data"
	// 文件存储可写挂载（file-upload-download-plan §13 P1 跨模块）：
	// platform-data PVC subPath "files/" 挂 /data/files，业务 agent 文件上传/产出落此处。
	FilesVolumeName   = "agent-files"
	FilesSubPath      = "files"
	FilesMountPath    = "/data/files"
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

type ObjectParams struct {
	K8sName   string
	Namespace string
	Image     string
	Env       map[string]string // 用户 env（已校验，不含保留键）
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

// EnvConfigMap 构造 env ConfigMap（oaf-{name}-env）。
func EnvConfigMap(p ObjectParams) *corev1.ConfigMap {
	return &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: p.K8sName + "-env", Namespace: p.Namespace, Labels: labels(p.K8sName)},
		Data:       p.Env,
	}
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
						EnvFrom:         []corev1.EnvFromSource{{ConfigMapRef: &corev1.ConfigMapEnvSource{LocalObjectReference: corev1.LocalObjectReference{Name: p.K8sName + "-env"}}}},
						Env:             fixedEnv,
VolumeMounts: []corev1.VolumeMount{
						// OAF 包只读挂载到 /config（同一可写卷的只读 subPath）；工作区为独立可写空目录
						{Name: FilesVolumeName, MountPath: "/config", SubPath: p.SubPath, ReadOnly: true},
						{Name: WorkspaceVolumeName, MountPath: "/workspace"},
						// 文件存储可写挂载：platform-data subPath files/ → /data/files（FILE_STORAGE_LOCAL_DIR）
						// 与 /config 共用同一卷（agent-files）——避免同 PVC 双 volume 引用
						{Name: FilesVolumeName, MountPath: FilesMountPath, SubPath: FilesSubPath},
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
				"nginx.ingress.kubernetes.io/rewrite-target":    "/$2",
				"nginx.ingress.kubernetes.io/use-regex":         "true",
				"nginx.ingress.kubernetes.io/ssl-redirect":      "false",
				// A2A blocking 请求与 SSE 流式场景需要长超时
				"nginx.ingress.kubernetes.io/proxy-read-timeout":  "3600",
				"nginx.ingress.kubernetes.io/proxy-send-timeout":  "3600",
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
