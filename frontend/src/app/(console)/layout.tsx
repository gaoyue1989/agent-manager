"use client";
// 用户侧控制台布局（demo navConsole 还原）：顶栏（logo + 工作空间切换器 + 用户菜单）
// + 左侧导航（212px 分组：概览/观测/AGENT 构建/资产管理/AGENT 协作/身份与权限）。
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { ConsoleWsContext, ConsoleWsRoleContext, useCurrentWs, useMe } from "@/lib/useMe";
import WorkspaceSwitcher from "@/components/WorkspaceSwitcher";

const NAV: { group?: string; items: { href: string; label: string; soon?: boolean }[] }[] = [
  { items: [{ href: "/console", label: "概览" }, { href: "/console/observability", label: "观测大盘", soon: true }] },
  {
    group: "AGENT 构建",
    items: [
      { href: "/console/agents", label: "Agent" },
      { href: "/console/agents/templates", label: "Agent 模板" },
      { href: "/console/integrations", label: "集成 Agent" },
    ],
  },
  {
    group: "资产管理",
    items: [
      { href: "/console/assets/models", label: "模型连接" },
      { href: "/console/assets/skills", label: "Skill 管理" },
      { href: "/console/assets/tools", label: "工具管理" },
      { href: "/console/assets/sandbox", label: "沙箱配置" },
      { href: "/console/assets/knowledge", label: "知识库", soon: true },
      { href: "/console/assets/memory", label: "记忆空间" },
      { href: "/console/assets/credentials", label: "凭证管理" },
    ],
  },
  { group: "AGENT 协作", items: [{ href: "#", label: "Team 协作", soon: true }] },
  { group: "身份与权限", items: [{ href: "/console/settings", label: "成员管理" }] },
];

function NavIcon({ label }: { label: string }) {
  const icons: Record<string, string> = {
    概览: "M3 12 9 3l6 9-6 9-6-9ZM15 8h6v6h-6", Agent: "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8ZM2 12h2m16 0h2M12 2v2m0 16v2",
    "Agent 模板": "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z",
    "集成 Agent": "M8 12h8M12 8v8m7-4a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z",
    模型连接: "M4 6h16M4 12h16M4 18h16M7 6v12", "Skill 管理": "m12 3 2 5 5 2-5 2-2 5-2-5-5-2 5-2 2-5Z",
    工具管理: "M14 6a4 4 0 1 0 4 4l4 4-4 4-4-4a4 4 0 0 1-4-4L4 4l4 4 6-2Z",
    沙箱配置: "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1ZM8 9h8M8 13h8M8 17h5",
    知识库: "M4 4h13a3 3 0 0 1 3 3v13H7a3 3 0 0 1-3-3V4ZM8 8h8M8 12h8",
    记忆空间: "M9 4a5 5 0 0 1 5 5v1a5 5 0 0 1 0 8 5 5 0 0 1-9-3 5 5 0 0 1 0-8 5 5 0 0 1 4-3Z",
    凭证管理: "M15 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM3 5h18v14H3zM7 9h4",
    "成员管理": "M16 14a4 4 0 1 0-8 0M12 4a3 3 0 1 1 0 6 3 3 0 0 1 0-6ZM4 20a8 8 0 0 1 16 0",
    观测大盘: "M4 19h16M7 16V9m5 7V5m5 11v-5",
  };
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" className="size-[15px] flex-none">
      <path d={icons[label] ?? icons["概览"]} />
    </svg>
  );
}

