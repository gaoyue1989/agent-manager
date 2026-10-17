"use client";
// 创建/编辑 Agent 共用表单（M3 §3.1，demo c/create 与 c/edit 同构还原）：
// 选择创建方式三卡片（仅创建页）+ 五个编号区块（基本信息/文件配置/资产配置/资源配置/网络配置）。
// 编辑模式：无方式卡片、名称锁定、标题/按钮换「保存」、提交 PUT（copy-on-write 新版本 + republish）。
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, type EditFormView, type ImageOption, type McpConnectorView, type ModelConnectionView, type IntegrationView, type PackageRec } from "@/lib/api";
import { Badge, Btn, Card, Note, PageHead, Tag } from "@/components/ui";
import { PERM_STYLE } from "@/lib/status";

export interface AgentFormState {
  mode: "harness" | "import" | "adopt";
  name: string; description: string; image: string;
  modelConnections: { connectionId: number; isDefault: boolean }[];
  prompt: string;
  skills: { name: string; source: string; packageId?: number; version?: string }[];
  subAgents: { agent: string; role?: string; delegations?: string[]; endpoint?: string }[];
  mcpServers: { connectorId?: number; builtin?: string }[];
  spec: string; replicas: number; publicEndpoint: boolean;
  importPackageId?: number; adoptServiceId?: number;
}

export const SPEC_CARDS = [
  { id: "small", title: "1核 2G", sub: "0.5 CU · 常规（默认）" },
  { id: "medium", title: "2核 4G", sub: "1 CU · 生产" },
  { id: "large", title: "4核 8G", sub: "2 CU · 高并发" },
  { id: "micro", title: "最低配置", sub: "250m/256Mi · ⚠️ 不满足托管 Java 运行时，会被 OOMKilled，仅极轻量自备镜像调试用" },
];

function Section({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <div className="border-t border-gray-200 pb-3 pt-4">
      <b className="mb-3 block text-[13px] font-semibold">{n} {title}</b>
      {children}
    </div>
  );
}

function Field({ label, required, hint, children, dim }: { label: string; required?: boolean; hint?: string; children: React.ReactNode; dim?: boolean }) {
  return (
    <div className={`mb-4 ${dim ? "pointer-events-none opacity-45" : ""}`}>
      <label className="mb-1.5 block text-[12.5px] font-medium">{label}{required && <em className="ml-0.5 not-italic text-red-500">*</em>}</label>
      {children}
      {hint && <div className="mt-1 text-[11.5px] text-gray-400">{hint}</div>}
    </div>
  );
}

