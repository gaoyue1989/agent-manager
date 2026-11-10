import assert from "node:assert/strict";
import { test } from "node:test";
import {
  isLockedRow, isReadonlyPkgPath, classifyEnvLayer, validateEnvKey, validateEnvValue,
  validateEnvRowCount, markerIssue, parseImageWhitelist, routingSuffixIssue,
  buildRuntimeDefaultPayload, groupFieldsByCategory, RUNTIME_DEFAULT_FIELDS, WS_CONFIG_TABS, MAX_ENV_KEYS,
  RESERVED_ENV_KEYS, EXPORT_META_ENV_KEYS, prefillEnvRows, validateEnvRows, buildPackageTree, ADVANCE_NEXT,
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

test("buildRuntimeDefaultPayload：非敏感键 trim 上送（issue #262，与同页后缀预检口径对齐）", () => {
  const fields = RUNTIME_DEFAULT_FIELDS.filter((f) =>
    ["CHECKPOINT_JDBC_BASE", "REGISTRY_ADDRESS", "LLM_API_KEY"].includes(f.envKey));
  const existing = {
    CHECKPOINT_JDBC_BASE: { value: "jdbc:mysql://old/oaf_checkpoint", sensitive: false, category: "mysql" },
    REGISTRY_ADDRESS: { hasValue: true, sensitive: false, category: "registry" },
  };
  const { values, clearing } = buildRuntimeDefaultPayload(
    fields,
    {
      // textarea 尾随换行 / 粘贴首尾空白：trim 后原样设置，不以污染值落库
      CHECKPOINT_JDBC_BASE: "  jdbc:mysql://mysql.prod.svc:3306/oaf_checkpoint\n",
      REGISTRY_ADDRESS: "\t 172.20.0.1:5001 ",
    },
    {},
    existing,
  );
  assert.deepEqual(values, {
    CHECKPOINT_JDBC_BASE: "jdbc:mysql://mysql.prod.svc:3306/oaf_checkpoint",
    REGISTRY_ADDRESS: "172.20.0.1:5001",
  });
  // 纯空白 = 空串（三态删除语义）：已配置键进 clearing 确认清单
  const blank = buildRuntimeDefaultPayload(
    RUNTIME_DEFAULT_FIELDS.filter((f) => f.envKey === "REGISTRY_ADDRESS"),
    { REGISTRY_ADDRESS: "   " }, {}, existing);
  assert.equal(blank.values.REGISTRY_ADDRESS, "");
  assert.deepEqual(blank.clearing, ["REGISTRY_ADDRESS"]);
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

test("prefillEnvRows：过滤 L3 保留/派生键，值留空占位，敏感标注透传", () => {
  const rows = prefillEnvRows({
    MCP_E2E_TOKEN: { hasValue: true, sensitive: false },
    LLM_API_KEY: { hasValue: true, sensitive: true },
    CHECKPOINT_JDBC_URL: { hasValue: true },                 // L3 派生键 → 不预填（预填后必被 validateEnvKey 拦截，阻塞首次导出）
    AGENT_REDIS_PREFIX: { hasValue: true },                  // L3 派生键
    SERVER_PORT: { hasValue: true },                         // 平台保留键
    AGENT_CONFIG_DIR: { hasValue: true },                    // 平台保留键
  });
  assert.deepEqual(rows, [
    { key: "MCP_E2E_TOKEN", value: "", secret: false },
    { key: "LLM_API_KEY", value: "", secret: true },
  ]);
  // 全部保留键也 → 空列表；null/undefined 容错
  assert.deepEqual(prefillEnvRows({ CHECKPOINT_JDBC_URL: { hasValue: true } }), []);
  assert.deepEqual(prefillEnvRows(null), []);
  assert.deepEqual(prefillEnvRows(undefined), []);
  // 预填行在空值下必须能通过 validateEnvRows（否则阻塞首次导出，issue #215 回归点）
  const errs = validateEnvRows(rows, []);
  for (const k of Object.keys(errs)) assert.fail(`预填行 ${k} 不应有错: ${errs[k]}`);
});

test("validateEnvRows：空占位行不报错，已填行跑键/值校验，锁定行跳过", () => {
  const locked = ["LLM_BASE_URL"];
  const rows = [
    { key: "MCP_E2E_TOKEN", value: "" },          // 占位行（服务现 env）→ 不报错
    { key: "LLM_API_KEY", value: "   " },         // 纯空白 = 占位 → 不报错
    { key: "LLM_BASE_URL", value: "https://x" },  // 锁定行 → 锁标呈现，不报错
    { key: "1BAD", value: "v" },                  // 已填 + 格式非法 → 报错
    { key: "CHECKPOINT_JDBC_URL", value: "jdbc:x" }, // 已填 + 保留键 → 报错
    { key: "REGISTRY_ADDRESS", value: "1.2.3.4:5" }, // 已填 + 导出参数键 → 报错
    { key: "BIG", value: "x".repeat(32 * 1024 + 1) }, // 已填 + 超 32KB → 报错
    { key: "DUP", value: "a" },                   // 已填 + 重复键 → 报错
    { key: "DUP", value: "b" },
  ];
  const errs = validateEnvRows(rows, locked);
  assert.deepEqual(Object.keys(errs).map(Number).sort((a, b) => a - b), [3, 4, 5, 6, 7, 8]);
  assert.match(errs[3], /格式非法/);
  assert.match(errs[4], /保留/);
  assert.match(errs[5], /导出参数键/);
  assert.match(errs[6], /32KB/);
  assert.match(errs[7], /重复/);
  assert.match(errs[8], /重复/);
  // 全占位 + 锁定行 → 零错误（首次导出不阻塞）
  assert.deepEqual(validateEnvRows([{ key: "MCP_E2E_TOKEN", value: "" }, { key: "LLM_BASE_URL", value: "" }], locked), {});
});

test("ADVANCE_NEXT：前向链逐段可达（built→…→deployed），终态无下一跳", () => {
  assert.deepEqual(ADVANCE_NEXT, { built: "approving", approving: "approved", approved: "deploying", deploying: "deployed" });
  // 从 built 沿链走 4 步到 deployed，链上无环无缺口
  let cur = "built";
  for (let i = 0; i < 4; i++) cur = ADVANCE_NEXT[cur];
  assert.equal(cur, "deployed");
  assert.equal(ADVANCE_NEXT.deployed, undefined);
  assert.equal(ADVANCE_NEXT.failed, undefined);
});

test("buildPackageTree：拍平路径构建嵌套树，目录在前文件在后，readOnly/size 透传", () => {
  const tree = buildPackageTree([
    { path: "skills/weather/SKILL.md", size: 3, readOnly: true },
    { path: "config.yaml", size: 1 },
    { path: "AGENTS.md", size: 2, readOnly: true },
    { path: "skills/x.py", size: 4 },
    { path: "a/b/c.yaml", size: 5, readOnly: true },
    { path: "a/b/d.yaml" },
  ]);
  // 顶层：目录（a, skills）在前，文件（AGENTS.md, config.yaml）在后，各按字典序
  assert.deepEqual(tree.map((n) => `${n.isDir ? "d" : "f"}:${n.path}`),
    ["d:a", "d:skills", "f:AGENTS.md", "f:config.yaml"]);
  // 目录节点去重合并（skills 下两个文件挂在同一 children）
  const skills = tree.find((n) => n.path === "skills");
  assert.deepEqual(skills.children.map((c) => c.path), ["skills/weather", "skills/x.py"]); // 目录再在前
  assert.equal(skills.children[0].children[0].path, "skills/weather/SKILL.md");
  assert.equal(skills.children[0].children[0].readOnly, true);
  assert.equal(skills.children[1].readOnly, undefined);
  // 深层嵌套 leaf 属性完整
  const a = tree.find((n) => n.path === "a");
  assert.equal(a.children[0].path, "a/b");
  assert.deepEqual(a.children[0].children.map((c) => `${c.path}|${c.readOnly ? "ro" : "rw"}`),
    ["a/b/c.yaml|ro", "a/b/d.yaml|rw"]);
  assert.equal(a.children[0].children[0].size, 5);
});

test("buildPackageTree：空输入与仅文件输入", () => {
  assert.deepEqual(buildPackageTree([]), []);
  const flat = buildPackageTree([{ path: "b.yaml" }, { path: "a.yaml" }]);
  assert.equal(flat.every((n) => !n.isDir), true);
  assert.deepEqual(flat.map((n) => n.path), ["a.yaml", "b.yaml"]); // 同级按字典序
});
