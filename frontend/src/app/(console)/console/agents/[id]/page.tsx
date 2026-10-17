"use client";
// 用户侧 · Agent 详情六 Tab（M4 §3.1，demo c/detail 还原）：
// 概览（kv + Pod 实时表 + 事件时间线）/ 调试（ChatPane 复用，agentBase 直连业务运行时）/
// 会话（threads 前端直取，按 userId 隔离）/ 技能（包级只读 + L4）/ 版本（copy-on-write 链 + 升级）/
// 调用凭证（API Key 作用域过滤 + curl 示例）。tab 经 URL query 同步（刷新保位）。
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { api, type ApiKeyView, type ServiceDetail, type PackageRec } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole, useMe } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead, Tag } from "@/components/ui";
import ChatPane from "@/components/chat/ChatPane";

const TABS = [
  { id: "overview", label: "概览" },
  { id: "debug", label: "调试" },
  { id: "threads", label: "会话" },
  { id: "skills", label: "技能" },
  { id: "versions", label: "版本" },
  { id: "credentials", label: "调用凭证" },
];

export default function AgentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const sid = Number(id);
  const ws = useConsoleWs();
  const wsRole = useConsoleWsRole();
  const { me } = useMe();
  const router = useRouter();
  const params = useSearchParams();
  const tab = params.get("tab") ?? "overview";
  const [detail, setDetail] = useState<ServiceDetail | null>(null);
  const [msg, setMsg] = useState("");
  const writable = wsRole === "owner" || wsRole === "admin";

  const load = useCallback(async () => {
    try { setDetail(await api.console.agentDetail(ws, sid)); setMsg(""); }
    catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, [ws, sid]);
  // 非法 ID（NaN/0/负数）直接进错误态（issue #152：曾既不请求也不置错误，永久「加载中…」）
  const invalidId = !Number.isInteger(sid) || sid <= 0;
  useEffect(() => {
    if (invalidId) { setMsg("Agent 不存在：非法 ID"); return; }
    if (ws) load();
  }, [ws, sid, load, invalidId]);

  const act = async (action: "publish" | "unpublish" | "republish" | "register", okMsg: string) => {
    try {
      await api.console.agentAction(ws, sid, action);
      setMsg(okMsg);
      await load();
    } catch (e: any) { setMsg(`操作失败: ${e.message}`); }
  };
  const remove = async () => {
    if (!confirm(`确认删除 Agent「${detail?.displayName}」？将删除全部 K8s 资源与记录。`)) return;
    try { await api.console.deleteAgent(ws, sid); router.push("/console/agents"); }
    catch (e: any) { setMsg(`删除失败: ${e.message}`); }
  };

  if (invalidId && !detail) return (
    <div data-testid="agent-detail-invalid" className="rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">
      Agent 不存在：非法 ID（{String(id)}）
    </div>
  );
  if (msg && !detail) return <div className="rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>;
  if (!detail) return <p className="text-sm text-gray-400">加载中…</p>;

  const model = (() => { try { return JSON.parse(detail.envJson || "{}")["LLM_MODEL"] || "—"; } catch { return "—"; } })();

  return (
    <div data-testid="agent-detail">
      <PageHead
        title={<>
          <span className="flex size-[30px] items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 to-blue-400 text-[13px] font-semibold text-white">
            {(detail.displayName || "?").slice(0, 1)}
          </span>
          {detail.displayName}
          <Badge status={detail.status} />
          <Tag>{detail.packageId ? `包 #${detail.packageId}` : ""}</Tag>
        </>}
        sub={<span className="font-mono">
          Endpoint：{detail.endpoint || "（仅集群内）"}
          {detail.endpoint && (
            <button className="ml-2 text-blue-600 hover:underline"
              onClick={() => navigator.clipboard?.writeText(detail.endpoint)}>复制</button>
          )}
        </span>}
        actions={<>
          {writable && <Link href={`/console/agents/${sid}/edit`} data-testid="detail-edit"
            className="inline-flex items-center gap-1.5 rounded-md border border-blue-600 bg-blue-600 px-3 py-1.5 text-[13px] text-white hover:bg-blue-700">编辑</Link>}
          {writable && (detail.status === "stopped" || detail.status === "error" || detail.status === "deploy_failed" || detail.status === "register_failed"
            ? <Btn onClick={() => act("publish", "上线中")}>上线</Btn>
            : <Btn onClick={() => act("republish", "重发布中")}>重发布</Btn>)}
          {writable && detail.status !== "stopped" && <Btn kind="danger" onClick={() => act("unpublish", "已下线")}>下线</Btn>}
          {writable && <Btn kind="danger" onClick={remove}>删除</Btn>}
        </>} />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}

      <div className="mb-4 flex gap-0.5 overflow-x-auto border-b border-gray-200" data-testid="detail-tabs">
        {TABS.map((t) => (
          <Link key={t.id} href={`/console/agents/${sid}?tab=${t.id}`} data-testid={`tab-${t.id}`}
            className={`whitespace-nowrap border-b-2 px-3.5 py-2 text-[13px] ${tab === t.id ? "border-blue-600 font-medium text-blue-600" : "border-transparent text-gray-500 hover:text-gray-900"}`}>
            {t.label}
          </Link>
        ))}
      </div>

      {tab === "overview" && <OverviewTab detail={detail} model={model} ws={ws} />}
      {tab === "debug" && (
        <>
          <Note>调试流量直连业务运行时（SSE，不经控制面后端）；身份以平台用户注入（X-User-Id: u-{me?.user.id}），会话与记忆按用户隔离。</Note>
          <ChatPane agentBase={`/agent/${detail.shortName}`} persistKey={`oaf-debug-sid-${detail.shortName}`}
            title="调试对话" subtitle={`直连 ${detail.shortName} · durable SSE`}
            userId={`u-${me?.user.id ?? 0}`} testId="debug-pane" />
        </>
      )}
      {tab === "threads" && <ThreadsTab shortName={detail.shortName || ""} uid={`u-${me?.user.id ?? 0}`} />}
      {tab === "skills" && <SkillsTab detail={detail} shortName={detail.shortName || ""} />}
      {tab === "versions" && <VersionsTab ws={ws} sid={sid} detail={detail} writable={writable} onReload={load} />}
      {tab === "credentials" && <CredentialsTab ws={ws} sid={sid} detail={detail} writable={writable} />}
    </div>
  );
}

