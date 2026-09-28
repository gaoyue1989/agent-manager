package service

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"agent-manager/backend/internal/k8s"
	"agent-manager/backend/internal/k8s/k8sfake"
	"agent-manager/backend/internal/store"
)

// publishWithEnv 带 env 发布并推进到稳态（register_failed）。
func publishWithEnv(t *testing.T, core *Core, fk *k8sfake.FakeK8s, pkgID uint,
	env map[string]string, secretKeys []string) *store.ServiceEntity {
	t.Helper()
	svc, err := core.Publish(PublishRequest{PackageID: pkgID, Image: "agent-framework:latest",
		Env: env, SecretKeys: secretKeys})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).Update("cluster_url", "http://127.0.0.1:1")
	_ = fk.SetReady("test", svc.K8sName, 1)
	got := waitForStatus(t, core, svc.ID, store.StatusRegisterFailed)
	return &got
}

// TestPublishRoutesSensitiveEnvToSecret 发布时模板敏感键落服务 Secret，CM 与 env_json 不含。
func TestPublishRoutesSensitiveEnvToSecret(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishWithEnv(t, core, fk, pkg.ID,
		map[string]string{"LLM_API_KEY": "sk-1", "LOG_LEVEL": "info"}, nil)

	if svc.EnvJSON != `{"LOG_LEVEL":"info"}` {
		t.Fatalf("env_json must exclude sensitive key, got %s", svc.EnvJSON)
	}
	var secret map[string]string
	if err := json.Unmarshal([]byte(svc.EnvSecretJSON), &secret); err != nil || secret["LLM_API_KEY"] != "sk-1" {
		t.Fatalf("env_secret_json wrong: %s (%v)", svc.EnvSecretJSON, err)
	}

	cm, err := fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), "oaf-acme-demo-env", metav1.GetOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := cm.Data["LLM_API_KEY"]; ok {
		t.Fatal("CM must not contain sensitive key")
	}
	if cm.Data["LOG_LEVEL"] != "info" {
		t.Fatalf("CM plain env wrong: %+v", cm.Data)
	}
	sobj, err := fk.CS().CoreV1().Secrets("test").Get(t.Context(), k8s.EnvSecretName("oaf-acme-demo"), metav1.GetOptions{})
	if err != nil {
		t.Fatalf("service secret must exist: %v", err)
	}
	if string(sobj.Data["LLM_API_KEY"]) != "sk-1" {
		t.Fatalf("service secret data wrong: %v", sobj.Data)
	}

	// 平台默认对象随 applyAll 兜底创建（当前无配置 → 空对象）
	if _, err := fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), k8s.DefaultConfigCMName, metav1.GetOptions{}); err != nil {
		t.Fatalf("platform default CM must exist: %v", err)
	}
	if _, err := fk.CS().CoreV1().Secrets("test").Get(t.Context(), k8s.DefaultSecretName, metav1.GetOptions{}); err != nil {
		t.Fatalf("platform default secret must exist: %v", err)
	}
}

// TestPublishSecretKeysForceRouting secretKeys 指定的任意键强制进服务 Secret。
func TestPublishSecretKeysForceRouting(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishWithEnv(t, core, fk, pkg.ID,
		map[string]string{"MY_MCP_TOKEN": "tok", "LOG_LEVEL": "info"}, []string{"MY_MCP_TOKEN"})

	if svc.EnvJSON != `{"LOG_LEVEL":"info"}` {
		t.Fatalf("forced key must leave env_json, got %s", svc.EnvJSON)
	}
	var secret map[string]string
	_ = json.Unmarshal([]byte(svc.EnvSecretJSON), &secret)
	if secret["MY_MCP_TOKEN"] != "tok" {
		t.Fatalf("forced key must enter env_secret_json: %s", svc.EnvSecretJSON)
	}
	cm, _ := fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), "oaf-acme-demo-env", metav1.GetOptions{})
	if _, ok := cm.Data["MY_MCP_TOKEN"]; ok {
		t.Fatal("forced key must not enter CM")
	}
}

