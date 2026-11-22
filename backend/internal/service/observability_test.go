package service

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"agent-manager/backend/internal/store"
)

// stubPrometheus 按查询内容返回 canned Prometheus 响应（query 精选 + query_range 趋势）。
func stubPrometheus(t *testing.T) (*httptest.Server, *[]string) {
	t.Helper()
	var seen []string
	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/query", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query().Get("query")
		seen = append(seen, q)
		var result []map[string]any
		switch {
		case strings.Contains(q, `sum by (tool)`) && strings.Contains(q, `result="error"`):
			result = []map[string]any{{"metric": map[string]string{"tool": "read_file"}, "value": []any{1700000000.0, "2"}}}
		case strings.Contains(q, `sum by (tool)`):
			result = []map[string]any{
				{"metric": map[string]string{"tool": "read_file"}, "value": []any{1700000000.0, "10"}},
				{"metric": map[string]string{"tool": "web_fetch"}, "value": []any{1700000000.0, "3"}},
			}
		case strings.Contains(q, `sum by (service)`) && strings.Contains(q, `result="error"`):
			result = []map[string]any{{"metric": map[string]string{"service": "alpha"}, "value": []any{1700000000.0, "2"}}}
		case strings.Contains(q, `sum by (service)`):
			// beta 序列仅在 beta 被选中时返回（模拟真实 Prometheus 按选择器过滤）；
			// 用例中 beta 无调用 → HasData=false
			result = []map[string]any{{"metric": map[string]string{"service": "alpha"}, "value": []any{1700000000.0, "10"}}}
			if strings.Contains(q, `"beta"`) {
				result = append(result, map[string]any{
					"metric": map[string]string{"service": "beta"}, "value": []any{1700000000.0, "3"}})
			}
		case strings.Contains(q, `result="error"`):
			result = []map[string]any{{"metric": map[string]string{}, "value": []any{1700000000.0, "2"}}}
		default:
			result = []map[string]any{{"metric": map[string]string{}, "value": []any{1700000000.0, "13"}}}
		}
		writeJSON(w, map[string]any{"status": "success", "data": map[string]any{
			"resultType": "vector", "result": result}})
	})
	mux.HandleFunc("/api/v1/query_range", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query().Get("query")
		seen = append(seen, q)
		// 采样点相对请求 start 落位（真实时间无关），保证桶对齐断言稳定
		st, _ := strconv.ParseFloat(r.URL.Query().Get("start"), 64)
		vals := [][]any{{st, "8"}, {st + 3600, "5"}}
		if strings.Contains(q, `result="error"`) {
			vals = [][]any{{st + 3600, "1"}}
		}
		writeJSON(w, map[string]any{"status": "success", "data": map[string]any{
			"resultType": "matrix", "result": []map[string]any{{"metric": map[string]string{}, "values": vals}}}})
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv, &seen
}

func writeJSON(w http.ResponseWriter, body any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(body)
}

// stubAgentRecent 单 agent /debug/monitor/tools 明细桩。
func stubAgentRecent(t *testing.T, body map[string]any) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/debug/monitor/tools") {
			http.NotFound(w, r)
			return
		}
		writeJSON(w, body)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func seedObsService(t *testing.T, core *Core, name, display, clusterURL string, wsID uint) {
	t.Helper()
	if err := core.DB.Create(&store.ServiceEntity{
		K8sName: name, DisplayName: display, ClusterURL: clusterURL,
		WorkspaceID: wsID, Status: store.StatusRunning,
	}).Error; err != nil {
		t.Fatal(err)
	}
}

func TestToolCallsShouldAggregateAndMerge(t *testing.T) {
	core, _, cleanup := newTestCore(t)
	defer cleanup()
	prom, seen := stubPrometheus(t)
	agent := stubAgentRecent(t, map[string]any{
		"connected": true,
		"recent": []map[string]any{
			{"created_at": "2026-11-21 10:00:00", "tool_name": "read_file",
				"session_id": "s1", "result_state": "success"},
		},
	})
	core.Cfg.PrometheusURL = prom.URL
	seedObsService(t, core, "oaf-alpha", "Alpha", agent.URL, 1)
	seedObsService(t, core, "oaf-beta", "Beta", "", 1)

	view, err := core.ToolCalls(1, "24h")
	if err != nil {
		t.Fatalf("ToolCalls: %v", err)
	}

	// 选择器只含本空间服务（slug = k8s 名去 oaf- 前缀），且不含他空间服务
	for _, q := range *seen {
		if !strings.Contains(q, `service=~"alpha|beta"`) {
			t.Fatalf("选择器应含本空间服务 slug: %q", q)
		}
		if strings.Contains(q, "gamma") {
			t.Fatalf("选择器不得含他空间服务: %q", q)
		}
	}

	if view.Totals.Calls != 13 || view.Totals.Errors != 2 {
		t.Fatalf("totals = %v/%v, want 13/2", view.Totals.Calls, view.Totals.Errors)
	}
	if view.Totals.ErrorRate <= 0 {
		t.Fatalf("errorRate 应 > 0: %v", view.Totals.ErrorRate)
	}
	if len(view.ByTool) != 2 || view.ByTool[0].Tool != "read_file" || view.ByTool[0].Calls != 10 {
		t.Fatalf("byTool 应按次数降序: %+v", view.ByTool)
	}
	if view.ByTool[0].Errors != 2 {
		t.Fatalf("read_file 失败数 = %v, want 2", view.ByTool[0].Errors)
	}
	// byService 与 DB 清单全外联：beta 无指标序列 → HasData=false
	if len(view.ByService) != 2 {
		t.Fatalf("byService 应含全部 DB 服务: %+v", view.ByService)
	}
	if !view.ByService[0].HasData || view.ByService[1].HasData {
		t.Fatalf("HasData 标注不符: %+v", view.ByService)
	}
	if view.ByService[0].Name != "Alpha" || view.ByService[1].Name != "Beta" {
		t.Fatalf("展示名应来自 DB: %+v", view.ByService)
	}
	// 趋势：24h → 24 桶；缺失点补 0
	if len(view.Trend) != 24 {
		t.Fatalf("trend 桶数 = %d, want 24", len(view.Trend))
	}
	var trendCalls float64
	for _, p := range view.Trend {
		trendCalls += p.Calls
	}
	if trendCalls != 13 {
		t.Fatalf("trend 汇总 = %v, want 13", trendCalls)
	}
	// 明细合并：fan-out 到可达 agent，附服务名
	if len(view.Recent) != 1 || view.Recent[0].Service != "alpha" || view.Recent[0].Name != "Alpha" {
		t.Fatalf("recent = %+v", view.Recent)
	}
	if view.Recent[0].ResultState != "success" {
		t.Fatalf("recent result = %q", view.Recent[0].ResultState)
	}
}

