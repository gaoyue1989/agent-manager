-- V15: 沙箱生命周期簿记两表（sandbox-pool-opensandbox-design §4.1，PR-D）
-- 落 per-service checkpoint 库（{原库}_{k8s 名}），配额天然按服务隔离（架构原则二）。
-- 职责边界：TTL 真相在 OpenSandbox 服务端（expiresAt + 到期 GC），本表仅是
-- LRU 排序缓存 + 单用户配额仲裁锚点；「执行中 turn」事实源仍是 turn_lease（§4.1.1 派生，不冗余）。

-- 用户配额计数表：占额 CAS 锚点。占额（检查计数 + 占位）必须原子——仅靠绑定表做不到
-- （0 绑定行时 SELECT ... FOR UPDATE 锁不住空集，并发会话各自 count=quota-1 都去 create 突破配额）。
CREATE TABLE IF NOT EXISTS sandbox_user (
  user_key       VARCHAR(255) NOT NULL,
  -- 该用户当前绑定数；与 COUNT(sandbox_binding WHERE user_key=?) 事务内同增同减
  -- 不变量（§4.1）：任意时刻 binding_count == COUNT(sandbox_binding.user_key = 本行)
  binding_count  INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 绑定表：user/session ↔ sandbox 簿记。绑定行不会在会话结束时删除（框架无会话结束信号），
-- 沙箱到期后行仍在，由 acquire 懒剔除（正确性）+ SessionCleanupService 定时清理（容量）双轨回收。
CREATE TABLE IF NOT EXISTS sandbox_binding (
  id         BIGINT NOT NULL AUTO_INCREMENT,
  user_key   VARCHAR(255) NOT NULL,
  session_id VARCHAR(255) NOT NULL,
  sandbox_id VARCHAR(128) NOT NULL,
  -- LRU 排序缓存（服务端绝对 expiresAt 回填，不用本地 now+TTL 推算）；真相在服务端 expiresAt。
  -- PENDING 占额行固定取 NOW() + 10min（= 陈旧 PENDING 回收窗，§4.2 ②，天然豁免 ⑤a 过期行删除）
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sandbox (sandbox_id),          -- 一个沙箱至多一条绑定
  UNIQUE KEY uk_session (session_id),          -- 一个会话至多一条绑定（LRU 复用 = UPDATE 改归属）
  KEY idx_user (user_key, expires_at),         -- 配额校验 + LRU 取最小 expires_at
  KEY idx_expiry (expires_at)                  -- 定时清理扫描（§4.1.2）
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- PENDING 占额不变量（§4.1，评审修订）：占额（sandbox_user CAS）与绑定落库是两个事务，
-- 若占额提交后进程崩溃（create 前或落绑定前），binding_count +1 却无对应绑定行——幽灵占额。
-- 修复：占额事务同时 INSERT 一条 PENDING 绑定行（sandbox_id = 'pending:{uuid}'，
-- session_id 即本会话，expires_at = NOW() + 10min），create 成功后短事务把该行
-- UPDATE 为真实 sandbox_id + 服务端 expiresAt（转正）；create 失败或唯一键冲突
-- → 删除 PENDING 行 + 计数递减（补偿一律按自身 pending:{uuid} 的 sandbox_id 定位，
-- 严禁按 session_id 删——同用户 A2A 链路共享 session_id，会误删他人在途占位）。
-- 此后任意时刻 binding_count == COUNT(binding 行)，⑤ 的重算/懒剔除在锚行锁下恒安全；
-- 崩溃残留的 PENDING 行由懒剔除按行龄回收（created_at < NOW() - 10min）。
