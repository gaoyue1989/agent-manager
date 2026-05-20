package config

import "os"

type Config struct {
	ServerPort            string
	MySQLDSN              string
	MinIOEndpoint         string
	MinIOAccessKey        string
	MinIOSecretKey        string
	MinIOBucket           string
	KubeConfig            string
	KubeNamespace         string
	KubeIngressClass      string
	KubeClientMode        string
	LocalRegistry         string
	CodeGenScript         string
	CodeGenPython         string
	LLMModel              string
	LLMEndpoint           string
	LLMAPIKey             string
	IngressHost           string
	IngressEnabled        bool
	BaseImageName         string
	BuildBaseImage        bool
	AvailableImages       string
	DefaultImage          string
	DefaultCheckpointDSN  string
	DeployMethod          string
	DeployTemplateDir     string
}

func Load() *Config {
	return &Config{
		ServerPort:           getEnv("SERVER_PORT", "8080"),
		MySQLDSN:             getEnv("MYSQL_DSN", "agent_manager:Agent@Manager2026@tcp(127.0.0.1:3307)/agent_manager?charset=utf8mb4&parseTime=True&loc=Local"),
		MinIOEndpoint:        getEnv("MINIO_ENDPOINT", "127.0.0.1:9000"),
		MinIOAccessKey:       getEnv("MINIO_ACCESS_KEY", "minioadmin"),
		MinIOSecretKey:       getEnv("MINIO_SECRET_KEY", "minioadmin"),
		MinIOBucket:          getEnv("MINIO_BUCKET", "agent-manager"),
		KubeConfig:           getEnv("KUBE_CONFIG", ""),
		KubeNamespace:        getEnv("KUBE_NAMESPACE", "default"),
		KubeIngressClass:     getEnv("KUBE_INGRESS_CLASS", "nginx"),
		KubeClientMode:       getEnv("K8S_CLIENT_MODE", "kubectl"),
		LocalRegistry:        getEnv("LOCAL_REGISTRY", "172.20.0.1:5001"),
		CodeGenScript:        getEnv("CODEGEN_SCRIPT", "/root/agent-manager/codegen/generator.py"),
		CodeGenPython:        getEnv("CODEGEN_PYTHON", "/root/agent-manager/codegen/venv/bin/python3"),
		LLMModel:             getEnv("LLM_MODEL", "qwen3.6-plus"),
		LLMEndpoint:          getEnv("LLM_ENDPOINT", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
		LLMAPIKey:            getEnv("LLM_API_KEY", "sk-****"),
		IngressHost:          getEnv("INGRESS_HOST", "localhost"),
		IngressEnabled:       getEnv("INGRESS_ENABLED", "true") == "true",
		BaseImageName:        getEnv("BASE_IMAGE_NAME", "agent-base:latest"),
		BuildBaseImage:       getEnv("BUILD_BASE_IMAGE", "true") == "true",
		AvailableImages:      getEnv("AVAILABLE_IMAGES", "agent-framework:latest|Agent Framework v0.5.5"),
		DefaultImage:         getEnv("DEFAULT_IMAGE", "agent-framework:latest"),
		DefaultCheckpointDSN: getEnv("DEFAULT_CHECKPOINT_DSN", ""),
		DeployMethod:         getEnv("DEPLOY_METHOD", "sandbox"),
		DeployTemplateDir:    getEnv("DEPLOY_TEMPLATE_DIR", ""),
	}
}

func getEnv(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}
