/**
 * 会话消息轨归档种子设施（HA3/U15 共用，docs/session-history-archive-design.md）。
 *
 * 无 compact 场景 LLM 录制件，压缩归档行用直插 session_message 的替代路线：
 * 先按 SessionMessageStore.deleteBySession 同款 5 形谓词清掉本会话真实归档行
 *（制造"state 有、归档无"的未归档尾部），再单条多值 INSERT 压缩前历史 +
 * __compaction_summary__ 摘要行（msg_data 与 SDK Msg 序列化同形，形态取证自
 * 实跑库真实 kind='compaction_summary' 行，与单测 ThreadControllerHistoryMergeTest
 * 的 MSG_M2_SUMMARY 同构）。三行同一 INSERT，自增 id 相邻升序，翻页断言可控。
 *
 * 连接走 mysql2（node 直连，参数化占位）：不再依赖宿主机 mysql CLI——
 * 本地开发机普遍没有（spawnSync mysql ENOENT），CI 镜像有但没必要继续分叉。
 * DB 地址解析顺序与 start-agent.sh 一致：显式 env > .runtime/env.json > 内置默认。
 */
import fs from 'node:fs';
import path from 'node:path';
import mysql from 'mysql2/promise';

const RUNTIME_DIR = process.env.E2E_RUNTIME_DIR ?? '.runtime';
const DB_USER = process.env.MYSQL_USER ?? 'e2e';
const DB_PASS = process.env.MYSQL_PASS ?? 'e2e-pass';

/** 显式 env > .runtime/env.json（env-up 产物）> 内置默认（与 CI services 端口一致） */
function resolveJdbc(): string {
  if (process.env.MYSQL_URL) return process.env.MYSQL_URL;
  try {
    const envJson = JSON.parse(
      fs.readFileSync(path.join(RUNTIME_DIR, 'env.json'), 'utf8')) as { mysqlUrl?: string };
    if (envJson.mysqlUrl) return envJson.mysqlUrl;
  } catch { /* env.json 缺失/坏 JSON：回落默认 */ }
  return 'jdbc:mysql://127.0.0.1:3306/agent_framework_e2e';
}

/** 建立直连（archive-seed 两处种子共用；解析失败抛错，不静默回落） */
async function connect() {
  const m = /jdbc:mysql:\/\/([^:/]+):(\d+)\/([^?]+)/.exec(resolveJdbc());
  if (!m) throw new Error(`MYSQL_URL 解析失败: ${resolveJdbc()}`);
  const [, host, port, db] = m;
  return mysql.createConnection({
    host, port: Number(port), user: DB_USER, password: DB_PASS, database: db,
    multipleStatements: true,
  });
}

export interface CompactionSeed { m1: string; summaryId: string; m3: string; summaryText: string }

export async function seedCompactionArchive(
  slotKey: string, sid: string, mark: string, userName: string): Promise<CompactionSeed> {
  const conn = await connect();
  try {
    const m1 = `ha-m1-${mark}`;
    const summaryId = `__compaction_summary__:${mark}`;
    const m3 = `ha-m3-${mark}`;
    const summaryText = `You are in the middle of a conversation that has been summarized. 压缩摘要-${mark}`;
    const ts = '2026-09-27 10:00:00.000';
    // LIKE 通配在 sid 里是数据不是模式：与旧 like() 同款先剥 % 再拼 5 形谓词
    const safeSid = sid.replace(/%/g, '');
    // 摘要行的 name 必须是 __compaction_summary__（服务端合成压缩分隔条以此为据，
    // ThreadController.postProcessMerged 只认 name 不认 kind 列），其余两行 name=角色名
    const json = (msgId: string, name: string, role: string, text: string) =>
      JSON.stringify({ id: msgId, name, role, content: [{ type: 'text', text }] });
    await conn.query(
      `DELETE FROM session_message WHERE session_id = ? OR session_id LIKE ? OR session_id LIKE ?
         OR session_id LIKE ? OR session_id LIKE ?;
       INSERT INTO session_message (session_id, msg_id, kind, role, reply_id, msg_data, created_at, updated_at) VALUES
       (?, ?, 'message', 'USER', NULL, ?, ?, ?),
       (?, ?, 'compaction_summary', 'USER', NULL, ?, ?, ?),
       (?, ?, 'message', 'ASSISTANT', NULL, ?, ?, ?)`,
      [safeSid, `${safeSid}:%`, `${safeSid}__%`, `%:${safeSid}`, `%__${safeSid}`,
        slotKey, m1, json(m1, userName, 'USER', `归档-压缩前用户消息-${mark}`), ts, ts,
        slotKey, summaryId, json(summaryId, '__compaction_summary__', 'USER', summaryText), ts, ts,
        slotKey, m3, json(m3, 'E2E Test Agent', 'ASSISTANT', `归档-压缩前助手回复-${mark}`), ts, ts]);
    return { m1, summaryId, m3, summaryText };
  } finally {
    await conn.end();
  }
}

