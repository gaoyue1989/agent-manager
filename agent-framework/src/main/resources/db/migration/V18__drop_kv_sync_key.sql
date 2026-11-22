-- =====================================================================
-- V18：删除 kv_sync_key 表（issue #408，2026-11-22）。
--
-- 该表按 rel_path 全局唯一（PRIMARY KEY，无用户维度），present_file 曾按路径
-- 反查写入侧 userKey 充当 KV 读取候选——同 Agent 任一已认证用户猜中常见路径
-- 即可读取其他用户的文件（P0 跨用户泄露）。修复后读取端只认当前主体可证明
-- 归属的命名空间键（ctx.userId / ctx.sessionId / session_user 反查的真实用户，
-- SessionKeyResolver 与 #52 沙箱归属校验同源），写入侧登记与全部查询通道
-- （upsertKvSyncKey / findKvSyncKey / findUserKeyByFileName）一并移除，表无
-- 读写方，按"删除无效机制"纪律整表退役。
--
-- 幂等：DROP TABLE IF EXISTS 天然幂等（FW3 升级路径在已迁移库上重放为 no-op，
-- 与 V14/V16 的 information_schema 守卫同一纪律）。
-- =====================================================================

DROP TABLE IF EXISTS kv_sync_key;
