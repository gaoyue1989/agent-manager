"use client";
// 登录态共享 hook：auth/me（用户 + 工作空间列表）+ localStorage 记忆的当前空间。
import { useCallback, useEffect, useState } from "react";
import { api, type MeResponse, type WorkspaceBrief } from "@/lib/api";

const WS_KEY = "oaf-console-ws";

export function useMe() {
  const [me, setMe] = useState<MeResponse | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    try {
      const data = await api.auth.me();
      setMe(data);
      setError("");
    } catch (e: any) {
      setError(e.message || "加载登录态失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { reload(); }, [reload]);
  return { me, error, loading, reload };
}

// 当前工作空间：localStorage 记最近选择（失效自动回落第一个成员空间）。
export function useCurrentWs(me: MeResponse | null): [WorkspaceBrief | null, (ws: WorkspaceBrief) => void] {
  const [ws, setWs] = useState<WorkspaceBrief | null>(null);
  useEffect(() => {
    if (!me) return;
    const real = me.workspaces.filter((w) => w.id > 0); // admin 虚拟空间（id=0）不参与切换
    if (real.length === 0) { setWs(null); return; }
    const saved = Number(window.localStorage.getItem(WS_KEY) || 0);
    setWs(real.find((w) => w.id === saved) ?? real[0]);
  }, [me]);
  const pick = useCallback((next: WorkspaceBrief) => {
    window.localStorage.setItem(WS_KEY, String(next.id));
    setWs(next);
    window.dispatchEvent(new CustomEvent("oaf-ws-changed", { detail: next.id }));
  }, []);
  return [ws, pick];
}

// ---- 当前工作空间 id 的全局 context（(console)/layout 提供方，页面消费）----
import { createContext, useContext } from "react";

export const ConsoleWsContext = createContext<number>(0);

export function useConsoleWs(): number {
  return useContext(ConsoleWsContext);
}
