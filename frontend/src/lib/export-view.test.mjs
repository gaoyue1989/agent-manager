import assert from "node:assert/strict";
import { test } from "node:test";
import {
  isLockedRow, isReadonlyPkgPath, classifyEnvLayer, validateEnvKey, validateEnvValue,
  validateEnvRowCount, markerIssue, parseImageWhitelist, routingSuffixIssue,
  buildRuntimeDefaultPayload, groupFieldsByCategory, RUNTIME_DEFAULT_FIELDS, WS_CONFIG_TABS, MAX_ENV_KEYS,
  RESERVED_ENV_KEYS, EXPORT_META_ENV_KEYS,
} from "./export-view.ts";

test("isLockedRow：命中运行时默认键锁定，空列表不锁", () => {
  assert.equal(isLockedRow("LLM_BASE_URL", ["LLM_BASE_URL", "AGENT_REDIS_URL"]), true);
  assert.equal(isLockedRow("MCP_E2E_TOKEN", ["LLM_BASE_URL"]), false);
  assert.equal(isLockedRow("ANY_KEY", []), false);
});

test("isReadonlyPkgPath：AGENTS.md 与 skills/** 只读，其余可编辑", () => {
  for (const p of ["AGENTS.md", "skills/weather/SKILL.md", "skills/x.py"]) assert.equal(isReadonlyPkgPath(p), true, p);
  for (const p of ["config.yaml", "agents.md", "myskills/a.md", "README.md"]) assert.equal(isReadonlyPkgPath(p), false, p);
});

test("classifyEnvLayer：L3 派生/保留键 > L1 锁定 > L2 用户覆盖", () => {
  // L1：命中运行时默认
  assert.equal(classifyEnvLayer("LLM_BASE_URL", ["LLM_BASE_URL"]), "L1");
  // L2：未锁定的普通键
  assert.equal(classifyEnvLayer("MCP_E2E_TOKEN", ["LLM_BASE_URL"]), "L2");
  // L3：平台保留键与派生键，即使误入 lockedKeys 也按 L3 裁决（L1 键空间本就排除它们）
  assert.equal(classifyEnvLayer("AGENT_CONFIG_DIR", ["AGENT_CONFIG_DIR"]), "L3");
  assert.equal(classifyEnvLayer("CHECKPOINT_JDBC_URL", ["CHECKPOINT_JDBC_URL"]), "L3");
  assert.equal(classifyEnvLayer("AGENT_REDIS_PREFIX", []), "L3");
});

test("validateEnvKey：格式/保留键/锁定键/重复四类拦截", () => {
  const locked = ["LLM_BASE_URL"];
  assert.equal(validateEnvKey("MCP_E2E_TOKEN", locked, ["MCP_E2E_TOKEN"]), "");
  assert.match(validateEnvKey("1BAD", locked), /格式非法/);
  assert.match(validateEnvKey("A-B", locked), /格式非法/);
  assert.match(validateEnvKey("SERVER_PORT", locked), /保留/);
  assert.match(validateEnvKey("CHECKPOINT_JDBC_URL", locked), /保留/);
  assert.match(validateEnvKey("LLM_BASE_URL", locked), /锁定/);
  assert.match(validateEnvKey("K", locked, ["K", "K"]), /重复/);
  assert.match(validateEnvKey("", locked), /不能为空/);
});

test("validateEnvKey：导出参数键（L1 专属）拒绝——L1 未配置也不可经导出页 env 表设置", () => {
  // 与 backend resolveExportLayers 的 exportMetaKeys 400 拦截同口径（评审修复）；
  // 即便未出现在 lockedKeys（L1 未配置）也要拦，防绕过运维锁定
  for (const k of EXPORT_META_ENV_KEYS) {
    assert.match(validateEnvKey(k, []), /导出参数键/, k);
  }
  // 导出参数键与保留键/模板键键空间互斥（每键恰一处裁决）
  for (const k of EXPORT_META_ENV_KEYS) {
    assert.equal(RESERVED_ENV_KEYS.includes(k), false, k);
  }
});

test("validateEnvValue / validateEnvRowCount：32KB 单值上限与 64 键上限", () => {
  assert.equal(validateEnvValue("K", "x".repeat(32 * 1024)), "");
  assert.match(validateEnvValue("K", "x".repeat(32 * 1024 + 1)), /32KB/);
  assert.equal(validateEnvRowCount(MAX_ENV_KEYS), "");
  assert.match(validateEnvRowCount(MAX_ENV_KEYS + 1), /超过上限/);
});

test("markerIssue：{包版本}-e{序号} 格式校验", () => {
  assert.equal(markerIssue("1.2.0-e1"), "");
  assert.equal(markerIssue("1.2.0-e12"), "");
  assert.match(markerIssue(""), /发布标记/);
  assert.match(markerIssue("1.2.0"), /格式/);
  assert.match(markerIssue("e1"), /格式/);
  assert.match(markerIssue("1.2.0-e"), /格式/);
});

