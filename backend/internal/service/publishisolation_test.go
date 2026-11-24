// 会话隔离派生（issue #142）单测：JDBC URL 改写 / 库名派生与截断 / Redis 前缀 /
// Publish 链路挂载（默认值派生、用户显式值优先、无平台默认不派生）。
package service

import (
	"strings"
	"testing"

	"agent-manager/backend/internal/store"
)

func TestDeriveCheckpointURL(t *testing.T) {
	cases := []struct {
		name, in, k8s, want string
		ok                  bool
	}{
		{"标准 URL", "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint", "oaf-demo",
			"jdbc:mysql://oaf-mysql:3306/oaf_checkpoint_oaf_demo?createDatabaseIfNotExist=true", true},
		{"带查询参数", "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint?useSSL=false", "order-agent",
			"jdbc:mysql://oaf-mysql:3306/oaf_checkpoint_order_agent?useSSL=false&createDatabaseIfNotExist=true", true},
		{"已含建库参数不重复追加", "jdbc:mysql://h:3306/db?createDatabaseIfNotExist=true", "x",
			"jdbc:mysql://h:3306/db_x?createDatabaseIfNotExist=true", true},
		{"带账号凭据", "jdbc:mysql://oaf:pw@oaf-mysql:3306/oaf_checkpoint", "x",
			"jdbc:mysql://oaf:pw@oaf-mysql:3306/oaf_checkpoint_x?createDatabaseIfNotExist=true", true},
		{"无库段", "jdbc:mysql://oaf-mysql:3306", "x",
			"jdbc:mysql://oaf-mysql:3306/oaf_checkpoint_x?createDatabaseIfNotExist=true", true},
		{"非 jdbc URL 不派生", "http://example.com/db", "x", "", false},
		{"无 authority 不派生", "jdbc:mysql:embedded", "x", "", false},
	}
	for _, tc := range cases {
		got, ok := deriveCheckpointURL(tc.in, tc.k8s)
		if ok != tc.ok || (ok && got != tc.want) {
			t.Errorf("%s: got (%q,%v) want (%q,%v)", tc.name, got, ok, tc.want, tc.ok)
		}
	}
}

func TestCheckpointDBNameTruncation(t *testing.T) {
	// 常规：连字符转下划线
	if got := checkpointDBNameFor("jdbc:mysql://h/db", "order-agent"); got != "db_order_agent" {
		t.Fatalf("db name: %q", got)
	}
	// 超长截断到 64
	long := strings.Repeat("a", 70)
	got := checkpointDBNameFor("jdbc:mysql://h/oaf_checkpoint", long)
	if len(got) != 64 || !strings.HasPrefix(got, "oaf_checkpoint_") {
		t.Fatalf("truncate: len=%d %q", len(got), got)
	}
}

func TestApplyIsolationEnv(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	// 种平台默认配置
	if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: "CHECKPOINT_JDBC_URL",
		Value: "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint"}).Error; err != nil {
		t.Fatal(err)
	}

	// 1) env 缺失 → 派生 checkpoint 库 + redis 前缀（redis 前缀要求平台已配置共享 Redis）
	if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: "AGENT_REDIS_URL",
		Value: "redis://oaf-redis:6379"}).Error; err != nil {
		t.Fatal(err)
	}
	plain := map[string]string{"LLM_MODEL": "m"}
	secret := map[string]string{}
	c.applyIsolationEnv("oaf-demo", plain, secret)
	if plain["CHECKPOINT_JDBC_URL"] != "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint_oaf_demo?createDatabaseIfNotExist=true" {
		t.Fatalf("derived checkpoint: %q", plain["CHECKPOINT_JDBC_URL"])
	}
	if plain["AGENT_REDIS_PREFIX"] != "oaf-demo:" {
		t.Fatalf("derived redis prefix: %q", plain["AGENT_REDIS_PREFIX"])
	}

	// 2) env = 平台默认值（表单预填场景）→ 同样派生
	plain = map[string]string{"CHECKPOINT_JDBC_URL": "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint"}
	c.applyIsolationEnv("oaf-demo2", plain, map[string]string{})
	if plain["CHECKPOINT_JDBC_URL"] != "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint_oaf_demo2?createDatabaseIfNotExist=true" {
		t.Fatalf("prefill-derived checkpoint: %q", plain["CHECKPOINT_JDBC_URL"])
	}

	// 3) 用户显式自定义（≠平台默认）→ 保持不动，redis 前缀显式值同样优先
	custom := "jdbc:mysql://my-mysql:3306/mydb"
	plain = map[string]string{"CHECKPOINT_JDBC_URL": custom, "AGENT_REDIS_PREFIX": "team1:"}
	c.applyIsolationEnv("oaf-demo3", plain, map[string]string{})
	if plain["CHECKPOINT_JDBC_URL"] != custom || plain["AGENT_REDIS_PREFIX"] != "team1:" {
		t.Fatalf("explicit values must win: %v", plain)
	}

	// 4) 无平台默认配置 → 完全不派生（checkpoint 与 redis 前缀都不动）
	if err := c.DB.Where("env_key IN ?", []string{"CHECKPOINT_JDBC_URL", "AGENT_REDIS_URL"}).
		Delete(&store.PlatformConfigEntity{}).Error; err != nil {
		t.Fatal(err)
	}
	plain = map[string]string{}
	c.applyIsolationEnv("oaf-demo4", plain, map[string]string{})
	if len(plain) != 0 {
		t.Fatalf("no defaults → no derivation at all: %v", plain)
	}
}

