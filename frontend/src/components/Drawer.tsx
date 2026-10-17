"use client";
// 右侧抽屉（slide-over）：新建/编辑类操作的统一弹出形态（自右侧滑入，Esc/遮罩关闭）。
import { useEffect } from "react";

export default function Drawer({ open, title, onClose, children, width = 480, testid }: {
  open: boolean;
  title: React.ReactNode;
  onClose: () => void;
  children: React.ReactNode;
  width?: number;
  testid?: string;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-[60]" data-testid={testid}>
      <div className="absolute inset-0 bg-black/30" onClick={onClose} data-testid="drawer-mask" />
      <aside role="dialog" aria-label={typeof title === "string" ? title : undefined}
        style={{ width: `min(${width}px, calc(100vw - 24px))` }}
        className="absolute right-0 top-0 flex h-full flex-col border-l border-gray-200 bg-white shadow-2xl animate-slide-in-right">
        <header className="flex flex-none items-center justify-between border-b border-gray-200 px-4 py-3">
          <b className="text-sm font-semibold">{title}</b>
          <button onClick={onClose} aria-label="关闭" data-testid="drawer-close"
            className="rounded-md p-1 text-gray-400 transition hover:bg-gray-100 hover:text-gray-700">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="size-4"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </header>
        <div className="flex-1 overflow-y-auto p-4">{children}</div>
      </aside>
    </div>
  );
}
