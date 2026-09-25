#!/usr/bin/env node
/**
 * E2E 数据重置（env-up 前置）：清空上一轮遗留的会话/租约/事件，保证每轮从干净状态开始。
 *
 * 为什么需要：ASKING 挂起态与 turn_lease 会跨轮残留（DB/Redis 未清），
 * 导致后续用例的 /threads/chat 被 SDK 会话级守卫拒绝，表现为"流未收敛"超时（CI 上曾累积 20+ 分钟）。
 *
 * 只 DROP E2E 自有表（实例启动时 initSchema 幂等重建），不触碰 MySQL 其他库。
 * node 直连实现：CI runner 不保证有 mysql 客户端；redis 走 RESP 协议裸 socket。
 */
import net from 'node:net';
import { execFileSync } from 'node:child_process';

const [jdbcUrl, user, pass, redisUrl] = process.argv.slice(2);
const m = /jdbc:mysql:\/\/([^:/]+):(\d+)\/([^?]+)/.exec(jdbcUrl ?? '');
if (!m) { console.log('[reset] MYSQL_URL 解析失败，跳过'); process.exit(0); }
const [, host, port, db] = m;
const TABLES = ['agent_state', 'agent_fs', 'confirm_context', 'turn_lease', 'session_user',
  'file_asset', 'ui_context', 'kv_sync_key', 'tool_audit_log', 'model_config'];

// 优先用 mysql 客户端；缺失时回退到 JDBC 不可用 → 仅提示（CI 镜像默认带 mysql 客户端）
let dropped = 0;
try {
  for (const t of TABLES) {
    try {
      execFileSync('mysql', ['-h', host, '-P', port, '-u', user, `-p${pass}`, db, '-e', `DROP TABLE IF EXISTS ${t}`], { stdio: 'ignore' });
      dropped++;
    } catch { /* 表不存在或客户端缺失 */ }
  }
} catch { /* 忽略 */ }
console.log(`[reset] MySQL 表清理：${dropped}/${TABLES.length}`);

// Redis：FLUSHDB via RESP（裸 socket，无 redis-cli 依赖）
const rm = /redis:\/\/([^:/]+):(\d+)/.exec(redisUrl ?? '');
if (rm) {
  await new Promise(resolve => {
    const sock = net.createConnection({ host: rm[1], port: Number(rm[2]) });
    sock.setTimeout(3000);
    sock.on('connect', () => sock.write('*1\r\n$7\r\nFLUSHDB\r\n'));
    sock.on('data', d => { console.log('[reset] Redis:', d.toString().trim().replace(/\r\n/g, ' ')); sock.end(); resolve(); });
    sock.on('error', e => { console.log('[reset] Redis 跳过:', e.message); resolve(); });
    sock.on('timeout', () => { sock.destroy(); resolve(); });
    sock.on('close', resolve);
  });
}