function OverviewTab({ detail, model, ws }: { detail: ServiceDetail; model: string; ws: number }) {
  return (
    <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-[1.6fr_1fr]">
      <div className="flex flex-col gap-3.5">
        <Card title="基本信息">
          <dl className="grid grid-cols-1 gap-2.5 text-[13px] md:grid-cols-2">
            <Kv k="状态" v={<Badge status={detail.status} />} />
            <Kv k="模型" v={model} />
            <Kv k="规格 / 副本" v={`${detail.spec || "平台默认"} · ${detail.replicas} 副本`} />
            <Kv k="运行镜像" v={<span className="break-all font-mono text-xs">{detail.image}</span>} />
            <Kv k="对外入口" v={detail.endpoint ? <span className="break-all font-mono text-xs">{detail.endpoint}</span> : "仅集群内"} />
            <Kv k="A2A 注册" v={detail.registeredName ? `已注册 · ${detail.registeredName}@${detail.registeredVersion}` : "未注册"} />
          </dl>
        </Card>
        <Card title="实时 Pod（5s 轮询）">
          <PodTable ws={ws} id={detail.id} />
        </Card>
      </div>
      <Card title="事件时间线">
        <ul className="pl-1.5">
          {detail.events.map((e) => (
            <li key={e.id} className="relative border-l border-gray-200 pb-3.5 pl-5">
              <span className={`absolute -left-[3.5px] top-1.5 size-1.5 rounded-full ${["running", "deploying", "created"].includes(e.toStatus) ? "bg-emerald-500" : e.toStatus === "stopped" ? "bg-gray-300" : "bg-red-500"}`} />
              <span className="text-[13px]">{e.reason || `${e.fromStatus} → ${e.toStatus}`}</span>
              <small className="block text-[11px] text-gray-400">{new Date(e.createdAt).toLocaleString("zh-CN", { hour12: false })}</small>
            </li>
          ))}
          {detail.events.length === 0 && <p className="text-sm text-gray-400">暂无事件</p>}
        </ul>
      </Card>
    </div>
  );
}

