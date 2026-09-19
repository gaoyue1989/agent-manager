/**
 * SSE 收流与帧收集（e2e-ci-plan §6.3）。
 * /threads/chat 是 POST，不能用 EventSource——fetch + ReadableStream 手解析。
 */
export type Frame = Record<string, unknown> & { type: string; id?: string | number };

export interface Collected {
  frames: Frame[];
  /** 终态帧：显式 done/error/permission_ask；正常结束（AGENT_END 后关流）归一为 done */
  terminal: Frame | undefined;
  abort: () => void;
  closed: Promise<void>;
}

const TERMINAL_TYPES = new Set(['done', 'error', 'permission_ask']);

export function collectStream(p: Promise<Response>, timeoutMs = 150_000): Collected {
  const frames: Frame[] = [];
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), timeoutMs);
  let terminal: Frame | undefined;
  let resolveClosed!: () => void;
  const closed = new Promise<void>(r => { resolveClosed = r; });

  (async () => {
    try {
      const res = await p;
      if (!res.ok || !res.body) throw new Error(`stream http ${res.status}`);
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      let buf = '';
      let pendingSeq: number | undefined; // `id:` 行 = 数字 seq（仅 /subscribe 携带；chat 流为事件哈希）
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let idx;
        while ((idx = buf.indexOf('\n')) >= 0) {
          let line = buf.slice(0, idx);
          buf = buf.slice(idx + 1);
          if (line.endsWith('\r')) line = line.slice(0, -1);
          if (line.startsWith('id:')) {
            const v = Number(line.slice(3).trim());
            pendingSeq = Number.isNaN(v) ? undefined : v;
            continue;
          }
          if (!line.startsWith('data:')) continue; // `: hb` 注释心跳等
          const raw = line.slice(5).trim();
          try {
            const f = JSON.parse(raw) as Frame;
            if (pendingSeq !== undefined) (f as Frame & { seq?: number }).seq = pendingSeq;
            frames.push(f);
            if (TERMINAL_TYPES.has(f.type)) terminal = f;
          } catch { /* 非 JSON 行忽略 */ }
        }
      }
    } catch {
      // abort/网络错误：保留已收帧（R2 断连模拟依赖此语义）
    } finally {
      clearTimeout(timer);
      if (!terminal) {
        const last = frames[frames.length - 1];
        if (last?.type === 'AGENT_END') terminal = { type: 'done' };
      }
      resolveClosed();
    }
  })();

  return { frames, get terminal() { return terminal; }, abort: () => ac.abort(), closed };
}

/** 文本增量拼接 */
export const textOf = (frames: Frame[]) => frames.filter(f => f.type === 'TEXT_BLOCK_DELTA').map(f => String(f.delta ?? '')).join('');

/** 帧序列的有序子集匹配（忽略中间无关帧） */
export function expectSequence(frames: Frame[], types: string[]): void {
  let i = 0;
  for (const f of frames) {
    if (f.type === types[i]) i++;
    if (i === types.length) return;
  }
  throw new Error(`帧序列缺失：期望 ${types.join(' → ')}，实际已收 [${frames.map(f => f.type).join(',')}]`);
}

/** seq（SSE id）单调递增 */
export function seqMonotonic(frames: Frame[]): void {
  let prev = -1;
  for (const f of frames) {
    const seq = (f as Frame & { seq?: number }).seq;
    if (seq === undefined) continue;
    if (seq < prev) throw new Error(`seq 非单调: ${prev} → ${seq}`); // done 合成帧允许与末事件同 seq
    prev = seq;
  }
}
