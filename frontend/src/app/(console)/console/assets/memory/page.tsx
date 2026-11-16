"use client";
// 用户侧 · 记忆管理（openviking-memory-integration-design §11.1，与沙箱管理平级）：
// 空间内 Agent×供给状态总览（只读）+ 选定 Agent 的凭证自助签发 + 本人记忆空间浏览
// （viking://~/ 经 backend 只读代理；编辑/删除在 OpenViking Studio 完成）。
import { useCallback, useEffect, useState } from "react";
import { api, type MemorySupplyItem, type VikingFsEntry } from "@/lib/api";
import { useConsoleWs, useMe } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead } from "@/components/ui";

export default function MemoryPage() {
  const ws = useConsoleWs();
  const { me } = useMe();
  const [rows, setRows] = useState<MemorySupplyItem[]>([]);
  const [keyword, setKeyword] = useState("");
  const [selected, setSelected] = useState<number>(0);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    if (!ws) return;
    setLoading(true);
    try { setRows(await api.console.memorySupply(ws, keyword)); } catch { setRows([]); }
    setLoading(false);
  }, [ws, keyword]);
  useEffect(() => { load(); }, [load]);

  const sel = rows.find((r) => r.serviceId === selected) || null;
  const ovRows = rows.filter((r) => r.provider === "openviking");
  return (
    <div data-testid="console-memory">
      <PageHead title="记忆管理" sub="空间内 Agent 的 OpenViking 记忆供给状态总览（只读）；供给模式切换由管理员在服务环境变量中修改"
        actions={
          <input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="搜索 Agent"
            className="rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]" />
        } />

      <div className="mb-4 overflow-x-auto rounded-lg border border-gray-200 bg-white" data-testid="memory-supply-table">
        <table className="w-full text-sm">
          <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
            <th className="p-2.5">Agent</th><th className="p-2.5">供给模式</th><th className="p-2.5">会话归档</th>
            <th className="p-2.5">知识导入</th><th className="p-2.5">共享检索</th><th className="p-2.5">供给状态</th>
            <th className="p-2.5 text-right">操作</th>
          </tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.serviceId} className={`border-b last:border-0 hover:bg-gray-50 ${selected === r.serviceId ? "bg-blue-50/60" : ""}`}>
                <td className="p-2.5 font-medium">{r.displayName}</td>
                <td className="p-2.5">{r.provider === "openviking"
                  ? <Badge status="running">OpenViking</Badge>
                  : <Badge status="stopped">本地文件记忆</Badge>}</td>
                <td className="p-2.5 text-xs">{r.provider === "openviking" ? (r.sessionCommit ? "会话归档" : "逐轮提取") : "—"}</td>
                <td className="p-2.5 text-xs">{r.provider === "openviking" ? (r.knowledgeImport ? "已导入" : "未导入") : "—"}</td>
                <td className="p-2.5 text-xs">{r.provider === "openviking" ? (r.mcpSharedCtx ? "已注入" : "未注入") : "—"}</td>
                <td className="p-2.5">{r.provider === "openviking"
                  ? <Badge status={r.provisioned ? "running" : "stopped"}>{r.provisioned ? "已供给" : "未供给"}</Badge>
                  : "—"}</td>
                <td className="p-2.5 text-right">
                  {r.provider === "openviking" && (
                    <button onClick={() => setSelected(r.serviceId)}
                      className="text-xs px-2 py-1 border rounded hover:bg-gray-100">管理</button>
                  )}
                </td>
              </tr>
            ))}
            {rows.length === 0 && !loading && <tr><td colSpan={7} className="p-6 text-center text-sm text-gray-400">空间内暂无 Agent</td></tr>}
          </tbody>
        </table>
      </div>

      {sel && sel.provider === "openviking" && (
        <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
          <MemoryCredentialCard ws={ws} item={sel} />
          <MemoryBrowserCard ws={ws} item={sel} uid={me?.user.id ?? 0} />
        </div>
      )}
      {!sel && ovRows.length > 0 && (
        <Note tone="info">选择一个 OpenViking 托管的 Agent（上表点「管理」）进行凭证签发与记忆浏览。</Note>
      )}
    </div>
  );
}

