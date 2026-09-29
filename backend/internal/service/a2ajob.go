// A2A message/send 幂等 Job 薄封装（travel-fulfillment 设计 §8 平台端/§12 评审 P1-4）：
// 官方 order-fulfillment 的 Job Endpoint 以 API key + Idempotency-Key 为入口，而 A2A
// taskId 只是执行句柄非幂等键——重复 message/send 会建新任务。本封装以 (service,
// idempotency_key) 映射表保证同键稳定锚点。
//
// 并发与失败语义（CR P0-1/P1-1 修订，2026-09-30）：
//   - 预留行以认领令牌（task_id='claim:<token>'）独占发送权：同键并发请求在他人
//     认领期间得 ErrJobInFlight（409），绝不双发；
//   - 发送失败分类：确定未受理（连接失败/HTTP 4xx/JSON-RPC error）→ 释放认领允许
//     重试；结果未知（超时/HTTP 5xx）→ 保留认领并刷新租期（A2A message/send 是
//     blocking 长对话，超时≠未受理，成员端可能已建任务）；
//   - 认领租期 = 2×发送超时 + 60s，进程崩溃/结果未知的残留认领到期后可被接管，
//     自愈不悬挂。
package service

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"

	"gorm.io/gorm"

	"agent-manager/backend/internal/store"
)

// 认领令牌前缀：预留/发送中行的 task_id 形态（视图层脱敏为空串）
const a2aClaimPrefix = "claim:"

// 键字符集：URL-safe（GET /jobs/:key 路径段约束）
var a2aKeyRe = regexp.MustCompile(`^[A-Za-z0-9._-]{1,128}$`)

// 文本报文上限（对齐 env 32KB 风格）
const a2aMaxTextBytes = 32 << 10

// a2aSendTimeoutDefault message/send blocking 发送超时（Core.A2ASendTimeout 缺省值，
// env AGENT_A2A_SEND_TIMEOUT_SECONDS 经 cmd/server 装配覆盖）
const a2aSendTimeoutDefault = 300 * time.Second

// netError 鸭子类型接口（匹配 net.Error 的 Timeout 子集，避免引入 net 包断言耦合）
type netError interface {
	error
	Timeout() bool
}

// 幂等键进行中（409）：同键已有在途认领且未过租期
var ErrJobInFlight = errors.New("a2a job submission in progress: retry with the same Idempotency-Key later")

// 幂等键输入非法（400）
var ErrJobInvalid = errors.New("a2a job input invalid")

// SubmitJob 幂等提交：同键命中最终 taskId 直接返回（idempotent=true）；未命中走
// "预留 → 认领 → 发送 → 回填"，认领独占发送权。返回 ErrJobInFlight（409）表示
// 同键在途/结果未知，客户端持同键重试直至收敛；ErrJobInvalid（400）为输入非法。
func (c *Core) SubmitJob(serviceID uint, key, text string) (*store.A2aJob, bool, error) {
	if !a2aKeyRe.MatchString(key) {
		return nil, false, fmt.Errorf("%w: Idempotency-Key must match [A-Za-z0-9._-]{1,128}", ErrJobInvalid)
	}
	if strings.TrimSpace(text) == "" {
		return nil, false, fmt.Errorf("%w: text is required", ErrJobInvalid)
	}
	if len(text) > a2aMaxTextBytes {
		return nil, false, fmt.Errorf("%w: text too large (max %d bytes)", ErrJobInvalid, a2aMaxTextBytes)
	}
	svc, err := c.Get(serviceID)
	if err != nil {
		return nil, false, err
	}
	if svc.Status != store.StatusRunning {
		return nil, false, fmt.Errorf("%w: service %d is %s", ErrBadState, serviceID, svc.Status)
	}

	// 有限次"查 → 建行/认领"竞争循环：Create 输家/认领被抢各重查一次
	for attempt := 0; attempt < 3; attempt++ {
		var existing store.A2aJob
		qerr := c.DB.Where("service_id = ? AND idempotency_key = ?", serviceID, key).
			First(&existing).Error
		switch {
		case qerr == nil:
			if taskID := viewTaskID(&existing); taskID != "" {
				existing.TaskID = taskID
				return &existing, true, nil // 幂等命中
			}
			// 空行/在途认领：过期可接管，未过期返回进行中
			if !c.a2aClaimExpired(&existing) {
				return nil, false, ErrJobInFlight
			}
			token := randomHex()
			if c.DB.Model(&store.A2aJob{}).
				Where("id = ? AND task_id = ?", existing.ID, existing.TaskID).
				Updates(map[string]any{"task_id": a2aClaimPrefix + token, "reserved_at": time.Now()}).
				RowsAffected != 1 {
				continue // 认领被抢，重查
			}
			existing.TaskID = a2aClaimPrefix + token
			return c.sendClaimed(&existing, token, svc.ClusterURL, text)
		case errors.Is(qerr, gorm.ErrRecordNotFound):
			reservation := store.A2aJob{ServiceID: serviceID, IdempotencyKey: key,
				ReservedAt: ptrTime(time.Now())}
			if cerr := c.DB.Create(&reservation).Error; cerr != nil {
				continue // 联合唯一输家：重查走命中/在途分支
			}
			return c.sendClaimed(&reservation, "", svc.ClusterURL, text)
		default:
			return nil, false, fmt.Errorf("query job mapping: %w", qerr)
		}
	}
	return nil, false, ErrJobInFlight
}

