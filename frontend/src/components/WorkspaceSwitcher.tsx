"use client";
// 工作空间切换器（demo oa-ws 还原）：顶栏下拉列空间（slug 副标注）+「创建工作空间」
// 普通用户一期不可建（决议 6：仅 admin 建号分派）→ toast 引导找管理员。
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import type { MeResponse, WorkspaceBrief } from "@/lib/api";

export default function WorkspaceSwitcher({ me, current, onPick }: {
  me: MeResponse; current: WorkspaceBrief | null; onPick: (ws: WorkspaceBrief) => void;
}) {
  const [open, setOpen] = useState(false);
  const [toast, setToast] = useState("");
  const boxRef = useRef<HTMLDivElement>(null);
  const router = useRouter();

  useEffect(() => {
    const close = (e: MouseEvent) => {
      if (!boxRef.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("click", close);
    return () => document.removeEventListener("click", close);
  }, []);

  const real = me.workspaces.filter((w) => w.id > 0);
  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(""), 2200);
  };

  return (
    <div ref={boxRef} className="relative" data-testid="ws-switcher">
      <button onClick={() => setOpen(!open)} data-testid="ws-switcher-btn"
        className="flex items-center gap-1.5 rounded-lg border border-gray-200 bg-white px-2.5 py-1.5 text-[13px] text-gray-500 hover:border-blue-200 hover:text-blue-600">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="size-3.5"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" /></svg>
        <b className="max-w-[140px] truncate font-medium text-gray-900">{current?.name ?? "工作空间"}</b>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="size-3"><path d="m6 9 6 6 6-6" /></svg>
      </button>
      {open && (
        <div className="absolute left-0 top-[calc(100%+6px)] z-50 min-w-[240px] rounded-xl border border-gray-200 bg-white p-1.5 shadow-xl" data-testid="ws-menu">
          {real.map((w) => (
            <button key={w.id} onClick={() => { onPick(w); setOpen(false); }}
              data-testid={`ws-item-${w.slug}`}
              className={`flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-[13px] hover:bg-gray-50 ${current?.id === w.id ? "bg-blue-50 text-blue-600" : "text-gray-700"}`}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="size-3.5 text-gray-400"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" /></svg>
              {w.name}
              <small className="ml-auto text-[11px] text-gray-400">{w.slug}</small>
            </button>
          ))}
          {me.user.role === "admin" ? (
            <button onClick={() => { setOpen(false); router.push("/admin/workspaces"); }}
              className="mt-0.5 flex w-full items-center gap-2 rounded-md border-t border-gray-100 px-2.5 py-2 text-left text-[13px] text-blue-600 hover:bg-gray-50">
              + 创建工作空间（运维侧）
            </button>
          ) : (
            <button onClick={() => { setOpen(false); showToast("工作空间由管理员创建，请联系平台管理员加入"); }}
              className="mt-0.5 flex w-full items-center gap-2 rounded-md border-t border-gray-100 px-2.5 py-2 text-left text-[13px] text-gray-400">
              + 创建工作空间
            </button>
          )}
        </div>
      )}
      {toast && (
        <div className="fixed bottom-6 left-1/2 z-[100] -translate-x-1/2 rounded-lg bg-gray-900 px-4 py-2 text-xs text-white">{toast}</div>
      )}
    </div>
  );
}