// 我的记忆访问凭证：自助签发（userId 固定当前登录人）+ 一次性明文弹窗（同 credentials 模式）。
function MemoryCredentialCard({ ws, item }: { ws: number; item: MemorySupplyItem }) {
  const [issuing, setIssuing] = useState(false);
  const [issued, setIssued] = useState<{ userKey: string; openVikingUrl: string } | null>(null);
  const [msg, setMsg] = useState("");

  const issue = async () => {
    setIssuing(true); setMsg("");
    try {
      const out = await api.console.issueMemoryKey(ws, item.serviceId);
      setIssued({ userKey: out.userKey, openVikingUrl: out.openVikingUrl });
    } catch (e: any) { setMsg(e.message); }
    setIssuing(false);
  };

  return (
    <Card title={`我的记忆访问凭证 · ${item.displayName}`}>
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <Note tone="info">Key 的身份 = 你在该 Agent 对话中的身份（u-&#123;你的用户 ID&#125;），仅能访问自己的记忆空间；
      同一身份重复签发得到相同 Key；管理员轮换后旧 Key 失效。明文仅签发时展示一次。</Note>
      <div className="flex items-center gap-2">
        <Btn kind="pri" data-testid="memory-page-issue-btn" disabled={issuing} onClick={issue}>
          {issuing ? "签发中…" : "签发我的记忆访问 Key"}
        </Btn>
        {item.openVikingUrl && (
          <span className="text-xs text-gray-400">Studio 地址：<code className="font-mono">{item.openVikingUrl}</code></span>
        )}
      </div>
      {issued && (
        <div className="mt-3 rounded border p-3 bg-gray-50" data-testid="memory-page-key-dialog">
          <div className="mb-2 break-all rounded-md bg-gray-900 p-3 font-mono text-xs text-emerald-300">{issued.userKey}</div>
          <Note tone="warn">关闭后不再可见，请立即复制保存。</Note>
          <div className="flex gap-2 mt-2">
            <Btn onClick={() => navigator.clipboard?.writeText(issued.userKey)}>复制</Btn>
            <Btn kind="pri" onClick={() => setIssued(null)}>我已保存，关闭</Btn>
          </div>
        </div>
      )}
    </Card>
  );
}

// 本人记忆空间浏览（只读代理 viking://~/memories）：目录列表 + 文本内容查看。
function MemoryBrowserCard({ ws, item }: { ws: number; item: MemorySupplyItem; uid: number }) {
  const [entries, setEntries] = useState<VikingFsEntry[]>([]);
  const [content, setContent] = useState("");
  const [reading, setReading] = useState("");
  const [path, setPath] = useState("viking://~/memories");
  const [msg, setMsg] = useState("");

  const list = useCallback(async (uri: string) => {
    setMsg(""); setContent(""); setReading("");
    try {
      const raw = await api.console.memoryFiles(ws, item.serviceId, "list", uri);
      const result = (raw as any)?.result ?? raw ?? [];
      setEntries(Array.isArray(result) ? result : []);
      setPath(uri);
    } catch (e: any) { setMsg(e.message); setEntries([]); }
  }, [ws, item.serviceId]);

  useEffect(() => { list("viking://~/memories"); }, [list]);

  const read = async (uri: string) => {
    setMsg(""); setReading(uri);
    try {
      const raw = await api.console.memoryFiles(ws, item.serviceId, "read", uri);
      const text = (raw as any)?.result ?? "";
      setContent(typeof text === "string" ? text : JSON.stringify(text, null, 2));
    } catch (e: any) { setMsg(e.message); setContent(""); }
  };

  const crumb = path.replace("viking://~/memories", "").replace(/^\//, "");
  return (
    <Card title={`我的记忆空间 · ${item.displayName}`}>
      <Note tone="info">只读浏览 viking://~/memories（编辑与删除请在 OpenViking Studio 完成）。{msg && <span className="text-amber-700"> {msg}</span>}</Note>
      <div className="mb-2 flex items-center gap-2 text-xs">
        <Btn sm onClick={() => list("viking://~/memories")}>根目录</Btn>
        {path !== "viking://~/memories" && (
          <Btn sm onClick={() => list(path.split("/").slice(0, -1).join("/") || "viking://~/memories")}>上一级</Btn>
        )}
        <span className="font-mono text-gray-400">{crumb || "memories/"}</span>
      </div>
      <ul className="mb-3 max-h-56 overflow-auto rounded-lg border border-gray-200 bg-white text-sm" data-testid="memory-browser-list">
        {entries.map((e) => (
          <li key={e.uri} className="flex items-center justify-between border-b last:border-0 px-2.5 py-1.5 hover:bg-gray-50">
            <button className="text-left font-mono text-xs text-blue-700 hover:underline"
              onClick={() => (e.isDir ? list(e.uri + "/") : read(e.uri))}>
              {e.isDir ? "📁" : "📄"} {e.uri.split("/").pop()}
            </button>
            <span className="text-[11px] text-gray-400">{e.isDir ? "目录" : `${e.size} B`}</span>
          </li>
        ))}
        {entries.length === 0 && !msg && <li className="p-4 text-center text-sm text-gray-400">空目录</li>}
      </ul>
      {content && (
        <pre className="max-h-64 overflow-auto rounded-lg bg-[#0d1526] p-3 font-mono text-[11.5px] leading-6 text-[#c9d6f2]" data-testid="memory-browser-content">{content}</pre>
      )}
      {reading && <p className="text-xs text-gray-400">读取中：{reading.split("/").pop()}</p>}
    </Card>
  );
}
