"use client";
// 用户侧 · Skill 管理（2026-10-19 重设计，设计 §8.4）：三 Tab——
// ① 我的 Skill（空间技能资产 CRUD + 版本管理）；② Skill 广场（市场条目 + 多源预留，
// 一期仅内置源「平台精选」）；③ Agent 使用情况（每个 Agent 最后生成的 OAF 包实时解析：
// 包内 skills/ 目录 + frontmatter skills 声明；行「查看详情」= 单 Agent 抽屉）。
// 创建方式两种：zip 技能包上传 / Markdown 定义（对标 AgentCore create-skill）。
import { useCallback, useEffect, useState } from "react";
import { api, type MarketItemRec, type MarketSourceRec, type SkillDetail, type SkillRec, type SkillUsageDetail, type SkillUsageItem, type SkillVersionRec } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead, Tag } from "@/components/ui";

function parseTags(raw: string): string[] {
  try { return JSON.parse(raw) as string[]; } catch { return []; }
}
// agentStatusLabel services 状态 → 中文展示（Badge 无 children 时直出原始值）
function agentStatusLabel(s: string): string {
  return ({ running: "运行中", stopped: "已下线", deploying: "部署中", created: "已创建",
    register_failed: "注册失败", deploy_failed: "部署失败", error: "异常" } as Record<string, string>)[s] ?? s;
}
function sourceLabel(s: string): string {
  return s === "market" ? "市场导入" : s === "zip" ? "zip 上传" : "Markdown 定义";
}

const skillStatusBadge = (v: SkillVersionRec) =>
  v.status === "published" ? <Badge status="running">已发布</Badge>
  : v.status === "draft" ? <Badge status="pending">草稿</Badge>
  : v.status === "offline" ? <Badge status="stopped">已下线</Badge>
  : <Badge status="pending">{v.status}</Badge>;

export default function SkillsPage() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const writable = role === "owner" || role === "admin";
  const [tab, setTab] = useState<"mine" | "market" | "usage">("mine");
  const [msg, setMsg] = useState("");

  return (
    <div data-testid="console-skills">
      <PageHead title="Skill 管理" sub="工作空间技能资产中心：市场导入 / zip 技能包上传 / Markdown 定义创建，版本管理 + Agent 引用查询；创建/编辑 Agent 时在资产配置区勾选注入生成包"
        actions={writable && tab === "mine" ? <CreateSkillButton ws={ws} onDone={(m) => { setMsg(m); reloadTick(); }} /> : undefined} />
      {msg && <div className="mb-3 rounded border border-emerald-200 bg-emerald-50 p-2 text-xs text-emerald-700" data-testid="skill-msg">{msg}</div>}

      <div className="mb-4 flex gap-1 border-b border-gray-200" data-testid="skill-tabs">
        {([["mine", "我的 Skill"], ["market", "Skill 广场"], ["usage", "Agent 使用情况"]] as const).map(([k, label]) => (
          <button key={k} type="button" onClick={() => setTab(k)} data-testid={`skill-tab-${k}`}
            className={`-mb-px border-b-2 px-3 py-2 text-[13px] ${tab === k ? "border-blue-600 font-medium text-blue-600" : "border-transparent text-gray-500 hover:text-gray-800"}`}>
            {label}
          </button>
        ))}
      </div>

      {tab === "mine" && <MySkillsTab ws={ws} writable={writable} onChanged={reloadTick} setMsg={setMsg} />}
      {tab === "market" && <MarketTab ws={ws} writable={writable} onDone={(m) => { setMsg(m); reloadTick(); }} />}
      {tab === "usage" && <UsageTab ws={ws} />}
      <p className="mt-4 text-[11px] text-gray-400">提示：三个 Tab 的数据由下方区块各自维护。</p>
    </div>
  );
}

// ---- 全局刷新号（创建/安装后让我的 Skill 列表重载；无状态库，页面内提升即可）----
let reloadHook: (() => void) | null = null;
function reloadTick() { reloadHook?.(); }