test("parseImageWhitelist：ref|Label 逗号分隔，空段跳过（config.go parseImages 同口径）", () => {
  const out = parseImageWhitelist(" 172.20.0.1:5001/agent-framework:v1 | Agent Framework 2.1.0 ,, registry.local/af:latest");
  assert.deepEqual(out, [
    { image: "172.20.0.1:5001/agent-framework:v1", label: "Agent Framework 2.1.0" },
    { image: "registry.local/af:latest", label: "" },
  ]);
  assert.deepEqual(parseImageWhitelist(""), []);
  assert.deepEqual(parseImageWhitelist(" , ,"), []);
});

test("routingSuffixIssue：host 后缀以 . 开头且段合法（config.go:150-172 简化口径）", () => {
  assert.equal(routingSuffixIssue(""), "");
  assert.equal(routingSuffixIssue(".prod.example.com"), "");
  assert.match(routingSuffixIssue("prod.example.com"), /以 \. 开头/);
  assert.match(routingSuffixIssue("."), /不能只有/);
  assert.match(routingSuffixIssue(".prod-.example"), /段非法/);
  assert.match(routingSuffixIssue(".Prod"), /段非法/);
  assert.match(routingSuffixIssue(`.${"a".repeat(64)}`), /段非法/); // 单段超 63
});

test("buildRuntimeDefaultPayload：三态语义（非空=设置、空串=删除、缺失=不变；敏感键仅非空上送）", () => {
  const fields = RUNTIME_DEFAULT_FIELDS.filter((f) =>
    ["CHECKPOINT_JDBC_BASE", "REGISTRY_ADDRESS", "LLM_API_KEY", "CHECKPOINT_PASSWORD"].includes(f.envKey));
  const existing = {
    CHECKPOINT_JDBC_BASE: { value: "jdbc:mysql://old/oaf_checkpoint", sensitive: false, category: "mysql" },
    REGISTRY_ADDRESS: { value: "", sensitive: false, category: "registry" }, // 原本就为空 → 清空不算 clearing
    LLM_API_KEY: { value: "sk-****", sensitive: true, category: "llm" },
  };
  const { values, clearing } = buildRuntimeDefaultPayload(
    fields,
    { CHECKPOINT_JDBC_BASE: "", REGISTRY_ADDRESS: "172.20.0.1:5001" }, // 基库清空、仓库设置
    { CHECKPOINT_PASSWORD: "pw-1" },                                   // 敏感键新输入；LLM_API_KEY 留空 = 不变
    existing,
  );
  assert.deepEqual(values, {
    CHECKPOINT_JDBC_BASE: "",      // 空串 = 删除
    REGISTRY_ADDRESS: "172.20.0.1:5001",
    CHECKPOINT_PASSWORD: "pw-1",
    // LLM_API_KEY 缺失 = 不变
  });
  assert.deepEqual(clearing, ["CHECKPOINT_JDBC_BASE"]); // 仅「有值 → 清空」进确认清单
});

test("groupFieldsByCategory：按 schema 顺序分组、标题映射齐全", () => {
  const groups = groupFieldsByCategory(RUNTIME_DEFAULT_FIELDS);
  assert.deepEqual(groups.map((g) => g.category), ["mysql", "redis", "otel", "llm", "sandbox", "protocol", "registry", "routing", "runtime"]);
  for (const g of groups) assert.ok(g.title && g.title !== g.category, `${g.category} 缺标题`);
});

test("RUNTIME_DEFAULT_FIELDS：24 模板键 + 8 导出键 = 32 键，键无重复，敏感键 7 个", () => {
  assert.equal(RUNTIME_DEFAULT_FIELDS.length, 32);
  const keys = RUNTIME_DEFAULT_FIELDS.map((f) => f.envKey);
  assert.equal(new Set(keys).size, 32);
  // L3 派生键/平台保留键结构性排除（契约 §1.4/D13）
  for (const banned of ["CHECKPOINT_JDBC_URL", "AGENT_REDIS_PREFIX", "AGENT_CONFIG_DIR", "SERVER_PORT"]) {
    assert.ok(!keys.includes(banned), banned);
  }
  assert.equal(RUNTIME_DEFAULT_FIELDS.filter((f) => f.sensitive).length, 7);
  assert.equal(RUNTIME_DEFAULT_FIELDS.filter((f) => f.exportOnly).length, 8);
  // 每键恰属一个配置页页签
  const tabCats = WS_CONFIG_TABS.flatMap((t) => t.categories);
  for (const f of RUNTIME_DEFAULT_FIELDS) assert.equal(tabCats.filter((c) => c === f.category).length, 1, f.envKey);
});
