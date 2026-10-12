#!/usr/bin/env node
/**
 * collector 自研 YAML 解析器回归断言（issue #97 问题c；由 flywheel.py selftest 以子进程挂载）。
 *
 * 零依赖：从 server.mjs 源码抽取真实出货的 parseMiniYaml/stripComment/parseFlowMap/parseScalar
 * 执行（非复制粘贴，改 server.mjs 必须同步过这里），对 collector.example.yaml 每键断言类型与值，
 * 另覆盖：行尾注释剥离、引号内 '#' 不剥、列表三形态（flow / 块式多行 map / 标量）、
 * 混合文档（map + 列表 + 后续同级 key）互不串扰。
 *
 * 背景：修复前 example 原样加载后 sampling/body_max_bytes/retention_days 全带行尾注释变字符串
 * （采样率比较 NaN → 永不录制、体积上限失效、保留期失效），列表项被静默丢弃。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = path.dirname(fileURLToPath(import.meta.url));
const serverPath = path.resolve(dir, '..', '..', 'eval-collector', 'server.mjs');
const examplePath = path.resolve(dir, '..', '..', 'eval-collector', 'collector.example.yaml');

let failed = 0;
function assert(cond, msg) {
  if (cond) return;
  failed += 1;
  console.error(`  ✘ ${msg}`);
}

// ---- 抽取 server.mjs 真实实现 ----
const src = fs.readFileSync(serverPath, 'utf-8');
const start = src.indexOf('function stripComment');
const end = src.indexOf('const GLOBAL_DEFAULTS');
assert(start > 0 && end > start, 'server.mjs 中未定位到 YAML 解析源码段（stripComment → GLOBAL_DEFAULTS）');
const segment = start >= 0 ? src.slice(start, end) : '';
for (const name of ['function stripComment', 'function parseFlowMap', 'function parseScalar', 'function parseMiniYaml']) {
  assert(segment.includes(name), `抽取段缺少 ${name}`);
}
const parseMiniYaml = new Function(`${segment}; return parseMiniYaml;`)();

// ---- ① example.yaml 原样加载：每个键的类型与值（issue #97 问题c 主回归） ----
assert(fs.existsSync(examplePath), `collector.example.yaml 不存在: ${examplePath}`);
const cfg = parseMiniYaml(fs.readFileSync(examplePath, 'utf-8'));
assert(cfg.defaults?.sampling === 1.0, `defaults.sampling 应为 number 1.0，实得 ${JSON.stringify(cfg.defaults?.sampling)}`);
assert(cfg.defaults?.body_max_bytes === 1048576, `defaults.body_max_bytes 应为 number 1048576，实得 ${JSON.stringify(cfg.defaults?.body_max_bytes)}`);
assert(cfg.storage?.retention_days === 14, `storage.retention_days 应为 number 14，实得 ${JSON.stringify(cfg.storage?.retention_days)}`);

// ---- ② 行尾注释剥离 + 引号内 '#' 保留 ----
const cmt = parseMiniYaml("a: 1.0  # 注释\nb: 'x # y'\nc: \"y # z\"\n");
assert(cmt.a === 1, `行尾注释应剥离（a=1），实得 ${JSON.stringify(cmt.a)}`);
assert(cmt.b === 'x # y', `单引号值内 # 不剥，实得 ${JSON.stringify(cmt.b)}`);
assert(cmt.c === 'y # z', `双引号值内 # 不剥，实得 ${JSON.stringify(cmt.c)}`);

// ---- ③ 列表三形态 ----
const flow = parseMiniYaml("mask_rules:\n  - {pattern: '[0-9]{17,19}', replace: '<CARD>'}\n");
assert(Array.isArray(flow.mask_rules) && flow.mask_rules.length === 1
  && flow.mask_rules[0]?.pattern === '[0-9]{17,19}' && flow.mask_rules[0]?.replace === '<CARD>',
  `flow 列表项应为 [{pattern,replace}]，实得 ${JSON.stringify(flow.mask_rules)}`);

const block = parseMiniYaml("mask_rules:\n  - pattern: 'a+'\n    replace: 'X'\n  - pattern: 'b'\n    replace: 'Y'\n");
assert(Array.isArray(block.mask_rules) && block.mask_rules.length === 2
  && block.mask_rules[0]?.pattern === 'a+' && block.mask_rules[0]?.replace === 'X'
  && block.mask_rules[1]?.pattern === 'b' && block.mask_rules[1]?.replace === 'Y',
  `块式多行 map 列表应完整两项，实得 ${JSON.stringify(block.mask_rules)}`);

const scal = parseMiniYaml('tags:\n  - one\n  - two\n');
assert(Array.isArray(scal.tags) && scal.tags.length === 2 && scal.tags[0] === 'one' && scal.tags[1] === 'two',
  `标量列表应完整，实得 ${JSON.stringify(scal.tags)}`);

// ---- ④ 混合文档：map、列表、后续同级 key 互不串扰 ----
const mixed = parseMiniYaml("defaults:\n  sampling: 0.5\nmask_rules:\n  - pattern: 'p'\n    replace: 'r'\nstorage:\n  retention_days: 7\n");
assert(mixed.defaults?.sampling === 0.5, `混合：defaults.sampling，实得 ${JSON.stringify(mixed.defaults)}`);
assert(Array.isArray(mixed.mask_rules) && mixed.mask_rules[0]?.replace === 'r', `混合：列表项，实得 ${JSON.stringify(mixed.mask_rules)}`);
assert(mixed.storage?.retention_days === 7, `混合：列表后的同级 map 键，实得 ${JSON.stringify(mixed.storage)}`);

if (failed) {
  console.error(`collector_yaml_selftest FAIL（${failed} 项断言失败）`);
  process.exit(1);
}
console.log('collector_yaml_selftest PASS（example 每键类型/值 + 行尾注释/引号# + 列表三形态 + 混合文档）');
