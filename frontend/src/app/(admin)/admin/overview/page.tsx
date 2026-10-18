"use client";
// 运维侧 · 平台概览（demo a/overview）：前端聚合 services/users/packages 三列表（M1 §3.1 采用前端聚合，不加端点）。
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api, type PackageRec, type ServiceRec, type UserView } from "@/lib/api";
import type { PodInfo } from "@/lib/api";
import { Card, PageHead, StatCard, Badge } from "@/components/ui";

export default function AdminOverviewPage() {
  const [services, setServices] = useState<(ServiceRec & { pods?: PodInfo[] })[]>([]);
  const [users, setUsers] = useState<UserView[]>([]);
  const [packages, setPackages] = useState<PackageRec[]>([]);
  const [msg, setMsg] = useState("");

  const load = useCallback(async () => {
    try {
      const [s, u, p] = await Promise.all([api.listServices(), api.admin.listUsers(), api.listPackages()]);
      setServices(s); setUsers(u); setPackages(p);
      setMsg("");
    } catch (e: any) { setMsg(`加载失败: ${e.message}`); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const running = services.filter((s) => s.status === "running").length;
  const bad = services.filter((s) => ["error", "deploy_failed", "register_failed", "stopped"].includes(s.status)).length;
  const admins = users.filter((u) => u.role === "admin").length;
  const publicPkgs = packages.filter((p) => (p.workspaceId ?? 0) === 0).length;

  return (
    <div data-testid="admin-overview">
      <PageHead title="平台概览" sub="运维侧 · 全部工作空间聚合" />
      {msg && <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-5">
        <StatCard label="服务总数" value={services.length} sub={`${new Set(services.map((s) => s.workspaceId)).size} 个工作空间`} />
        <StatCard label="运行中" value={running} />
        <StatCard label="异常 / 已停止" value={bad} danger={bad > 0} />
        <StatCard label="用户 / 成员" value={users.length} sub={`${admins} 管理员`} />
        <StatCard label="OAF 包" value={packages.length} sub={`公共 ${publicPkgs}`} />
      </div>
      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-[1.6fr_1fr]">
        <Card title="最近服务状态（实时轮询）">
          <ul className="space-y-0 pl-1.5 text-[13px]">
            {services.slice(0, 8).map((s) => (
              <li key={s.id} className="relative border-l border-gray-200 pb-3.5 pl-5 before:absolute before:-left-[4.5px] before:top-1 before:size-2 before:rounded-full"
                style={{ ["--tw-before-bg" as string]: "" }}>
                <span className={`absolute -left-[3.5px] top-1.5 size-1.5 rounded-full ${s.status === "running" ? "bg-emerald-500" : s.status === "stopped" ? "bg-gray-300" : "bg-red-500"}`} />
                <Link href={`/admin/services/${s.id}`} className="font-medium text-blue-600 hover:underline">{s.displayName}</Link>
                <span className="ml-2"><Badge status={s.status} /></span>
                <small className="block text-[11px] text-gray-400">{s.k8sName} · {new Date(s.createdAt).toLocaleString("zh-CN", { hour12: false })}</small>
              </li>
            ))}
            {services.length === 0 && <p className="text-sm text-gray-400">暂无服务</p>}
          </ul>
        </Card>
        <Card title="运维入口">
          <div className="flex flex-col gap-2.5">
            <Link href="/admin" className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">服务列表（现有功能）</Link>
            <Link href="/admin/workspaces" className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">工作空间管理</Link>
            <Link href="/admin/users" className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">用户管理</Link>
            <Link href="/admin/settings" className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">平台默认配置</Link>
          </div>
        </Card>
      </div>
    </div>
  );
}
