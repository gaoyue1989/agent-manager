package main

import (
	"fmt"
	"log"

	"agent-manager/backend/config"
	"agent-manager/backend/internal/codegen"
	"agent-manager/backend/internal/docker"
	"agent-manager/backend/internal/handler"
	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/minio"
	"agent-manager/backend/internal/model"
	"agent-manager/backend/internal/service"

	"github.com/gin-contrib/cors"
	"github.com/gin-gonic/gin"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"
)

func main() {
	cfg := config.Load()

	db, err := gorm.Open(mysql.Open(cfg.MySQLDSN), &gorm.Config{})
	if err != nil {
		log.Fatalf("failed to connect database: %v", err)
	}
	if err := db.AutoMigrate(&model.Agent{}, &model.CodeGeneration{}, &model.ImageBuild{}, &model.Deployment{}); err != nil {
		log.Fatalf("failed to migrate: %v", err)
	}

	storage, err := minio.New(cfg.MinIOEndpoint, cfg.MinIOAccessKey, cfg.MinIOSecretKey, cfg.MinIOBucket)
	if err != nil {
		log.Fatalf("failed to init minio: %v", err)
	}

	cgRunner := codegen.NewRunner(cfg.CodeGenScript, cfg.CodeGenPython, storage)

	// 初始化 K8s 客户端（kubectl 或 API）
	kubeClient, err := k8s.NewK8sClient(cfg.KubeClientMode, cfg.KubeConfig, cfg.KubeNamespace)
	if err != nil {
		log.Fatalf("failed to init k8s client: %v", err)
	}

	// 初始化模板引擎
	templateEngine, err := k8s.NewTemplateEngine(cfg.DeployTemplateDir)
	if err != nil {
		log.Fatalf("failed to init template engine: %v", err)
	}

	// 初始化 SandboxClient（使用 K8sClient）
	sandbox, err := k8s.NewSandboxClientWithConfig(kubeClient, cfg.KubeNamespace, cfg.IngressHost, cfg.IngressEnabled, cfg.KubeIngressClass)
	if err != nil {
		log.Printf("WARNING: failed to init k8s sandbox client: %v", err)
	}

	// 初始化 DeploymentClient（模板驱动的 Deployment 管理）
	deploymentClient := k8s.NewDeploymentClient(kubeClient, templateEngine, cfg.KubeNamespace)

	builder, err := docker.NewBuilder("gaoyue1989", "gao19891104")
	if err != nil {
		log.Printf("WARNING: failed to init docker builder: %v", err)
	}

	if cfg.BuildBaseImage {
		baseImageTag := fmt.Sprintf("%s/%s", cfg.LocalRegistry, cfg.BaseImageName)
		if !builder.ImageExists(baseImageTag) {
			log.Printf("Building base image: %s", baseImageTag)
			if _, err := builder.BuildBaseImage(cfg.LocalRegistry, cfg.BaseImageName); err != nil {
				log.Fatalf("failed to build base image: %v", err)
			}
			log.Printf("Base image built successfully: %s", baseImageTag)
		} else {
			log.Printf("Base image already exists: %s", baseImageTag)
		}
	}

	agentSvc := service.NewAgentServiceWithDeployMethod(db, storage, cgRunner, sandbox, builder, cfg.LocalRegistry, cfg.DeployMethod, deploymentClient)
	deploySvc := service.NewDeployServiceWithDeployMethod(db, storage, builder, sandbox, cfg.LocalRegistry, agentSvc, cfg.IngressEnabled, cfg.BaseImageName, cfg.LLMAPIKey, cfg.LLMModel, cfg.LLMEndpoint, cfg.DefaultCheckpointDSN, cfg.DeployMethod, deploymentClient)

	r := gin.Default()
	r.Use(cors.New(cors.Config{
		AllowOrigins:     []string{"*"},
		AllowMethods:     []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
		AllowHeaders:     []string{"*"},
		AllowCredentials: true,
	}))

	v1 := r.Group("/api/v1")
	handler.NewAgentHandler(agentSvc, cfg.AvailableImages).Register(v1)
	handler.NewDeployHandler(deploySvc).Register(v1)

	addr := fmt.Sprintf(":%s", cfg.ServerPort)
	log.Printf("Server starting on %s", addr)
	r.Run(addr)
}
