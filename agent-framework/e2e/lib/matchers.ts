/**
 * 断言辅助：终态收敛、帧子集、expect.poll 包装。
 * 所有流式断言先收敛终态（done/permission_ask/error），再做内容断言——防"流没关就断言"的假绿。
 */
import { expect } from '@playwright/test';
import { type Collected } from './sse.js';

export async function waitTerminal(stream: Collected): Promise<void> {
  await stream.closed;
  const t = stream.terminal;
  if (!t) throw new Error('流未收敛到终态帧（done/permission_ask/error）');
}

export const textOf = (frames: Array<Record<string, unknown> & { type: string }>) =>
  frames.filter(f => f.type === 'TEXT_BLOCK_DELTA').map(f => String(f.delta ?? '')).join('');

export const toolNames = (frames: Array<Record<string, unknown> & { type: string }>) =>
  frames.filter(f => f.type === 'TOOL_CALL_START').map(f => String(f.toolName ?? ''));

export const toolResults = (frames: Array<Record<string, unknown> & { type: string }>) =>
  frames.filter(f => f.type === 'TOOL_RESULT_END');

/** 轮询直至条件成立（status/interrupted 接管类场景） */
export async function pollUntil<T>(fn: () => Promise<T>, pred: (v: T) => boolean, timeoutMs = 30_000, intervalMs = 1000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const v = await fn();
    if (pred(v)) return v;
    if (Date.now() > deadline) throw new Error(`pollUntil 超时（${timeoutMs}ms）`);
    await new Promise(r => setTimeout(r, intervalMs));
  }
}

export { expect };
