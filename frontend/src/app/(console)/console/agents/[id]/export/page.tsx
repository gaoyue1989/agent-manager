"use client";
// 用户侧 · 导出发布页（跨集群导出 U5，契约 §7.1 / 设计 §2.1 目标 1）：
// ① 配置包编辑：文件树 + 文本编辑器（AGENTS.md / skills 只读标注），保存 → PUT 草稿，打发布标记冻结快照；
// ② 生产环境变量：行级增删改，来自运行时默认（L1）的行锁定不可编辑（lockedKeys 锁标）；
// ③ 模型/沙箱参数：模板全量键只读展示（LLM 10 键 / Sandbox 6 键），默认锁定——运维在「空间配置」改默认；
// ④ 确认导出：白名单基底镜像 + frozen 标记 + L2 env → POST export → 导出单状态卡轮询，built 后出下载按钮。
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import CodeMirror from "@uiw/react-codemirror";
import { yaml as cmYaml } from "@codemirror/lang-yaml";
import { json as cmJson } from "@codemirror/lang-json";
import { markdown as cmMarkdown } from "@codemirror/lang-markdown";
import { api, type ExportOrderListItem, type ExportOrderView, type ExportPreview, type FileEntry } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead, Tag } from "@/components/ui";
import { FileTreeView } from "@/lib/file-tree";
import {
  classifyEnvLayer, isReadonlyPkgPath, markerIssue, validateEnvRowCount,
  prefillEnvRows, validateEnvRows, buildPackageTree, ADVANCE_NEXT,
  RUNTIME_DEFAULT_FIELDS,
} from "@/lib/export-view";

// 导出单状态徽标配色（本地映射，不进共享 status.ts——状态集为导出链路专属）
const EXPORT_STATUS_STYLE: Record<string, string> = {
  draft: "bg-gray-100 text-gray-600", building: "bg-blue-100 text-blue-800",
  built: "bg-green-100 text-green-800", approving: "bg-yellow-100 text-yellow-800",
  approved: "bg-green-100 text-green-800", deploying: "bg-blue-100 text-blue-800",
  deployed: "bg-green-100 text-green-800", failed: "bg-red-100 text-red-800",
  cancelled: "bg-gray-200 text-gray-700",
};
// 轮询继续的非终态（v1 创建即入 building；approving 之后为人工推进，停轮询留手动刷新）
const POLLING_STATUSES = new Set(["draft", "building"]);

// 编辑态文件：原内容 + 当前内容（草稿保存以单文件 PUT 提交）
type EditFile = { path: string; original: string; current: string; readonly: boolean; binary: boolean };
type EnvRow = { key: string; value: string; secret: boolean };

function langExt(path: string) {
  const ext = path.slice(path.lastIndexOf(".") + 1).toLowerCase();
  if (ext === "yaml" || ext === "yml") return [cmYaml()];
  if (ext === "json") return [cmJson()];
  if (ext === "md" || ext === "markdown") return [cmMarkdown()];
  return [];
}