// sendClaimed 对已认领（新建空行为认领前，token 传 ""）的预留行发送并收口。
// 新建行先原子认领（防并发同键双发），失败路径按错误类别释放/保留。
func (c *Core) sendClaimed(job *store.A2aJob, token, clusterURL, text string) (*store.A2aJob, bool, error) {
	if token == "" {
		// 新建空行 → 认领（联合唯一已保证行归属，条件更新防同键并发复用空行）
		token = randomHex()
		if c.DB.Model(&store.A2aJob{}).
			Where("id = ? AND task_id = ''", job.ID).
			Updates(map[string]any{"task_id": a2aClaimPrefix + token, "reserved_at": time.Now()}).
			RowsAffected != 1 {
			return nil, false, ErrJobInFlight
		}
	}
	taskID, err := c.sendA2AMessage(clusterURL, text)
	if err == nil {
		// 条件回填：仅当仍持本人认领（防接管者覆盖）
		if c.DB.Model(&store.A2aJob{}).
			Where("id = ? AND task_id = ?", job.ID, a2aClaimPrefix+token).
			Update("task_id", taskID).RowsAffected != 1 {
			return nil, false, ErrJobInFlight
		}
		job.TaskID = taskID
		return job, false, nil
	}
	if a2aDefiniteReject(err) {
		// 确定未受理：释放认领，同键可立即重试
		c.DB.Model(&store.A2aJob{}).
			Where("id = ? AND task_id = ?", job.ID, a2aClaimPrefix+token).
			Updates(map[string]any{"task_id": "", "reserved_at": time.Now()})
		return nil, false, err
	}
	// 结果未知（超时/5xx）：保留认领 + 刷新租期；任务可能已受理，禁止重发
	c.DB.Model(&store.A2aJob{}).
		Where("id = ? AND task_id = ?", job.ID, a2aClaimPrefix+token).
		Update("reserved_at", time.Now())
	return nil, false, fmt.Errorf("%w (last error: %v)", ErrJobInFlight, err)
}

// viewTaskID 脱敏视图：认领中/崩溃残留行 task_id 视为空串（客户端语义 = 处理中）。
func viewTaskID(job *store.A2aJob) string {
	if job == nil || job.TaskID == "" || strings.HasPrefix(job.TaskID, a2aClaimPrefix) {
		return ""
	}
	return job.TaskID
}

// a2aClaimExpired 认领租期判断：reserved_at 距今超过 2×发送超时+60s 可接管
//（发送超时可配置，覆盖阻塞式长对话；崩溃残留最终自愈）。
func (c *Core) a2aClaimExpired(job *store.A2aJob) bool {
	if job.ReservedAt == nil {
		return true
	}
	return time.Since(*job.ReservedAt) > c.a2aClaimLease()
}

