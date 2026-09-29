// A2A message/send 幂等 Job 薄封装（travel-fulfillment 设计 §8 平台端/§12 评审 P1-4）：
// 官方 order-fulfillment 的 Job Endpoint 以 API key + Idempotency-Key 为入口，而 A2A
// taskId 只是执行句柄非幂等键——重复 message/send 会建新任务。本封装以 (service,
// idempotency_key) 映射表（先预留后发送的联合唯一锁）保证同键稳定锚点同一任务。
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
	"strings"
	"time"

	"gorm.io/gorm"

	"agent-manager/backend/internal/store"
)

const a2aSendTimeout = 15 * time.Second

// SubmitJob 幂等提交：同 (service, key) 命中既有映射直接返回（idempotent=true）；
// 未命中先预留映射行（联合唯一索引作并发锁）再发 A2A message/send，回填 taskId。
// 发送失败删除预留允许重试；命中但 taskId 为空（发送方崩溃残留）视为可续发预留。
func (c *Core) SubmitJob(serviceID uint, key, text string) (*store.A2aJob, bool, error) {
	if strings.TrimSpace(key) == "" {
		return nil, false, fmt.Errorf("idempotency key is required (header Idempotency-Key)")
	}
	if len(key) > 128 {
		return nil, false, fmt.Errorf("idempotency key too long (max 128)")
	}
	if strings.TrimSpace(text) == "" {
		return nil, false, fmt.Errorf("text is required")
	}
	svc, err := c.Get(serviceID)
	if err != nil {
		return nil, false, err
	}
	if svc.Status != store.StatusRunning {
		return nil, false, fmt.Errorf("service %d is %s, not running", serviceID, svc.Status)
	}

	var existing store.A2aJob
	err = c.DB.Where("service_id = ? AND idempotency_key = ?", serviceID, key).First(&existing).Error
	switch {
	case err == nil && existing.TaskID != "":
		return &existing, true, nil
	case err != nil && !errors.Is(err, gorm.ErrRecordNotFound):
		return nil, false, fmt.Errorf("query job mapping: %w", err)
	}

	// 预留映射行（新键插入 / 崩溃残留的空 task_id 行复用）：联合唯一索引挡并发同键
	job := existing
	if job.ID == 0 {
		job = store.A2aJob{ServiceID: serviceID, IdempotencyKey: key}
		if err := c.DB.Create(&job).Error; err != nil {
			// 并发同键输家：回查既有映射返回幂等命中
			var winner store.A2aJob
			if qerr := c.DB.Where("service_id = ? AND idempotency_key = ?", serviceID, key).
				First(&winner).Error; qerr == nil && winner.TaskID != "" {
				return &winner, true, nil
			}
			return nil, false, fmt.Errorf("reserve job mapping: %w", err)
		}
	}

	taskID, err := c.sendA2AMessage(svc.ClusterURL, text)
	if err != nil {
		if job.TaskID == "" {
			c.DB.Delete(&job) // 释放预留，同键可重试（发送未被成员受理）
		}
		return nil, false, err
	}
	if err := c.DB.Model(&job).Update("task_id", taskID).Error; err != nil {
		return nil, false, fmt.Errorf("save task id: %w", err)
	}
	job.TaskID = taskID
	return &job, false, nil
}

// GetJob 按幂等键查询映射；不存在返回 gorm.ErrRecordNotFound。
func (c *Core) GetJob(serviceID uint, key string) (*store.A2aJob, error) {
	var job store.A2aJob
	if err := c.DB.Where("service_id = ? AND idempotency_key = ?", serviceID, key).
		First(&job).Error; err != nil {
		return nil, err
	}
	return &job, nil
}

// sendA2AMessage 调业务服务的 A2A JSON-RPC message/send（TransportProperties path "/"），
// 解析 result.id（回落 result.task.id）为 taskId；JSON-RPC error / 非 2xx 均报错。
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
				"role":      "ROLE_USER",
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
	client := &http.Client{Timeout: a2aSendTimeout}
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
