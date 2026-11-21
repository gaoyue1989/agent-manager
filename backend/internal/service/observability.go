// 工具调用观测聚合（tool-call-observability-design）。
//
// 数据契约（SSOT）：
//   - 计数/趋势/失败率 → Prometheus 指标 oaf_tool_calls_total{service,tool,result}
//     （agent-framework Micrometer 暴露，本服务经 PromQL 聚合）
//   - 最近调用明细 → 各 agent /debug/monitor/tools（framework tool_audit_log，只读 fan-out）
//
// 聚合采用拉模式（Prometheus query/query_range），与 AgentModels/ConsoleMemoryBrowse
// 同一「backend 透传、业务面零依赖」方向；Prometheus 不可达返回 ErrObservabilityDown
// 由 handler 映射为明确错误态，明细 fan-out 单服务失败静默跳过。
package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"agent-manager/backend/internal/store"
)

// ErrObservabilityDown 观测栈未部署或不可达（PROMETHEUS_URL 未配置 / 查询失败）。
var ErrObservabilityDown = errors.New("observability stack unavailable")

// ErrInvalidRange 观测窗口档位非法。
var ErrInvalidRange = errors.New("invalid range")

// 观测窗口档位 →（Prometheus range 选择器、趋势步长、桶数）。
// step 即 rate 窗口（Grafana increase(counter[$__interval]) 同款口径）。
var toolCallRanges = map[string]toolCallRange{
	"24h": {Selector: "24h", Step: time.Hour, Points: 24},
	"7d":  {Selector: "7d", Step: 6 * time.Hour, Points: 28},
	"30d": {Selector: "30d", Step: 24 * time.Hour, Points: 30},
}

type toolCallRange struct {
	Selector string
	Step     time.Duration
	Points   int
}

// ToolCallTotals 汇总计数。
type ToolCallTotals struct {
	Calls      float64 `json:"calls"`
	Errors     float64 `json:"errors"`
	ErrorRate  float64 `json:"errorRate"`
	ToolKinds  int     `json:"toolKinds"`
	ServiceNum int     `json:"serviceNum"`
}

// ToolCallToolRow 按工具聚合行（calls 降序）。
type ToolCallToolRow struct {
	Tool   string  `json:"tool"`
	Calls  float64 `json:"calls"`
	Errors float64 `json:"errors"`
}

// ToolCallServiceRow 按 agent 聚合行（与 DB 服务清单全外联，窗口内无序列者 HasData=false）。
type ToolCallServiceRow struct {
	Service string  `json:"service"` // 指标 service 标签（OAF slug）
	Name    string  `json:"name"`    // 展示名
	Status  string  `json:"status"`  // 服务发布状态（DB）
	Calls   float64 `json:"calls"`
	Errors  float64 `json:"errors"`
	HasData bool    `json:"hasData"`
}

// ToolCallTrendPoint 趋势分桶点。
type ToolCallTrendPoint struct {
	Bucket string  `json:"bucket"` // 桶起点（Prometheus UTC）
	Calls  float64 `json:"calls"`
	Errors float64 `json:"errors"`
}

// ToolCallRecent 最近调用明细行（多服务合并）。
type ToolCallRecent struct {
	At          string `json:"at"` // framework created_at（DATETIME 字符串，字典序即时间序）
	Service     string `json:"service"`
	Name        string `json:"name"`
	Tool        string `json:"tool"`
	SessionID   string `json:"sessionId"`
	ResultState string `json:"result"` // success / error / ""（未落终态或存量行）
}

// ToolCallStatsView 面板聚合视图。
type ToolCallStatsView struct {
	Range     string               `json:"range"`
	Totals    ToolCallTotals       `json:"totals"`
	ByTool    []ToolCallToolRow    `json:"byTool"`
	ByService []ToolCallServiceRow `json:"byService"`
	Trend     []ToolCallTrendPoint `json:"trend"`
	Recent    []ToolCallRecent     `json:"recent"`
}

