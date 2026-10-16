"use client";
// 运维侧布局（M0）：沿用现有顶栏风格 + 迁移后页面导航 + 平台治理新页 + 顶栏「用户侧」切换（仅 admin）。
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { useMe } from "@/lib/useMe";

const LINKS = [
  { href: "/admin", label: "服务列表" },
  { href: "/admin/publish", label: "发布新服务" },
  { href: "/admin/assistant", label: "发布助手" },
  { href: "/admin/packages", label: "配置包" },
  { href: "/admin/workspaces", label: "工作空间管理" },
  { href: "/admin/users", label: "用户管理" },
  { href: "/admin/audit", label: "审计日志" },
  { href: "/admin/overview", label: "平台概览" },
  { href: "/admin/settings", label: "平台配置" },
];

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const { me } = useMe();
  const router = useRouter();
  const logout = async () => {
    try { await api.auth.logout(); } catch { /* 无状态 */ }
    router.replace("/login");
  };
  return (
    <div className="min-h-screen">
      <nav className="sticky top-0 z-40 flex items-center gap-5 border-b border-gray-200 bg-white px-6 py-3">
        <span className="text-lg font-bold">OAF 发布平台</span>
        <span className="rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-[11px] text-amber-600">运维侧</span>
        {LINKS.map((l) => (
          <Link key={l.href} href={l.href} className="text-sm text-gray-700 hover:text-blue-600">{l.label}</Link>
        ))}
        <span className="ml-auto flex items-center gap-3 text-sm">
          {me?.user.role === "admin" && (
            <Link href="/console" data-testid="switch-console" className="text-gray-500 hover:text-blue-600">用户侧</Link>
          )}
          <span className="text-gray-500">{me?.user.displayName || me?.user.username || ""}</span>
          <button onClick={logout} data-testid="admin-logout" className="text-gray-400 hover:text-red-600">退出</button>
        </span>
      </nav>
      <main className="mx-auto max-w-6xl p-6">{children}</main>
    </div>
  );
}