func TestToolCallsShouldScopeToWorkspace(t *testing.T) {
	core, _, cleanup := newTestCore(t)
	defer cleanup()
	prom, seen := stubPrometheus(t)
	agent := stubAgentRecent(t, map[string]any{"connected": true, "recent": []map[string]any{}})
	core.Cfg.PrometheusURL = prom.URL
	seedObsService(t, core, "oaf-mine", "Mine", agent.URL, 7)
	seedObsService(t, core, "oaf-other", "Other", agent.URL, 9)

	if _, err := core.ToolCalls(7, "7d"); err != nil {
		t.Fatalf("ToolCalls: %v", err)
	}
	for _, q := range *seen {
		if strings.Contains(q, "other") {
			t.Fatalf("工作空间 7 的查询不得含空间 9 服务: %q", q)
		}
		if strings.Contains(q, `service=~"mine"`) == false && strings.Contains(q, "sum") {
			t.Fatalf("查询应含 mine: %q", q)
		}
	}
}

func TestToolCallsShouldMergeRecentAcrossAgents(t *testing.T) {
	core, _, cleanup := newTestCore(t)
	defer cleanup()
	prom, _ := stubPrometheus(t)
	core.Cfg.PrometheusURL = prom.URL
	a := stubAgentRecent(t, map[string]any{"connected": true, "recent": []map[string]any{
		{"created_at": "2026-11-21 10:00:00", "tool_name": "a_tool", "session_id": "s1", "result_state": "success"},
	}})
	b := stubAgentRecent(t, map[string]any{"connected": true, "recent": []map[string]any{
		{"created_at": "2026-11-21 11:00:00", "tool_name": "b_tool", "session_id": "s2", "result_state": "error"},
	}})
	down := httptest.NewServer(http.HandlerFunc(http.NotFound))
	defer down.Close()
	seedObsService(t, core, "oaf-a", "A", a.URL, 1)
	seedObsService(t, core, "oaf-b", "B", b.URL, 1)
	seedObsService(t, core, "oaf-down", "Down", down.URL, 1) // 不可达：静默跳过

	view, err := core.ToolCalls(1, "30d")
	if err != nil {
		t.Fatalf("ToolCalls: %v", err)
	}
	if len(view.Recent) != 2 {
		t.Fatalf("recent 应合并 2 条（不可达跳过）: %+v", view.Recent)
	}
	if view.Recent[0].Tool != "b_tool" || view.Recent[1].Tool != "a_tool" {
		t.Fatalf("recent 应按时间倒序: %+v", view.Recent)
	}
}

func TestToolCallsShouldFailFastWithoutPrometheus(t *testing.T) {
	core, _, cleanup := newTestCore(t)
	defer cleanup()
	if _, err := core.ToolCalls(0, "24h"); !errors.Is(err, ErrObservabilityDown) {
		t.Fatalf("PROMETHEUS_URL 缺失应返回 ErrObservabilityDown, got %v", err)
	}
}

func TestToolCallsShouldRejectInvalidRange(t *testing.T) {
	core, _, cleanup := newTestCore(t)
	defer cleanup()
	core.Cfg.PrometheusURL = "http://127.0.0.1:1"
	if _, err := core.ToolCalls(0, "1w"); !errors.Is(err, ErrInvalidRange) {
		t.Fatalf("非法 range 应返回 ErrInvalidRange, got %v", err)
	}
}

func TestPromServiceSelectorShouldEscapeAndSort(t *testing.T) {
	sel := promServiceSelector([]observabilityService{
		{metricLabel: "b-agent"}, {metricLabel: "a.1"},
	})
	if sel != `service=~"a\.1|b-agent"` {
		t.Fatalf("selector = %q", sel)
	}
}

func TestOafSlugForMetricShouldStripPrefix(t *testing.T) {
	if oafSlugForMetric("oaf-order-svc") != "order-svc" {
		t.Fatalf("slug 派生不符")
	}
	if oafSlugForMetric("plain") != "plain" {
		t.Fatalf("无前缀名应原样")
	}
}