// ToolCalls 工具调用观测聚合（workspaceID > 0 = 工作空间范围；0 = 全平台）。
func (c *Core) ToolCalls(workspaceID int64, rangeKey string) (*ToolCallStatsView, error) {
	// range 先行校验（400 语义确定性，不依赖观测栈是否部署）；空缺省 24h
	if rangeKey == "" {
		rangeKey = "24h"
	}
	r, ok := toolCallRanges[rangeKey]
	if !ok {
		return nil, fmt.Errorf("%w: %q (supported: 24h, 7d, 30d)", ErrInvalidRange, rangeKey)
	}
	if c.Cfg.PrometheusURL == "" {
		return nil, ErrObservabilityDown
	}

	// 服务清单（DB 是服务身份唯一真相源）
	services, err := c.observabilityServices(workspaceID)
	if err != nil {
		return nil, err
	}
	if len(services) == 0 {
		return emptyToolCallStats(rangeKey, r), nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	selector := promServiceSelector(services)

	// ---- 汇总 / 排行 / 服务维度：instant 查询六发并行 ----
	instant := map[string]string{
		"totals":    fmt.Sprintf(`sum(increase(oaf_tool_calls_total{%s}[%s]))`, selector, r.Selector),
		"errors":    fmt.Sprintf(`sum(increase(oaf_tool_calls_total{%s,result="error"}[%s]))`, selector, r.Selector),
		"byTool":    fmt.Sprintf(`sum by (tool) (increase(oaf_tool_calls_total{%s}[%s]))`, selector, r.Selector),
		"byToolErr": fmt.Sprintf(`sum by (tool) (increase(oaf_tool_calls_total{%s,result="error"}[%s]))`, selector, r.Selector),
		"bySvc":     fmt.Sprintf(`sum by (service) (increase(oaf_tool_calls_total{%s}[%s]))`, selector, r.Selector),
		"bySvcErr":  fmt.Sprintf(`sum by (service) (increase(oaf_tool_calls_total{%s,result="error"}[%s]))`, selector, r.Selector),
	}
	vectors := make(map[string][]promVector, len(instant))
	promErr := fanOutQueries(ctx, c, instant, vectors)
	if promErr != nil {
		return nil, errors.Join(ErrObservabilityDown, promErr)
	}

	view := &ToolCallStatsView{Range: rangeKey, ByTool: []ToolCallToolRow{}, ByService: []ToolCallServiceRow{}}
	view.Totals.Calls = vectorSum(vectors["totals"])
	view.Totals.Errors = vectorSum(vectors["errors"])
	if view.Totals.Calls > 0 {
		view.Totals.ErrorRate = view.Totals.Errors / view.Totals.Calls
	}

	// byTool：失败数按 tool 名并入（无失败者 0），次数降序
	toolErrByName := vectorByLabel(vectors["byToolErr"], "tool")
	for _, v := range vectors["byTool"] {
		view.ByTool = append(view.ByTool, ToolCallToolRow{
			Tool: v.Metric["tool"], Calls: promValue(v.Value), Errors: toolErrByName[v.Metric["tool"]],
		})
		view.Totals.ToolKinds++
	}
	sort.SliceStable(view.ByTool, func(i, j int) bool { return view.ByTool[i].Calls > view.ByTool[j].Calls })

	// byService：DB 清单全外联 metric（无序列 = 未抓取/零调用 → HasData=false）
	svcCalls := vectorByLabel(vectors["bySvc"], "service")
	svcErrs := vectorByLabel(vectors["bySvcErr"], "service")
	for _, s := range services {
		calls := svcCalls[s.metricLabel]
		view.ByService = append(view.ByService, ToolCallServiceRow{
			Service: s.metricLabel, Name: s.DisplayName, Status: s.Status,
			Calls: calls, Errors: svcErrs[s.metricLabel], HasData: calls > 0,
		})
		if calls > 0 {
			view.Totals.ServiceNum++
		}
	}
	sort.SliceStable(view.ByService, func(i, j int) bool { return view.ByService[i].Calls > view.ByService[j].Calls })

	// ---- 趋势：query_range 两发（step 即 rate 窗口），合并成完整桶序列 ----
	end := time.Now().UTC().Truncate(r.Step)
	start := end.Add(-time.Duration(r.Points-1) * r.Step)
	callsSeries, errsSeries, err := c.trendSeries(ctx, selector, r, start, end)
	if err != nil {
		return nil, errors.Join(ErrObservabilityDown, err)
	}
	view.Trend = buildTrendBuckets(start, r.Points, r.Step, callsSeries, errsSeries)

	// 最近明细：fan-out 各 agent /debug/monitor/tools，合并按时间倒序取 50
	view.Recent = c.recentToolCalls(services, 50)
	return view, nil
}

// observabilityService 参与观测聚合的服务（DB 身份 + 指标标签）。
type observabilityService struct {
	metricLabel string // 指标 service 标签值（OAF slug）
	K8sName     string
	DisplayName string
	Status      string
	ClusterURL  string
}

func (c *Core) observabilityServices(workspaceID int64) ([]observabilityService, error) {
	q := c.DB.Model(&store.ServiceEntity{}).Order("id DESC").
		Select("k8s_name, display_name, status, cluster_url")
	if workspaceID > 0 {
		q = q.Where("workspace_id = ?", workspaceID)
	}
	var rows []struct {
		K8sName     string
		DisplayName string
		Status      string
		ClusterURL  string
	}
	if err := q.Scan(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]observabilityService, 0, len(rows))
	for _, r := range rows {
		out = append(out, observabilityService{
			metricLabel: oafSlugForMetric(r.K8sName),
			K8sName:     r.K8sName, DisplayName: r.DisplayName,
			Status: r.Status, ClusterURL: r.ClusterURL,
		})
	}
	return out, nil
}

