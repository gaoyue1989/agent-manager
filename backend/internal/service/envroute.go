// env 路由与合并：用户 env 按模板 Sensitive 分类为非敏感/敏感两份（设计 §3.6）。
// 敏感键绝不进入 CM/env_json；写路径对旧 env_json 中的存量敏感键做自愈迁移。
package service

import (
	"encoding/json"
	"fmt"

	"agent-manager/backend/internal/service/platformconfig"
	"agent-manager/backend/internal/store"
)

// splitUserEnv 按模板 Sensitive 与显式 secretKeys 把用户 env 路由为非敏感/敏感两份。
func splitUserEnv(env map[string]string, secretKeys []string) (plain, secret map[string]string) {
	force := make(map[string]bool, len(secretKeys))
	for _, k := range secretKeys {
		force[k] = true
	}
	return platformconfig.Split(env, force)
}

// validateSecretKeys 校验显式 secretKeys：键名格式与保留键（值合法性随 env 一并校验）。
func validateSecretKeys(keys []string) error {
	for _, k := range keys {
		if !envKeyRe.MatchString(k) {
			return fmt.Errorf("illegal env key %q", k)
		}
		for rk := range reservedKeys {
			if k == rk {
				return fmt.Errorf("env key %q is reserved by platform", k)
			}
		}
	}
	return nil
}

// partitionServiceEnv 读取服务两级 env 并做存量迁移分区：
// 旧 env_json 中命中模板敏感集合的存量键归入敏感份（写路径据此自动完成
// "敏感键 → env_secret_json + 服务 Secret" 迁移，见设计 §3.6 防丢失规则）。
func (c *Core) partitionServiceEnv(svc *store.ServiceEntity) (plain, secret map[string]string, err error) {
	plain = map[string]string{}
	if err := json.Unmarshal([]byte(svc.EnvJSON), &plain); err != nil {
		return nil, nil, fmt.Errorf("corrupt env json: %w", err)
	}
	secret = map[string]string{}
	if svc.EnvSecretJSON != "" {
		if err := json.Unmarshal([]byte(svc.EnvSecretJSON), &secret); err != nil {
			return nil, nil, fmt.Errorf("corrupt env secret json: %w", err)
		}
	}
	sensitive := platformconfig.SensitiveKeys()
	for k, v := range plain {
		if sensitive[k] {
			secret[k] = v
			delete(plain, k)
		}
	}
	return plain, secret, nil
}

// resolveEnvMerge 写路径合并：以旧两级 env 为基底（含存量迁移），传入 env 为
// 非敏感部分的全量覆盖；敏感键三态——出现且非空=设置、出现且空串=删除
// （回落平台默认值）、未出现=sticky 保持不变（防页面盲点误删）。
func resolveEnvMerge(oldPlain, oldSecret, env map[string]string, secretKeys []string) (plain, secret map[string]string) {
	secret = map[string]string{}
	for k, v := range oldSecret {
		secret[k] = v // sticky 基底
	}
	sensitive := platformconfig.SensitiveKeys()
	for k, v := range oldPlain {
		if sensitive[k] {
			secret[k] = v // 存量迁移
		}
	}
	plain, inSecret := splitUserEnv(env, secretKeys)
	for k, v := range inSecret {
		if v == "" {
			delete(secret, k) // 空串=显式删除，优先于 sticky/迁移
		} else {
			secret[k] = v
		}
	}
	return plain, secret
}