function Kv({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div>
      <dt className="text-[11.5px] text-gray-400">{k}</dt>
      <dd className="mt-0.5 break-all">{v}</dd>
    </div>
  );
}

function PodTable({ ws, id }: { ws: number; id: number }) {
  const [pods, setPods] = useState<{ name: string; phase: string; ready: boolean; restarts: number }[]>([]);
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const d = await api.console.agentDetail(ws, id);
        if (alive) setPods(d.pods ?? []);
      } catch { /* 轮询失败静默 */ }
    };
    tick();
    const t = setInterval(tick, 5000);
    return () => { alive = false; clearInterval(t); };
  }, [ws, id]);
  if (pods.length === 0) return <p className="text-sm text-gray-400">暂无 Pod（未部署或已下线）</p>;
  return (
    <div className="overflow-x-auto">
    <table className="w-full text-sm">
      <thead><tr className="border-b text-left text-xs text-gray-500"><th className="p-2">Pod</th><th className="p-2">状态</th><th className="p-2">重启</th><th className="p-2">就绪</th></tr></thead>
      <tbody>
        {pods.map((p) => (
          <tr key={p.name} className="border-b last:border-0">
            <td className="p-2 font-mono text-xs">{p.name}</td>
            <td className="p-2"><Badge status={p.phase === "Running" ? "running" : "error"}>{p.phase}</Badge></td>
            <td className="p-2">{p.restarts}</td>
            <td className="p-2">{p.ready ? "1/1" : "0/1"}</td>
          </tr>
        ))}
      </tbody>
    </table>
    </div>
  );
}

type ThreadRow = { session_id: string; title?: string; user_id?: string; model?: string; message_count?: number; updated_at?: string };

function ThreadsTab({ shortName, uid }: { shortName: string; uid: string }) {
  const [rows, setRows] = useState<ThreadRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [replayTo, setReplayTo] = useState("");
  const load = useCallback(async () => {
    try {
      // 前端直取业务面（不经 backend；proxy 注入 X-User-Id，按用户隔离）
      const resp = await fetch(`/agent/${shortName}/threads?userId=${encodeURIComponent(uid)}`);
      if (resp.ok) {
        const list = await resp.json();
        setRows(Array.isArray(list) ? list : []);
      }
    } catch { /* 业务面不可达静默 */ }
    setLoading(false);
  }, [shortName, uid]);
  useEffect(() => { load(); }, [load]);

  const removeThread = async (sid: string) => {
    if (!confirm(`确认删除会话 ${sid}？（业务面真删）`)) return;
    await fetch(`/agent/${shortName}/threads/${encodeURIComponent(sid)}`, { method: "DELETE" }).catch(() => {});
    await load();
  };

  if (replayTo) {
    return (
      <>
        <Btn onClick={() => setReplayTo("")}>返回会话列表</Btn>
        <div className="mt-3">
          <ChatPane agentBase={`/agent/${shortName}`} persistKey={`oaf-debug-sid-${shortName}`}
            title="会话回放" subtitle={`回放 ${replayTo}`}
            userId={uid} testId="replay-pane" key={replayTo} />
        </div>
      </>
    );
  }
  return (
    <>
      <div className="overflow-x-auto">
      <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="threads-table">
        <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
          <th className="p-2.5">会话</th><th className="p-2.5">标题</th><th className="p-2.5">用户</th>
          <th className="p-2.5">模型</th><th className="p-2.5">消息数</th><th className="p-2.5">更新时间</th><th className="p-2.5 text-right">操作</th>
        </tr></thead>
        <tbody>
          {rows.map((t) => (
            <tr key={t.session_id} className="border-b last:border-0 hover:bg-gray-50">
              <td className="p-2.5 font-mono text-xs">{t.session_id.slice(0, 24)}{t.session_id.length > 24 ? "…" : ""}</td>
              <td className="p-2.5">{t.title || "—"}</td>
              <td className="p-2.5 font-mono text-xs">{t.user_id || uid}</td>
              <td className="p-2.5 text-xs">{t.model || "system"}</td>
              <td className="p-2.5">{t.message_count ?? "—"}</td>
              <td className="p-2.5 text-xs text-gray-500">{(t.updated_at ?? "").replace("T", " ").slice(0, 19)}</td>
              <td className="p-2.5 text-right">
                <span className="inline-flex gap-1">
                  <Btn sm onClick={() => setReplayTo(t.session_id)}>回放</Btn>
                  <Btn sm kind="danger" onClick={() => removeThread(t.session_id)}>删除</Btn>
                </span>
              </td>
            </tr>
          ))}
          {rows.length === 0 && !loading && <tr><td colSpan={7} className="p-6 text-center text-sm text-gray-400">当前用户暂无会话（去「调试」发一句话即可建立）</td></tr>}
        </tbody>
      </table>
      </div>
      <div className="mt-2.5 text-xs text-gray-400">会话数据存于服务 checkpoint 库与 Redis Streams，跨副本可续传；此处为按用户过滤视图。</div>
    </>
  );
}

