#!/usr/bin/env node
/** 一次性夹具归一化：与 record-llm flush 同规则（过滤记忆提取调用 + 前导裁剪），存量录制件就地修整。 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'mock', 'fixtures', 'llm');
const isMemoryExtract = c => (c.request?.messages ?? []).some(m =>
  m.role === 'system' && String(typeof m.content === 'string' ? m.content : JSON.stringify(m.content)).includes('memory extraction assistant'));
const hasRealToolCall = c => (c.chunks ?? []).some(ch => ch.includes('"tool_calls": [') || ch.includes('"tool_calls":{'));
// 首个人类可见调用（真实 tool_call 或非空文本增量）之前的调用一律视为噪声
const hasVisibleContent = c => hasRealToolCall(c) || (c.chunks ?? []).some(ch => /"content":\s*"[^"]+"/.test(ch));

for (const f of fs.readdirSync(DIR).filter(f => f.endsWith('.json'))) {
  const p = path.join(DIR, f);
  const fx = JSON.parse(fs.readFileSync(p, 'utf8'));
  let calls = (fx.calls ?? []).filter(c => !isMemoryExtract(c));
  const firstVisible = calls.findIndex(hasVisibleContent);
  if (firstVisible > 0) calls = calls.slice(firstVisible);
  if (calls.length !== (fx.calls ?? []).length) {
    fx.calls = calls;
    fs.writeFileSync(p, JSON.stringify(fx, null, 1));
    console.log(`${f}: ${(fx.calls ?? []).length} → ${calls.length}`);
  } else {
    console.log(`${f}: 不变（${calls.length}）`);
  }
}