export default function AgentCreateForm({ ws, mode, initial, serviceId, deepImportPackageId }: {
  ws: number; mode: "create" | "edit"; initial?: EditFormView | null; serviceId?: number;
  /** 模板页「去导入」深链：预选派生源包并切到 import 模式 */
  deepImportPackageId?: number;
}) {
  const router = useRouter();
  const [state, setState] = useState<AgentFormState>({
    mode: deepImportPackageId ? "import" : "harness",
    importPackageId: deepImportPackageId,
    name: initial?.name ?? "", description: initial?.description ?? "",
    image: initial?.image ?? "", modelConnections: initial?.modelConnections ?? [],
    prompt: initial?.prompt ?? "", skills: initial?.skills ?? [], subAgents: initial?.subAgents ?? [],
    mcpServers: initial?.mcpServers ?? [], spec: initial?.spec || "small",
    replicas: initial?.replicas || 1, publicEndpoint: initial?.publicEndpoint ?? true,
  });
  const [images, setImages] = useState<ImageOption[]>([]);
  const [conns, setConns] = useState<ModelConnectionView[]>([]);
  const [connectors, setConnectors] = useState<McpConnectorView[]>([]);
  const [integrations, setIntegrations] = useState<IntegrationView[]>([]);
  const [packages, setPackages] = useState<PackageRec[]>([]);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<{ agentKey: string; slug: string; k8sName: string; conflict: boolean } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [msg, setMsg] = useState("");

  useEffect(() => {
    api.console.images().then(setImages).catch(() => {});
    api.console.modelConnections.list(ws).then(setConns).catch(() => {});
    api.console.tools.list(ws).then(setConnectors).catch(() => {});
    api.console.integrations.list(ws).then((r) => setIntegrations(r.relations.filter((x) => x.enabled))).catch(() => {});
    api.console.packages(ws).then(setPackages).catch(() => {});
  }, [ws]);
  useEffect(() => {
    if (!state.image && images.length > 0) setState((s) => ({ ...s, image: images[0].Image }));
  }, [images, state.image]);

  const previewName = useCallback(async (name: string) => {
    if (mode === "edit" || !name.trim()) { setPreview(null); return; }
    try { setPreview(await api.console.previewAgentName(ws, name)); } catch { setPreview(null); }
  }, [ws, mode]);

  const toggleConn = (id: number) => {
    setState((s) => {
      const has = s.modelConnections.some((x) => x.connectionId === id);
      let next = has
        ? s.modelConnections.filter((x) => x.connectionId !== id)
        : [...s.modelConnections, { connectionId: id, isDefault: false }];
      // 首个勾选自动标默认
      next = next.map((x, i) => ({ ...x, isDefault: i === 0 }));
      return { ...s, modelConnections: next };
    });
  };

  const connRole = (id: number) => {
    const idx = state.modelConnections.findIndex((x) => x.connectionId === id);
    if (idx < 0) return "—";
    return idx === 0 ? "默认（注入 LLM_* env）" : "就绪后下发 /models";
  };

  const selectedCount = {
    skill: state.skills.length,
    sub: state.subAgents.length,
    mcp: state.mcpServers.length,
  };

  const submit = async () => {
    setMsg("");
    if (mode === "create" && state.mode === "harness") {
      if (!state.name.trim()) { setMsg("请填写 Agent 名称"); return; }
      if (!state.prompt.trim()) { setMsg("请填写系统提示词（将写入生成包 AGENTS.md 正文）"); return; }
      if (state.modelConnections.length === 0) { setMsg("请至少勾选一条模型连接（先到「资产管理 → 模型连接」创建）"); return; }
    }
    if (mode === "edit" && !state.prompt.trim()) { setMsg("系统提示词不能为空"); return; }
    setSubmitting(true);
    try {
      if (mode === "create" && state.mode === "import") {
        let pkgId = state.importPackageId ?? 0;
        if (uploadFile) {
          const rec = await api.console.uploadPackage(ws, uploadFile);
          pkgId = rec.id;
        }
        if (!pkgId) { setMsg("请上传 zip 包或选择空间内已有包"); setSubmitting(false); return; }
        const out = await api.console.createAgent(ws, { ...state, importPackageId: pkgId });
        router.push(`/console/agents/${out.service.id}`);
        return;
      }
      if (mode === "create" && state.mode === "adopt") {
        if (!state.adoptServiceId) { setMsg("请选择要纳管的存量服务"); setSubmitting(false); return; }
        const out = await api.console.createAgent(ws, { ...state, adoptServiceId: state.adoptServiceId });
        router.push(out.service?.id ? `/console/agents/${out.service.id}` : "/console/agents");
        return;
      }
      if (mode === "edit" && serviceId) {
        await api.console.updateAgent(ws, serviceId, state);
        router.push(`/console/agents/${serviceId}`);
        return;
      }
      const out = await api.console.createAgent(ws, state);
      router.push(`/console/agents/${out.service.id}`);
    } catch (e: any) {
      setMsg(e.message || "提交失败");
      setSubmitting(false);
    }
  };

  return (
    <div data-testid={mode === "create" ? "agent-create" : "agent-edit"}>
      <PageHead
        title={mode === "create" ? "创建 Agent" : <>编辑 Agent <Tag>当前 v{initial?.version}</Tag></>}
        sub={mode === "create"
          ? "填写配置生成 OAF 包并发布（自动注册 A2A）——托管 Harness 主模式；导入/纳管为次要方式"
          : "保存将生成包新版本（copy-on-write）并重发布：提示词/Skill/SubAgent/MCP 热生效；镜像/规格/副本滚动重启生效"}
        actions={<>
          <Btn onClick={() => router.push(mode === "edit" ? `/console/agents` : "/console/agents")}>取消</Btn>
          <Btn kind="pri" testid={mode === "create" ? "agent-submit" : "agent-save"} disabled={submitting} onClick={submit}>
            {submitting ? "提交中…" : mode === "create" ? "立即创建" : "保存"}
          </Btn>
        </>} />
      {msg && <div data-testid="agent-form-msg" className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}

      <Card>
        {mode === "create" && (
          <>
            <Field label="选择创建方式" required>
              <div className="grid grid-cols-1 gap-2.5 md:grid-cols-3" data-testid="create-mode-cards">
                {([
                  { id: "harness", title: "托管 Harness", desc: "选定运行时托管，逐区填写下方表单，提交时生成全新 OAF 包并发布" },
                  { id: "import", title: "导入 OAF 包", desc: "上传符合 OAF 规范的 zip 包，或选公共包派生副本（copy-on-write）" },
                  { id: "adopt", title: "纳管已有服务", desc: "把存量服务挂入当前工作空间（管理员场景），发布链路与 K8s 资源不变" },
                ] as const).map((m) => (
                  <button key={m.id} type="button" data-testid={`mode-${m.id}`}
                    onClick={() => setState((s) => ({ ...s, mode: m.id }))}
                    className={`rounded-lg border p-3 text-left ${state.mode === m.id ? "border-blue-600 shadow-[0_0_0_2px_rgba(22,104,220,.14)]" : "border-gray-300"}`}>
                    <b className="block text-[13px]">{m.title}</b>
                    <small className="mt-1 block text-[11.5px] leading-relaxed text-gray-400">{m.desc}</small>
                  </button>
                ))}
              </div>
            </Field>
            {state.mode === "import" && (
              <div className="mb-4">
                <Note>上传符合 OAF 规范的 zip 包（含 frontmatter 与 AGENTS.md），或选择空间内已有包派生新版本（copy-on-write，不影响原包）。</Note>
                <input type="file" accept=".zip" data-testid="import-zip-input"
                  onChange={(e) => setUploadFile(e.target.files?.[0] ?? null)}
                  className="mb-3 text-xs" />
                <div className="rounded-lg border border-gray-200 p-3">
                  <div className="mb-2 text-xs text-gray-500">或选择空间内 / 公共包派生：</div>
                  <select data-testid="import-pkg-select"
                    onChange={(e) => setState((s) => ({ ...s, importPackageId: Number(e.target.value) }))}
                    className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
                    <option value={0}>— 选择包 —</option>
                    {packages.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.slug}@{p.version}{(p.workspaceId ?? 0) === 0 ? "（公共）" : ""}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="mt-3">
                  <Btn kind="pri" disabled={submitting} onClick={submit}>导入并发布</Btn>
                </div>
              </div>
            )}
            {state.mode === "adopt" && <AdoptPanel ws={ws} state={state} setState={setState} submitting={submitting} onSubmit={submit} />}
          </>
        )}

        {state.mode === "harness" && <>
          <Section n={1} title="基本信息">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <Field label="Agent 名称" required
                hint={mode === "edit"
                  ? "服务名（K8s short 名）创建后不可变；显示名修改将同步至新版本包 frontmatter name"
                  : preview ? `agentKey: ${preview.agentKey} · slug: ${preview.slug} · K8s: ${preview.k8sName}${preview.conflict ? " · ⚠ 重名冲突" : ""}`
                    : "同时作为 K8s 服务名与生成包 agentKey / slug 的派生源"}>
                <input value={state.name} disabled={mode === "edit"} data-testid="agent-name"
                  onBlur={(e) => previewName(e.target.value)}
                  onChange={(e) => { setMsg(""); setState((s) => ({ ...s, name: e.target.value })); }}
                  className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm disabled:bg-gray-50 disabled:text-gray-500" />
              </Field>
              <Field label="运行镜像（托管 Harness）" hint={mode === "edit" ? "变更镜像将随重发布滚动重启生效" : "托管 Harness 内置 Agent Loop 与 Shell / 文件工具"}>
                <select value={state.image} onChange={(e) => setState((s) => ({ ...s, image: e.target.value }))}
                  data-testid="agent-image" className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
                  {images.map((im) => <option key={im.Image} value={im.Image}>{im.Label ? `${im.Label} (${im.Image})` : im.Image}</option>)}
                </select>
              </Field>
            </div>
            <Field label="模型连接" required
              hint="来自「资产管理 → 模型连接」：首个勾选为默认（发布时注入 LLM_* env，key 走服务 Secret）；全部勾选连接在 Agent 就绪后异步下发至其 /models 接口">
              {conns.length === 0 ? (
                <div className="rounded-md border border-dashed border-gray-300 p-3 text-xs text-gray-400">
                  暂无可用模型连接 —— 请先到「资产管理 → 模型连接」创建并测试通过
                </div>
              ) : (
                <table className="w-full rounded-lg border border-gray-200 text-sm" data-testid="conn-table">
                  <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500">
                    <th className="w-9 p-2"></th><th className="p-2">连接</th><th className="p-2">角色</th><th className="p-2">状态</th>
                  </tr></thead>
                  <tbody>
                    {conns.map((cn) => (
                      <tr key={cn.id} className="border-b last:border-0">
                        <td className="p-2">
                          <input type="checkbox" checked={state.modelConnections.some((x) => x.connectionId === cn.id)}
                            disabled={cn.status !== "verified"}
                            onChange={() => { setMsg(""); toggleConn(cn.id); }} className="accent-blue-600" />
                        </td>
                        <td className="p-2 font-medium">{cn.name}<div className="font-mono text-[11px] text-gray-400">{cn.modelId}</div></td>
                        <td className="p-2"><Tag>{connRole(cn.id)}</Tag></td>
                        <td className="p-2"><Badge status={cn.status}>{cn.status === "verified" ? "已验证" : cn.status === "failed" ? "测试失败" : "未验证"}</Badge></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Field>
            <Field label="描述">
              <textarea rows={2} maxLength={256} value={state.description}
                onChange={(e) => setState((s) => ({ ...s, description: e.target.value }))}
                className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
            </Field>
          </Section>

          <Section n={2} title="文件配置">
            <Note>提交时按本页填写内容{mode === "edit" ? "生成包新版本（copy-on-write 派生，不影响历史版本）" : "生成全新 OAF 包"}：系统提示词写入包内 AGENTS.md 正文，frontmatter 由表单字段自动派生；SOUL.md、脚本等可在创建后于包编辑器补充。</Note>
            <Field label="系统提示词" required hint={`${state.prompt.length} / 100000 字符 · 提交时写入生成包的 AGENTS.md 正文`}>
              <textarea rows={9} maxLength={100000} value={state.prompt} data-testid="agent-prompt"
                onChange={(e) => { setMsg(""); setState((s) => ({ ...s, prompt: e.target.value })); }}
                className="w-full rounded-md border border-gray-300 px-2.5 py-2 font-mono text-xs" />
            </Field>
            <div className="mb-1.5 text-xs text-gray-400">将生成的 OAF 包（预览）</div>
            <div className="rounded-md bg-gray-50 p-3 font-mono text-xs leading-6 text-gray-600" data-testid="pkg-preview">
              <b className="flex items-center gap-1.5 font-medium text-gray-800">📁 {state.name || "<agent>"} /</b>
              <span className="pl-5">📄 AGENTS.md ← frontmatter 自动派生 + 本次系统提示词</span>
              <span className="pl-5">📁 skills/ ← 资产配置区勾选后生成</span>
              <span className="pl-5">📁 mcp-configs/ ← 资产配置区勾选 MCP 后注入生成</span>
            </div>
          </Section>

          <Section n={3} title="Agent 资产配置">
            <div className="mb-3 rounded-md bg-gray-100 px-3 py-1.5 text-xs text-gray-600" data-testid="asset-count">
              已选资产：Skill {selectedCount.skill} · SubAgent {selectedCount.sub} · 工具 / MCP {selectedCount.mcp} · 记忆 默认 · 凭证 0 · 知识库 0
            </div>
            <Note>勾选的资产将写入生成的 OAF 包（frontmatter skills / agents / mcpServers + mcp-configs/）：MCP 配置取自「工具管理」、远程 SubAgent 端点取自「集成 Agent」；运行时三层裁决不变。</Note>
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
              <div>
                <div className="mb-2 text-xs text-gray-400">Skill（从可用列表添加）</div>
                <table className="w-full rounded-lg border border-gray-200 text-sm" data-testid="skill-table">
                  <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500"><th className="w-9 p-2"></th><th className="p-2">技能</th><th className="p-2">来源</th></tr></thead>
                  <tbody>
                    {state.skills.map((sk, i) => (
                      <tr key={sk.name} className="border-b last:border-0">
                        <td className="p-2"><input type="checkbox" checked className="accent-blue-600"
                          onChange={() => setState((s) => ({ ...s, skills: s.skills.filter((_, j) => j !== i) }))} /></td>
                        <td className="p-2 font-medium">{sk.name}</td>
                        <td className="p-2"><Tag>{sk.source === "l4" ? "用户级 · L4（运行时注入）" : "公共技能（进包）"}</Tag></td>
                      </tr>
                    ))}
                    {state.skills.length === 0 && <tr><td colSpan={3} className="p-3 text-xs text-gray-400">从 Agent 详情 → 技能 / 模板包中可用技能按需声明（一期可留空）</td></tr>}
                  </tbody>
                </table>

                <div className="mb-2 mt-4 text-xs text-gray-400">SubAgent（名称 + 职责，或从「集成 Agent」勾选远程端点）</div>
                <table className="w-full rounded-lg border border-gray-200 text-sm" data-testid="subagent-table">
                  <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500"><th className="p-2">名称</th><th className="p-2">职责</th><th className="p-2">形态</th><th className="p-2"></th></tr></thead>
                  <tbody>
                    {state.subAgents.map((sa, i) => (
                      <tr key={sa.agent} className="border-b last:border-0">
                        <td className="p-2 font-medium">{sa.agent}</td>
                        <td className="p-2 text-xs">{sa.role}</td>
                        <td className="p-2"><Tag>{sa.endpoint ? "远程 · Agent Protocol" : "本地"}</Tag></td>
                        <td className="p-2 text-right"><Btn sm kind="danger" onClick={() => setState((s) => ({ ...s, subAgents: s.subAgents.filter((_, j) => j !== i) }))}>移除</Btn></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="mt-2 flex flex-wrap gap-2">
                  <SubAgentAdd integrations={integrations} onAdd={(sa) => setState((s) => ({ ...s, subAgents: [...s.subAgents, sa] }))} />
                </div>
              </div>
              <div>
                <div className="mb-2 text-xs text-gray-400">工具 / MCP（从「工具管理」勾选，发布时注入）</div>
                <table className="w-full rounded-lg border border-gray-200 text-sm" data-testid="mcp-table">
                  <thead><tr className="border-b bg-gray-50 text-left text-xs text-gray-500"><th className="w-9 p-2"></th><th className="p-2">连接器</th><th className="p-2">工具数</th></tr></thead>
                  <tbody>
                    <tr className="border-b">
                      <td className="p-2"><input type="checkbox" className="accent-blue-600"
                        checked={state.mcpServers.some((m) => m.builtin === "platform")}
                        onChange={(e) => setState((s) => ({
                          ...s,
                          mcpServers: e.target.checked
                            ? [...s.mcpServers, { builtin: "platform" }]
                            : s.mcpServers.filter((m) => m.builtin !== "platform"),
                        }))} /></td>
                      <td className="p-2 font-medium">platform-mcp <Tag>平台内置</Tag></td>
                      <td className="p-2">17</td>
                    </tr>
                    {connectors.map((cn) => (
                      <tr key={cn.id} className="border-b last:border-0">
                        <td className="p-2"><input type="checkbox" className="accent-blue-600"
                          checked={state.mcpServers.some((m) => m.connectorId === cn.id)}
                          onChange={(e) => setState((s) => ({
                            ...s,
                            mcpServers: e.target.checked
                              ? [...s.mcpServers, { connectorId: cn.id }]
                              : s.mcpServers.filter((m) => m.connectorId !== cn.id),
                          }))} /></td>
                        <td className="p-2 font-medium">{cn.name}<div className="font-mono text-[11px] text-gray-400">{cn.endpoint}</div></td>
                        <td className="p-2">{toolCount(cn) >= 0 ? toolCount(cn) : "—"}</td>
                      </tr>
                    ))}
                    {connectors.length === 0 && <tr><td colSpan={3} className="p-3 text-xs text-gray-400">暂无连接器 —— 先到「工具管理」创建 MCP 连接器</td></tr>}
                  </tbody>
                </table>
                <PermPreview connectors={connectors} selected={state.mcpServers} />
                <div className="mt-4 rounded-md bg-gray-100 px-3 py-1.5 text-xs text-gray-500">凭证 / 知识库：暂无可用 · 规划中</div>
              </div>
            </div>
          </Section>

          <Section n={4} title="资源配置">
            <Field label="规格" required hint="映射为 Deployment resources（requests/limits），与 DEPLOYMENT_TEMPLATE overlay 互斥校验；创建后的规格变更以运维侧为主">
              <div className="flex flex-wrap gap-2.5" data-testid="spec-cards">
                {SPEC_CARDS.map((sp) => (
                  <button key={sp.id} type="button" data-testid={`spec-${sp.id}`}
                    onClick={() => setState((s) => ({ ...s, spec: sp.id }))}
                    className={`min-w-[108px] rounded-lg border px-4 py-2.5 text-left ${state.spec === sp.id ? "border-blue-600 shadow-[0_0_0_2px_rgba(22,104,220,.14)]" : "border-gray-300"}`}>
                    <b className="block text-[13px]">{sp.title}</b>
                    <small className="text-[11px] text-gray-400">{sp.sub}</small>
                  </button>
                ))}
              </div>
            </Field>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <Field label="副本数" required>
                <select value={state.replicas} data-testid="agent-replicas"
                  onChange={(e) => setState((s) => ({ ...s, replicas: Number(e.target.value) }))}
                  className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm">
                  {[1, 2, 3].map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </Field>
              <Field label="最大实例数" dim hint="对应 HPA 弹性 · 规划中">
                <input value={state.replicas} disabled className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" />
              </Field>
            </div>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <Field label="实例最大 Session 数（1-20）" dim><input value="10" disabled className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" /></Field>
              <Field label="Session TTL（秒，60-86400）" dim><input value="3600" disabled className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm" /></Field>
            </div>
          </Section>

          <Section n={5} title="网络配置">
            <div className="flex items-center gap-2.5 border-b border-dashed border-gray-200 py-2.5">
              <button type="button" role="switch" aria-checked={state.publicEndpoint} data-testid="public-endpoint-switch"
                onClick={() => setState((s) => ({ ...s, publicEndpoint: !s.publicEndpoint }))}
                className={`relative h-[19px] w-[34px] flex-none rounded-full transition ${state.publicEndpoint ? "bg-blue-600" : "bg-gray-300"}`}>
                <span className={`absolute top-0.5 size-[15px] rounded-full bg-white transition-all ${state.publicEndpoint ? "left-[17px]" : "left-0.5"}`} />
              </button>
              <span className="text-[13px]"><b className="font-medium">发布对外访问入口</b><br />
                <small className="text-gray-400">开 = 创建 Ingress / 注册到 platform-router（router 模式）；关 = 仅集群内可访问</small></span>
            </div>
            <div className="mt-2.5 rounded-md bg-gray-100 px-3 py-1.5 font-mono text-xs text-gray-600">
              Endpoint 预览：<span className="text-blue-600">http://{"{INGRESS_HOST}"}/agent/{preview?.k8sName || "<short>"}/</span>
              {!state.publicEndpoint && <span className="ml-2 text-amber-600">（关闭：仅集群内）</span>}
            </div>
            <div className="pointer-events-none mt-2.5 flex items-center gap-2.5 border-b border-dashed border-gray-200 py-2.5 opacity-45">
              <span className="h-[19px] w-[34px] flex-none rounded-full bg-gray-300" />
              <span className="text-[13px]"><b className="font-medium">允许访问 VPC</b><br /><small className="text-gray-400">自建内网依赖（vLLM / 业务库）· 内网部署形态下规划中</small></span>
            </div>
          </Section>

          <div className="flex justify-end gap-2 border-t border-gray-200 pt-3.5">
            <Btn onClick={() => router.push(mode === "edit" ? "/console/agents" : "/console/agents")}>取消</Btn>
            <Btn kind="pri" testid="agent-submit-foot" disabled={submitting} onClick={submit}>
              {submitting ? "提交中…" : mode === "create" ? "立即创建" : "保存"}
            </Btn>
          </div>
        </>}
      </Card>
    </div>
  );
}

function toolCount(cn: McpConnectorView): number {
  try {
    const scope = JSON.parse(cn.toolScope || "{}");
    if (scope.mode === "subset" && Array.isArray(scope.tools)) return scope.tools.length;
  } catch { /* 忽略 */ }
  return -1; // 全量模式：运行时枚举数未知，前端显示 —
}

function PermPreview({ connectors, selected }: { connectors: McpConnectorView[]; selected: { connectorId?: number; builtin?: string }[] }) {
  const picked = connectors.filter((cn) => selected.some((s) => s.connectorId === cn.id));
  if (picked.length === 0) return null;
  return (
    <div className="mt-2 flex flex-wrap items-center gap-1.5 rounded-md bg-gray-100 px-2.5 py-1.5 text-xs text-gray-500">
      权限预览：
      {picked.slice(0, 3).map((cn) => {
        let perms: Record<string, string> = {};
        try { perms = JSON.parse(cn.permissions || "{}").tools ?? {}; } catch { /* 忽略 */ }
        return Object.entries(perms).slice(0, 3).map(([tool, perm]) => (
          <span key={`${cn.id}-${tool}`} className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] ${PERM_STYLE[perm] ?? "bg-gray-100 text-gray-500"}`}>
            {tool} · {perm}
          </span>
        ));
      })}
      <span className="text-gray-400">细调在创建后经包编辑器修改</span>
    </div>
  );
}

function SubAgentAdd({ integrations, onAdd }: {
  integrations: IntegrationView[];
  onAdd: (sa: { agent: string; role?: string; delegations?: string[]; endpoint?: string }) => void;
}) {
  const [open, setOpen] = useState(false);
  const [fromIntegration, setFromIntegration] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState("");
  const [delegations, setDelegations] = useState("");
  const add = () => {
    if (fromIntegration) {
      const it = integrations.find((x) => x.id === Number(fromIntegration));
      if (it) onAdd({ agent: it.name, role: role || "子任务", delegations: delegations ? [delegations] : [], endpoint: it.endpointUrl });
    } else if (name.trim()) {
      onAdd({ agent: name.trim(), role: role || "子任务", delegations: delegations ? [delegations] : [] });
    }
    setOpen(false); setName(""); setRole(""); setDelegations(""); setFromIntegration("");
  };
  if (!open) return <Btn sm onClick={() => setOpen(true)}>+ 添加 SubAgent</Btn>;
  return (
    <div className="w-full rounded-lg border border-gray-200 p-3">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <select value={fromIntegration} onChange={(e) => setFromIntegration(e.target.value)}
          className="rounded border border-gray-300 px-2 py-1.5 text-xs">
          <option value="">手动填写（本地）</option>
          {integrations.map((it) => <option key={it.id} value={it.id}>集成：{it.name}</option>)}
        </select>
        {!fromIntegration && <input value={name} onChange={(e) => setName(e.target.value)} placeholder="名称"
          className="rounded border border-gray-300 px-2 py-1.5 text-xs" />}
        <input value={role} onChange={(e) => setRole(e.target.value)} placeholder="职责"
          className="rounded border border-gray-300 px-2 py-1.5 text-xs" />
        <input value={delegations} onChange={(e) => setDelegations(e.target.value)} placeholder="委派任务描述"
          className="min-w-[160px] flex-1 rounded border border-gray-300 px-2 py-1.5 text-xs" />
        <Btn sm kind="pri" onClick={add} disabled={!fromIntegration && !name.trim()}>确认</Btn>
        <Btn sm onClick={() => setOpen(false)}>取消</Btn>
      </div>
      {fromIntegration && <div className="text-[11px] text-gray-400">endpoint 自动固化：{integrations.find((x) => x.id === Number(fromIntegration))?.endpointUrl}</div>}
    </div>
  );
}

function AdoptPanel({ ws, state, setState, submitting, onSubmit }: {
  ws: number; state: AgentFormState; setState: (fn: (s: AgentFormState) => AgentFormState) => void;
  submitting: boolean; onSubmit: () => void;
}) {
  const [candidates, setCandidates] = useState<PackageRec[]>([]);
  useEffect(() => { api.console.packages(ws).then(setCandidates).catch(() => {}); }, [ws]);
  void state; void setState; void submitting; void onSubmit; void candidates;
  return (
    <Note>纳管入口见「Agent 列表 → 创建 Agent → 纳管已有服务」或运维侧服务列表（仅平台管理员可见）。本空间候选服务在保存后挂入。</Note>
  );
}