export default function ConsoleLayout({ children }: { children: React.ReactNode }) {
  const { me, loading } = useMe();
  const [current, pick] = useCurrentWs(me);
  const pathname = usePathname();
  const router = useRouter();
  const [userOpen, setUserOpen] = useState(false);
  const userRef = useRef<HTMLDivElement>(null);
  // 窄屏侧边栏抽屉（issue #154：固定 212px 曾把内容挤出横向滚动）
  const [navOpen, setNavOpen] = useState(false);
  // 路由变化即收起移动端抽屉
  useEffect(() => { setNavOpen(false); }, [pathname]);

  useEffect(() => {
    const close = (e: MouseEvent) => { if (!userRef.current?.contains(e.target as Node)) setUserOpen(false); };
    document.addEventListener("click", close);
    return () => document.removeEventListener("click", close);
  }, []);

  // 工作空间切换 → 整页刷新（各页读 localStorage 重新取数）
  useEffect(() => {
    const handler = () => router.refresh();
    window.addEventListener("oaf-ws-changed", handler);
    return () => window.removeEventListener("oaf-ws-changed", handler);
  }, [router]);

  const logout = async () => {
    try { await api.auth.logout(); } catch { /* 服务端无状态，清 cookie 即可 */ }
    router.replace("/login");
  };
  const isAdmin = me?.user.role === "admin";
  const initial = (me?.user.displayName || me?.user.username || "·").slice(0, 1);

  return (
    <div className="flex min-h-screen flex-col">
      {/* z-[60] 高于侧边栏 z-50：顶栏内下拉（工作空间/用户菜单）需覆盖侧边栏 */}
      <header className="sticky top-0 z-[60] flex h-[52px] items-center justify-between gap-2 border-b border-gray-200 bg-white px-3 sm:px-4">
        <div className="flex min-w-0 items-center gap-2 sm:gap-2.5">
          <button onClick={() => setNavOpen(!navOpen)} data-testid="nav-toggle" aria-label="导航"
            className="-ml-1 rounded-md p-1.5 text-gray-600 hover:bg-gray-100 md:hidden">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="size-5"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
          </button>
          <Link href="/console" className="flex items-center gap-2 pr-2 text-sm font-semibold">
            <span className="flex size-[26px] items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 to-blue-400 text-white">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="size-[15px]"><rect x="4" y="7" width="16" height="12" rx="3" /><circle cx="9" cy="13" r="1.2" fill="currentColor" stroke="none" /><circle cx="15" cy="13" r="1.2" fill="currentColor" stroke="none" /></svg>
            </span>
            <span className="hidden sm:inline">智能体平台</span>
          </Link>
          {me && current && <WorkspaceSwitcher me={me} current={current} onPick={pick} />}
          <span className="hidden rounded-full border border-blue-200 bg-blue-50 px-2 py-0.5 text-[11px] text-blue-600 sm:inline">用户侧</span>
        </div>
        <div className="flex flex-none items-center gap-1.5">
          {isAdmin && (
            <Link href="/admin" data-testid="switch-admin"
              className="flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-[13px] text-gray-500 hover:bg-gray-100 hover:text-gray-900">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="size-4"><path d="M12 3 4 6v6c0 4.4 3.4 8 8 9 4.6-1 8-4.6 8-9V6l-8-3Z" /><path d="m9 12 2 2 4-4" /></svg>
              <span className="hidden sm:inline">运维侧</span>
            </Link>
          )}
          <div ref={userRef} className="relative">
            <button onClick={() => setUserOpen(!userOpen)} data-testid="user-menu"
              className="flex items-center gap-1.5 rounded-lg px-2 py-1 hover:bg-gray-100">
              <span className="flex size-6 items-center justify-center rounded-full bg-gradient-to-br from-indigo-500 to-purple-500 text-xs text-white">{initial}</span>
              <span className="hidden text-[13px] text-gray-700 sm:inline">{me?.user.displayName || me?.user.username || "…"}</span>
            </button>
            {userOpen && (
              <div className="absolute right-0 top-[calc(100%+6px)] z-50 w-44 rounded-xl border border-gray-200 bg-white p-1.5 shadow-xl">
                <div className="px-2.5 py-1.5 text-[11px] text-gray-400">
                  {me?.user.username} · {me?.user.role === "admin" ? "管理员" : "开发者"}
                </div>
                <button onClick={logout} data-testid="logout-btn"
                  className="flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-[13px] text-gray-700 hover:bg-gray-50">
                  退出登录
                </button>
              </div>
            )}
          </div>
        </div>
      </header>
      <div className="flex flex-1">
        {/* 窄屏抽屉遮罩 */}
        {navOpen && <div className="fixed inset-0 z-40 bg-black/30 md:hidden" onClick={() => setNavOpen(false)} />}
        <aside data-testid="console-nav"
          className={`fixed inset-y-0 left-0 z-50 w-[212px] flex-none overflow-y-auto border-r border-gray-200 bg-white px-2 pt-[60px] transition-transform duration-200 md:static md:translate-x-0 md:px-2 md:pt-2.5 ${navOpen ? "translate-x-0" : "-translate-x-full"}`}>
          {NAV.map((sec, i) => (
            <div key={i}>
              {sec.group && <div className="px-3 pb-1 pt-3.5 text-[11px] tracking-wider text-gray-400">{sec.group}</div>}
              {sec.items.map((item) => {
                const active = item.href === "/console"
                  ? pathname === "/console"
                  : item.href === "/console/agents"
                    ? pathname.startsWith("/console/agents") && !pathname.includes("templates")
                    : pathname.startsWith(item.href);
                if (item.href === "#") {
                  return (
                    <span key={item.label} className="flex cursor-default items-center gap-2 rounded-lg px-2.5 py-1.5 text-[13px] text-gray-400">
                      <NavIcon label={item.label} /><span>{item.label}</span>
                      {item.soon && <i className="ml-auto rounded border border-gray-200 px-1 text-[10px] not-italic text-gray-400">远期</i>}
                    </span>
                  );
                }
                return (
                  <Link key={item.href} href={item.href}
                    className={`flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-[13px] ${active ? "bg-blue-50 font-medium text-blue-600" : "text-gray-600 hover:bg-gray-50 hover:text-gray-900"}`}>
                    <NavIcon label={item.label} /><span>{item.label}</span>
                    {item.soon && <i className="ml-auto rounded border border-gray-200 px-1 text-[10px] not-italic text-gray-400">规划中</i>}
                  </Link>
                );
              })}
            </div>
          ))}
        </aside>
        <main className="min-w-0 flex-1 px-5 pb-8 pt-4.5">
          {loading ? (
            <p className="p-6 text-sm text-gray-400">加载中…</p>
          ) : !current && me ? (
            <div className="mx-auto mt-10 max-w-md rounded-xl border border-gray-200 bg-white p-6 text-center text-sm text-gray-500">
              你还未加入任何工作空间。请联系平台管理员在「运维侧 → 工作空间管理」中创建并邀请你加入。
            </div>
          ) : current ? (
            <ConsoleWsContext.Provider value={current.id}>
              <ConsoleWsRoleContext.Provider value={current.role}>{children}</ConsoleWsRoleContext.Provider>
            </ConsoleWsContext.Provider>
          ) : null}
        </main>
      </div>
    </div>
  );
}


