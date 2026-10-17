"use client";
// 用户侧 · Skill 管理（设计口径 2026-10-17 修订）：工作空间内 **OAF 包配置技能清单**。
// 数据源 = 空间可见包（公共 + 本空间）的 frontmatter skills 声明 + 包内 skills/ 目录扫描；
// 去除 L4 运行时分级概念（技能分级是 Agent 运行时的事，本页只做配置的展示与查询）。
// 技能的新增/修改/删除 = Agent 编辑页（copy-on-write 生成新版本包），本页提供跳转入口。
import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { api } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole } from "@/lib/useMe";
import { Badge, Btn, Card, Note, PageHead, Tag } from "@/components/ui";

interface SpaceSkill {
  name: string;
  description: string;
  packageId: number;
  packageSlug: string;
  packageVersion: string;
  workspaceId: number;
  declared: boolean;
  usedBy: string[];
}

export default function SkillsPage() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const writable = role === "owner" || role === "admin";
  const [list, setList] = useState<SpaceSkill[]>([]);
  const [keyword, setKeyword] = useState("");
  const [onlyDeclared, setOnlyDeclared] = useState(false);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState("");

  const load = useCallback(async () => {
    try {
      setList(await api.console.spaceSkills(ws, keyword));
      setMsg("");
    } catch (e: any) { setMsg(`加载失败: ${e.message}`); }
    finally { setLoading(false); }
  }, [ws, keyword]);
  useEffect(() => { if (ws) load(); }, [ws, load]);

  // 按包分组（同包多技能聚合展示）
  const groups = useMemo(() => {
    const filtered = onlyDeclared ? list.filter((s) => s.declared) : list;
    const byPkg = new Map<number, { slug: string; version: string; workspaceId: number; skills: SpaceSkill[] }>();
    for (const s of filtered) {
      let g = byPkg.get(s.packageId);
      if (!g) { g = { slug: s.packageSlug, version: s.packageVersion, workspaceId: s.workspaceId, skills: [] }; byPkg.set(s.packageId, g); }
      g.skills.push(s);
    }
    return [...byPkg.entries()];
  }, [list, onlyDeclared]);

  const declaredCount = list.filter((s) => s.declared).length;
  const dirCount = list.length - declaredCount;

  return (
    <div data-testid="console-skills">
      <PageHead title="Skill 管理" sub="工作空间内 OAF 包的配置技能清单（frontmatter skills 声明 + 包内 skills/ 目录）；技能的新增与修改经 Agent 编辑页生成新版本包"
        actions={
          <input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="搜索技能名 / 描述"
            data-testid="skill-keyword"
            className="w-[220px] rounded-md border border-gray-300 px-2.5 py-1.5 text-[13px]" />
        } />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}

      <div className="mb-3 flex items-center gap-4 text-xs text-gray-500">
        <span>共 <b className="text-gray-800">{list.length}</b> 个技能 · 声明 {declaredCount} · 目录 {dirCount}</span>
        <label className="inline-flex cursor-pointer items-center gap-1.5">
          <input type="checkbox" checked={onlyDeclared} onChange={(e) => setOnlyDeclared(e.target.checked)}
            className="accent-blue-600" data-testid="skill-declared-only" />
          仅看 frontmatter 声明
        </label>
      </div>

      {loading ? <p className="text-sm text-gray-400">加载中…</p> : groups.length === 0 ? (
        <Card><div className="py-10 text-center text-sm text-gray-400">
          工作空间内的 OAF 包暂未包含技能——在 Agent 编辑页的「资产配置 → Skill」勾选技能（或经包编辑器在包内添加 skills/ 目录），保存后生成新版本包并出现在此清单。
        </div></Card>
      ) : (
        <div className="space-y-3" data-testid="skill-groups">
          {groups.map(([pkgId, g]) => (
            <div key={pkgId} className="rounded-xl border border-gray-200 bg-white">
              {/* 包头：包信息 + 编辑入口（copy-on-write） */}
              <div className="flex items-center gap-2 border-b border-gray-100 px-4 py-2.5">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="size-4 text-gray-400"><path d="M21 8l-9-5-9 5v8l9 5 9-5V8Z M3 8l9 5 9-5 M12 13v8" /></svg>
                <b className="text-[13px] font-semibold">{g.slug}</b>
                <Tag>v{g.version}</Tag>
                {g.workspaceId === 0 ? <Tag>公共包</Tag> : null}
                <span className="ml-auto flex items-center gap-2">
                  <small className="text-[11px] text-gray-400">
                    {/* usedBy ?? []：后端旧版本可能序列化 null，直接取 length 会整页崩 */}
                    {g.skills.some((s) => (s.usedBy ?? []).length > 0)
                      ? `被引用：${[...new Set(g.skills.flatMap((s) => s.usedBy ?? []))].join("、")}`
                      : "未被引用"}
                  </small>
                  <Link href={`/admin/packages/${pkgId}`} data-testid={`skill-pkg-${pkgId}`}
                    className="rounded border border-gray-300 px-2 py-0.5 text-[11px] text-gray-600 hover:border-blue-200 hover:text-blue-600">包编辑器</Link>
                  {writable && (
                    <Link href={`/console/agents`} data-testid={`skill-edit-${pkgId}`}
                      className="rounded border border-blue-600 bg-blue-600 px-2 py-0.5 text-[11px] text-white hover:bg-blue-700">经 Agent 编辑添加技能</Link>
                  )}
                </span>
              </div>
              {/* 技能条目 */}
              <table className="w-full text-sm">
                <thead><tr className="bg-gray-50/60 text-left text-[11px] text-gray-400">
                  <th className="px-4 py-1.5 font-medium">技能</th>
                  <th className="px-4 py-1.5 font-medium">描述</th>
                  <th className="px-4 py-1.5 font-medium">来源</th>
                </tr></thead>
                <tbody>
                  {g.skills.map((s) => (
                    <tr key={s.name} className="border-t border-gray-100">
                      <td className="px-4 py-2 font-mono text-xs font-medium">{s.name}</td>
                      <td className="max-w-[440px] px-4 py-2 text-xs text-gray-500">
                        {s.description || <span className="text-gray-300">—</span>}
                      </td>
                      <td className="px-4 py-2">
                        {s.declared
                          ? <Badge status="running">frontmatter 声明</Badge>
                          : <Badge status="stopped">skills/ 目录</Badge>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
        </div>
      )}

      <Note>技能来源与修改口径：本页清单来自 **OAF 包配置**（frontmatter `skills:` 声明，或包内 `skills/&lt;name&gt;/SKILL.md` 目录——目录是事实来源，声明是意图）。
      技能分级（系统/包级/用户级）是 Agent **运行时**的加载概念，不在本页管理；新增/修改技能请进入 Agent 编辑页（copy-on-write 生成新版本包）或直接使用包编辑器。</Note>
    </div>
  );
}
