"use client";
// 控制台轻量 UI 基件（Tailwind 原生，对齐 demo 视觉：蓝主色 + 卡片 + 徽标）。
import Link from "next/link";
import { badgeClass } from "@/lib/status";

export function Badge({ status, children }: { status: string; children?: React.ReactNode }) {
  return (
    <span data-testid={`badge-${status}`}
      className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] whitespace-nowrap ${badgeClass(status)}`}>
      <span className="size-1.5 rounded-full bg-current" />
      {children ?? status}
    </span>
  );
}

export function Tag({ children }: { children: React.ReactNode }) {
  return <span className="inline-flex items-center rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-600 mr-1 whitespace-nowrap">{children}</span>;
}

export function StatCard({ label, value, sub, danger }: { label: string; value: React.ReactNode; sub?: string; danger?: boolean }) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white px-4 py-3" data-testid={`stat-${label}`}>
      <div className="text-xs text-gray-400">{label}</div>
      <div className={`mt-0.5 text-[22px] font-semibold tabular-nums ${danger ? "text-red-600" : ""}`}>
        {value}{sub && <em className="ml-1.5 text-xs font-normal not-italic text-gray-400">{sub}</em>}
      </div>
    </div>
  );
}

export function Card({ title, children, className = "" }: { title?: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={`rounded-xl border border-gray-200 bg-white p-4 ${className}`}>
      {title && <h3 className="mb-3 text-[13px] font-semibold">{title}</h3>}
      {children}
    </div>
  );
}

export function PageHead({ title, sub, actions }: { title: React.ReactNode; sub?: React.ReactNode; actions?: React.ReactNode }) {
  return (
    <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="flex flex-wrap items-center gap-2.5 text-[17px] font-semibold">{title}</h1>
        {sub && <div className="mt-0.5 text-xs text-gray-400">{sub}</div>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function Note({ children, tone = "info" }: { children: React.ReactNode; tone?: "info" | "warn" | "plain" }) {
  const cls = tone === "warn"
    ? "border-amber-200 bg-amber-50 text-amber-700"
    : tone === "plain"
      ? "border-gray-200 bg-gray-50 text-gray-600"
      : "border-blue-200 bg-blue-50 text-blue-700";
  return <div className={`mb-3 flex items-start gap-2 rounded-lg border px-3 py-2 text-xs ${cls}`}>{children}</div>;
}

export function Btn({ children, onClick, kind = "default", sm, disabled, testid, type = "button" }: {
  children: React.ReactNode; onClick?: () => void; kind?: "default" | "pri" | "danger"; sm?: boolean; disabled?: boolean; testid?: string; type?: "button" | "submit";
}) {
  const base = "inline-flex items-center gap-1.5 rounded-md border whitespace-nowrap transition disabled:opacity-50";
  const kindCls = kind === "pri"
    ? "border-blue-600 bg-blue-600 text-white hover:bg-blue-700"
    : kind === "danger"
      ? "border-gray-300 text-red-600 hover:border-red-200 hover:bg-red-50"
      : "border-gray-300 bg-white text-gray-600 hover:border-blue-200 hover:text-blue-600";
  return (
    <button type={type} onClick={onClick} disabled={disabled} data-testid={testid}
      className={`${base} ${kindCls} ${sm ? "px-2 py-1 text-xs" : "px-3 py-1.5 text-[13px]"}`}>
      {children}
    </button>
  );
}

// 占位页（M2 起：侧导航全量挂出，未实现模块先占位）
export function ComingSoon({ icon = "🚧", title, desc, action }: { icon?: string; title: string; desc?: string; action?: { href: string; label: string } }) {
  return (
    <Card>
      <div className="py-12 text-center text-gray-400">
        <div className="mx-auto mb-3 flex size-11 items-center justify-center rounded-xl bg-gray-100 text-xl">{icon}</div>
        <b className="text-gray-600">{title}</b>
        <div className="mx-auto mt-1.5 max-w-md text-xs leading-relaxed">{desc}</div>
        {action && (
          <Link href={action.href} className="mt-4 inline-block rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs text-gray-600 hover:border-blue-200 hover:text-blue-600">
            {action.label}
          </Link>
        )}
      </div>
    </Card>
  );
}