// TestPublishDerivesIsolationEnv 发布链路端到端：CreateAgent（走 Publish）落库的
// env_json 携带 per-service checkpoint 库与 redis 前缀。
func TestPublishDerivesIsolationEnv(t *testing.T) {
	c, _, done := newTestCore(t)
	defer done()
	_ = seedWs(t, c, "ws-77770007", "隔离空间")
	if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: "CHECKPOINT_JDBC_URL",
		Value: "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint"}).Error; err != nil {
		t.Fatal(err)
	}
	if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: "AGENT_REDIS_URL",
		Value: "redis://oaf-redis:6379"}).Error; err != nil {
		t.Fatal(err)
	}
	pkg := uploadTestPkg(t, c, "")
	svc, err := c.Publish(PublishRequest{PackageID: pkg.ID, Image: "agent-framework:latest",
		Env: map[string]string{"CHECKPOINT_JDBC_URL": "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint"}})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	env := map[string]string{}
	if err := jsonUnmarshalStrict(svc.EnvJSON, &env); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(env["CHECKPOINT_JDBC_URL"], "/oaf_checkpoint_"+strings.ReplaceAll(svc.K8sName, "-", "_")+"?") {
		t.Fatalf("checkpoint not derived per service: %q", env["CHECKPOINT_JDBC_URL"])
	}
	if env["AGENT_REDIS_PREFIX"] != svc.K8sName+":" {
		t.Fatalf("redis prefix not derived: %q", env["AGENT_REDIS_PREFIX"])
	}
}

