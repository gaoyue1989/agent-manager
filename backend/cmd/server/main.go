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

	sandbox, err := k8s.NewSandboxClientWithIngress(cfg.IngressHost, cfg.IngressEnabled)
	if err != nil {
		log.Printf("WARNING: failed to init k8s sandbox client: %v", err)
	}

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

	agentSvc := service.NewAgentServiceWithDeps(db, storage, cgRunner, sandbox, builder, cfg.LocalRegistry)
	deploySvc := service.NewDeployServiceWithBaseImage(db, storage, builder, sandbox, cfg.LocalRegistry, agentSvc, cfg.IngressEnabled, cfg.BaseImageName)

	r := gin.Default()
	r.Use(cors.New(cors.Config{
		AllowOrigins:     []string{"*"},
		AllowMethods:     []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
		AllowHeaders:     []string{"*"},
		AllowCredentials: true,
	}))

	v1 := r.Group("/api/v1")
	handler.NewAgentHandler(agentSvc).Register(v1)
	handler.NewDeployHandler(deploySvc).Register(v1)

	addr := fmt.Sprintf(":%s", cfg.ServerPort)
	log.Printf("Server starting on %s", addr)
	r.Run(addr)
}
