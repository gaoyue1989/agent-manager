/**
 * e2e-sandbox-lifecycle API 组·复用/抢占 + HITL + 可观测/清理（sandbox-pool-opensandbox-design
 * §9.3 L3/L9/L10/L11/L12/L14/L18/L19/L21，PR-D）：沙箱生命周期开启（SANDBOX_LIFECYCLE_ENABLED=true +
 * 加速参数 QUOTA=2 / TTL=1min / RENEW_INTERVAL=3s / CONFIRM_HOLD=1min / POOL_REF=pool-e2e / grace 下限
 * 6s / 注册表超龄 2min / turn_lease 硬寿命 1min）+ mock OpenSandbox（池/renew-expiration/故障注入，§9.2）。
 *
 * 断言面 = agent 行为（turn 终态/结构化帧）+ mock /stats（creates[带请求体]/renews）
 * + /debug/sandbox-bindings（跨副本绑定表/注册表健康/事件流）+ 直连 MySQL（L11 清理钩子）。
 *
 * 分组顺序执行（共享 mock 状态，前组产物供后组复用）：C 复用与抢占（L3/L12/L14/L19）→ D HITL（L18）→
 * E 可观测与清理（L9/L10/L11）。
 */
import { test, expect } from '@playwright/test';
import { BASE, sessionIdFor } from '../lib/env.js';
import { chat, confirmStream, createApprovalApp } from '../lib/client.js';
import { upload } from '../lib/files.js';
import type { UploadResult } from '../lib/files.js';
import { waitTerminal, pollUntil } from '../lib/matchers.js';
import { SANDBOX_MOCK, U, uniq, sleep, mockReset, createPool, destroySandbox, plantFile, probeFile, lifecycleDebug, bindingOf, renewCount, withSql } from '../lib/sandbox-lifecycle.js';

// L 组共享状态（分组顺序执行的产物传递）
const shared: {
  poolCreated: boolean;
  l3Uid?: string;
  l3VictimSid?: string; l3VictimSandbox?: string;
} = { poolCreated: false };

test.beforeAll(async () => {
  await mockReset();
  // 建池（L6 断言 create 载荷 poolRef 透传 + 池耗尽面共用）；幂等：409 视为已存在
  const res = await createPool('pool-e2e');
  expect([201, 409]).toContain(res.status);
  shared.poolCreated = true;
});
// ==================== C 组：复用与抢占 ====================