export default function AgentExportPage() {
  const { id } = useParams<{ id: string }>();
  const sid = Number(id);
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const writable = role === "owner" || role === "admin"; // 推进属运维动作，member 只读（契约 §4.2）
  const invalidId = !Number.isInteger(sid) || sid <= 0;

  const [preview, setPreview] = useState<ExportPreview | null>(null);
  const [files, setFiles] = useState<Record<string, EditFile>>({});
  const [treeEntries, setTreeEntries] = useState<FileEntry[]>([]);
  const [active, setActive] = useState("");
  const [envRows, setEnvRows] = useState<EnvRow[]>([]);
  const [baseImage, setBaseImage] = useState("");
  const [chartVersion, setChartVersion] = useState("0.1.0");
  const [replicas, setReplicas] = useState(2);
  const [order, setOrder] = useState<ExportOrderView | null>(null);
  const [orders, setOrders] = useState<ExportOrderListItem[]>([]);
  const [itsmDrafts, setItsmDrafts] = useState<Record<number, string>>({});
  const [advancingId, setAdvancingId] = useState(0);
  const [creating, setCreating] = useState(false);
  const [msg, setMsg] = useState("");
  const loadIdRef = useRef(0); // 竞态防护：只有最新一次 load 允许 setState（先例 admin/packages/[id]/edit）

  const lockedKeys = useMemo(() => preview?.lockedKeys ?? [], [preview]);

  const load = useCallback(async () => {
    loadIdRef.current += 1;
    const myLoad = loadIdRef.current;
    try {
      const [p, pkgView] = await Promise.all([
        api.exportOrders.preview(ws, sid),
        api.exportOrders.packageTree(ws, sid).catch(() => null), // 包树失败不阻塞预填展示
      ]);
      if (loadIdRef.current !== myLoad) return;
      setPreview(p);
      if (pkgView) {
        // 组装编辑态：服务端 files[] 为准（binary 文件不内联内容；readOnly 为服务端只读标注，
        // 与 isReadonlyPkgPath 本地判定双重兜底）；目录树由合并草稿叠加后的 files[].path 构建
        // （草稿可新增嵌套文件，必须从有效视图派生，issue #213）
        const entries: Record<string, EditFile> = {};
        const treeInput: { path: string; size?: number; readOnly: boolean }[] = [];
        for (const f of pkgView.files ?? []) {
          const ro = !!f.readOnly || isReadonlyPkgPath(f.path);
          entries[f.path] = { path: f.path, original: f.content ?? "", current: f.content ?? "", readonly: ro, binary: !!f.binary };
          treeInput.push({ path: f.path, size: f.size, readOnly: ro });
        }
        setFiles(entries);
        setTreeEntries(buildPackageTree(treeInput));
        // 默认选中 AGENTS.md（函数式更新只在首载兜底，不覆盖用户已选项）
        setActive((cur) => cur || (entries["AGENTS.md"] ? "AGENTS.md" : (Object.keys(entries)[0] ?? "")));
      }
      // 白名单缺省取首项（契约 §4.3，模式同 config.go DEFAULT_IMAGE）
      setBaseImage((cur) => cur || p.baseImageWhitelist?.[0]?.image || "");
      // 现 env 键预填生产环境变量行：后端仅回 hasValue/sensitive 掩码视图（值永不回传），
      // 行值留空待填；过滤 L3 保留/派生键（CHECKPOINT_JDBC_URL 等由 values 自动生成，
      // 预填成可编辑行会命中校验拦截、阻塞首次导出，issue #215）；仅首次预填，用户编辑后重载不覆盖输入
      setEnvRows((cur) => (cur.length ? cur : prefillEnvRows(p.serviceEnv ?? {})));
    } catch (e: any) {
      if (loadIdRef.current === myLoad) setMsg(`加载失败: ${e.message}`);
    }
  }, [ws, sid]);
  useEffect(() => { if (!invalidId && ws) load(); }, [ws, sid, load, invalidId]);

  // ---- 导出记录（issue #216）----
  const loadOrders = useCallback(async () => {
    try {
      setOrders(await api.exportOrders.list(ws, sid));
    } catch (e: any) { setMsg(`导出记录加载失败: ${e.message}`); }
  }, [ws, sid]);
  useEffect(() => { if (!invalidId && ws) loadOrders(); }, [ws, sid, loadOrders, invalidId]);

  // ---- ① 配置包编辑 ----
  const dirtyFiles = useMemo(() => Object.values(files).filter((f) => !f.readonly && !f.binary && f.current !== f.original), [files]);
  const activeFile = files[active];

  const saveFile = async () => {
    if (!activeFile) return;
    try {
      await api.exportOrders.saveFile(ws, sid, { path: activeFile.path, content: activeFile.current });
      setFiles((m) => ({ ...m, [activeFile.path]: { ...activeFile, original: activeFile.current } }));
      setMsg(`已保存草稿：${activeFile.path}`);
    } catch (e: any) { setMsg(`保存失败: ${e.message}`); }
  };

  const [marking, setMarking] = useState(false);
  const doMark = async () => {
    if (dirtyFiles.length > 0) { setMsg("存在未保存的草稿修改，请先逐文件保存再打发布标记"); return; }
    if (!confirm("打发布标记将冻结当前包内容快照（含已保存草稿），生成 {包版本}-e{序号} 标记。确认？")) return;
    setMarking(true);
    try {
      const mark = await api.exportOrders.mark(ws, sid);
      setMsg(`已打发布标记 ${mark.marker}（checksum ${mark.checksum.slice(0, 16)}…）`);
      await load();
    } catch (e: any) { setMsg(`打标记失败: ${e.message}`); }
    finally { setMarking(false); }
  };

  // ---- ② 生产环境变量 ----
  const addRow = () => setEnvRows((rows) => [...rows, { key: "", value: "", secret: false }]);
  const removeRow = (i: number) => setEnvRows((rows) => rows.filter((_, idx) => idx !== i));
  const patchRow = (i: number, patch: Partial<EnvRow>) => setEnvRows((rows) => rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r)));

  // 行级校验：锁定行由锁标呈现不报错；空值占位行不校验（提交按 filledRows 过滤，
  // 服务现 env 含导出参数键等场景不再空拦截，issue #215）；已填值行跑 L3 保留键/
  // 导出参数键/锁定键/重复/格式/值大小校验
  const envErrors = useMemo(() => validateEnvRows(envRows, lockedKeys), [envRows, lockedKeys]);
  const rowCountError = validateEnvRowCount(envRows.length);

  // L2 提交集：非锁定行（L3 键已在 validateEnvKey 拦截，不会通过提交）
  const submittableRows = envRows.filter((r) => r.key && classifyEnvLayer(r.key, lockedKeys) !== "L1");
  // 实际随导出提交的行：预填现 env 键值留空 = 占位，仅已填值的行参与提交
  const filledRows = submittableRows.filter((r) => r.value.trim() !== "");

  // ---- ④ 确认导出 ----
  const frozenMarks = useMemo(() => (preview?.marks ?? []).filter((m) => m.status === "frozen"), [preview]);
  // marker 选择独立状态
  const [marker, setMarker] = useState("");
  const markerCheck = markerIssue(marker);

  const submitExport = async () => {
    setMsg("");
    if (markerCheck) { setMsg(markerCheck); return; }
    if (!baseImage) { setMsg("请选择基础镜像（须在白名单内）"); return; }
    if (rowCountError) { setMsg(rowCountError); return; }
    const bad = Object.entries(envErrors).find(([, e]) => e);
    if (bad) { setMsg(`环境变量校验失败：${bad[1]}`); return; }
    if (!confirm(`确认创建导出单？\n标记 ${marker} · 基底镜像 ${baseImage} · L2 覆盖 ${filledRows.length} 键（其中 Secret ${filledRows.filter((r) => r.secret).length}）`)) return;
    setCreating(true);
    try {
      // 现环境键预填行为空值 = 未填写的占位行，不随导出提交（后端对空值剔除，此处提前过滤对齐语义）
      const env: Record<string, string> = {};
      for (const r of filledRows) env[r.key] = r.value;
      const secretKeys = filledRows.filter((r) => r.secret).map((r) => r.key);
      const o = await api.exportOrders.create(ws, sid, { marker, baseImage, chartVersion, env, secretKeys, replicas });
      setOrder(o);
      setMsg(`导出单 EXP 单 #${o.id} 已创建，构建进行中…（导出记录区同步可见）`);
      await loadOrders();
    } catch (e: any) { setMsg(`创建导出单失败: ${e.message}`); }
    finally { setCreating(false); }
  };

  // 人工推进（owner/admin）：逐段前向（ADVANCE_NEXT），ITSM 单号可空；409/400 错误文案透出
  const advanceOrder = async (o: ExportOrderListItem, status: string) => {
    setAdvancingId(o.id);
    try {
      await api.exportOrders.advance(o.id, { status, itsmTicketId: (itsmDrafts[o.id] ?? "").trim() || undefined });
      setMsg(`导出单 ${o.orderNo} 已推进至 ${status}`);
      await loadOrders();
    } catch (e: any) { setMsg(`推进失败（${o.orderNo}）: ${e.message}`); }
    finally { setAdvancingId(0); }
  };

  // 导出单状态轮询（3s；非终态 draft/building 才续，先例 PodTable alive 标志）
  useEffect(() => {
    if (!order) return;
    let alive = true;
    const tick = async () => {
      try {
        const o = await api.exportOrders.get(order.id);
        if (alive) setOrder(o);
        return POLLING_STATUSES.has(o.status);
      } catch { return true; }
    };
    const t = setInterval(async () => { if (!(await tick())) clearInterval(t); }, 3000);
    return () => { alive = false; clearInterval(t); };
  }, [order?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // 轮询中的导出单离开 draft/building（built/failed…）时刷新导出记录列表（状态卡与列表对齐）
  useEffect(() => {
    if (order && !POLLING_STATUSES.has(order.status)) void loadOrders();
  }, [order?.status, loadOrders]); // eslint-disable-line react-hooks/exhaustive-deps

  if (invalidId && !preview) return (
    <div className="rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">Agent 不存在：非法 ID（{String(id)}）</div>
  );
  if (msg && !preview) return <div className="rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>;
  if (!preview) return <p className="text-sm text-gray-400">加载中…</p>;

  const fmt = (s: string) => { try { return new Date(s).toLocaleString("zh-CN", { hour12: false }); } catch { return s; } };
  const modelSandboxFields = RUNTIME_DEFAULT_FIELDS.filter((f) => f.category === "llm" || f.category === "sandbox");
  // 模型/沙箱参数只读展示：非敏感回真值；敏感键 value 不回传，以 hasValue 区分「已配置（掩码）/未配置」
  const rdDisplay = (key: string) => {
    const e = preview.runtimeDefaults?.[key];
    if (!e) return "未配置";
    return e.value || (e.hasValue ? "已配置（掩码）" : "未配置");
  };

  return (
    <div data-testid="export-page" className="space-y-4">
      <PageHead
        title={<>
          <Link href={`/console/agents/${sid}`} className="text-gray-400 hover:text-blue-600">←</Link>
          导出发布 · {preview.service.displayName ?? `Agent #${sid}`}
          {preview.package?.version && <Tag>源包 {preview.package.slug ?? ""}@{preview.package.version}</Tag>}
        </>}
        sub={<span>生成 4 件套交付生产（OAF 配置镜像直推 + values/manifest 提交 ITSM）；运行时默认行锁定，改默认请运维前往 <Link href="/console/workspace-config" className="text-blue-600 hover:underline">空间配置</Link></span>} />

      {msg && <div data-testid="export-msg" className="rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700 whitespace-pre-wrap">{msg}</div>}

      {/* ① 配置包编辑 */}
      <Card title="① 配置包编辑（文本直改 → 保存草稿 → 打发布标记冻结快照）">
        <div className="grid grid-cols-[240px_1fr] gap-4">
          <div className="max-h-[420px] overflow-auto rounded border border-gray-100 p-2">
            <FileTreeView entries={treeEntries} selected={active} onSelect={setActive} />
          </div>
          <div>
            {!activeFile ? (
              <p className="p-3 text-sm text-gray-400">选择左侧文件</p>
            ) : activeFile.binary ? (
              <p className="p-3 text-sm text-gray-400">二进制文件不支持在线编辑（随快照原样进入导出镜像）</p>
            ) : (
              <>
                {activeFile.readonly && <Note tone="plain">🔒 {active} 为只读项（AGENTS.md / skills 随包只读，不开放导出侧直改）</Note>}
                <CodeMirror
                  key={active}
                  value={activeFile.current}
                  height="300px"
                  extensions={langExt(active)}
                  editable={!activeFile.readonly}
                  basicSetup={{ foldGutter: true, highlightActiveLine: !activeFile.readonly }}
                  onChange={(v: string) => !activeFile.readonly && setFiles((m) => ({ ...m, [active]: { ...m[active], current: v } }))}
                />
                <div className="mt-2 flex items-center gap-2">
                  <Btn kind="pri" disabled={activeFile.readonly || activeFile.current === activeFile.original} onClick={saveFile} testid="export-save-file">
                    保存草稿{activeFile.current !== activeFile.original ? "（有修改）" : ""}
                  </Btn>
                  <Btn onClick={doMark} disabled={marking} testid="export-mark-btn">打发布标记</Btn>
                  <span className="text-xs text-gray-400">保存 = 写编辑草稿（不冻结）；打标记 = 冻结当前快照生成 {"{包版本}-e{序号}"}</span>
                </div>
              </>
            )}
          </div>
        </div>

        <div className="mt-4" data-testid="marks-table">
          <h4 className="mb-1.5 text-[13px] font-semibold">发布标记列表</h4>
          {(preview.marks ?? []).length === 0 ? <p className="text-sm text-gray-400">暂无标记（编辑后可打标记冻结导出内容）</p> : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead><tr className="border-b text-left text-xs text-gray-500"><th className="p-2">标记</th><th className="p-2">包版本</th><th className="p-2">checksum</th><th className="p-2">文件数</th><th className="p-2">状态</th><th className="p-2">时间</th></tr></thead>
                <tbody>
                  {preview.marks.map((m) => (
                    <tr key={m.marker} className="border-b last:border-0">
                      <td className="p-2 font-mono text-xs">{m.marker}</td>
                      <td className="p-2 text-xs">{m.packageVersion}</td>
                      <td className="p-2 font-mono text-xs">{m.checksum.slice(0, 18)}…</td>
                      <td className="p-2">{m.fileCount}</td>
                      <td className="p-2"><Badge status={m.status === "frozen" ? "running" : "stopped"}>{m.status === "frozen" ? "frozen" : m.status}</Badge></td>
                      <td className="p-2 text-xs text-gray-500">{fmt(m.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </Card>

      {/* ② 生产环境变量 */}
      <Card title="② 生产环境变量（服务专属键可增删改；来自运行时默认的行锁定）">
        <Note>🔒 行 = 工作空间运行时默认（L1），导出页锁定不可修改，如需调整请运维在「空间配置」改默认（留审计）。
          {preview.derivedPreview && <> 平台派生键（L3，自动生成不可设置）：CHECKPOINT_JDBC_URL → <code>{preview.derivedPreview.checkpointDB}</code>；AGENT_REDIS_PREFIX → <code>{preview.derivedPreview.redisPrefix}</code>。</>}
        </Note>
        <div className="overflow-x-auto" data-testid="env-table">
          <table className="w-full text-sm">
            <thead><tr className="border-b text-left text-xs text-gray-500">
              <th className="p-2">键</th><th className="p-2">值</th><th className="p-2 w-16">Secret</th><th className="p-2 w-14">层</th><th className="p-2 w-16 text-right">操作</th>
            </tr></thead>
            <tbody>
              {envRows.map((r, i) => {
                const layer = classifyEnvLayer(r.key, lockedKeys);
                const locked = layer === "L1";
                return (
                  <tr key={i} data-testid={`env-row-${r.key || i}`} className={`border-b last:border-0 ${locked ? "bg-gray-50" : ""}`}>
                    <td className="p-2">
                      {locked ? (
                        <span className="font-mono text-xs text-gray-500">🔒 {r.key}</span>
                      ) : (
                        <input value={r.key} onChange={(e) => patchRow(i, { key: e.target.value.trim() })} placeholder="ENV_KEY"
                          data-testid={`env-key-${i}`} className="w-full rounded border border-gray-300 px-2 py-1 font-mono text-xs disabled:bg-gray-100" disabled={locked} />
                      )}
                    </td>
                    <td className="p-2">
                      <input value={r.value} onChange={(e) => patchRow(i, { value: e.target.value })} placeholder={locked ? "运行时默认值（锁定）" : "生产值（现 env 仅回显键名不回值，请按需填写）"}
                        data-testid={`env-value-${i}`} className="w-full rounded border border-gray-300 px-2 py-1 font-mono text-xs disabled:bg-gray-100"
                        disabled={locked} />
                    </td>
                    <td className="p-2 text-center">
                      <input type="checkbox" checked={r.secret} disabled={locked} onChange={(e) => patchRow(i, { secret: e.target.checked })}
                        data-testid={`env-secret-${i}`} title="勾选后该键路由进 values secretEnv（生产 Secret）" />
                    </td>
                    <td className="p-2"><Tag>{layer}</Tag></td>
                    <td className="p-2 text-right">
                      {!locked && <Btn sm kind="danger" onClick={() => removeRow(i)} testid={`env-del-${i}`}>删除</Btn>}
                    </td>
                  </tr>
                );
              })}
              {envRows.length === 0 && <tr><td colSpan={5} className="p-4 text-center text-sm text-gray-400">暂无环境变量行（服务现 env 为空且未添加）</td></tr>}
            </tbody>
          </table>
        </div>
        {Object.entries(envErrors).filter(([, e]) => e).map(([i, e]) => (
          <p key={i} className="mt-1 text-xs text-red-600">行 {Number(i) + 1}：{e}</p>
        ))}
        {rowCountError && <p className="mt-1 text-xs text-red-600">{rowCountError}</p>}
        <div className="mt-2"><Btn sm onClick={addRow} testid="env-add-row">+ 添加环境变量</Btn></div>
      </Card>

      {/* ③ 模型/沙箱参数 */}
      <Card title="③ 模型 / 沙箱参数（默认锁定 · 只读）">
        <Note tone="plain">与 <code>platformconfig/template.go</code> 同源的模板全量键（LLM 10 键 / Sandbox 6 键）；取空间运行时默认（敏感掩码）。导出页不可改——单次导出不能悄悄改基线，修改请前往「空间配置」。</Note>
        <div className="grid grid-cols-1 gap-x-6 gap-y-1.5 text-[13px] md:grid-cols-2" data-testid="model-sandbox-params">
          {modelSandboxFields.map((f) => (
            <div key={f.envKey} data-testid={`param-${f.envKey}`} className="flex items-start justify-between gap-2 border-b border-dashed border-gray-100 py-1">
              <span className="text-gray-600"><code className="text-[11px] text-gray-400">{f.envKey}</code><span className="ml-1.5">{f.label}</span></span>
              <span className="max-w-[55%] break-all text-right font-mono text-xs text-gray-700">
                🔒 {rdDisplay(f.envKey)}
              </span>
            </div>
          ))}
        </div>
      </Card>

      {/* ④ 确认导出 */}
      <Card title="④ 确认导出">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <label className="text-[13px]">
            <span className="mb-1 block text-xs text-gray-500">发布标记（仅 frozen）*</span>
            <select value={marker} onChange={(e) => setMarker(e.target.value)} data-testid="export-marker"
              className="w-full rounded border border-gray-300 px-2 py-1.5 text-sm disabled:bg-gray-100" disabled={frozenMarks.length === 0}>
              <option value="">{frozenMarks.length ? "请选择…" : "暂无 frozen 标记"}</option>
              {frozenMarks.map((m) => <option key={m.marker} value={m.marker}>{m.marker}（{m.packageVersion} · {fmt(m.createdAt)}）</option>)}
            </select>
            {markerCheck && <span className="mt-0.5 block text-xs text-red-600">{markerCheck}</span>}
          </label>
          <label className="text-[13px]">
            <span className="mb-1 block text-xs text-gray-500">基础镜像（白名单内）*</span>
            <select value={baseImage} onChange={(e) => setBaseImage(e.target.value)} data-testid="export-base-image"
              className="w-full rounded border border-gray-300 px-2 py-1.5 text-sm disabled:bg-gray-100" disabled={!preview.baseImageWhitelist?.length}>
              {(preview.baseImageWhitelist ?? []).map((o) => <option key={o.image} value={o.image}>{o.label ? `${o.label} · ${o.image}` : o.image}</option>)}
            </select>
            {!preview.baseImageWhitelist?.length && <span className="mt-0.5 block text-xs text-red-600">空间未配置基础镜像白名单（BASE_IMAGE_WHITELIST）</span>}
          </label>
          <label className="text-[13px]">
            <span className="mb-1 block text-xs text-gray-500">Chart 版本</span>
            <input value={chartVersion} onChange={(e) => setChartVersion(e.target.value)} data-testid="export-chart-version"
              className="w-full rounded border border-gray-300 px-2 py-1.5 text-sm" placeholder="0.1.0" />
          </label>
          <label className="text-[13px]">
            <span className="mb-1 block text-xs text-gray-500">副本数（生产缺省 2，无 sessionAffinity）</span>
            <input type="number" min={1} value={replicas} onChange={(e) => setReplicas(Math.max(1, Number(e.target.value) || 1))} data-testid="export-replicas"
              className="w-full rounded border border-gray-300 px-2 py-1.5 text-sm" />
          </label>
        </div>
        <p className="mt-2 text-xs text-gray-500">
          将提交 L2 覆盖 <b>{filledRows.length}</b> 键（Secret <b>{filledRows.filter((r) => r.secret).length}</b>，未填值占位行不计入——issue #215 口径对齐）；L1 运行时默认 {lockedKeys.length} 键随 values 组装、不随本表单提交；L3 派生键由平台自动生成。
        </p>
        <div className="mt-3"><Btn kind="pri" disabled={creating} onClick={submitExport} testid="export-create-btn">{creating ? "创建中…" : "确认导出"}</Btn></div>

        {order && (
          <div className="mt-4 rounded-lg border border-gray-200 bg-gray-50 p-3" data-testid="export-order-card">
            <div className="flex flex-wrap items-center gap-2">
              <b className="text-[13px]">导出单 #{order.id}</b>
              <span data-testid="order-status" className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] ${EXPORT_STATUS_STYLE[order.status] ?? "bg-gray-100 text-gray-600"}`}>
                <span className="size-1.5 rounded-full bg-current" />{order.status}
              </span>
              {POLLING_STATUSES.has(order.status) && <span className="text-xs text-gray-400">（3s 轮询中…）</span>}
              <Btn sm onClick={() => api.exportOrders.get(order.id).then(setOrder).catch((e: any) => setMsg(`刷新失败: ${e.message}`))}>刷新</Btn>
            </div>
            <dl className="mt-2 grid grid-cols-1 gap-1.5 text-xs md:grid-cols-2">
              <Kv k="OAF 镜像" v={order.oafImageRef || "构建中…"} />
              <Kv k="Digest" v={order.oafImageDigest || "—"} />
              <Kv k="ITSM 单号" v={order.itsmTicketId || "—"} />
              <Kv k="更新时间" v={fmt(order.updatedAt)} />
            </dl>
            {order.error && <p className="mt-1.5 text-xs text-red-600">失败原因：{order.error}</p>}
            {order.status === "built" && (
              <div className="mt-2 flex flex-wrap gap-2" data-testid="export-downloads">
                <a href={api.exportOrders.downloadValues(order.id)} data-testid="download-values"
                  className="rounded-md border border-blue-600 bg-blue-600 px-3 py-1.5 text-[13px] text-white hover:bg-blue-700">下载 values.yaml</a>
                <a href={api.exportOrders.downloadManifest(order.id)} data-testid="download-manifest"
                  className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">下载 manifest.json</a>
                <span className="self-center text-xs text-gray-400">values 含明文敏感值，下载写审计日志；随 ITSM 按密级流转</span>
              </div>
            )}
          </div>
        )}
      </Card>

      {/* ⑤ 导出记录（issue #216：导出单跨刷新可找回——重复下载交付物、UI 推进状态） */}
      <Card title="⑤ 导出记录（本服务全部导出单）">
        <Note tone="plain">刷新/离开页面后可在此找回全部导出单：交付物可重复下载（每次写审计）；状态推进为 v1 人工逐段操作，仅 owner/admin 可见推进按钮，member 只读。构建中的单可稍后点「刷新列表」。</Note>
        <div className="mt-2 mb-2"><Btn sm onClick={() => loadOrders()} testid="export-orders-refresh">刷新列表</Btn></div>
        {orders.length === 0 ? <p className="text-sm text-gray-400">暂无导出单（「确认导出」成功后在此出现）</p> : (
          <div className="overflow-x-auto" data-testid="export-orders-table">
            <table className="w-full text-sm">
              <thead><tr className="border-b text-left text-xs text-gray-500">
                <th className="p-2">单号</th><th className="p-2">状态</th><th className="p-2">marker</th>
                <th className="p-2">创建时间</th><th className="p-2">创建人</th><th className="p-2">ITSM 单号</th><th className="p-2">操作</th>
              </tr></thead>
              <tbody>
                {orders.map((o) => {
                  const next = ADVANCE_NEXT[o.status];
                  const downloadable = ["built", "approving", "approved", "deploying", "deployed"].includes(o.status);
                  return (
                    <tr key={o.id} data-testid={`export-order-row-${o.id}`} className="border-b last:border-0 align-top">
                      <td className="p-2 font-mono text-xs">{o.orderNo}</td>
                      <td className="p-2">
                        <span data-testid={`export-order-status-${o.id}`} className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] ${EXPORT_STATUS_STYLE[o.status] ?? "bg-gray-100 text-gray-600"}`}>
                          <span className="size-1.5 rounded-full bg-current" />{o.status}
                        </span>
                        {o.error && <p className="mt-1 max-w-[240px] break-all text-xs text-red-600">{o.error}</p>}
                      </td>
                      <td className="p-2 font-mono text-xs">{o.marker || "—"}</td>
                      <td className="p-2 text-xs text-gray-500">{fmt(o.createdAt)}</td>
                      <td className="p-2 text-xs">{o.createdBy}</td>
                      <td className="p-2 text-xs">{o.itsmTicketId || "—"}</td>
                      <td className="p-2">
                        <div className="flex flex-wrap items-center gap-1.5">
                          {downloadable && <>
                            <a href={api.exportOrders.downloadValues(o.id)} data-testid={`order-download-values-${o.id}`}
                              className="rounded border border-blue-600 px-2 py-1 text-xs text-blue-600 hover:bg-blue-50">values.yaml</a>
                            <a href={api.exportOrders.downloadManifest(o.id)} data-testid={`order-download-manifest-${o.id}`}
                              className="rounded border border-gray-300 px-2 py-1 text-xs text-gray-600 hover:border-blue-200 hover:text-blue-600">manifest.json</a>
                          </>}
                          {writable && next && (
                            <span className="inline-flex items-center gap-1" data-testid={`order-advance-${o.id}`}>
                              <input value={itsmDrafts[o.id] ?? o.itsmTicketId ?? ""} placeholder="ITSM 单号（可空）"
                                onChange={(e) => setItsmDrafts((m) => ({ ...m, [o.id]: e.target.value }))}
                                data-testid={`order-itsm-${o.id}`}
                                className="w-28 rounded border border-gray-300 px-1.5 py-1 text-xs" />
                              <Btn sm disabled={advancingId === o.id} onClick={() => advanceOrder(o, next)} testid={`order-advance-btn-${o.id}`}>
                                {advancingId === o.id ? "推进中…" : `推进→${next}`}
                              </Btn>
                            </span>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}

function Kv({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div>
      <dt className="text-[11px] text-gray-400">{k}</dt>
      <dd className="mt-0.5 break-all font-mono">{v}</dd>
    </div>
  );
}
