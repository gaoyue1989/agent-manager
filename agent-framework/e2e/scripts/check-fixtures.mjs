#!/usr/bin/env node
/**
 * 录制件覆盖校验（e2e-ci-plan §7 check:fixtures）：
 * 场景注册表 ↔ 夹具一一对应 + 敏感信息扫描。CI 三个 e2e job 的前置步骤，缺件即红。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'mock', 'fixtures');
const registry = JSON.parse(fs.readFileSync(path.join(ROOT, 'registry.json'), 'utf8'));
const errors = [];
const SECRET_PATTERNS = [
  [/sk-[A-Za-z0-9]{16,}/, 'OpenAI 风格密钥'],
  [/Bearer\s+[A-Za-z0-9._-]{16,}/, 'Bearer 令牌'],
  [/Authorization/i, 'Authorization 头残留'],
  [/ghp_[A-Za-z0-9]{20,}/, 'GitHub PAT'],
];
const scan = (file, raw) => { for (const [re, label] of SECRET_PATTERNS) if (re.test(raw)) errors.push(`${file}: 疑似敏感信息（${label}）`); };

for (const [scenario, spec] of Object.entries(registry.llm)) {
  const file = path.join(ROOT, 'llm', `${scenario}.json`);
  if (!fs.existsSync(file)) { errors.push(`缺少 LLM 夹具: llm/${scenario}.json`); continue; }
  const raw = fs.readFileSync(file, 'utf8');
  scan(`llm/${scenario}.json`, raw);
  let fx; try { fx = JSON.parse(raw); } catch (e) { errors.push(`llm/${scenario}.json 解析失败: ${e.message}`); continue; }
  if (!Array.isArray(fx.calls) || fx.calls.length < spec.calls) errors.push(`llm/${scenario}.json: calls=${fx.calls?.length} < 期望 ${spec.calls}`);
  for (const [i, c] of (fx.calls ?? []).entries()) {
    if (c.request?.stream && (!Array.isArray(c.chunks) || c.chunks.length === 0)) errors.push(`llm/${scenario}.json call[${i}]: 流式调用 chunks 为空`);
    if (c.request?.stream && c.chunks?.[c.chunks.length - 1] !== '[DONE]') errors.push(`llm/${scenario}.json call[${i}]: 缺 [DONE] 结束帧`);
    if (!c.request?.stream && !c.body) errors.push(`llm/${scenario}.json call[${i}]: 非流式调用缺 body`);
  }
  for (const v of spec.variants ?? []) if (!fx.variants?.[v]?.chunks?.length) errors.push(`llm/${scenario}.json: 缺 variants.${v}`);
}

for (const f of registry.sandbox) {
  const file = path.join(ROOT, 'sandbox', f);
  if (!fs.existsSync(file)) { errors.push(`缺少沙箱协议夹具: sandbox/${f}`); continue; }
  const raw = fs.readFileSync(file, 'utf8');
  scan(`sandbox/${f}`, raw);
  let entries; try { entries = JSON.parse(raw); } catch (e) { errors.push(`sandbox/${f} 解析失败: ${e.message}`); continue; }
  if (!Array.isArray(entries) || entries.length === 0) errors.push(`sandbox/${f}: 交互记录为空`);
}

if (errors.length) { console.error('[check:fixtures] 失败：\n  ' + errors.join('\n  ')); process.exit(1); }
console.log(`[check:fixtures] OK（llm=${Object.keys(registry.llm).length} 场景，sandbox=${registry.sandbox.length} 文件）`);