test('L3 LRU 复用：占满后空闲行被继承（同 sandboxId + renew 计数 +1）', async () => {
  const uid = U();
  const sidA = sessionIdFor(`l3a-${uniq()}`);
  const sidB = sessionIdFor(`l3b-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidA, timeoutMs: 120_000 }));
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidB, timeoutMs: 120_000 }));
  const bindA = await bindingOf(sidA);
  const bindB = await bindingOf(sidB);
  expect(bindA && bindB).toBeTruthy();
  await sleep(12_000);   // grace(=3×RENEW=9s) 过后双双 idle（LRU 可选）
  // victim = expires_at 较小者（A 先建先收尾）——预读两条的 renew 基线
  const renewsA = await renewCount(bindA!.sandbox_id);
  const renewsB = await renewCount(bindB!.sandbox_id);
  const sidC = sessionIdFor(`l3c-${uniq()}`);
  const t = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidC, timeoutMs: 120_000 });
  await waitTerminal(t);
  expect(t.terminal?.type).toBe('done');
  const bindC = await bindingOf(sidC);
  expect(bindC?.sandbox_id, '新会话未继承既有沙箱（LRU 失效或走了 create）').toBeTruthy();
  expect([bindA!.sandbox_id, bindB!.sandbox_id]).toContain(bindC!.sandbox_id);
  // LRU 复用伴随 renew 顶 TTL（⑤c renew）：victim 沙箱的 renew 计数必须增加
  const victimRenewsBefore = bindC!.sandbox_id === bindA!.sandbox_id ? renewsA : renewsB;
  expect(await renewCount(bindC!.sandbox_id), 'LRU 复用未伴随 renew（⑤c 应 renew 顶 TTL）')
    .toBeGreaterThan(victimRenewsBefore);
  // 记录现场供 L14（受害会话=被夺走行的原 session，含其归属 uid）
  shared.l3Uid = uid;
  shared.l3VictimSid = bindC!.sandbox_id === bindA!.sandbox_id ? sidA : sidB;
  shared.l3VictimSandbox = bindC!.sandbox_id;
});

test('L14 被抢会话不重连易主沙箱：归属校验拦截 → 降级重建 + 换代提示帧', async () => {
  const victimSid = shared.l3VictimSid;
  const stolenSandbox = shared.l3VictimSandbox;
  const uid = shared.l3Uid;
  test.skip(!victimSid || !stolenSandbox || !uid, 'L3 未产出受害现场');
  // 受害会话再发 turn（归属 uid 不变）：P3 resume 指向易主沙箱 → 归属校验+占位 0 行 → close+降级 → 新决策
  const stream = chat({ message: `[E2E:plain]`, userId: uid!, sessionId: victimSid!, timeoutMs: 120_000 });
  await waitTerminal(stream);
  expect(stream.terminal?.type, `受害会话重入终态异常: ${JSON.stringify(stream.terminal)}`).toBe('done');
  const now_ = (await lifecycleDebug()).bindings.find(b => b.session_id === victimSid);
  expect(now_?.sandbox_id, '受害会话重建后无绑定（降级 create 脱簿记——身份归还缺失）').toBeTruthy();
  expect(now_!.sandbox_id, '受害会话仍持易主沙箱（两道防线失效）').not.toBe(stolenSandbox);
  // 换代提示帧（§4.4 T4 恢复路径）：首个事件前一次性提示
  expect(stream.frames.some(f => f.type === 'sandbox_rebuilt'), '缺换代提示帧').toBe(true);
});

test('L12 租约硬释放不误判：>1min 长命令执行中（turn_lease 已消失）⑤ 不改绑该会话', async () => {
  const uid = U();
  const sidFill = sessionIdFor(`l12f-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidFill, timeoutMs: 120_000 }));
  await sleep(8_000);   // S2 idle
  const sidLong = sessionIdFor(`l12a-${uniq()}`);
  const long = chat({ message: `[E2E:execute:sleep](70)`, userId: uid, sessionId: sidLong, timeoutMs: 180_000 });
  await sleep(66_000);   // turn_lease 硬寿命 1min 到期物理删行（执行线程不感知，靠 updated_at 判 busy）
  const longSandbox = (await bindingOf(sidLong))?.sandbox_id;
  expect(longSandbox, '长命令 turn 未建沙箱').toBeTruthy();
  // 同 user 新会话触发 ⑤：长命令会话 busy（在途续期保持 updated_at 新鲜）→ 不被改绑
  const sidNew = sessionIdFor(`l12n-${uniq()}`);
  const t = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidNew, timeoutMs: 120_000 });
  await waitTerminal(t);
  expect(t.terminal?.type).toBe('done');
  expect((await bindingOf(sidLong))?.sandbox_id, '长命令会话在 turn_lease 消失后被改绑（L12 回归）')
    .toBe(longSandbox);
  await waitTerminal(long);
  expect(long.terminal?.type, '长命令 turn 未正常完成').toBe('done');
});

