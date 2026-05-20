package k8s

import (
	"fmt"
	"strings"
)

// DeploymentClient 封装标准 Deployment 资源的创建、删除、存在性检查
type DeploymentClient struct {
	client    K8sClient
	engine    *TemplateEngine
	namespace string
}

func NewDeploymentClient(client K8sClient, engine *TemplateEngine, namespace string) *DeploymentClient {
	return &DeploymentClient{client: client, engine: engine, namespace: namespace}
}

// CreateDeployment 创建标准 Deployment（build 模式，端口 8000）
func (d *DeploymentClient) CreateDeployment(name, image string, envVars map[string]string) error {
	return d.CreateDeploymentWithPort(name, image, 8000, envVars)
}

// CreateDeploymentWithPort 创建带指定端口的标准 Deployment
func (d *DeploymentClient) CreateDeploymentWithPort(name, image string, port int, envVars map[string]string) error {
	yaml, err := d.buildDeploymentYAML(name, image, port, envVars, nil, nil, nil)
	if err != nil {
		return fmt.Errorf("build deployment yaml: %w", err)
	}
	return d.client.ApplyYAML(yaml)
}

// CreateDeploymentWithMounts 创建挂载模式 Deployment（含 ConfigMap/Secret 卷挂载）
func (d *DeploymentClient) CreateDeploymentWithMounts(name, image string, port int, envVars map[string]string, configMapName, secretName, checkpointDSN string) error {
	envYAML := buildEnvYAML(envVars)
	envYAML += fmt.Sprintf("          - name: AGENT_CONFIG_DIR\n            value: \"/config\"\n")
	envYAML += fmt.Sprintf("          - name: LLM_API_KEY\n            valueFrom:\n              secretKeyRef:\n                name: %s\n                key: LLM_API_KEY\n", secretName)
	envYAML += fmt.Sprintf("          - name: SERVER_HOST\n            value: \"0.0.0.0\"\n")
	envYAML += fmt.Sprintf("          - name: SERVER_PORT\n            value: \"%d\"\n", port)
	if checkpointDSN != "" {
		envYAML += fmt.Sprintf("          - name: CHECKPOINT_MYSQL_DSN\n            value: \"%s\"\n", escapeK8sValue(checkpointDSN))
	}

	volumeMounts := fmt.Sprintf("        - name: config-volume\n          mountPath: /config\n")
	volumes := fmt.Sprintf("        - name: config-volume\n          configMap:\n            name: %s\n", configMapName)

	params := DeployParams{
		Name:         name,
		Namespace:    d.namespace,
		Image:        image,
		Port:         port,
		Replicas:     1,
		EnvYAML:      envYAML,
		VolumeMounts: volumeMounts,
		Volumes:      volumes,
	}

	yaml, err := d.engine.RenderDeployment(params)
	if err != nil {
		return fmt.Errorf("render deployment: %w", err)
	}
	return d.client.ApplyYAML(yaml)
}

func (d *DeploymentClient) buildDeploymentYAML(name, image string, port int, envVars map[string]string, livenessProbe, readinessProbe, resources *string) (string, error) {
	params := DeployParams{
		Name:      name,
		Namespace: d.namespace,
		Image:     image,
		Port:      port,
		Replicas:  1,
		EnvYAML:   buildEnvYAML(envVars),
	}

	if livenessProbe != nil && *livenessProbe != "" {
		params.LivenessProbe = *livenessProbe
	}
	if readinessProbe != nil && *readinessProbe != "" {
		params.ReadinessProbe = *readinessProbe
	}
	if resources != nil && *resources != "" {
		params.Resources = *resources
	}

	return d.engine.RenderDeployment(params)
}

// buildEnvYAML 将 map[string]string 转换为 Deployment YAML 格式的 env 片段 (10空格缩进)
func buildEnvYAML(envVars map[string]string) string {
	var sb strings.Builder
	for key, value := range envVars {
		sb.WriteString(fmt.Sprintf("          - name: %s\n            value: \"%s\"\n", key, escapeK8sValue(value)))
	}
	return sb.String()
}

// DeleteDeployment 删除指定名称的 Deployment
func (d *DeploymentClient) DeleteDeployment(name string) error {
	return d.client.DeleteResource("deployment", name)
}

// DeploymentExists 检查 Deployment 是否存在
func (d *DeploymentClient) DeploymentExists(name string) bool {
	return d.client.ResourceExists("deployment", name)
}

// escapeK8sValue 转义字符串中的双引号
func escapeK8sValue(value string) string {
	return strings.ReplaceAll(value, "\"", "\\\"")
}
