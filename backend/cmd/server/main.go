// 平台管理后端入口：REST(/api/v1) + MCP(/mcp，M3 接入) 同进程。
package main

import (
	"fmt"
	"log"
	"os"

	"github.com/gin-gonic/gin"

	"agent-manager/backend/config"
	"agent-manager/backend/internal/handler"
	k8sclient "agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/mcpsrv"
	"agent-manager/backend/internal/service"
	"agent-manager/backend/internal/store"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	db, err := store.Open(cfg.MySQLDSN)
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	fs := &store.FS{Root: cfg.DataRoot}

	kc, err := k8sclient.NewInCluster(os.Getenv("KUBECONFIG"))
	if err != nil {
		log.Fatalf("k8s client: %v", err)
	}
	kc.WithNamespace(cfg.Namespace)

	core := service.NewCore(db, fs, kc, service.ConfigView{
		Namespace:       cfg.Namespace,
		IngressClass:    cfg.IngressClass,
		IngressHost:     cfg.IngressHost,
		IngressPort:     cfg.IngressPort,
		DefaultImage:    cfg.DefaultImage,
		ImageOptions:    toServiceImages(cfg.AvailableImages),
		ImageAllowed:    cfg.IsAllowedImage,
		ResCPU:          cfg.ResourceRequestsCPU,
		ResMem:          cfg.ResourceRequestsMem,
		LimCPU:          cfg.ResourceLimitsCPU,
		LimMem:          cfg.ResourceLimitsMem,
		RegisterTimeout: cfg.RegisterTimeout,
		RegisterRetry:   cfg.RegisterRetry,
	})

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery())

	handler.Register(r, core, toHandlerImages(cfg.AvailableImages), cfg.AuthToken)

	// M3：MCP streamableHttp /mcp 与 REST 同端口挂载
	// MCP 门面：streamableHttp /mcp 与 REST 同端口
	r.Any("/mcp", gin.WrapH(mcpsrv.New(core)))

	log.Printf("platform-backend listening on :%d (ns=%s)", cfg.ServerPort, cfg.Namespace)
	if err := r.Run(fmt.Sprintf(":%d", cfg.ServerPort)); err != nil {
		log.Fatal(err)
	}
}

func toHandlerImages(opts []config.ImageOption) []struct{ Image, Label string } {
	out := make([]struct{ Image, Label string }, 0, len(opts))
	for _, o := range opts {
		out = append(out, struct{ Image, Label string }{o.Image, o.Label})
	}
	return out
}

func toServiceImages(opts []config.ImageOption) []service.ImageOption {
	out := make([]service.ImageOption, 0, len(opts))
	for _, o := range opts {
		out = append(out, service.ImageOption{Image: o.Image, Label: o.Label})
	}
	return out
}