export interface SyntheticReminderSeed {
  realUser: string;
  reminderWithMeta: string;
  reminderLegacy: string;
  assistant: string;
  reminderText: string;
}

/**
 * SDK 合成提醒归档种子（HA4）：清本会话归档行后直插
 * 真实用户消息 + 两条合成提醒（元数据判据 / name=system 陈旧判据）+ 助手回复。
 *
 * 形态取证自 agentscope-harness `SubagentsMiddleware.buildDeliveryReminder`：
 * role=USER + name=system + metadata.agentscope_synthetic=true，内容为
 * <system-reminder>…</system-reminder>（后台子 agent 任务投递，被持久化进 AgentState.context）。
 * 服务端 StateDataParser.isSyntheticReminder 应将其从 history 中剔除，不得以 user 上屏。
 */
export async function seedSyntheticReminderArchive(
  slotKey: string, sid: string, mark: string, userName: string): Promise<SyntheticReminderSeed> {
  const conn = await connect();
  try {
    const realUser = `sr-user-${mark}`;
    const reminderWithMeta = `sr-rem-meta-${mark}`;
    const reminderLegacy = `sr-rem-legacy-${mark}`;
    const assistant = `sr-assistant-${mark}`;
    const reminderText =
      `<system-reminder>1 background subagent task has completed since your last turn. ` +
      `合成提醒-${mark}</system-reminder>`;
    const ts = '2026-09-27 10:00:00.000';
    const safeSid = sid.replace(/%/g, '');
    const json = (msgId: string, name: string, role: string, text: string,
                  metadata?: Record<string, unknown>) =>
      JSON.stringify({
        id: msgId, name, role, content: [{ type: 'text', text }],
        ...(metadata ? { metadata } : {}),
      });
    await conn.query(
      `DELETE FROM session_message WHERE session_id = ? OR session_id LIKE ? OR session_id LIKE ?
         OR session_id LIKE ? OR session_id LIKE ?;
       INSERT INTO session_message (session_id, msg_id, kind, role, reply_id, msg_data, created_at, updated_at) VALUES
       (?, ?, 'message', 'USER', NULL, ?, ?, ?),
       (?, ?, 'message', 'USER', NULL, ?, ?, ?),
       (?, ?, 'message', 'USER', NULL, ?, ?, ?),
       (?, ?, 'message', 'ASSISTANT', NULL, ?, ?, ?)`,
      [safeSid, `${safeSid}:%`, `${safeSid}__%`, `%:${safeSid}`, `%__${safeSid}`,
        slotKey, realUser, json(realUser, userName, 'USER', `归档-真实用户消息-${mark}`), ts, ts,
        slotKey, reminderWithMeta,
        json(reminderWithMeta, 'system', 'USER', reminderText,
          { agentscope_synthetic: true, agentscope_reminder_kind: 'task_delivery' }), ts, ts,
        slotKey, reminderLegacy,
        json(reminderLegacy, 'system', 'USER', reminderText), ts, ts,
        slotKey, assistant, json(assistant, 'E2E Test Agent', 'ASSISTANT', `归档-助手回复-${mark}`), ts, ts]);
    return { realUser, reminderWithMeta, reminderLegacy, assistant, reminderText };
  } finally {
    await conn.end();
  }
}
