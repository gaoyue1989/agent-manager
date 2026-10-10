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
  checkCompletionIdReuse(`llm/${scenario}.json`, fx);
  for (const v of spec.variants ?? []) if (!fx.variants?.[v]?.chunks?.length) errors.push(`llm/${scenario}.json: 缺 variants.${v}`);
}

// 同一夹具的多次 LLM 调用必须用互不相同的 completion id：真实 OpenAI 兼容端点每次请求
// 换一个 id，SDK 直接把 chunk 的 id 当 Msg.id 落库。而 session_message 归档按
// (session_id, msg_id) 幂等合并、history 合并视图又按「已归档 msg_id」过滤 state 侧消息
// ——id 复用会把一个会话里的多条 assistant 消息折叠成一条，回放只剩最后一条
// （实测 oaf-package 夹具三段调用共用一个 id → 回放既无工具组也无开场文本气泡，U16 红）。
// 修 #97 评审补漏：variants 变体回放与 calls 互斥但同会话落地，变体复用主 call 的 id
// 同样触发折叠——故变体一并纳入校验，id 集合必须两两互斥。
function checkCompletionIdReuse(relFile, fx) {
  // 段 = calls 逐个 + variants 逐个（变体视为独立回放分支，与任一 call 同级）
  const segments = [
    ...(fx.calls ?? []).map((c, i) => ({ label: `call[${i}]`, chunks: c.chunks })),
    ...Object.entries(fx.variants ?? {}).map(([name, v]) => ({ label: `variant ${name}`, chunks: v?.chunks })),
  ];
  const idSegment = new Map();
  const reportedIds = new Set();
  for (const seg of segments) {
    for (const ch of seg.chunks ?? []) {
      if (typeof ch !== 'string' || ch === '[DONE]') continue;
      let obj; try { obj = JSON.parse(ch); } catch (e) { errors.push(`${relFile} ${seg.label}: chunk 解析失败: ${e.message}`); continue; }
      const cid = obj && typeof obj.id === 'string' ? obj.id : '';
      if (!cid) continue;
      const prev = idSegment.get(cid);
      // 同一段（一次调用/一个变体）的所有分片共用一个 id（SSE 本来如此），跨段复用才是问题
      if (prev === undefined) idSegment.set(cid, seg.label);
      else if (prev !== seg.label && !reportedIds.has(cid)) {
        reportedIds.add(cid); // 一个 id 只报一次（复用会让该段的每个分片都命中）
        errors.push(`${relFile}: completion id "${cid}" 被 ${prev} 与 ${seg.label} 复用（每次调用须用独立 id，否则历史回放折叠消息）`);
      }
    }
  }
}

for (const f of registry.sandbox) {
  const file = path.join(ROOT, 'sandbox', f);
  if (!fs.existsSync(file)) { errors.push(`缺少沙箱协议夹具: sandbox/${f}`); continue; }
  const raw = fs.readFileSync(file, 'utf8');
  scan(`sandbox/${f}`, raw);
  let entries; try { entries = JSON.parse(raw); } catch (e) { errors.push(`sandbox/${f} 解析失败: ${e.message}`); continue; }
  if (!Array.isArray(entries) || entries.length === 0) errors.push(`sandbox/${f}: 交互记录为空`);
}

// 目录级兜底扫描（修 #97：守卫不能只看 registry——未登记的夹具同样会被运行时回放，
// 漏登记即守卫失明，如 P 组在用的 proto-lead-spawn/proto-member-echo 就曾漏检且确实
// 违反跨调用 completion id 复用不变式）。已登记文件上方 registry 循环已全量覆盖，
// 此处跳过避免重复报错；未登记文件只做解析 + 敏感信息扫描 + id 复用检查，
// 不照搬 calls 期望数/流式 chunks/[DONE]/非流式 body/variants 结构检查
// ——未登记夹具（proto 录制件）request.stream 缺失且无 body 属录制产物，套上会误报红。
const warnings = [];
for (const f of fs.readdirSync(path.join(ROOT, 'llm')).sort().filter(name => name.endsWith('.json'))) {
  const rel = `llm/${f}`;
  if (path.basename(f, '.json') in registry.llm) continue;
  warnings.push(rel);
  const raw = fs.readFileSync(path.join(ROOT, 'llm', f), 'utf8');
  scan(rel, raw);
  let fx; try { fx = JSON.parse(raw); } catch (e) { errors.push(`${rel} 解析失败: ${e.message}`); continue; }
  checkCompletionIdReuse(rel, fx);
}

if (errors.length) { console.error('[check:fixtures] 失败：\n  ' + errors.join('\n  ')); process.exit(1); }
if (warnings.length) console.warn(`[check:fixtures] 告警：${warnings.length} 个 LLM 夹具未登记 registry.llm（守卫无法校验其 calls 期望数等 spec，请确认是否遗漏）：\n  ` + warnings.join('\n  '));
console.log(`[check:fixtures] OK（llm=${Object.keys(registry.llm).length} 场景，sandbox=${registry.sandbox.length} 文件）`);