// TestRepublishReDerivesIsolationEnv（issue #385）Republish 是 env 重建出口之一，
// 派生键可能在上游写路径被平台默认预填覆盖回共享值（UpdateAgent 的 buildPublishEnv
// 会把平台默认 CHECKPOINT_JDBC_URL 预填进 env 再全量合并）——而 Pod 滚动重启后即回连
// 共享库，会话列表/调试面板串入其它服务会话。与 Publish 同语义：出口重跑派生。
//   1) 派生键被覆盖回平台默认 → 重新派生 per-service 库（编辑保存场景，本 issue 主案例）
//   2) 派生键原样保留（纯 republish / 升级版本，无 Env 覆盖）→ 保持不动
//   3) 用户显式自定义（≠平台默认）→ 保持不动
func TestRepublishReDerivesIsolationEnv(t *testing.T) {
	for _, tc := range []struct {
		name    string
		withEnv func(derived string) map[string]string // Republish 携带的 Env（nil = 不带）
		wantCK  string                                  // 期望落库的 checkpoint URL 前缀段
	}{
		{"覆盖回平台默认 → 重新派生", func(derived string) map[string]string {
			return map[string]string{"CHECKPOINT_JDBC_URL": "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint", "LLM_MODEL": "m"}
		}, "/oaf_checkpoint_oaf_"},
		{"派生键保留 → 原样", func(derived string) map[string]string { return nil }, "/oaf_checkpoint_oaf_"},
		{"用户显式自定义 → 不动", func(derived string) map[string]string {
			return map[string]string{"CHECKPOINT_JDBC_URL": "jdbc:mysql://my-mysql:3306/mydb"}
		}, "/mydb"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c, fk, done := newTestCore(t)
			defer done()
			_ = seedWs(t, c, "ws-77770011", "隔离重发布")
			if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: "CHECKPOINT_JDBC_URL",
				Value: "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint"}).Error; err != nil {
				t.Fatal(err)
			}
			if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: "AGENT_REDIS_URL",
				Value: "redis://oaf-redis:6379"}).Error; err != nil {
				t.Fatal(err)
			}
			pkg := uploadTestPkg(t, c, "")
			svc := publishToRegisterFailed(t, c, fk, pkg.ID, "")
			if !strings.Contains(envJSONField(t, svc.EnvJSON, "CHECKPOINT_JDBC_URL"), "/oaf_checkpoint_"+strings.ReplaceAll(svc.K8sName, "-", "_")+"?") {
				t.Fatalf("publish 未派生: %s", svc.EnvJSON)
			}
			opt := RepublishOptions{}
			if tc.withEnv != nil {
				opt.Env = tc.withEnv(envJSONField(t, svc.EnvJSON, "CHECKPOINT_JDBC_URL"))
			}
			updated, err := c.Republish(svc.ID, opt)
			if err != nil {
				t.Fatalf("republish: %v", err)
			}
			got := envJSONField(t, updated.EnvJSON, "CHECKPOINT_JDBC_URL")
			switch tc.name {
			case "覆盖回平台默认 → 重新派生", "派生键保留 → 原样":
				if !strings.Contains(got, "/oaf_checkpoint_"+strings.ReplaceAll(svc.K8sName, "-", "_")+"?") {
					t.Fatalf("republish 后 checkpoint 应为 per-service 派生库, got %q", got)
				}
			case "用户显式自定义 → 不动":
				if got != "jdbc:mysql://my-mysql:3306/mydb" {
					t.Fatalf("用户显式值被改动: %q", got)
				}
			}
			if p := envJSONField(t, updated.EnvJSON, "AGENT_REDIS_PREFIX"); p != svc.K8sName+":" {
				t.Fatalf("redis prefix: %q", p)
			}
		})
	}
}

// TestUpdateEnvReDerivesIsolationEnv（issue #385）UpdateEnv 全量覆盖语义下未回传
// 派生键时（运维侧编辑遗漏），出口派生兜底：Pod 不得因 env 编辑回退共享库。
func TestUpdateEnvReDerivesIsolationEnv(t *testing.T) {
	c, fk, done := newTestCore(t)
	defer done()
	_ = seedWs(t, c, "ws-77770012", "隔离UpdateEnv")
	if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: "CHECKPOINT_JDBC_URL",
		Value: "jdbc:mysql://oaf-mysql:3306/oaf_checkpoint"}).Error; err != nil {
		t.Fatal(err)
	}
	if err := c.DB.Create(&store.PlatformConfigEntity{EnvKey: "AGENT_REDIS_URL",
		Value: "redis://oaf-redis:6379"}).Error; err != nil {
		t.Fatal(err)
	}
	pkg := uploadTestPkg(t, c, "")
	svc := publishToRegisterFailed(t, c, fk, pkg.ID, "")
	// 全量覆盖且不回传派生键
	updated, err := c.UpdateEnv(svc.ID, map[string]string{"LLM_MODEL": "m2"}, nil)
	if err != nil {
		t.Fatalf("updateEnv: %v", err)
	}
	got := envJSONField(t, updated.EnvJSON, "CHECKPOINT_JDBC_URL")
	if !strings.Contains(got, "/oaf_checkpoint_"+strings.ReplaceAll(svc.K8sName, "-", "_")+"?") {
		t.Fatalf("updateEnv 后 checkpoint 应回退共享库: %q", got)
	}
	if envJSONField(t, updated.EnvJSON, "LLM_MODEL") != "m2" {
		t.Fatalf("普通键覆盖语义被破坏: %s", updated.EnvJSON)
	}
}

// envJSONField 从服务 env_json 提取单键（测试辅助）。
func envJSONField(t *testing.T, raw, key string) string {
	t.Helper()
	env := map[string]string{}
	if err := jsonUnmarshalStrict(raw, &env); err != nil {
		t.Fatalf("env json: %v", err)
	}
	return env[key]
}
