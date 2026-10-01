-- =====================================================================
-- V9：PER_PEER 会话键迁移（issue #87，Channel 切 DmScope.PER_PEER 的存量重键）。
--
-- 背景：Channel 链路原走 ChatUiChannel.create()（DmScope.MAIN），SDK 网关路由层
-- 把所有 DM 折叠成同一 canonicalKey（"chatui|x:agentId=main"）→ SDK 会话 id 恒为
-- 全进程共享 gw-3f20f08c5499，agent_state 槽位 = "{peer}:gw-3f20f08c5499"（peer 即
-- 平台 sessionId；SDK MysqlAgentStateStore.slotId = normalizeUser(userId) + ":" +
-- sessionId，normalizeUser 恒等）——所有会话状态挤在同一槽位，且 SDK 网关按该 key
-- 过整 turn 持有的单许可闸门，全进程 turn 串行（实测排队 17s~169s）。
-- 切 PER_PEER 后 canonicalKey = "chatui|r:{peer}|x:agentId=main"，每会话独立
-- gw-hash；存量行必须重键，否则老会话续聊/多副本恢复读不到状态（上下文全丢）。
--
-- 规则（纯 SQL；旧行自含重键所需全部信息——槽位前缀即 peer）：
--   * 仅迁移共享 gw-hash 形态（LIKE '%:gw-3f20f08c5499'）；A2A（{tenant}__{tid}，
--     无 ':' 复合形态）与其他槽位不受影响；
--   * 新槽位 = "{peer}:gw-" + SHA-256("chatui|r:{peer}|x:agentId=main") 前 12 字符
--     hex，与 HarnessGateway / AgentRuntimeService.channelGatewaySessionId 派生一致
--     （MySQL SHA2(...,256) 为 64 字符小写 hex，SUBSTRING(...,1,12) 等价 Java
--     HexFormat.formatHex(digest,0,6)）；
--   * 幂等：重键后不再有行匹配 WHERE（Flyway 亦保证只执行一次）；
--   * updated_at = updated_at：显式赋原值，避免 ON UPDATE CURRENT_TIMESTAMP 把
--     全部存量行时间戳刷成迁移时刻（会话列表排序不受扰）；
--   * 回滚（退回旧镜像时）反向执行一次即可——新行同样自含旧键推导所需信息
--     （SHA-256("chatui|x:agentId=main") 前 12 字符恒为 3f20f08c5499）：
--       UPDATE agent_state
--       SET session_id = CONCAT(SUBSTRING_INDEX(session_id, ':', 1), ':gw-3f20f08c5499'),
--           updated_at = updated_at
--       WHERE session_id LIKE '%:gw-' AND session_id NOT LIKE '%:gw-3f20f08c5499'
--         AND SUBSTRING(SHA2(CONCAT('chatui|r:', SUBSTRING_INDEX(session_id, ':', 1),
--                                   '|x:agentId=main'), 256), 1, 12)
--             = SUBSTRING_INDEX(session_id, ':', -1);
-- =====================================================================

UPDATE agent_state
SET session_id = CONCAT(
      SUBSTRING_INDEX(session_id, ':', 1), ':gw-',
      SUBSTRING(SHA2(CONCAT('chatui|r:', SUBSTRING_INDEX(session_id, ':', 1),
                            '|x:agentId=main'), 256), 1, 12)),
    updated_at = updated_at
WHERE session_id LIKE '%:gw-3f20f08c5499';
