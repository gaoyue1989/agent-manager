// Package handler：Gin REST 门面（薄层，全部转发 service.Core）。
package handler

import (
	"errors"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"

	"agent-manager/backend/internal/service"
	"agent-manager/backend/internal/store"
)

type resp struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

func OK(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, resp{Code: 0, Message: "ok", Data: data})
}

func Fail(c *gin.Context, status int, msg string) {
	c.JSON(status, resp{Code: status, Message: msg})
}

// mapError 业务错误 → HTTP 状态码。
func mapError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, service.ErrNotFound), errors.Is(err, gorm.ErrRecordNotFound),
		errors.Is(err, service.ErrWorkspaceNotFound):
		Fail(c, http.StatusNotFound, err.Error())
	case errors.Is(err, service.ErrForbidden), errors.Is(err, service.ErrUserDisabled):
		Fail(c, http.StatusForbidden, err.Error())
	case errors.Is(err, service.ErrConflict):
		Fail(c, http.StatusConflict, err.Error())
	case errors.Is(err, service.ErrDuplicateName), errors.Is(err, service.ErrInvalidForm),
		errors.Is(err, service.ErrInvalidCredentials):
		Fail(c, http.StatusBadRequest, err.Error())
	case errors.Is(err, service.ErrSkillReferenced):
		// 技能被 Agent 引用：删除/下线冲突（设计 §8.4.1 决议 5，曾因未映射落 500）
		Fail(c, http.StatusConflict, err.Error())
	case errors.Is(err, service.ErrSkillVersionImmutable):
		// 正式版本不可变 / 版本状态不允许该动作（发布已发布版、下线草稿等）
		Fail(c, http.StatusConflict, err.Error())
	case errors.Is(err, service.ErrChecksumMismatch),
		errors.Is(err, service.ErrVersionConflict):
		// 乐观锁/版本冲突：基础包已被并发修改或版本号被并发保存占用（issue #172）
		Fail(c, http.StatusConflict, err.Error())
	case errors.Is(err, service.ErrImageNotAllowed),
		errors.Is(err, service.ErrPackageInUse),
		errors.Is(err, service.ErrBadState),
		errors.Is(err, service.ErrNoEffectiveChanges),
		errors.Is(err, service.ErrAgentsMDUndeletable),
		errors.Is(err, service.ErrUpsertInvalid),
		errors.Is(err, service.ErrUnknownConfigKey),
		errors.Is(err, service.ErrRequiredConfigKey),
		errors.Is(err, service.ErrEmptyConfigValues),
		errors.Is(err, service.ErrMarkNotBuildable), // 无可用 frozen 标记（创建导出单前置校验，表单类 400）
		errors.Is(err, store.ErrNoAgentsMD),
		errors.Is(err, store.ErrZipTooLarge),
		errors.Is(err, store.ErrTooManyFiles),
		errors.Is(err, store.ErrExtractTooLarge),
		errors.Is(err, store.ErrZipSlip):
		Fail(c, http.StatusBadRequest, err.Error())
	default:
		// env 校验与 zip 解析类错误统一 400
		msg := err.Error()
		if strings.Contains(msg, "env key") || strings.Contains(msg, "env value") ||
			strings.Contains(msg, "too many env") || strings.Contains(msg, "invalid zip") ||
			strings.Contains(msg, "parse AGENTS.md") || strings.Contains(msg, "invalid AGENTS.md") {
			Fail(c, http.StatusBadRequest, err.Error())
			return
		}
		Fail(c, http.StatusInternalServerError, err.Error())
	}
}