// ================= Tab 1 · 我的 Skill =================
function MySkillsTab({ ws, writable, onChanged, setMsg }: {
  ws: number; writable: boolean; onChanged: () => void; setMsg: (m: string) => void;
}) {
  const [list, setList] = useState<SkillRec[]>([]);
  const [keyword, setKeyword] = useState("");
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState<SkillDetail | null>(null);

  const load = useCallback(async () => {
    try { setList(await api.console.skillCenter.list(ws, keyword)); setMsg?.(""); }
    catch (e) { setMsg?.(`加载失败: ${(e as Error).message}`); }
    finally { setLoading(false); }
  }, [ws, keyword, setMsg]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { reloadHook = load; return () => { reloadHook = null; }; }, [load]);

  const toggleEnable = async (s: SkillRec) => {
    if (!writable) return;
    try { await api.console.skillCenter.enable(ws, s.id, !s.enabled); await load(); onChanged(); }
    catch (e) { setMsg?.(`${(e as Error).message}`); }
  };
  const remove = async (s: SkillRec) => {
    if (!writable || !confirm(`删除技能 ${s.name}？有 Agent 引用时将被拒绝。`)) return;
    try { await api.console.skillCenter.remove(ws, s.id); await load(); onChanged(); }
    catch (e) { setMsg?.(`${(e as Error).message}`); }
  };
  const openDetail = async (s: SkillRec) => {
    try { setDetail(await api.console.skillCenter.detail(ws, s.id)); }
    catch (e) { setMsg?.(`${(e as Error).message}`); }
  };

  return (
    <div data-testid="skills-mine">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="搜索技能名 / 描述 / 标签" data-testid="skill-center-keyword"
          className="w-[220px] rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]" />
      </div>
      {loading ? <p className="text-sm text-gray-400">加载中…</p> : list.length === 0 ? (
        <Card><div className="py-10 text-center text-sm text-gray-400" data-testid="skill-center-empty">
          空间内暂无技能资产——右上角「创建 Skill」（zip 技能包上传或 Markdown 定义），或到「Skill 广场」一键导入。
        </div></Card>
      ) : (
        <table className="w-full rounded-xl border border-gray-200 bg-white text-sm" data-testid="skill-center-table">
          <thead><tr className="border-b bg-gray-50 text-left text-[11px] text-gray-400">
            <th className="px-4 py-2 font-medium">技能</th><th className="px-4 py-2 font-medium">描述</th>
            <th className="px-4 py-2 font-medium">来源</th><th className="px-4 py-2 font-medium">最新版本</th>
            <th className="px-4 py-2 font-medium">状态</th><th className="px-4 py-2 font-medium">被引用</th>
            <th className="px-4 py-2 text-right font-medium">操作</th>
          </tr></thead>
          <tbody>
            {list.map((s) => (
              <tr key={s.id} className="border-b last:border-0" data-testid={`skill-row-${s.name}`}>
                <td className="px-4 py-2">
                  <b className="font-mono text-xs">{s.name}</b>
                  <div className="mt-0.5 flex gap-1">{parseTags(s.tagsJson).map((t) => <Tag key={t}>{t}</Tag>)}</div>
                </td>
                <td className="max-w-[260px] px-4 py-2 text-xs text-gray-500">{s.description}</td>
                <td className="px-4 py-2"><Tag>{sourceLabel(s.source)}{s.sourceName ? ` · ${s.sourceName}` : ""}</Tag></td>
                <td className="px-4 py-2">
                  {s.latestPublish ? (<span className="flex items-center gap-1.5"><b>{s.latestPublish.version}</b><Badge status="running">已发布</Badge></span>)
                    : <Badge status="pending">草稿</Badge>}
                </td>
                <td className="px-4 py-2">{s.enabled ? <Badge status="running">已启用</Badge> : <Badge status="stopped">已停用</Badge>}</td>
                <td className="px-4 py-2 text-xs">
                  {(s.refAgentCount ?? 0) > 0
                    ? <a href="#skill-usage" onClick={(e) => { e.preventDefault(); document.querySelector<HTMLButtonElement>('[data-testid="skill-tab-usage"]')?.click(); }}
                        className="text-blue-600 hover:underline" data-testid={`skill-refcount-${s.name}`}>{s.refAgentCount} 个 Agent</a>
                    : <span className="text-gray-400">未引用</span>}
                </td>
                <td className="px-4 py-2">
                  <div className="flex justify-end gap-1.5">
                    <Btn sm onClick={() => openDetail(s)} data-testid={`skill-detail-${s.name}`}>详情</Btn>
                    {writable && (s.enabled
                      ? <Btn sm kind="danger" onClick={() => toggleEnable(s)}>停用</Btn>
                      : <Btn sm onClick={() => toggleEnable(s)}>启用</Btn>)}
                    {writable && (s.refAgentCount ?? 0) === 0 && <Btn sm kind="danger" onClick={() => remove(s)}>删除</Btn>}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {detail && <SkillDetailDrawer ws={ws} detail={detail} onClose={() => setDetail(null)} writable={writable} onChanged={() => { load(); onChanged(); }} setMsg={setMsg} />}
      <Note>口径：本页管理的是<b>空间技能资产</b>（独立于 OAF 包）。创建/编辑 Agent 在资产配置区勾选后，把所选<b>发布版本</b>目录复制进生成包（frontmatter skills 声明 + skills/&lt;name&gt;/ 目录，随包自包含）——技能中心后续发版<b>不影响</b>已发布 Agent（版本快照语义），升级经 Agent 编辑页重新勾选。已停用技能不再出现在创建/编辑候选列表，已生成包内容不回变。</Note>
    </div>
  );
}

// ================= 技能详情抽屉（版本管理 + 引用 Agent）=================
function SkillDetailDrawer({ ws, detail, onClose, writable, onChanged, setMsg }: {
  ws: number; detail: SkillDetail; onClose: () => void; writable: boolean;
  onChanged: () => void; setMsg: (m: string) => void;
}) {
  const [versions, setVersions] = useState<SkillVersionRec[]>(detail.versions);
  const { skill, referencingAgents } = detail;
  const reloadVersions = async () => {
    try { setVersions(await api.console.skillCenter.versions(ws, skill.id)); } catch { /* 保持现状 */ }
  };
  const act = async (fn: () => Promise<unknown>, ok: string) => {
    try { await fn(); setMsg(ok); await reloadVersions(); onChanged(); }
    catch (e) { setMsg(`${(e as Error).message}`); }
  };
  return (
    <div className="fixed inset-0 z-[70] bg-gray-900/40" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }} data-testid="skill-detail-drawer">
      <div className="absolute inset-y-0 right-0 w-[560px] max-w-[94vw] overflow-y-auto bg-white shadow-2xl">
        <div className="flex items-center justify-between border-b px-5 py-3.5">
          <b className="text-sm">Skill 详情 · <span className="font-mono">{skill.name}</span></b>
          <Btn sm onClick={onClose}>关闭</Btn>
        </div>
        <div className="space-y-4 p-5">
          <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-xs">
            <div><dt className="text-gray-400">描述</dt><dd className="text-gray-700">{skill.description}</dd></div>
            <div><dt className="text-gray-400">标签</dt><dd className="mt-0.5 flex gap-1">{parseTags(skill.tagsJson).map((t) => <Tag key={t}>{t}</Tag>)}</dd></div>
            <div><dt className="text-gray-400">来源</dt><dd><Tag>{sourceLabel(skill.source)}{skill.sourceName ? ` · ${skill.sourceName}` : ""}</Tag></dd></div>
            <div><dt className="text-gray-400">启用状态</dt><dd>{skill.enabled ? <Badge status="running">已启用</Badge> : <Badge status="stopped">已停用</Badge>}<span className="ml-1 text-gray-400">（停用后创建/编辑候选不再出现）</span></dd></div>
          </dl>

          <div>
            <div className="mb-1.5 text-xs text-gray-400">版本管理（正式版本不可变；修正走「创建新版本」）</div>
            <table className="w-full rounded-lg border border-gray-200 text-xs" data-testid="skill-version-table">
              <thead><tr className="border-b bg-gray-50 text-left text-[11px] text-gray-400">
                <th className="p-2 font-medium">版本</th><th className="p-2 font-medium">状态</th>
                <th className="p-2 font-medium">变更说明</th><th className="p-2 text-right font-medium">操作</th>
              </tr></thead>
              <tbody>
                {versions.map((v) => (
                  <tr key={v.id} className="border-b last:border-0" data-testid={`skill-version-${v.version}`}>
                    <td className="p-2 font-mono"><b>{v.version}</b>{skill.latestPublish?.version === v.version && <Tag>最新发布</Tag>}</td>
                    <td className="p-2">{skillStatusBadge(v)}</td>
                    <td className="max-w-[180px] p-2 text-gray-500">{v.changelog || "—"}</td>
                    <td className="p-2"><div className="flex justify-end gap-1.5">
                      <a href={api.console.skillCenter.versionDownload(ws, skill.id, v.id)} data-testid={`skill-dl-${v.version}`}
                        className="rounded border border-gray-300 px-2 py-0.5 text-[11px] text-gray-600 hover:border-blue-200 hover:text-blue-600">下载</a>
                      {writable && v.status === "published" && <Btn sm onClick={() => act(() => api.console.skillCenter.versionAction(ws, skill.id, v.id, "offline"), `版本 ${v.version} 已下线`)}>下线</Btn>}
                      {writable && v.status === "offline" && <Btn sm onClick={() => act(() => api.console.skillCenter.versionAction(ws, skill.id, v.id, "reline"), `版本 ${v.version} 已重新上线`)}>重新上线</Btn>}
                      {writable && (v.status === "published" || v.status === "offline") && (
                        <Btn sm kind="pri" onClick={() => { const nv = prompt(`基于 ${v.version} 创建新版本（语义化版本，回车默认 ${bumpPatch(v.version)}）`, bumpPatch(v.version)); if (nv) act(() => api.console.skillCenter.createVersion(ws, skill.id, { baseVersionId: v.id, version: nv, changelog: `基于 ${v.version} 派生` }), `新版本 ${nv} 已创建（草稿）`); }}>创建新版本</Btn>
                      )}
                      {writable && (v.status === "draft") && <Btn sm kind="pri" onClick={() => act(() => api.console.skillCenter.versionAction(ws, skill.id, v.id, "publish"), `版本 ${v.version} 已发布`)} data-testid={`skill-publish-${v.version}`}>发布</Btn>}
                    </div></td>
                  </tr>
                ))}
              </tbody>
            </table>
            {writable && <div className="mt-2 text-[11px] text-gray-400">Markdown 定义的技能可经「编辑」更新工作区草稿（列表行编辑入口）；zip 形态修正 = 基于发布版创建新版本。</div>}
          </div>

          <div>
            <div className="mb-1.5 text-xs text-gray-400">引用 Agent（版本快照；技能发版不影响已发布 Agent，升级经编辑页重新勾选）</div>
            <table className="w-full rounded-lg border border-gray-200 text-xs" data-testid="skill-ref-table">
              <thead><tr className="border-b bg-gray-50 text-left text-[11px] text-gray-400">
                <th className="p-2 font-medium">Agent</th><th className="p-2 font-medium">状态</th><th className="p-2 font-medium">锁定版本</th>
              </tr></thead>
              <tbody>
                {referencingAgents.length === 0 && <tr><td colSpan={3} className="p-3 text-center text-gray-400">暂无 Agent 引用</td></tr>}
                {referencingAgents.map((a) => (
                  <tr key={a.id} className="border-b last:border-0">
                    <td className="p-2 font-medium">{a.displayName}</td>
                    <td className="p-2"><Badge status={a.status === "running" ? "running" : a.status === "stopped" ? "stopped" : "pending"}>{agentStatusLabel(a.status)}</Badge></td>
                    <td className="p-2"><Tag>{a.lockedVersion || "—"}</Tag>{a.packageVersion ? <span className="ml-1 text-[10.5px] text-gray-400">包 v{a.packageVersion}</span> : null}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {writable && (
            <div className="flex justify-end gap-2 border-t pt-3">
              <Btn kind="danger" onClick={async () => { if (confirm(`删除技能 ${skill.name}？`)) { try { await api.console.skillCenter.remove(ws, skill.id); setMsg("已删除"); onChanged(); onClose(); } catch (e) { setMsg(`${(e as Error).message}`); } } }}>删除技能</Btn>
            </div>
          )}
          <div className="text-[11px] text-gray-400">删除前校验引用：存在引用 Agent 时返回 409（先在编辑页解除勾选并重发布）。</div>
        </div>
      </div>
    </div>
  );
}

function bumpPatch(v: string): string {
  const m = v.match(/^(\d+)\.(\d+)\.(\d+)$/);
  return m ? `${m[1]}.${m[2]}.${Number(m[3]) + 1}` : v;
}

// ================= 创建 Skill 抽屉（zip / Markdown）=================
function CreateSkillButton({ ws, onDone }: { ws: number; onDone: (msg: string) => void }) {
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<"zip" | "markdown">("zip");
  const [name, setName] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [description, setDescription] = useState("");
  const [tags, setTags] = useState("");
  const [content, setContent] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [publishNow, setPublishNow] = useState(true);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const submit = async () => {
    setErr(""); setBusy(true);
    try {
      if (mode === "zip") {
        if (!file) throw new Error("请选择 zip 技能包");
        const r = await api.console.skillCenter.uploadZip(ws, file, {
          name: name || undefined, displayName: displayName || undefined,
          description: description || undefined, tags: tags || undefined, publishNow,
        });
        onDone(`技能 ${r.skill.name} 已创建${r.warnings.length ? `（${r.warnings.length} 条合规提示）` : "并发布"}`);
      } else {
        if (!name.trim()) throw new Error("技能名称必填");
        const r = await api.console.skillCenter.createMarkdown(ws, {
          name: name.trim(), displayName: displayName || undefined, description, tags: tags ? tags.split(",").map((x) => x.trim()).filter(Boolean) : undefined,
          content, publishNow,
        });
        onDone(`技能 ${r.name} 已创建${publishNow ? "并发布" : "（草稿）"}`);
      }
      setOpen(false); setName(""); setDisplayName(""); setDescription(""); setTags(""); setContent(""); setFile(null);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  };

  return (
    <>
      <Btn kind="pri" onClick={() => setOpen(true)} data-testid="skill-create-btn">+ 创建 Skill</Btn>
      {open && (
        <div className="fixed inset-0 z-[70] bg-gray-900/40" onClick={(e) => { if (e.target === e.currentTarget) setOpen(false); }} data-testid="skill-create-drawer">
          <div className="absolute inset-y-0 right-0 w-[520px] max-w-[94vw] overflow-y-auto bg-white shadow-2xl">
            <div className="flex items-center justify-between border-b px-5 py-3.5">
              <b className="text-sm">创建 Skill</b><Btn sm onClick={() => setOpen(false)}>关闭</Btn>
            </div>
            <div className="space-y-3 p-5">
              <div className="mb-1 flex gap-1 rounded-lg bg-gray-100 p-1 text-xs" data-testid="skill-create-mode">
                {([["zip", "上传技能包"], ["markdown", "通过 Markdown 定义"]] as const).map(([k, label]) => (
                  <button key={k} type="button" onClick={() => setMode(k)}
                    className={`flex-1 rounded-md px-3 py-1.5 ${mode === k ? "bg-white font-medium text-blue-600 shadow-sm" : "text-gray-500"}`}>{label}</button>
                ))}
              </div>
              {mode === "zip" ? (
                <div>
                  <label className="mb-1 block text-[12.5px] font-medium">技能包（zip）<em className="not-italic text-red-500">*</em></label>
                  <input type="file" accept=".zip" onChange={(e) => setFile(e.target.files?.[0] ?? null)} data-testid="skill-zip-file"
                    className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]" />
                  <div className="mt-1 text-[11.5px] text-gray-400">支持标准技能包布局：根目录 SKILL.md（可带 _meta.json 与 scripts/、references/、assets/）或 skills/&lt;name&gt;/SKILL.md；技能名取 SKILL.md frontmatter name，缺省用 zip 文件名。≤20MB、≤2000 条目（与 OAF 包上传同护栏）；上传即跑合规检查，warnings 明细回显（宽松不阻断）。</div>
                </div>
              ) : (
                <div>
                  <label className="mb-1 block text-[12.5px] font-medium">Markdown 内容（SKILL.md 正文）<em className="not-italic text-red-500">*</em></label>
                  <textarea rows={8} value={content} onChange={(e) => setContent(e.target.value)} data-testid="skill-md-content"
                    className="w-full rounded-md border border-gray-300 p-2 font-mono text-[11.5px]"
                    placeholder={"---\nname: release-checklist\ndescription: 发布前检查清单\n---\n## 检查项\n1. …"} />
                  <div className="mt-1 text-[11.5px] text-gray-400">frontmatter 的 name/description 将自动解析为下方字段（可改）；正文即技能内容本体</div>
                </div>
              )}
              <div className="grid grid-cols-2 gap-3">
                <div><label className="mb-1 block text-[12.5px] font-medium">技能名称{mode === "markdown" && <em className="not-italic text-red-500">*</em>}</label>
                  <input value={name} onChange={(e) => setName(e.target.value)} data-testid="skill-create-name" placeholder={mode === "zip" ? "缺省取 zip 内目录名" : "lower-case-name"}
                    className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]" />
                  <div className="mt-1 text-[11.5px] text-gray-400">字母/数字/连字符，≤64 字符；空间内唯一</div></div>
                <div><label className="mb-1 block text-[12.5px] font-medium">标签</label>
                  <input value={tags} onChange={(e) => setTags(e.target.value)} placeholder="逗号分隔，展示用" className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]" /></div>
              </div>
              <div><label className="mb-1 block text-[12.5px] font-medium">描述<em className="not-italic text-red-500">*</em></label>
                <textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} data-testid="skill-create-desc"
                  className="w-full rounded-md border border-gray-300 p-2 text-[13px]" placeholder="0/1000；zip 缺省回读 SKILL.md frontmatter" /></div>
              <label className="flex items-center gap-1.5 text-xs">
                <input type="checkbox" checked={publishNow} onChange={(e) => setPublishNow(e.target.checked)} data-testid="skill-create-publish" className="accent-blue-600" />
                保存并发布（v1.0.0 · 已发布；不勾则存为草稿）
              </label>
              {err && <div className="rounded border border-red-200 bg-red-50 p-2 text-xs text-red-600">{err}</div>}
              <div className="flex justify-end gap-2 border-t pt-3">
                <Btn onClick={() => setOpen(false)}>取消</Btn>
                <Btn kind="pri" onClick={submit} disabled={busy} testid="skill-create-submit">{busy ? "提交中…" : publishNow ? "保存并发布" : "存为草稿"}</Btn>
              </div>
              <div className="text-[11px] text-gray-400">一期不接安全护栏审核，保存并发布直接为「已发布」；状态机预留审核槽位（§8.4.1 决议 4）。</div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

// ================= Tab 2 · Skill 广场（多源预留，一期内置源）=================
function MarketTab({ ws, writable, onDone }: { ws: number; writable: boolean; onDone: (msg: string) => void }) {
  const [sources, setSources] = useState<MarketSourceRec[]>([]);
  const [source, setSource] = useState("builtin");
  const [keyword, setKeyword] = useState("");
  const [items, setItems] = useState<MarketItemRec[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");

  useEffect(() => { api.console.skillMarket.sources(ws).then((s) => { setSources(s); if (s.length && !s.some((x) => x.key === source)) setSource(s[0].key); }).catch(() => {}); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [ws]);
  const load = useCallback(async () => {
    try { setItems(await api.console.skillMarket.items(ws, source, keyword)); setErr(""); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }, [ws, source, keyword]);
  useEffect(() => { load(); }, [load]);

  const install = async (it: MarketItemRec) => {
    if (!writable) return;
    try { const s = await api.console.skillMarket.install(ws, it.sourceKey, it.key); onDone(`已安装 ${s.name}（v1.0.0 · 已发布），可在「我的 Skill」查看`); await load(); }
    catch (e) { setErr((e as Error).message); }
  };

  return (
    <div data-testid="skills-market">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <select value={source} onChange={(e) => setSource(e.target.value)} data-testid="skill-market-source"
          title="市场源（多源预留，一期仅内置源）"
          className="rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]">
          {sources.map((s) => <option key={s.key} value={s.key}>{s.name}{s.type !== "builtin" ? "（registry · 远期）" : ""}</option>)}
          {sources.length === 0 && <option value="builtin">平台精选（内置源）</option>}
        </select>
        <input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="搜索市场技能"
          className="w-[200px] rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]" />
        <span className="text-[11.5px] text-gray-400">多源预留：安装语义与源解耦（skill_market_sources 源注册表，§6.3）；registry 外部源待接入真实市场后开放</span>
      </div>
      {err && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{err}</div>}
      {loading ? <p className="text-sm text-gray-400">加载中…</p> : items.length === 0 ? (
        <Card><div className="py-10 text-center text-sm text-gray-400" data-testid="skill-market-empty">该市场源暂无上架技能。</div></Card>
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(250px,1fr))] gap-3" data-testid="skill-market-grid">
          {items.map((it) => (
            <div key={it.id} className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-white p-3.5 hover:border-blue-200" data-testid={`market-item-${it.key}`}>
              <div className="flex items-start gap-2.5">
                <div className="flex size-8 flex-none items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 to-blue-400 text-[13px] font-semibold text-white">{it.key.slice(0, 2)}</div>
                <b className="font-mono text-[13px]">{it.key}</b>
                {it.installedSkillId ? <span className="ml-auto"><Badge status="running">已导入</Badge></span> : null}
              </div>
              <p className="min-h-8 text-xs text-gray-500">{it.description}</p>
              <div className="flex flex-wrap gap-1">
                <Tag>平台精选</Tag>
                {parseTags(it.tagsJson).map((t) => <Tag key={t}>{t}</Tag>)}
                {it.installedVersion && <Tag>本空间 {it.installedVersion}</Tag>}
              </div>
              <div className="mt-auto flex items-center gap-2 border-t pt-2">
                <time className="mr-auto text-[11px] text-gray-400">▾ {it.downloads} 下载</time>
                {writable && !it.installedSkillId && <Btn sm kind="pri" onClick={() => install(it)} data-testid={`market-install-${it.key}`}>导入</Btn>}
              </div>
            </div>
          ))}
        </div>
      )}
      <Note>Skill 广场按<b>市场源</b>聚合（skill_market_sources 源注册表 + skill_market_items 条目，均只读）：点「导入」一键复制为本空间技能资产（v1.0.0 · 已发布，导入后出现在「我的 Skill」可编辑可发版，来源市场名记入技能资产的 sourceName）。<b>多源预留</b>：一期仅内置源（镜像内 zip）；registry 外部源（远端目录 JSON + zip 拉取）只建模型与 API 形参，接入真实市场时单独评审目录协议（认证/条目 schema/zip 校验）。</Note>
    </div>
  );
}

// ================= Tab 3 · Agent 使用情况（最后生成包解析）=================
function UsageTab({ ws }: { ws: number }) {
  const [list, setList] = useState<SkillUsageItem[]>([]);
  const [keyword, setKeyword] = useState("");
  const [onlyUsed, setOnlyUsed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState<SkillUsageDetail | null>(null);
  const [err, setErr] = useState("");

  const load = useCallback(async () => {
    try { setList(await api.console.skillUsages.list(ws, keyword)); setErr(""); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }, [ws, keyword]);
  useEffect(() => { load(); }, [load]);

  const openDetail = async (agentId: number) => {
    try { setDetail(await api.console.skillUsages.detail(ws, agentId)); }
    catch (e) { setErr((e as Error).message); }
  };

  const rows = onlyUsed ? list.filter((r) => r.skillCount > 0) : list;
  return (
    <div data-testid="skills-usage">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="搜索 Agent 名称 / 技能名" data-testid="skill-usage-keyword"
          className="w-[220px] rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]" />
        <label className="flex items-center gap-1.5 text-xs">
          <input type="checkbox" checked={onlyUsed} onChange={(e) => setOnlyUsed(e.target.checked)} className="accent-blue-600" />仅看有技能引用
        </label>
        <span className="ml-auto text-[11.5px] text-gray-400">数据源：每个 Agent <b>最后生成的 OAF 包</b>（当前版本包内 skills/ 目录 + frontmatter skills 声明），请求期实时解析</span>
      </div>
      {err && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{err}</div>}
      {loading ? <p className="text-sm text-gray-400">加载中…</p> : (
        <table className="w-full rounded-xl border border-gray-200 bg-white text-sm" data-testid="skill-usage-table">
          <thead><tr className="border-b bg-gray-50 text-left text-[11px] text-gray-400">
            <th className="px-4 py-2 font-medium">Agent</th><th className="px-4 py-2 font-medium">状态</th>
            <th className="px-4 py-2 font-medium">当前包版本</th><th className="px-4 py-2 font-medium">技能数</th>
            <th className="px-4 py-2 font-medium">技能清单（包内实际内容）</th>
            <th className="px-4 py-2 font-medium">包生成时间</th><th className="px-4 py-2 text-right font-medium">操作</th>
          </tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.agentId} className="border-b last:border-0" data-testid={`usage-row-${r.agentId}`}>
                <td className="px-4 py-2 font-medium">{r.agentName}</td>
                <td className="px-4 py-2"><Badge status={r.status === "running" ? "running" : r.status === "stopped" ? "stopped" : "pending"}>{agentStatusLabel(r.status)}</Badge></td>
                <td className="px-4 py-2"><b>{r.packageVersion || "—"}</b></td>
                <td className="px-4 py-2">{r.skillCount || <span className="text-gray-400">0</span>}</td>
                <td className="max-w-[340px] px-4 py-2">
                  {r.skills.length === 0 ? <span className="text-xs text-gray-400">—（包内无技能）</span> : (
                    <span className="flex flex-wrap gap-1">
                      {r.skills.map((s) => (
                        <span key={s.name} className="rounded bg-gray-100 px-1.5 py-0.5 text-[11px]">
                          <b className="font-mono">{s.name}</b>
                          {(s.declaredVersion || s.matchedVersion) && <span className="text-gray-500"> · {s.declaredVersion || s.matchedVersion}</span>}
                          {!s.declared && <i className="ml-0.5 not-italic text-gray-400">(仅目录)</i>}
                          {!s.matchedSkillId && s.declared && <i className="ml-0.5 not-italic text-gray-400">(包内自带)</i>}
                        </span>
                      ))}
                    </span>
                  )}
                </td>
                <td className="px-4 py-2 text-xs text-gray-400">{r.packageGeneratedAt ? new Date(r.packageGeneratedAt).toLocaleString("zh-CN", { hour12: false }) : "—"}</td>
                <td className="px-4 py-2"><div className="flex justify-end gap-1.5">
                  <Btn sm kind="pri" onClick={() => openDetail(r.agentId)} data-testid={`usage-detail-${r.agentId}`}>查看详情</Btn>
                  <a href={`/console/agents/${r.agentId}/edit`} className="rounded border border-gray-300 px-2 py-0.5 text-[11px] text-gray-600 hover:border-blue-200 hover:text-blue-600">换绑版本</a>
                </div></td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={7} className="p-8 text-center text-sm text-gray-400">空间内暂无 Agent。</td></tr>}
          </tbody>
        </table>
      )}
      {detail && <UsageDetailDrawer detail={detail} onClose={() => setDetail(null)} />}
      <Note>口径：本 Tab 按 <b>services.package_id 当前指向的最后生成包</b>实时解析（skills/ 目录扫描 = 事实来源，frontmatter skills 声明 = 意图与版本），与「沙箱配置」总览同构（无新表、无后台任务）。角标说明：<b>仅声明</b> = frontmatter 声明了但包内目录缺失（不合规，SpaceSkills issues 同口径）；<b>仅目录</b> = 目录存在但未声明；<b>包内自带</b> = 导入/存量包的技能，无对应技能资产（matchedSkillId 为空）。技能中心发版不影响已生成包——换绑需经编辑页重新勾选生成新版本。</Note>
    </div>
  );
}

// ================= 单 Agent 使用详情抽屉 =================
function UsageDetailDrawer({ detail, onClose }: { detail: SkillUsageDetail; onClose: () => void }) {
  const { agent, items } = detail;
  return (
    <div className="fixed inset-0 z-[70] bg-gray-900/40" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }} data-testid="usage-detail-drawer">
      <div className="absolute inset-y-0 right-0 w-[620px] max-w-[94vw] overflow-y-auto bg-white shadow-2xl">
        <div className="flex items-center justify-between border-b px-5 py-3.5">
          <b className="text-sm">Agent 技能使用详情 · {agent.agentName}</b>
          <Btn sm onClick={onClose}>关闭</Btn>
        </div>
        <div className="space-y-4 p-5">
          <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-xs">
            <div><dt className="text-gray-400">运行状态</dt><dd><Badge status={agent.status === "running" ? "running" : "pending"}>{agentStatusLabel(agent.status)}</Badge></dd></div>
            <div><dt className="text-gray-400">当前包</dt><dd><b>{agent.packageVersion}</b></dd></div>
            <div><dt className="text-gray-400">包生成时间</dt><dd>{agent.packageGeneratedAt ? new Date(agent.packageGeneratedAt).toLocaleString("zh-CN", { hour12: false }) : "—"}</dd></div>
            <div><dt className="text-gray-400">数据来源</dt><dd className="text-gray-600">最后生成的 OAF 包实时解析（skills/ 目录扫描 + frontmatter skills 声明）</dd></div>
          </dl>
          <div>
            <div className="mb-1.5 text-xs text-gray-400">当前包内技能完整清单</div>
            <table className="w-full rounded-lg border border-gray-200 text-xs" data-testid="usage-detail-table">
              <thead><tr className="border-b bg-gray-50 text-left text-[11px] text-gray-400">
                <th className="p-2 font-medium">技能</th><th className="p-2 font-medium">包内路径</th>
                <th className="p-2 font-medium">SKILL.md 描述</th><th className="p-2 font-medium">声明</th>
                <th className="p-2 font-medium">匹配技能资产</th><th className="p-2 font-medium">合规</th>
              </tr></thead>
              <tbody>
                {items.map((it) => (
                  <tr key={it.name} className="border-b last:border-0">
                    <td className="p-2 font-mono font-medium">{it.name}</td>
                    <td className="p-2 font-mono text-[11px] text-gray-500">{it.packagePath}</td>
                    <td className="max-w-[160px] p-2 text-gray-600">{it.description || <span className="text-gray-300">—</span>}</td>
                    <td className="p-2">{it.declared ? <Badge status="running">frontmatter 声明{it.declaredVersion ? ` · ${it.declaredVersion}` : ""}</Badge> : <Badge status="stopped">仅目录（未声明）</Badge>}</td>
                    <td className="p-2">{it.matchedSkillId ? <Tag>{it.name}{it.matchedVersion ? ` ${it.matchedVersion}` : ""}{it.matchedSource ? ` · ${sourceLabel(it.matchedSource)}` : ""}</Tag> : <span className="text-gray-400">包内自带</span>}</td>
                    <td className="p-2">{it.issues.length === 0 ? <Badge status="running">通过</Badge> : <span title={it.issues.join("；")}><Badge status="pending">不合规 {it.issues.length}</Badge></span>}</td>
                  </tr>
                ))}
                {items.length === 0 && <tr><td colSpan={6} className="p-4 text-center text-gray-400">当前包内无技能。</td></tr>}
              </tbody>
            </table>
            <div className="mt-2 text-[11px] text-gray-400">合规列与 SpaceSkills issues 同口径（skillcheck.go：缺 SKILL.md / 无 frontmatter / name 不一致 / 非法名称等）；「仅目录」= 目录存在但 frontmatter 未声明——运行时按目录加载不受影响，建议补声明。</div>
          </div>
          <div className="rounded-lg bg-gray-50 p-3 text-xs text-gray-600">
            包内快照 = 生成时复制的技能版本目录，<b>技能中心后续发版不影响本包</b>；版本迁移（如 1.2.0 → 新版本）经编辑页资产配置区重新勾选。
          </div>
          <div className="flex justify-end gap-2 border-t pt-3">
            <a href={`/console/agents/${agent.agentId}`} className="rounded border border-gray-300 px-2.5 py-1 text-xs text-gray-600 hover:border-blue-200 hover:text-blue-600">打开 Agent 详情</a>
            <a href={`/console/agents/${agent.agentId}/edit`} className="rounded bg-blue-600 px-2.5 py-1 text-xs text-white hover:bg-blue-700">换绑技能版本</a>
          </div>
        </div>
      </div>
    </div>
  );
}