test('L19 同 user 继承残留（契约钉死）：LRU 复用后新会话可见旧会话工作区文件', async () => {
  const uid = U();
  // 1) sidA 建 sandbox（写入代际）； grace 过后 idle
  const sidA = sessionIdFor(`l19a-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidA, timeoutMs: 120_000 }));
  const bindA = await bindingOf(sidA);
  expect(bindA?.sandbox_id).toBeTruthy();
  await plantFile(bindA!.sandbox_id, 'data/out.txt', `l19-residue-${uniq()}`);
  await sleep(12_000);   // A idle（grace=9s 过后）
  // 2) busy 会话占第 2 个配额槽（create 自有沙箱，在途续期保持 busy）
  const busy = chat({ message: `[E2E:execute:sleep](14)`, userId: uid, sessionId: sessionIdFor(`l19busy-${uniq()}`), timeoutMs: 120_000 });
  await sleep(5_000);   // busy 完成 acquire
  // 3) 新会话 ⑤：idle 唯一 = sidA 行 → 必然继承 sidA 沙箱（victim 确定）
  const sidC = sessionIdFor(`l19c-${uniq()}`);
  const t = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidC, timeoutMs: 120_000 });
  await waitTerminal(t);
  expect(t.terminal?.type).toBe('done');
  const bindC = await bindingOf(sidC);
  expect(bindC?.sandbox_id, '⑤ 未产出绑定').toBeTruthy();
  expect(bindC!.sandbox_id, '⑤ 未继承 sidA 沙箱（busy 行不可选未生效或走了 create）').toBe(bindA!.sandbox_id);
  // 4) 继承的沙箱内可见残留文件（同 user 残留为 §4.5 明示接受的行为，写进契约断言）
  const probe = await probeFile(bindC!.sandbox_id, '/workspace/data/out.txt');
  expect(probe.status, '继承沙箱内旧会话文件丢失').toBe(200);
  expect(probe.text).toContain('l19-residue-');
  await waitTerminal(busy);
});

test('L21 wrapReused 换代后历史上传件重注入（注入备忘失效，issue #413）', async () => {
  const uid = U();
  // turn1：S 建 C1，上传件 F 注入 C1（create 路径 memo 天然为 false）
  const name = `memo-${uniq()}.zip`;
  const bytes = Buffer.from([0x50, 0x4B, 0x03, 0x04, 0xCC, 0x04, 0x21, 0x43]);
  const up = await upload(BASE, bytes, name, 'application/zip', { userId: uid });
  expect(up.status, '上传失败').toBe(200);
  const fileId = (up.body as UploadResult).file_id;
  const sidS = sessionIdFor(`l21s-${uniq()}`);
  const t1 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidS, fileIds: [fileId], timeoutMs: 120_000 });
  await waitTerminal(t1);
  expect(t1.terminal?.type).toBe('done');
  const bindS1 = await bindingOf(sidS);
  expect(bindS1?.sandbox_id, 'S 无绑定').toBeTruthy();
  const probe1 = await probeFile(bindS1!.sandbox_id, `/workspace/uploads/${name}`);
  expect(probe1.status, 'turn1 上传件未注入 C1').toBe(200);

  // 另会话建 C2
  const sidOther = sessionIdFor(`l21o-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidOther, timeoutMs: 120_000 }));
  const bindOther = await bindingOf(sidOther);
  expect(bindOther?.sandbox_id, '另一会话无绑定').toBeTruthy();
  expect(bindOther!.sandbox_id).not.toBe(bindS1!.sandbox_id);

  // SQL 改绑（L13/L20 先例注入面）：S → C2，强制 turn2 的 resume(C1) 归属失效降级
  // create → ① 绑定复用 wrapReused(C2) 换代。uk_sandbox 唯一——先删另一会话占用 C2
  // 的行再改绑（槽位让渡，等价 ⑤ LRU 改绑的行迁移语义）
  await withSql(async q => {
    await q(`DELETE FROM sandbox_binding WHERE session_id = ?`, [sidOther]);
    await q(`UPDATE sandbox_binding SET sandbox_id = ? WHERE session_id = ?`, [bindOther!.sandbox_id, sidS]);
  });

  // turn2：wrapReused 先行注入查无 pending 置位备忘 → middleware 换代 reset 回滚 F 行
  // （n>0）→ 备忘失效 → 真实重查重注入 C2。旧实现备忘短路吞掉重注入批次 → probe 404
  const t2 = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidS, timeoutMs: 120_000 });
  await waitTerminal(t2);
  expect(t2.terminal?.type).toBe('done');
  const bindS2 = await bindingOf(sidS);
  expect(bindS2?.sandbox_id, 'turn2 未复用 C2（未走 ① 绑定复用）').toBe(bindOther!.sandbox_id);
  const dl = await fetch(`${SANDBOX_MOCK}/v1/sandboxes/${bindOther!.sandbox_id}/proxy/44772/files/download?path=${encodeURIComponent(`/workspace/uploads/${name}`)}`);
  expect(dl.status, '换代后历史上传件未重注入新代容器').toBe(200);
  const got = Buffer.from(await dl.arrayBuffer());
  expect(got.equals(bytes), '重注入字节与上传原始字节不一致').toBe(true);
});

// ==================== D 组：HITL 保留窗 ====================