// oafSlugForMetric 指标 service 标签来源：framework 侧 OafConfig.slug()，与发布链路
// shortName 同源（k8s 名去 oaf- 前缀）。契约见 docs/design/tool-call-observability-design。
func oafSlugForMetric(k8sName string) string {
	return strings.TrimPrefix(k8sName, "oaf-")
}

// promServiceSelector 服务指标选择器（service=~"a|b"；全转义，=~ 自带全锚定）。
func promServiceSelector(services []observabilityService) string {
	names := make([]string, 0, len(services))
	for _, s := range services {
		names = append(names, regexp.QuoteMeta(s.metricLabel))
	}
	sort.Strings(names)
	return `service=~"` + strings.Join(names, "|") + `"`
}

// fanOutQueries 并行执行 instant 查询集（单查询失败取第一个错误）。
func fanOutQueries(ctx context.Context, c *Core, queries map[string]string,
	out map[string][]promVector) error {
	var mu sync.Mutex
	var wg sync.WaitGroup
	var first error
	for key, query := range queries {
		wg.Add(1)
		go func(key, query string) {
			defer wg.Done()
			v, err := c.promQuery(ctx, query)
			mu.Lock()
			defer mu.Unlock()
			if err != nil && first == nil {
				first = err
				return
			}
			if err == nil {
				out[key] = v
			}
		}(key, query)
	}
	wg.Wait()
	return first
}

// recentToolCalls 并行拉取各 agent 最近调用明细，合并倒序截断（单服务失败静默跳过）。
func (c *Core) recentToolCalls(services []observabilityService, limit int) []ToolCallRecent {
	var mu sync.Mutex
	var wg sync.WaitGroup
	var all []ToolCallRecent
	client := &http.Client{Timeout: 5 * time.Second}
	for _, s := range services {
		if s.ClusterURL == "" {
			continue
		}
		wg.Add(1)
		go func(s observabilityService) {
			defer wg.Done()
			rows, err := fetchAgentRecentCalls(client, s.ClusterURL)
			if err != nil {
				return
			}
			mu.Lock()
			defer mu.Unlock()
			for _, r := range rows {
				all = append(all, ToolCallRecent{
					At: r.CreatedAt, Service: s.metricLabel, Name: s.DisplayName,
					Tool: r.ToolName, SessionID: r.SessionID, ResultState: r.ResultState,
				})
			}
		}(s)
	}
	wg.Wait()
	sort.SliceStable(all, func(i, j int) bool { return all[i].At > all[j].At })
	if len(all) > limit {
		all = all[:limit]
	}
	if all == nil {
		all = []ToolCallRecent{}
	}
	return all
}

// agentRecentPayload /debug/monitor/tools 响应中本功能消费的字段。
type agentRecentPayload struct {
	Connected bool `json:"connected"`
	Recent    []struct {
		CreatedAt   string `json:"created_at"`
		ToolName    string `json:"tool_name"`
		SessionID   string `json:"session_id"`
		ResultState string `json:"result_state"`
	} `json:"recent"`
}

type agentRecentCall struct {
	CreatedAt   string
	ToolName    string
	SessionID   string
	ResultState string
}

func fetchAgentRecentCalls(client *http.Client, clusterURL string) ([]agentRecentCall, error) {
	resp, err := client.Get(strings.TrimSuffix(clusterURL, "/") + "/debug/monitor/tools?limit=50")
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var payload agentRecentPayload
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return nil, err
	}
	if !payload.Connected {
		return nil, fmt.Errorf("agent db unavailable")
	}
	out := make([]agentRecentCall, 0, len(payload.Recent))
	for _, r := range payload.Recent {
		out = append(out, agentRecentCall{
			CreatedAt: r.CreatedAt, ToolName: r.ToolName,
			SessionID: r.SessionID, ResultState: r.ResultState,
		})
	}
	return out, nil
}

// trendSeries 调用/失败两条趋势序列（step 即 rate 窗口）。
func (c *Core) trendSeries(ctx context.Context, selector string, r toolCallRange,
	start, end time.Time) (promMatrixSeries, promMatrixSeries, error) {
	stepSec := strconv.Itoa(int(r.Step.Seconds()))
	calls, err := c.promQueryRange(ctx,
		fmt.Sprintf(`sum(increase(oaf_tool_calls_total{%s}[%s]))`, selector, stepSec+"s"),
		start, end, r.Step)
	if err != nil {
		return promMatrixSeries{}, promMatrixSeries{}, err
	}
	errs, err := c.promQueryRange(ctx,
		fmt.Sprintf(`sum(increase(oaf_tool_calls_total{%s,result="error"}[%s]))`, selector, stepSec+"s"),
		start, end, r.Step)
	if err != nil {
		return promMatrixSeries{}, promMatrixSeries{}, err
	}
	return calls, errs, nil
}