func (c *Core) a2aClaimLease() time.Duration {
	timeout := c.A2ASendTimeout
	if timeout <= 0 {
		timeout = 300 * time.Second
	}
	return 2*timeout + 60*time.Second
}

// a2aDefiniteReject 判定"成员端确定未受理该消息"：连接未建立、HTTP 4xx、
// JSON-RPC 显式 error。超时与 5xx 一律按结果未知处理（不释放认领）。
func a2aDefiniteReject(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	if strings.Contains(msg, "a2a error:") { // JSON-RPC 显式 error：服务端已处理并拒绝
		return true
	}
	if strings.Contains(msg, "http 4") { // 4xx：请求被拒，未建任务
		return true
	}
	var netErr netError
	if errors.As(err, &netErr) && !netErr.Timeout() {
		return true // 连接类错误（ refused/reset 等）
	}
	return false
}

// GetJob 按幂等键查询映射；认领中/残留行 task_id 脱敏为空串（处理中语义）。
func (c *Core) GetJob(serviceID uint, key string) (*store.A2aJob, error) {
	var job store.A2aJob
	if err := c.DB.Where("service_id = ? AND idempotency_key = ?", serviceID, key).
		First(&job).Error; err != nil {
		return nil, err
	}
	job.TaskID = viewTaskID(&job)
	return &job, nil
}

func ptrTime(t time.Time) *time.Time { return &t }

// sendA2AMessage 调业务服务的 A2A JSON-RPC message/send（TransportProperties path "/"），
// 解析 result.id（回落 result.task.id）为 taskId；JSON-RPC error / 非 2xx 均报错。
// 超时取 Core.A2ASendTimeout（AGENT_A2A_SEND_TIMEOUT_SECONDS，默认 300s——
// message/send 为 blocking 长对话语义，与业务 Ingress 3600s 长超时同量级口径）。
// 注：A2A 端点当前无认证（AgentProtocolAuthFilter 仅保护 /tasks*），无需携带协议 token。
func (c *Core) sendA2AMessage(clusterURL, text string) (string, error) {
	if clusterURL == "" {
		return "", fmt.Errorf("service has no cluster url")
	}
	payload := map[string]any{
		"jsonrpc": "2.0",
		"id":      randomHex(),
		"method":  "message/send",
		"params": map[string]any{
			"message": map[string]any{
				// A2A 0.3.x 规范 JSON：role 小写 "user"/"agent"（部署实测，
				// a2a-java-sdk Message.Role JSON 反序列化口径）
				"role":      "user",
				"kind":      "message",
				"messageId": randomHex(),
				"parts":     []map[string]any{{"kind": "text", "text": text}},
			},
		},
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	timeout := c.A2ASendTimeout
	if timeout <= 0 {
		timeout = 300 * time.Second
	}
	client := &http.Client{Timeout: timeout}
	resp, err := client.Post(clusterURL+"/", "application/json", bytes.NewReader(body))
	if err != nil {
		return "", fmt.Errorf("a2a message/send: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("a2a message/send http %d: %s", resp.StatusCode, truncateErr(raw))
	}
	var out struct {
		Result struct {
			ID   string `json:"id"`
			Task struct {
				ID string `json:"id"`
			} `json:"task"`
		} `json:"result"`
		Err struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", fmt.Errorf("parse a2a response: %w", err)
	}
	if out.Err.Message != "" {
		return "", fmt.Errorf("a2a error: %s", out.Err.Message)
	}
	taskID := out.Result.ID
	if taskID == "" {
		taskID = out.Result.Task.ID
	}
	if taskID == "" {
		return "", fmt.Errorf("a2a response has no task id")
	}
	if len(taskID) > 256 {
		return "", fmt.Errorf("a2a task id too long (%d)", len(taskID))
	}
	return taskID, nil
}

func randomHex() string {
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func truncateErr(raw []byte) string {
	s := string(raw)
	if len(s) > 200 {
		s = s[:200]
	}
	return s
}
