-- =====================================================================
-- V8：remote_task_registry 在途任务登记表（travel-fulfillment 设计 §18.2，多副本）。
--
-- 背景：RemoteConfirmBridge 的在途登记原为进程内 ConcurrentHashMap（v1.4，
-- 注释自认"lead 重启后丢失"）——未落卡先重启的 awaiting 任务无任何兜底
-- （TTL sweep 只扫 confirm_context 已落库行），member 侧任务永久挂起；
-- 多副本下登记副本本地，其他副本无法接管轮询/唤醒（G1/G4）。
--
-- 演进内容：
--   * 新表 remote_task_registry：spawn 登记行（session_id, task_id 联合唯一），
--     status 状态机 IN_FLIGHT → WAKING → TERMINAL / GIVEN_UP。
--   * 职责（§18.2 五机制）：①spawn 登记 ②启动/周期重建轮询登记
--     ③终态收口 ④唤醒幂等（IN_FLIGHT→WAKING 的 CAS，跨副本恰好一次汇总 turn）
--     ⑤endpoint 快照（跨副本确认路由退回链的一环）。
--   * confirm_context 仍是唯一授权事实源，本表只是轮询调度簿。
--   * 终态行保留 7 天（SessionCleanupService 清理，与 TaskRecord 节奏一致）。
--
-- 【幂等性纪律（FW3 基线演练约束）】FW3 会清空 flyway_schema_history 后以
-- baseline 重放未应用迁移——在"V8 已实际应用"的现库上本脚本会被再次执行。
-- 因此 CREATE TABLE 必须带 IF NOT EXISTS（已应用时整段退化为 no-op）；
-- 与 V6/V7 的 INFORMATION_SCHEMA 探测守卫同构。禁止把本文件改回无条件 DDL。
-- =====================================================================

CREATE TABLE IF NOT EXISTS remote_task_registry (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(191) NOT NULL COMMENT 'lead 规范会话 id（SessionKeyResolver 同源）',
  task_id    VARCHAR(191) NOT NULL COMMENT 'member 协议任务 id',
  service    VARCHAR(191) NOT NULL COMMENT '目标子服务名（OafConfig.subAgents 声明键）',
  endpoint   VARCHAR(512) NOT NULL COMMENT 'spawn 时解析的 endpoint 快照',
  status     VARCHAR(16)  NOT NULL DEFAULT 'IN_FLIGHT' COMMENT 'IN_FLIGHT/TERMINAL/GIVEN_UP（认领即收口，无中间态）',
  created_at DATETIME(3)  NOT NULL,
  updated_at DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_session_task (session_id, task_id),
  KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='lead 侧远程子任务在途登记（多副本重建源，设计 §18.2）';
