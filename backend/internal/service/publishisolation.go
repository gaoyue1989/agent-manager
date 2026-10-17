// 发布链路会话隔离派生（issue #142）：多服务共享同一 MySQL/Redis 时，checkpoint 库
// 与 Redis key 无 agent 维度，缺省即跨服务串列（会话列表相同、sbx:guard 互锁）。
// 仅在 Publish（新建服务）时派生——env 未显式配置才生效，用户显式值 / 存量服务
// （env 已固化）零影响；存量服务收敛由运维显式改 env 或重新发布承担。
// 前提：MySQL 账号对 `{原库}_%` 模式库有建库权限（manifests/platform.yaml init.sql
// 的 GRANT ... `oaf_checkpoint\_%`.*；存量集群需手工执行一次）。
package service

import "strings"

// isolation 派生所用平台默认键。
const (
	isolationCheckpointURLKey = "CHECKPOINT_JDBC_URL"
	isolationRedisURLKey      = "AGENT_REDIS_URL"
	isolationRedisPrefixKey   = "AGENT_REDIS_PREFIX"
)

// applyIsolationEnv 就地派生隔离 env（Publish 专用，plain/secret 已 split+dropEmpty）：
//   - CHECKPOINT_JDBC_URL：平台默认已配置且服务 env 缺失或等于默认值时，改写为
//     per-service 库名（`{原库}_{k8s名}`，createDatabaseIfNotExist=true 自动建库）；
//     用户显式自定义（≠平台默认）保持不动。
//   - AGENT_REDIS_PREFIX：服务 env 缺失时置 `{k8s名}:`（运行时按前缀切分 Redis key）。
func (c *Core) applyIsolationEnv(k8sName string, plain, secret map[string]string) {
	defaults, err := c.GetPlatformDefaults()
	if err != nil {
		return // 平台配置读失败：跳过派生（不阻断发布）
	}
	if defURL := defaults[isolationCheckpointURLKey]; defURL != "" {
		cur := plain[isolationCheckpointURLKey]
		if cur == "" {
			cur = secret[isolationCheckpointURLKey]
		}
		if cur == "" || cur == defURL {
			if url, ok := deriveCheckpointURL(defURL, k8sName); ok {
				delete(secret, isolationCheckpointURLKey)
				plain[isolationCheckpointURLKey] = url
			}
		}
	}
	// Redis 前缀仅在平台配置了共享 Redis 时派生：未配置 Redis 的部署保持 env 完全
	// 缺省（前缀无意义，也避免给既有发布断言/存量行为引入意外键）
	if defaults[isolationRedisURLKey] != "" && plain[isolationRedisPrefixKey] == "" && secret[isolationRedisPrefixKey] == "" {
		plain[isolationRedisPrefixKey] = redisPrefixFor(k8sName)
	}
}

// deriveCheckpointURL 把 JDBC URL 指向 per-service 库：`jdbc:mysql://host[:port]/db?query`
// → `jdbc:mysql://host[:port]/db_{k8s名}?query[&]createDatabaseIfNotExist=true`。
// 不识别的 URL（非 jdbc: 前缀 / 无 ://）原样返回 ok=false（不派生，保持显式值语义）。
func deriveCheckpointURL(jdbcURL, k8sName string) (string, bool) {
	sep := strings.Index(jdbcURL, "://")
	if !strings.HasPrefix(jdbcURL, "jdbc:") || sep < 0 {
		return "", false
	}
	prefix := jdbcURL[:sep+3] // jdbc:mysql://
	rest := jdbcURL[sep+3:]
	query := ""
	if q := strings.Index(rest, "?"); q >= 0 {
		rest, query = rest[:q], rest[q:]
	}
	// authority = [user[:pass]@]host[:port]，首个 / 之前；无 / 表示 URL 未指库
	i := strings.Index(rest, "/")
	out := prefix + rest
	if i >= 0 {
		out = prefix + rest[:i]
	}
	out += "/" + checkpointDBNameFor(jdbcURL, k8sName)
	if query == "" {
		out += "?createDatabaseIfNotExist=true"
	} else if !strings.Contains(strings.ToLower(query), "createdatabaseifnotexist") {
		out += query + "&createDatabaseIfNotExist=true"
	} else {
		out += query
	}
	return out, true
}

// checkpointDBNameFor per-service 库名：`{原库}_{k8s名去连字符}`，总长 ≤64（MySQL 库名
// 上限；超长截断 k8s 段）。原 URL 未指库时以 oaf_checkpoint 为基名。
func checkpointDBNameFor(jdbcURL, k8sName string) string {
	base := "oaf_checkpoint"
	sep := strings.Index(jdbcURL, "://")
	if sep >= 0 {
		rest := jdbcURL[sep+3:]
		if q := strings.Index(rest, "?"); q >= 0 {
			rest = rest[:q]
		}
		if i := strings.Index(rest, "/"); i >= 0 && i < len(rest)-1 {
			base = rest[i+1:]
		}
	}
	suffix := strings.ReplaceAll(k8sName, "-", "_")
	maxSuffix := 64 - len(base) - 1
	if maxSuffix < 1 {
		return base // 理论不可达：基名本身 ≥63 字符时无法追加，退回共享库
	}
	if len(suffix) > maxSuffix {
		suffix = suffix[:maxSuffix]
	}
	return base + "_" + suffix
}

// redisPrefixFor Redis key 统一前缀（agent.redis.prefix）：`{k8s名}:`。
// k8s 名字符集 [a-z0-9-] 天然满足运行时校验（非空白、不含 hash tag 花括号）。
func redisPrefixFor(k8sName string) string {
	return k8sName + ":"
}