// ---- Prometheus HTTP API 客户端（net/http 直连，同 httpGetJSON 信任模型） ----

type promVector struct {
	Metric map[string]string `json:"metric"`
	Value  []any             `json:"value"` // [ts, "value"]
}

type promMatrixSeries struct {
	Metric map[string]string `json:"metric"`
	Values [][]any           `json:"values"`
}

type promAPIResponse struct {
	Status string `json:"status"`
	Data   struct {
		ResultType string          `json:"resultType"`
		Result     json.RawMessage `json:"result"`
	} `json:"data"`
	Error string `json:"error"`
}

func (c *Core) promQuery(ctx context.Context, query string) ([]promVector, error) {
	var target []promVector
	err := c.promAPI(ctx, "/api/v1/query", url.Values{"query": []string{query}}, &target)
	return target, err
}

func (c *Core) promQueryRange(ctx context.Context, query string, start, end time.Time,
	step time.Duration) (promMatrixSeries, error) {
	q := url.Values{
		"query": []string{query},
		"start": []string{strconv.FormatInt(start.Unix(), 10)},
		"end":   []string{strconv.FormatInt(end.Unix(), 10)},
		"step":  []string{strconv.Itoa(int(step.Seconds()))},
	}
	var series []promMatrixSeries
	if err := c.promAPI(ctx, "/api/v1/query_range", q, &series); err != nil {
		return promMatrixSeries{}, err
	}
	if len(series) == 0 {
		return promMatrixSeries{}, nil
	}
	return series[0], nil
}

func (c *Core) promAPI(ctx context.Context, path string, q url.Values, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		strings.TrimSuffix(c.Cfg.PrometheusURL, "/")+path+"?"+q.Encode(), nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	var api promAPIResponse
	if err := json.NewDecoder(resp.Body).Decode(&api); err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK || api.Status != "success" {
		return fmt.Errorf("prometheus %s: HTTP %d %s", path, resp.StatusCode, api.Error)
	}
	return json.Unmarshal(api.Data.Result, out)
}

// vectorSum instant 查询结果求和（空 vector = 窗口内零调用 → 0）。
func vectorSum(v []promVector) float64 {
	var sum float64
	for _, r := range v {
		sum += promValue(r.Value)
	}
	return sum
}

// vectorByLabel 按 label 取值映射。
func vectorByLabel(v []promVector, label string) map[string]float64 {
	m := make(map[string]float64, len(v))
	for _, r := range v {
		m[r.Metric[label]] = promValue(r.Value)
	}
	return m
}

func promValue(raw []any) float64 {
	if len(raw) < 2 {
		return 0
	}
	s, _ := raw[1].(string)
	v, _ := strconv.ParseFloat(s, 64)
	return v
}

// buildTrendBuckets 按 step 生成完整桶序列（缺失点补 0），calls/errors 双系列并桶。
func buildTrendBuckets(start time.Time, points int, step time.Duration,
	calls, errs promMatrixSeries) []ToolCallTrendPoint {
	callsAt := bucketValues(calls, start, step, points)
	errsAt := bucketValues(errs, start, step, points)
	out := make([]ToolCallTrendPoint, points)
	for i := 0; i < points; i++ {
		out[i] = ToolCallTrendPoint{
			Bucket: start.Add(time.Duration(i) * step).Format("01-02 15:04"),
			Calls:  callsAt[i], Errors: errsAt[i],
		}
	}
	return out
}

// bucketValues series 采样值按桶位对齐（时间戳映射到桶序号，越界丢弃）。
func bucketValues(series promMatrixSeries, start time.Time, step time.Duration, points int) []float64 {
	vals := make([]float64, points)
	base := start.Unix()
	for _, v := range series.Values {
		if len(v) < 2 {
			continue
		}
		ts, ok := v[0].(float64)
		if !ok {
			continue
		}
		b := (int64(ts) - base) / int64(step.Seconds())
		if b >= 0 && b < int64(points) {
			vals[b] = promValue(v)
		}
	}
	return vals
}

func emptyToolCallStats(rangeKey string, r toolCallRange) *ToolCallStatsView {
	v := &ToolCallStatsView{Range: rangeKey}
	start := time.Now().UTC().Truncate(r.Step).Add(-time.Duration(r.Points-1) * r.Step)
	v.Trend = make([]ToolCallTrendPoint, r.Points)
	for i := range v.Trend {
		v.Trend[i] = ToolCallTrendPoint{Bucket: start.Add(time.Duration(i) * r.Step).Format("01-02 15:04")}
	}
	v.ByTool = []ToolCallToolRow{}
	v.ByService = []ToolCallServiceRow{}
	v.Recent = []ToolCallRecent{}
	return v
}
