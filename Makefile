.PHONY: dev dev-backend dev-frontend dev-nginx-setup
.PHONY: build build-backend build-frontend
.PHONY: backend-start backend-stop backend-restart backend-status
.PHONY: frontend-start frontend-stop frontend-restart frontend-status
.PHONY: docker-build docker-build-backend docker-build-frontend
.PHONY: docker-up docker-down docker-logs docker-restart
.PHONY: ingress-deploy ingress-delete ingress-status
.PHONY: kind-load kind-status
.PHONY: test test-backend test-e2e test-ingress
.PHONY: lint lint-backend lint-frontend
.PHONY: clean

BACKEND_BIN := backend/bin/server
BACKEND_PID := /tmp/agent-manager-backend.pid

# === 开发 ===
dev:
	@echo "Starting backend and frontend..."
	@echo "  backend: http://localhost:8080"
	@echo "  frontend: http://localhost:3000"
	@echo "  nginx:   http://localhost:8911 (run 'make dev-nginx-setup' first)"
	@echo ""
	$(MAKE) -j2 dev-backend dev-frontend

dev-backend:
	cd backend && go run ./cmd/server

dev-frontend:
	cd frontend && npm run dev

dev-nginx-setup:
	@echo "=== 本地开发 Nginx 反向代理配置 ==="
	@echo ""
	@echo "配置文件: docker/nginx/agent-manager.conf"
	@echo "统一入口: http://localhost:8911"
	@echo ""
	@echo "手动执行以下命令启用:"
	@echo "  sudo cp docker/nginx/agent-manager.conf /etc/nginx/conf.d/agent-manager.conf"
	@echo "  sudo nginx -t && sudo nginx -s reload"

# === 构建 ===
build: build-backend build-frontend

build-backend:
	cd backend && CGO_ENABLED=0 go build -o bin/server ./cmd/server

build-frontend:
	cd frontend && npm run build

# === 后台运行 ===
backend-start: build-backend
	@if [ -f $(BACKEND_PID) ] && kill -0 $$(cat $(BACKEND_PID)) 2>/dev/null; then \
		echo "Backend already running (PID: $$(cat $(BACKEND_PID)))"; \
		exit 1; \
	fi
	@echo "Starting backend in background..."
	@cd backend && nohup ./bin/server > /tmp/agent-manager-backend.log 2>&1 & echo $$! > $(BACKEND_PID)
	@sleep 1
	@if kill -0 $$(cat $(BACKEND_PID)) 2>/dev/null; then \
		echo "Backend started (PID: $$(cat $(BACKEND_PID)), Port: 8080)"; \
		echo "Logs: /tmp/agent-manager-backend.log"; \
	else \
		echo "Backend failed to start. Check /tmp/agent-manager-backend.log"; \
		exit 1; \
	fi

backend-stop:
	@if [ -f $(BACKEND_PID) ] && kill -0 $$(cat $(BACKEND_PID)) 2>/dev/null; then \
		kill $$(cat $(BACKEND_PID)) && rm -f $(BACKEND_PID); \
		echo "Backend stopped"; \
	else \
		echo "Backend not running"; \
		rm -f $(BACKEND_PID); \
	fi

backend-restart: backend-stop
	@sleep 1
	$(MAKE) backend-start

backend-status:
	@if [ -f $(BACKEND_PID) ] && kill -0 $$(cat $(BACKEND_PID)) 2>/dev/null; then \
		echo "Backend running (PID: $$(cat $(BACKEND_PID)))"; \
	else \
		echo "Backend not running"; \
	fi

# === 前端管理 (PM2) ===
FRONTEND_STANDALONE := frontend/.next/standalone/server.js

frontend-start: build-frontend
	@if [ ! -f frontend/.next/standalone/.next/static ]; then \
		echo "Copying static files..."; \
		cp -r frontend/.next/static frontend/.next/standalone/.next/; \
	fi
	@if [ ! -d frontend/.next/standalone/public ]; then \
		echo "Copying public files..."; \
		cp -r frontend/public frontend/.next/standalone/ 2>/dev/null || true; \
	fi
	@pm2 list | grep -q "frontend.*online" && echo "Frontend already running (PM2)" && exit 0 || true
	@echo "Starting frontend with PM2..."
	@cd frontend/.next/standalone && PORT=3000 HOSTNAME=0.0.0.0 pm2 start server.js --name "frontend"
	@pm2 save
	@echo "Frontend started (PM2, Port: 3000)"

