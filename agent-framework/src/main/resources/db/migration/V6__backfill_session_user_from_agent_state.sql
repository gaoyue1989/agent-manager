-- =====================================================================
-- V6：存量会话回填 session_user（本次新增，数据迁移）。
--
-- 背景：GET /threads 的列表以 session_user 为数据源（按 user_id 过滤），
-- 但该表是 e91d1f0 之后才引入、且只在发消息时 upsert——存量会话只存在于
-- agent_state，从未登记，导致列表对历史会话不可见（本轮排查结论）。
--
-- 回填规则（保守，只认自洽的规范形态 slot）：
--   * 来源行限定 state_key='agent_state'（会话状态轨，排除 _sandbox_state 等运行时状态）；
--   * slot 形态 = "{userId}:{sessionId}"，且与状态 JSON 内的
--     $.user_id / $.session_id 双向一致（老形态 "{peer}:{gwHash}" 的
--     真实用户不可考，不回填，避免把共享运行时桶当成会话）；
--   * user_id / session_id 为 'unknown' 的无主残留（无 sessionId 降级写入）跳过；
--   * 同一 sessionId 被多个 userId 认领的是共享运行时桶（gw-hash，老 Channel
--     链路所有 peer 共用一个 hash 作 session 键），不是真实业务会话，跳过——
--     真实业务会话 id 全局唯一，只归属一个用户；
--   * INSERT IGNORE：已登记的会话行原样保留，可重复执行（幂等）。
-- =====================================================================

INSERT IGNORE INTO session_user (session_id, user_id, created_at, updated_at)
SELECT
  c.sid,
  c.uid,
  MIN(s.created_at),
  MAX(s.updated_at)
FROM agent_state s
JOIN (
  SELECT
    a.session_id AS slot,
    JSON_UNQUOTE(JSON_EXTRACT(a.state_data, '$.session_id')) AS sid,
    JSON_UNQUOTE(JSON_EXTRACT(a.state_data, '$.user_id'))    AS uid
  FROM agent_state a
  WHERE a.state_key = 'agent_state'
    AND JSON_VALID(a.state_data)
) c ON c.slot = s.session_id
JOIN (
  -- 每个 sessionId 的认领用户数（跨全部会话状态行统计，与 slot 形态无关）：
  -- 共享运行时桶（gw-hash）会被多个 userId 认领，真实会话只归属一个
  SELECT
    JSON_UNQUOTE(JSON_EXTRACT(a.state_data, '$.session_id')) AS sid,
    COUNT(DISTINCT JSON_UNQUOTE(JSON_EXTRACT(a.state_data, '$.user_id'))) AS uid_claimants
  FROM agent_state a
  WHERE a.state_key = 'agent_state'
    AND JSON_VALID(a.state_data)
  GROUP BY sid
) cl ON cl.sid = c.sid
WHERE c.sid = SUBSTRING_INDEX(s.session_id, ':', -1)
  AND c.uid = SUBSTRING_INDEX(s.session_id, ':', 1)
  AND c.uid <> 'unknown'
  AND c.sid <> 'unknown'
  AND cl.uid_claimants = 1
GROUP BY c.sid, c.uid;
