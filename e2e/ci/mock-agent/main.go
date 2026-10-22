// CI 兜底业务镜像：真实 agent-framework 镜像拉取失败/超时时使用。
// 只实现平台发布链路需要的最小 A2A 面（/health + agent-card），name/version
// 从只读挂载的 /config/AGENTS.md 解析，保证 platform-e2e 的注册/换版断言仍然成立。
// 不实现会话/工具/沙箱，运行时会话类断言由脚本按 E2E_AGENT_RUNTIME=mock 跳过。
package main

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
)

func frontmatterValue(raw, key string) string {
	for _, line := range strings.Split(raw, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, key+":") {
			v := strings.TrimSpace(strings.TrimPrefix(line, key+":"))
			return strings.Trim(v, `"'`)
		}
	}
	return ""
}

// 最小 /models 面：让平台模型连接下发（GET/POST/PATCH）在 mock 兜底模式下也能走通，
// 不实现真实推理。契约见 backend/internal/service/modelconn.go DeliverOne。
type remoteModel struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	ModelID string `json:"model_id"`
	BaseURL string `json:"base_url"`
}

var (
	modelsMu sync.Mutex
	models   []remoteModel
)

func upsertModel(name string, body map[string]any) remoteModel {
	modelsMu.Lock()
	defer modelsMu.Unlock()
	m := remoteModel{ID: name, Name: name}
	if v, ok := body["modelId"].(string); ok {
		m.ModelID = v
	}
	if v, ok := body["baseUrl"].(string); ok {
		m.BaseURL = v
	}
	for i := range models {
		if models[i].ID == name || models[i].Name == name {
			models[i] = m
			return m
		}
	}
	models = append(models, m)
	return m
}

func main() {
	port := os.Getenv("SERVER_PORT")
	if port == "" {
		port = "8100"
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/models", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			modelsMu.Lock()
			defer modelsMu.Unlock()
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"default_model": "", "models": models})
		case http.MethodPost:
			var body map[string]any
			raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
			_ = json.Unmarshal(raw, &body)
			name, _ := body["name"].(string)
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(upsertModel(name, body))
		default:
			w.WriteHeader(http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/models/", func(w http.ResponseWriter, r *http.Request) {
		id := strings.TrimPrefix(r.URL.Path, "/models/")
		if r.Method == http.MethodDelete {
			modelsMu.Lock()
			defer modelsMu.Unlock()
			out := models[:0]
			for _, m := range models {
				if m.ID != id {
					out = append(out, m)
				}
			}
			models = out
			w.WriteHeader(http.StatusOK)
			return
		}
		var body map[string]any
		raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		_ = json.Unmarshal(raw, &body)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(upsertModel(id, body))
	})
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})
	mux.HandleFunc("/.well-known/agent-card.json", func(w http.ResponseWriter, r *http.Request) {
		name, version := "E2E Demo Agent", "1.0.0"
		if data, err := os.ReadFile("/config/AGENTS.md"); err == nil {
			if v := frontmatterValue(string(data), "name"); v != "" {
				name = v
			}
			if v := frontmatterValue(string(data), "version"); v != "" {
				version = v
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"name":            name,
			"version":         version,
			"description":     "E2E mock agent (agent-framework image unavailable)",
			"protocolVersion": "0.3.0",
			"url":             "http://" + r.Host + "/",
		})
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"service":"e2e-mock-agent"}`))
	})

	log.Printf("e2e mock agent listening on :%s", port)
	log.Fatal(http.ListenAndServe(":"+port, mux))
}