frontend-stop:
	@pm2 delete frontend 2>/dev/null && echo "Frontend stopped" || echo "Frontend not running"
	@pm2 save

frontend-restart:
	@pm2 restart frontend 2>/dev/null && echo "Frontend restarted" || (echo "Frontend not running, starting..."; $(MAKE) frontend-start)

frontend-status:
	@pm2 list

# === Docker 镜像 ===
docker-build-backend:
	docker build -t agent-manager-backend:latest -f backend/Dockerfile backend/

docker-build-frontend:
	docker build -t agent-manager-frontend:latest -f frontend/Dockerfile frontend/

docker-build: docker-build-backend docker-build-frontend

# === Docker Compose ===
docker-up:
	docker compose -f docker/docker-compose.yml up -d --build
	@echo ""
	@echo "Services started: http://localhost:8911"

docker-down:
	docker compose -f docker/docker-compose.yml down

docker-logs:
	docker compose -f docker/docker-compose.yml logs -f

docker-restart:
	docker compose -f docker/docker-compose.yml down
	docker compose -f docker/docker-compose.yml up -d --build

# === Kind 集群 ===
kind-status:
	@echo "=== Kind 集群状态 ==="
	@kind get clusters
	@echo ""
	@echo "=== K8s 资源 ==="
	@kubectl get nodes -o wide
	@echo ""
	@kubectl get pods -A

kind-load:
	@echo "=== 加载镜像到 Kind ==="
	@kind load docker-image registry.cn-hangzhou.aliyuncs.com/google_containers/nginx-ingress-controller:v1.10.1 --name agent-manager
	@kind load docker-image registry.cn-hangzhou.aliyuncs.com/google_containers/kube-webhook-certgen:v1.4.1 --name agent-manager

# === Ingress Controller ===
ingress-deploy:
	@echo "=== 部署 Nginx Ingress Controller ==="
	@kubectl apply -f sandbox/ingress-nginx-simple.yaml
	@echo ""
	@echo "等待 Controller 就绪..."
	@sleep 10
	@kubectl get pods -n ingress-nginx
	@echo ""
	@echo "Ingress Controller 已部署"
	@echo "HTTP NodePort: 30080"
	@echo "HTTPS NodePort: 30443"

ingress-delete:
	@echo "=== 删除 Nginx Ingress Controller ==="
	@kubectl delete -f sandbox/ingress-nginx-simple.yaml --ignore-not-found=true
	@kubectl delete clusterrole ingress-nginx --ignore-not-found=true
	@kubectl delete clusterrolebinding ingress-nginx --ignore-not-found=true
	@echo "Ingress Controller 已删除"

ingress-status:
	@echo "=== Ingress Controller 状态 ==="
	@kubectl get pods -n ingress-nginx 2>/dev/null || echo "Namespace not found"
	@echo ""
	@kubectl get svc -n ingress-nginx 2>/dev/null || echo "Service not found"
	@echo ""
	@kubectl get ingressclass 2>/dev/null || echo "IngressClass not found"
	@echo ""
	@echo "=== Agent Ingress 资源 ==="
	@kubectl get ingress -n default 2>/dev/null || echo "No agent ingress found"

# === 测试 ===
test: test-backend test-e2e

test-backend:
	cd backend && go test ./internal/... -v -count=1

test-e2e:
	cd e2e && node e2e-test.js

test-ingress:
	cd e2e && node e7-ingress-test.js

# === Lint ===
lint: lint-backend lint-frontend

lint-backend:
	cd backend && go vet ./...

lint-frontend:
	cd frontend && npm run lint

# === 清理 ===
clean:
	cd backend && rm -rf bin/
	docker compose -f docker/docker-compose.yml down -v 2>/dev/null || true