test('L18a 挂起保留窗：ask 挂起 → 保留窗内持续续期不可被 ⑤ 调度 → confirm 恢复同沙箱', async () => {
  const uid = U();
  const sidFill = sessionIdFor(`l18f-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidFill, timeoutMs: 120_000 }));
  const sidAsk = sessionIdFor(`l18a-${uniq()}`);
  const app = await createApprovalApp(BASE, 'E2E L18a 审批');
  const ask = chat({ message: `[E2E:hitl:submit](${app})`, userId: uid, sessionId: sidAsk, timeoutMs: 120_000 });
  await waitTerminal(ask);
  expect(ask.terminal?.type).toBe('permission_ask');
  const askSandbox = (await bindingOf(sidAsk))?.sandbox_id;
  expect(askSandbox, 'ask 会话未建沙箱（acquire 先于 ask）').toBeTruthy();
  // 向 ask 沙箱植入文件（保留窗恢复同沙箱 → 文件仍在）
  const marker = `l18-file-${uniq()}`;
  await plantFile(askSandbox!, 'data/l18.txt', marker);
  await sleep(12_000);   // ask 段租约释放 + grace(9s) 过后 fill 行 idle（ask 行由 hold 续期保持 busy）
  // 保留窗内同 user 新会话触发 ⑤：ask 行 busy（hold 续期）→ 不改绑，victim = fill 行
  const sidB = sessionIdFor(`l18b-${uniq()}`);
  const t = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidB, timeoutMs: 120_000 });
  await waitTerminal(t);
  expect(t.terminal?.type).toBe('done');
  expect((await bindingOf(sidAsk))?.sandbox_id, '保留窗内挂起会话被 ⑤ 改绑（T4 失效）').toBe(askSandbox);
  // confirm 恢复：同沙箱续跑（文件无损）
  const tcid = ((ask.terminal as Record<string, unknown>).tool_calls as Array<Record<string, unknown>>)[0].tool_call_id as string;
  await sleep(800);
  const rec = confirmStream(sidAsk, [{ tool_call_id: tcid, confirmed: true }], BASE, 120_000, uid);
  await waitTerminal(rec);
  expect(rec.terminal?.type, `confirm 恢复终态异常: ${JSON.stringify(rec.terminal)}`).toBe('done');
  expect((await bindingOf(sidAsk))?.sandbox_id, 'confirm 恢复未落同沙箱（文件丢失面）').toBe(askSandbox);
  const probe = await probeFile(askSandbox!, '/workspace/data/l18.txt');
  expect(probe.status, '保留窗恢复后工作区文件丢失').toBe(200);
  expect(probe.text).toContain(marker);
});

test('L18b 保留窗过期：保留窗后 A 行可被 ⑤ 改绑，A 过期 confirm → 新 sandboxId + 换代提示帧', async () => {
  const uid = U();
  const sidAsk = sessionIdFor(`l18oa-${uniq()}`);
  const app = await createApprovalApp(BASE, 'E2E L18b 审批');
  const ask = chat({ message: `[E2E:hitl:submit](${app})`, userId: uid, sessionId: sidAsk, timeoutMs: 120_000 });
  await waitTerminal(ask);
  expect(ask.terminal?.type).toBe('permission_ask');
  const askSandbox = (await bindingOf(sidAsk))?.sandbox_id;
  expect(askSandbox).toBeTruthy();
  // t0≈ask。A 行 expires_at ≈ t0+120（hold 续期到窗尾）；后建行 expires 恒更晚 → 欲让 ⑤ 选中 A 行，
  // 须令其它行 busy 遮蔽。t0+50 放 fill（sleep 15，t0+65 结束，T2 renew → expires ≈ t0+128 > A）；
  // t0+70（A 的 grace=9s 于 t0+69 刚过、fill 仍 busy）放 stealer → victim 唯一 = A 行。
  await sleep(50_000);
  const fill = chat({ message: `[E2E:execute:sleep](15)`, userId: uid, sessionId: sessionIdFor(`l18of-${uniq()}`), timeoutMs: 120_000 });
  await sleep(20_000);   // t0+70
  const sidSteal = sessionIdFor(`l18os-${uniq()}`);
  const steal = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidSteal, timeoutMs: 120_000 });
  await waitTerminal(steal);
  expect(steal.terminal?.type).toBe('done');
  // A 行已被改绑（session_id 归 stealer）：bindingOf(sidAsk) 落空即 steal 生效的签名
  const stolen = (await bindingOf(sidAsk)) === undefined;
  // 等全部行 freshness 窗口（fill 至 t0+77、steal 行至 ≈t0+83）过去 → 全 idle
  await sleep(16_000);   // ≈t0+86
  // A 过期 confirm：卡片仍可点（无硬拒）；恢复时归属校验拦截 → 降级 → ⑤（全 idle，victim=expires 最小行）
  // → A 换新归属（≠ 原 askSandbox）+ sandbox_rebuilt 换代提示帧
  const tcid = ((ask.terminal as Record<string, unknown>).tool_calls as Array<Record<string, unknown>>)[0].tool_call_id as string;
  const rec = confirmStream(sidAsk, [{ tool_call_id: tcid, confirmed: true }], BASE, 150_000, uid);
  await waitTerminal(rec);
  expect(rec.terminal?.type, `过期 confirm 终态异常: ${JSON.stringify(rec.terminal)}`).toBe('done');
  const newSandbox = (await bindingOf(sidAsk))?.sandbox_id;
  expect(newSandbox, '过期 confirm 后未换代（降级 create 脱簿记或恢复未走沙箱）').toBeTruthy();
  expect(newSandbox, '过期 confirm 仍落旧沙箱（保留窗过期未失效）').not.toBe(askSandbox);
  expect(rec.frames.some(f => f.type === 'sandbox_rebuilt'), '过期 confirm 缺换代提示帧').toBe(true);
  if (!stolen) {
    // steal 未发生（victim 选中其他行）时恢复可能落回原沙箱——此时换代断言应放宽为「confirm 正常完成」
    expect(newSandbox).toBeTruthy();
  }
  await waitTerminal(fill);
});

// ==================== E 组：可观测与清理 ====================

test('L9 到期重建：/admin/destroy 模拟 GC → 下 turn 重建 + 新绑定（对 turn 透明）', async () => {
  const uid = U();
  const sid = sessionIdFor(`l9a-${uniq()}`);
  await waitTerminal(chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid, timeoutMs: 120_000 }));
  const old = (await bindingOf(sid))?.sandbox_id;
  expect(old).toBeTruthy();
  await destroySandbox(old!);   // 模拟服务端 TTL GC
  // destroy 与在途续期（sweep/stop flush）存在竞态：下一拍 renew 404 → 框架按 §4.4 剔注册 +
  // 懒删绑定（提前释放配额）——等绑定行消失（≤ RENew 3s），区分不出「① 懒剔除」还是
  // 「renew_404 剔除」属预期（两条路径都收敛到行删 + 配额释放）
  await pollUntil(async () => (await bindingOf(sid)) === undefined, v => v === true, 15_000, 1_000)
    .catch(() => undefined);
  // 同会话下 turn：P3 resume connect 404（沙箱已 GC）→ 降级 create 重建（对 turn 透明）；
  // 换代提示帧 sandbox_rebuilt（§4.4 T4 / V6⑧ live 修复回归：行被懒剔除+沙箱 GC 的重建
  // 此前漏发帧——connect 404 登记路径已补）
  const t = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid, timeoutMs: 120_000 });
  await waitTerminal(t);
  expect(t.terminal?.type).toBe('done');
  expect(t.frames.some(f => f.type === 'sandbox_rebuilt'),
    `缺换代提示帧（connect-404 登记路径）: ${JSON.stringify(t.frames.slice(0, 3))}`).toBe(true);
  const nb = await bindingOf(sid);
  expect(nb?.sandbox_id, '重建后无绑定').toBeTruthy();
  expect(nb!.sandbox_id, '重建复用了已销毁沙箱').not.toBe(old);
});

test('L10 debug 端点：绑定表 + turn_running（双事实源派生）正确', async () => {
  const uid = U();
  const sidBusy = sessionIdFor(`l10a-${uniq()}`);
  const busy = chat({ message: `[E2E:execute:sleep](10)`, userId: uid, sessionId: sidBusy, timeoutMs: 120_000 });
  await sleep(6_000);   // acquire + 首拍续期
  let dbg = await lifecycleDebug();
  expect(dbg.enabled).toBe(true);
  expect(dbg.config).toMatchObject({ user_quota: 2, ttl_minutes: 1, renew_interval_seconds: 3, confirm_hold_minutes: 1 });
  const busyRow = dbg.bindings.find(b => b.session_id === sidBusy);
  expect(busyRow, 'busy 会话绑定行缺失').toBeTruthy();
  expect(busyRow!.turn_running, '执行中 turn_running 应为 true（turn_lease OR updated_at 新鲜）').toBe(true);
  expect(Array.isArray(dbg.events)).toBe(true);
  expect(dbg.registry).toHaveProperty('size');
  await waitTerminal(busy);
  // 终态 + grace(9s) 过后 → idle（双事实源双假）
  await sleep(12_000);
  dbg = await lifecycleDebug();
  const idleRow = dbg.bindings.find(b => b.session_id === sidBusy);
  expect(idleRow!.turn_running, 'turn 结束 + grace 过后仍 busy（grace 判定回归）').toBe(false);
});

test('L11 定时清理：短保留窗口 + 测试钩子触发 → 过期行删 + count 重算 + 空锚行删（重跑幂等）', async () => {
  const uid = U();
  // 注入：1 条过期超保留窗的绑定行（user_key=uid）+ 计数虚高 + 老化锚行
  await withSql(async q => {
    await q(`INSERT INTO sandbox_binding (user_key, session_id, sandbox_id, expires_at, created_at)
             VALUES (?, 'l11-expired', 'sbx-l11', DATE_SUB(NOW(3), INTERVAL 9 DAY), DATE_SUB(NOW(3), INTERVAL 9 DAY))`, [uid]);
    await q('INSERT IGNORE INTO sandbox_user (user_key) VALUES (?)', [uid]);
    await q('UPDATE sandbox_user SET binding_count = 5, updated_at = DATE_SUB(NOW(3), INTERVAL 9 DAY) WHERE user_key = ?', [uid]);
  });
  // 测试钩子：POST /debug/sandbox-cleanup?retentionDays=7（与 SessionCleanupService 同语义；
  // 钩子仅在 lifecycle 组 SANDBOX_LIFECYCLE_E2E_HOOKS=true 时注册——状态码必须先于 json() 断言，
  // 否则 404/400 空体或错误体会被静默当成功解析）
  const call = async () => {
    const res = await fetch(`${BASE}/debug/sandbox-cleanup?retentionDays=7`, { method: 'POST' });
    expect(res.status, 'sandbox-cleanup 测试钩子应已注册（SANDBOX_LIFECYCLE_E2E_HOOKS=true）且返回 200').toBe(200);
    return (await res.json()) as { enabled: boolean; bindings_deleted: number; anchors_deleted: number };
  };
  const body = await call();
  expect(body.enabled).toBe(true);
  expect(body.bindings_deleted, '过期绑定行未删').toBeGreaterThanOrEqual(1);
  await withSql(async q => {
    const rowsN = Number(((await q('SELECT COUNT(*) AS c FROM sandbox_binding WHERE user_key = ?', [uid])).rows[0] as Record<string, unknown>).c);
    const cnt = Number(((await q('SELECT binding_count FROM sandbox_user WHERE user_key = ?', [uid])).rows[0] as Record<string, unknown>).binding_count);
    expect(rowsN, '过期行仍在').toBe(0);
    expect(cnt, '计数未重算').toBe(0);
  });
  // 空锚行删除：第一轮的重算 UPDATE 会刷新锚行 updated_at（ON UPDATE）→ 回拨后二次清理验删
  // （与真库 IT cleanupExpiredRecomputesAndPrunesEmptyAnchorsIdempotently 同款时序）
  await withSql(async q => {
    await q(`UPDATE sandbox_user SET updated_at = DATE_SUB(NOW(3), INTERVAL 9 DAY) WHERE user_key = ?`, [uid]);
  });
  const body2 = await call();
  expect(body2.anchors_deleted, '空且老化的锚行未删').toBeGreaterThanOrEqual(1);
  // 重跑幂等（第三轮删 0）
  const body3 = await call();
  expect(body3.bindings_deleted).toBe(0);
  expect(body3.anchors_deleted).toBe(0);
});