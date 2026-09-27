/**
 * 会话消息轨归档种子设施（HA3/U15 共用，docs/session-history-archive-design.md）。
 *
 * 无 compact 场景 LLM 录制件，压缩归档行用直插 session_message 的替代路线：
 * 先按 SessionMessageStore.deleteBySession 同款 5 形谓词清掉本会话真实归档行
 *（制造"state 有、归档无"的未归档尾部），再单条多值 INSERT 压缩前历史 +
 * __compaction_summary__ 摘要行（msg_data 与 SDK Msg 序列化同形，形态取证自
 * 实跑库真实 kind='compaction_summary' 行，与单测 ThreadControllerHistoryMergeTest
 * 的 MSG_M2_SUMMARY 同构）。三行同一 INSERT，自增 id 相邻升序，翻页断言可控。
 * SQL 形态已在本机 e2e 库实跑 INSERT/SELECT/DELETE 往返验证。
 */
import { execFileSync } from 'node:child_process';

/** e2e 库连接参数：与 reset-data.mjs / env-up.sh 同源（CI job env 注入；本地同默认值） */
const JDBC = process.env.MYSQL_URL ?? 'jdbc:mysql://127.0.0.1:3306/agent_framework_e2e';
const DB_USER = process.env.MYSQL_USER ?? 'e2e';
const DB_PASS = process.env.MYSQL_PASS ?? 'e2e-pass';

export interface CompactionSeed { m1: string; summaryId: string; m3: string; summaryText: string }

const esc = (s: string) => s.replace(/\\/g, '\\\\').replace(/'/g, "''");
const like = (s: string) => esc(s.replace(/%/g, ''));

export function seedCompactionArchive(slotKey: string, sid: string, mark: string, userName: string): CompactionSeed {
  const m = /jdbc:mysql:\/\/([^:/]+):(\d+)\/([^?]+)/.exec(JDBC);
  if (!m) throw new Error(`MYSQL_URL 解析失败: ${JDBC}`);
  const [, host, port, db] = m;
  const run = (sql: string) => execFileSync('mysql',
    ['-h', host, '-P', port, '-u', DB_USER, `-p${DB_PASS}`, db, '--batch', '--skip-column-names', '-e', sql],
    { stdio: ['ignore', 'pipe', 'pipe'] }).toString();
  const m1 = `ha-m1-${mark}`;
  const summaryId = `__compaction_summary__:${mark}`;
  const m3 = `ha-m3-${mark}`;
  const summaryText = `You are in the middle of a conversation that has been summarized. 压缩摘要-${mark}`;
  const row = (msgId: string, kind: string, role: string, json: string) =>
    `('${esc(slotKey)}','${esc(msgId)}','${kind}','${role}',NULL,'${esc(json)}','2026-09-27 10:00:00.000','2026-09-27 10:00:00.000')`;
  run(`DELETE FROM session_message WHERE session_id='${like(sid)}' OR session_id LIKE '${like(sid)}:%'
     OR session_id LIKE '${like(sid)}__%' OR session_id LIKE '%:${like(sid)}' OR session_id LIKE '%__${like(sid)}';
INSERT INTO session_message (session_id, msg_id, kind, role, reply_id, msg_data, created_at, updated_at) VALUES
${row(m1, 'message', 'USER', `{"id":"${m1}","name":"${userName}","role":"USER","content":[{"type":"text","text":"归档-压缩前用户消息-${mark}"}]}`)},
${row(summaryId, 'compaction_summary', 'USER', `{"id":"${summaryId}","name":"__compaction_summary__","role":"USER","content":[{"type":"text","text":"${summaryText}"}]}`)},
${row(m3, 'message', 'ASSISTANT', `{"id":"${m3}","name":"E2E Test Agent","role":"ASSISTANT","content":[{"type":"text","text":"归档-压缩前助手回复-${mark}"}]}`)};`);
  return { m1, summaryId, m3, summaryText };
}
