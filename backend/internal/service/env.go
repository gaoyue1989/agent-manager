// Package service：协议无关业务层（REST 与 MCP 共用）。
package service

import (
	"fmt"
	"regexp"
)

var envKeyRe = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

const (
	MaxEnvKeys       = 64
	MaxEnvValueBytes = 32 << 10 // 单值上限 32KB
)

// ValidateEnv 校验用户环境变量：键名格式、非保留键、数量与大小限制。
func ValidateEnv(env map[string]string) error {
	if len(env) > MaxEnvKeys {
		return fmt.Errorf("too many env keys: %d (max %d)", len(env), MaxEnvKeys)
	}
	for k, v := range env {
		if !envKeyRe.MatchString(k) {
			return fmt.Errorf("illegal env key %q", k)
		}
		// k8s 对象构造层共享的保留键定义
		for rk := range reservedKeys {
			if k == rk {
				return fmt.Errorf("env key %q is reserved by platform", k)
			}
		}
		if len(v) > MaxEnvValueBytes {
			return fmt.Errorf("env value of %q exceeds %d bytes", k, MaxEnvValueBytes)
		}
	}
	return nil
}
