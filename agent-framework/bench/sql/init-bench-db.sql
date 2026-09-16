-- 并发压测独立库（幂等，见 concurrency-benchmark-plan.md §5）
-- 表（agent_state/agent_fs/turn_lease 等）由 agent-framework 启动时自动创建
CREATE DATABASE IF NOT EXISTS agent_manager_bench
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