function SkillsTab({ detail, shortName }: { detail: ServiceDetail; shortName: string }) {
  const [l4, setL4] = useState<{ name: string; description?: string }[]>([]);
  useEffect(() => {
    fetch(`/agent/${shortName}/skills/users`)
      .then((r) => (r.ok ? r.json() : []))
      .then((d) => setL4(Array.isArray(d) ? d : d?.skills ?? []))
      .catch(() => {});
  }, [shortName]);
  const pkgSkills = (() => {
    try { return JSON.parse(detail.skillsJson || "[]"); } catch { return []; }
  })();
  const removeL4 = async (name: string) => {
    if (!confirm(`确认删除用户技能 ${name}？`)) return;
    await fetch(`/agent/${shortName}/skills/users/${encodeURIComponent(name)}`, { method: "DELETE" }).catch(() => {});
    setL4((cur) => cur.filter((s) => s.name !== name));
  };
  return (
    <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
      <Card title="包级技能（L2/L3 · 随包只读）">
        {(pkgSkills.length ?? 0) === 0 ? <p className="text-sm text-gray-400">当前包未声明技能</p> : (
          <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead><tr className="border-b text-left text-xs text-gray-500"><th className="p-2">技能</th><th className="p-2">来源</th><th className="p-2">状态</th></tr></thead>
            <tbody>
              {pkgSkills.map((s: any, i: number) => (
                <tr key={i} className="border-b last:border-0">
                  <td className="p-2 font-medium">{s.name}</td>
                  <td className="p-2 font-mono text-xs text-gray-500">skills/{s.name}</td>
                  <td className="p-2"><Badge status="running">启用</Badge></td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </Card>
      <Card title="用户级技能（L4 · 当前登录用户的技能集）">
        <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead><tr className="border-b text-left text-xs text-gray-500"><th className="p-2">技能</th><th className="p-2">描述</th><th className="p-2 text-right">操作</th></tr></thead>
          <tbody>
            {l4.map((s) => (
              <tr key={s.name} className="border-b last:border-0">
                <td className="p-2 font-medium">{s.name}</td>
                <td className="p-2 max-w-[220px] truncate text-xs text-gray-500">{s.description || "—"}</td>
                <td className="p-2 text-right"><Btn sm kind="danger" onClick={() => removeL4(s.name)}>删除</Btn></td>
              </tr>
            ))}
            {l4.length === 0 && <tr><td colSpan={3} className="p-4 text-xs text-gray-400">暂无用户级技能（上传入口见「资产管理 → Skill 管理」，L4 按用户隔离）</td></tr>}
          </tbody>
        </table>
        </div>
      </Card>
    </div>
  );
}

function VersionsTab({ ws, sid, detail, writable, onReload }: {
  ws: number; sid: number; detail: ServiceDetail; writable: boolean; onReload: () => void;
}) {
  const [versions, setVersions] = useState<(PackageRec & { current: boolean })[]>([]);
  const [msg, setMsg] = useState("");
  const load = useCallback(async () => {
    try { setVersions(await api.console.agentVersions(ws, sid)); } catch (e: any) { setMsg(e.message); }
  }, [ws, sid]);
  useEffect(() => { load(); }, [load]);

  const upgrade = async (pkgId: number) => {
    if (!confirm(`升级到包版本 #${pkgId}（= republish 到该版本，Endpoint 不变）？`)) return;
    try { await api.console.upgradeVersion(ws, sid, pkgId); setMsg("已发起升级（republish）"); await load(); onReload(); }
    catch (e: any) { setMsg(`升级失败: ${e.message}`); }
  };

  return (
    <>
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <div className="overflow-x-auto">
      <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="versions-table">
        <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
          <th className="p-2.5">包版本</th><th className="p-2.5">包 ID</th><th className="p-2.5">派生自</th>
          <th className="p-2.5">发布状态</th><th className="p-2.5 text-right">操作</th>
        </tr></thead>
        <tbody>
          {versions.map((v) => (
            <tr key={v.id} className="border-b last:border-0 hover:bg-gray-50">
              <td className="p-2.5">
                <b>v{v.version}</b>
                {v.current && <Tag>当前</Tag>}
              </td>
              <td className="p-2.5 font-mono text-xs">#{v.id}</td>
              <td className="p-2.5 text-xs">{v.sourcePackageId ? `#${v.sourcePackageId}` : "—（初始）"}</td>
              <td className="p-2.5">{v.current ? <Badge status={detail.status} /> : <span className="text-xs text-gray-400">—</span>}</td>
              <td className="p-2.5 text-right">
                <span className="inline-flex gap-1">
                  {writable && v.current && (
                    <Link href={`/console/agents/${sid}/edit`}
                      className="rounded border border-blue-600 bg-blue-600 px-2 py-1 text-xs text-white hover:bg-blue-700">编辑</Link>
                  )}
                  {writable && !v.current && <Btn sm onClick={() => upgrade(v.id)}>升级到此版本</Btn>}
                  {writable && (
                    <Link href={`/admin/packages/${v.id}`} className="rounded border border-gray-300 px-2 py-1 text-xs text-gray-600 hover:border-blue-200">包编辑器</Link>
                  )}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
      <div className="mt-2.5 text-xs text-gray-400">
        编辑 = 表单级 copy-on-write 派生新版本 + republish（提示词/Skill/SubAgent/MCP 热生效；镜像/规格/副本滚动重启）；包编辑器为文件级高级入口，两者共用版本链。
      </div>
    </>
  );
}

function CredentialsTab({ ws, sid, detail, writable }: { ws: number; sid: number; detail: ServiceDetail; writable: boolean }) {
  const [keys, setKeys] = useState<ApiKeyView[]>([]);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [days, setDays] = useState(90);
  const [plaintext, setPlaintext] = useState("");
  const [msg, setMsg] = useState("");

  const load = useCallback(async () => {
    try { setKeys(await api.console.apiKeys.list(ws, sid)); } catch (e: any) { setMsg(e.message); }
  }, [ws, sid]);
  useEffect(() => { load(); }, [load]);

  const issue = async () => {
    try {
      const out = await api.console.apiKeys.create(ws, { name, agentId: sid, expiresInDays: days });
      setPlaintext(out.plaintext);
      setCreating(false);
      setName("");
      await load();
    } catch (e: any) { setMsg(e.message); }
  };
  const revoke = async (id: number) => {
    if (!confirm("确认吊销该 Key？（即时生效——吊销后不可恢复）")) return;
    try { await api.console.apiKeys.revoke(ws, id); await load(); } catch (e: any) { setMsg(e.message); }
  };

  return (
    <>
      <Note tone="warn">一期凭证仅用于身份标识与控制台内调用记账；对外数据面网关鉴权（auth_request + API Key 校验）在二期生效（设计 §5.4）。</Note>
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      {writable && <div className="mb-3"><Btn kind="pri" onClick={() => setCreating(true)}>+ 签发 API Key（作用域：本 Agent）</Btn></div>}
      <div className="overflow-x-auto">
      <table className="w-full rounded-lg border border-gray-200 bg-white text-sm" data-testid="cred-table">
        <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
          <th className="p-2.5">名称</th><th className="p-2.5">Key</th><th className="p-2.5">状态</th>
          <th className="p-2.5">过期时间</th><th className="p-2.5 text-right">操作</th>
        </tr></thead>
        <tbody>
          {keys.map((k) => (
            <tr key={k.id} className="border-b last:border-0 hover:bg-gray-50">
              <td className="p-2.5 font-medium">{k.name}</td>
              <td className="p-2.5 font-mono text-xs">{k.keyPrefix}****（仅创建时可见明文）</td>
              <td className="p-2.5"><Badge status={k.status === "active" ? "running" : "stopped"}>{k.status === "active" ? "启用" : k.status === "revoked" ? "已吊销" : "已过期"}</Badge></td>
              <td className="p-2.5 text-xs">{k.expiresAt ? new Date(k.expiresAt).toLocaleDateString() : "永不过期"}</td>
              <td className="p-2.5 text-right">{k.status === "active" && <Btn sm kind="danger" onClick={() => revoke(k.id)}>吊销</Btn>}</td>
            </tr>
          ))}
          {keys.length === 0 && <tr><td colSpan={5} className="p-6 text-center text-sm text-gray-400">本 Agent 暂无调用凭证</td></tr>}
        </tbody>
      </table>
      </div>
      <div className="mt-3.5">
        <div className="mb-1.5 text-xs text-gray-400">调用示例（A2A JSON-RPC · 二期网关生效后需携带 Bearer）</div>
        <pre className="overflow-x-auto rounded-lg bg-[#0d1526] p-3 font-mono text-[11.5px] leading-6 text-[#c9d6f2]">
{`curl -N -X POST ${detail.endpoint || "http://{INGRESS_HOST}/agent/" + detail.shortName + "/"} \\
  -H "Authorization: Bearer ${keys[0]?.keyPrefix ?? "ak-..."}..." -H "Content-Type: application/json" \\
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"kind":"text","text":"查下工单8821"}]}},"id":1}'`}
        </pre>
      </div>

      {creating && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4">
          <Card title="签发 API Key（作用域：本 Agent）" className="w-[380px]">
            <label className="mb-1 block text-xs font-medium">名称 *</label>
            <input value={name} onChange={(e) => setName(e.target.value)} className="mb-3 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <label className="mb-1 block text-xs font-medium">有效期（天，0 = 永不过期）</label>
            <input type="number" min={0} value={days} onChange={(e) => setDays(Number(e.target.value))}
              className="mb-4 w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            <div className="flex justify-end gap-2">
              <Btn onClick={() => setCreating(false)}>取消</Btn>
              <Btn kind="pri" disabled={!name.trim()} onClick={issue}>签发</Btn>
            </div>
          </Card>
        </div>
      )}
      {plaintext && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <Card title="凭证已签发 · 明文仅此一次展示" className="w-[460px]">
            <div className="mb-2 break-all rounded-md bg-gray-900 p-3 font-mono text-xs text-emerald-300">{plaintext}</div>
            <Note tone="warn">关闭后不再可见，请立即复制保存。</Note>
            <div className="flex justify-end gap-2">
              <Btn onClick={() => navigator.clipboard?.writeText(plaintext)}>复制</Btn>
              <Btn kind="pri" onClick={() => setPlaintext("")}>我已保存，关闭</Btn>
            </div>
          </Card>
        </div>
      )}
    </>
  );
}
