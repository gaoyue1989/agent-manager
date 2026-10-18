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