// TestUpdateEnvStickyAndClear 写路径三态 + 回落平台默认。
func TestUpdateEnvStickyAndClear(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishWithEnv(t, core, testFake(t, core), pkg.ID,
		map[string]string{"LLM_API_KEY": "sk-1", "LOG_LEVEL": "info"}, nil)

	// 只改非敏感键：敏感键 sticky 保持
	got, err := core.UpdateEnv(svc.ID, map[string]string{"LOG_LEVEL": "debug"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got.EnvJSON != `{"LOG_LEVEL":"debug"}` {
		t.Fatalf("plain env wrong: %s", got.EnvJSON)
	}
	var secret map[string]string
	_ = json.Unmarshal([]byte(got.EnvSecretJSON), &secret)
	if secret["LLM_API_KEY"] != "sk-1" {
		t.Fatalf("sticky sensitive key lost: %s", got.EnvSecretJSON)
	}

	waitForStatus(t, core, svc.ID, store.StatusRegisterFailed)
	// 空串 = 删除服务级敏感键（回落平台默认）
	got, err = core.UpdateEnv(svc.ID, map[string]string{"LOG_LEVEL": "debug", "LLM_API_KEY": ""}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got.EnvSecretJSON != "{}" {
		t.Fatalf("explicit clear must empty secret env, got %s", got.EnvSecretJSON)
	}
}

// 存量迁移：旧格式 env_json 直接含敏感键（升级前数据），PATCH 其他键自动迁入 Secret。
func TestUpdateEnvMigratesLegacyEnvJSON(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishToRegisterFailed(t, core, fk, pkg.ID, "")
	// 模拟存量：敏感键写在 env_json（旧格式），env_secret_json 为空
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", svc.ID).
		Updates(map[string]interface{}{
			"env_json":        `{"LOG_LEVEL":"info","CHECKPOINT_PASSWORD":"legacy-pwd"}`,
			"env_secret_json": `{}`,
		})

	got, err := core.UpdateEnv(svc.ID, map[string]string{"LOG_LEVEL": "debug"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got.EnvJSON != `{"LOG_LEVEL":"debug"}` {
		t.Fatalf("legacy sensitive key must leave env_json, got %s", got.EnvJSON)
	}
	var secret map[string]string
	_ = json.Unmarshal([]byte(got.EnvSecretJSON), &secret)
	if secret["CHECKPOINT_PASSWORD"] != "legacy-pwd" {
		t.Fatalf("legacy key must migrate to env_secret_json: %s", got.EnvSecretJSON)
	}
	cm, _ := fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), "oaf-acme-demo-env", metav1.GetOptions{})
	if _, ok := cm.Data["CHECKPOINT_PASSWORD"]; ok {
		t.Fatal("CM must be cleaned of sensitive key after migration")
	}
}

// TestRepublishPreservesServiceSecret republish 换镜像时服务 Secret 不得被 Ensure 覆盖清空。
func TestRepublishPreservesServiceSecret(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishWithEnv(t, core, testFake(t, core), pkg.ID,
		map[string]string{"LLM_API_KEY": "sk-keep", "LOG_LEVEL": "info"}, nil)

	waitForStatus(t, core, svc.ID, store.StatusRegisterFailed)
	replicas := int32(2)
	got, err := core.Republish(svc.ID, RepublishOptions{Replicas: &replicas})
	if err != nil {
		t.Fatal(err)
	}
	var secret map[string]string
	_ = json.Unmarshal([]byte(got.EnvSecretJSON), &secret)
	if secret["LLM_API_KEY"] != "sk-keep" {
		t.Fatalf("republish must preserve service secret: %s", got.EnvSecretJSON)
	}
	if got.EnvJSON != `{"LOG_LEVEL":"info"}` {
		t.Fatalf("republish must preserve plain env: %s", got.EnvJSON)
	}
}

// TestUpdatePlatformConfigFlow 校验 + 拆分渲染 + 掩码。
func TestUpdatePlatformConfigFlow(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()

	// 未知键拒绝
	if _, err := core.UpdatePlatformConfig(map[string]string{"NOT_IN_TEMPLATE": "x"}); !errors.Is(err, ErrUnknownConfigKey) {
		t.Fatalf("unknown key: %v", err)
	}

	view, err := core.UpdatePlatformConfig(map[string]string{
		"LLM_API_KEY":     "sk-platform",
		"LLM_BASE_URL":    "http://llm",
		"LLM_TEMPERATURE": "0.5",
	})
	if err != nil {
		t.Fatal(err)
	}
	// 清除必填键拒绝（必填仅防误清：键不存在时清除是无害 no-op）
	if _, err := core.UpdatePlatformConfig(map[string]string{"LLM_API_KEY": ""}); !errors.Is(err, ErrRequiredConfigKey) {
		t.Fatalf("clear required: %v", err)
	}
	// 视图：敏感键只回 hasValue，非敏感键回填真实值
	llm := view.Groups[0]
	if llm.Name != "llm" {
		t.Fatalf("first group: %+v", llm)
	}
	for _, f := range llm.Fields {
		if f.EnvKey == "LLM_API_KEY" && (!f.HasValue || f.Value != "") {
			t.Fatalf("sensitive field must mask: %+v", f)
		}
		if f.EnvKey == "LLM_BASE_URL" && f.Value != "http://llm" {
			t.Fatalf("plain field should回填: %+v", f)
		}
	}
	// 拆分渲染：非敏感进 CM，敏感进 Secret
	cm, err := fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), k8s.DefaultConfigCMName, metav1.GetOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if cm.Data["LLM_BASE_URL"] != "http://llm" || cm.Data["LLM_TEMPERATURE"] != "0.5" {
		t.Fatalf("platform CM wrong: %+v", cm.Data)
	}
	if _, ok := cm.Data["LLM_API_KEY"]; ok {
		t.Fatal("platform CM must not contain sensitive key")
	}
	sec, err := fk.CS().CoreV1().Secrets("test").Get(t.Context(), k8s.DefaultSecretName, metav1.GetOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if string(sec.Data["LLM_API_KEY"]) != "sk-platform" || len(sec.Data) != 1 {
		t.Fatalf("platform secret wrong: %v", sec.Data)
	}
	// 非必填键可清除
	if _, err := core.UpdatePlatformConfig(map[string]string{"LLM_TEMPERATURE": ""}); err != nil {
		t.Fatalf("clear optional: %v", err)
	}
	cm, _ = fk.CS().CoreV1().ConfigMaps("test").Get(t.Context(), k8s.DefaultConfigCMName, metav1.GetOptions{})
	if _, ok := cm.Data["LLM_TEMPERATURE"]; ok {
		t.Fatal("cleared key must leave platform CM")
	}
	// 审计只记键名不记值
	var events []store.PlatformConfigEvent
	core.DB.Order("id DESC").Limit(1).Find(&events)
	if len(events) == 0 || events[0].Action != "update" {
		t.Fatal("audit event missing")
	}
	if strings.Contains(events[0].EnvKeys, "sk-platform") {
		t.Fatal("audit must not contain values")
	}
}

// TestApplyRestartFlow 只重启可重启状态的服务；spec 重刷带四源 envFrom。
func TestApplyRestartFlow(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	if !strings.Contains(pkg.Slug, "acme/demo") {
		t.Fatalf("fixture slug changed: %s", pkg.Slug)
	}
	pkg2 := uploadTestPkg(t, core, "2")
	running := publishWithEnv(t, core, fk, pkg.ID, map[string]string{"LLM_API_KEY": "sk-r"}, nil)
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", running.ID).Update("status", store.StatusRunning)

	stopped := publishWithEnv(t, core, fk, pkg2.ID, nil, nil)
	core.DB.Model(&store.ServiceEntity{}).Where("id = ?", stopped.ID).Update("status", store.StatusStopped)

	res, err := core.ApplyRestart(ApplyRestartRequest{})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Restarted) != 1 || res.Restarted[0].ID != running.ID {
		t.Fatalf("restarted wrong: %+v", res.Restarted)
	}
	// stopped 不在候选集合（查询即过滤 running/register_failed），不算 skipped
	if len(res.Skipped) != 0 {
		t.Fatalf("skipped wrong: %+v", res.Skipped)
	}
	if len(fk.Restarts()) != 1 {
		t.Fatalf("restart calls: %v", fk.Restarts())
	}
	// spec 重刷：envFrom 四源（存量单源服务由此升级）
	dep, _ := fk.GetDeployment("test", running.K8sName)
	if len(dep.Spec.Template.Spec.Containers[0].EnvFrom) != 4 {
		t.Fatal("apply-restart must refresh envFrom to 4 sources")
	}
	// 点名过滤 + not found
	res, err = core.ApplyRestart(ApplyRestartRequest{ServiceIDs: []uint{stopped.ID, 999}})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Restarted) != 0 || len(res.Skipped) != 2 {
		t.Fatalf("filtered restart wrong: %+v", res)
	}
}

// TestDeleteRemovesServiceSecret 删除服务连带清理服务 Secret（平台对象保留）。
func TestDeleteRemovesServiceSecret(t *testing.T) {
	core, fk, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishWithEnv(t, core, fk, pkg.ID, map[string]string{"LLM_API_KEY": "sk-1"}, nil)

	if _, err := fk.CS().CoreV1().Secrets("test").Get(t.Context(), k8s.EnvSecretName(svc.K8sName), metav1.GetOptions{}); err != nil {
		t.Fatal("service secret should exist before delete")
	}
	if err := core.Delete(svc.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := fk.CS().CoreV1().Secrets("test").Get(t.Context(), k8s.EnvSecretName(svc.K8sName), metav1.GetOptions{}); err == nil {
		t.Fatal("service secret must be deleted")
	}
	if _, err := fk.CS().CoreV1().Secrets("test").Get(t.Context(), k8s.DefaultSecretName, metav1.GetOptions{}); err != nil {
		t.Fatal("platform secret must survive service delete")
	}
}

// TestEnvSecretKeysMasking 掩码视图：值不随 JSON 直出，键集含存量未迁移键。
func TestEnvSecretKeysMasking(t *testing.T) {
	core, _, done := newTestCore(t)
	defer done()
	pkg := uploadTestPkg(t, core, "")
	svc := publishWithEnv(t, core, testFake(t, core), pkg.ID,
		map[string]string{"LLM_API_KEY": "sk-secret-value", "LOG_LEVEL": "info"}, nil)

	detail, err := core.GetDetail(svc.ID)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, k := range detail.EnvSecretKeys {
		if k.Key == "LLM_API_KEY" {
			found = true
			if !k.HasValue {
				t.Fatal("hasValue should be true")
			}
		}
	}
	if !found {
		t.Fatalf("envSecretKeys missing LLM_API_KEY: %+v", detail.EnvSecretKeys)
	}
	// 实体 JSON 直出不泄漏敏感值
	b, _ := json.Marshal(detail)
	if strings.Contains(string(b), "sk-secret-value") {
		t.Fatal("sensitive value leaked in detail JSON")
	}
}

// testFake 从 Core 取回测试注入的 FakeK8s（publishWithEnv 需要 SetReady 而其他
// 用例从 newTestCore 直接持有 fk；此辅助用于先取 core 后取 fk 的书写顺序）。
func testFake(t *testing.T, c *Core) *k8sfake.FakeK8s {
	t.Helper()
	return c.K8s.(*k8sfake.FakeK8s)
}
