"use client";
// 登录页（demo oa-login 还原：左品牌区 + 右登录卡；demo 的「以角色进入」卡片不实装，M0 §3.2）
import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api } from "@/lib/api";

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username.trim() || !password || busy) return;
    setBusy(true);
    setErr("");
    try {
      const data = await api.auth.login(username.trim(), password);
      // cookie 由后端 Set-Cookie 写入（httpOnly）；前端按角色跳默认工作台
      const next = params.get("next");
      const target = next && next.startsWith("/") ? next : (data.user.role === "admin" ? "/admin" : "/console");
      router.replace(target);
    } catch (e: any) {
      setErr(e.message || "登录失败");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div data-testid="login-page" className="min-h-screen bg-gradient-to-br from-[#0b1f3f] via-[#123a75] to-[#1668dc] flex items-center justify-center gap-14 px-6 py-10 flex-wrap">
      <div className="max-w-sm text-white">
        <div className="flex items-center gap-2.5 text-xl font-semibold mb-4">
          <span className="flex size-9 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-blue-400 shadow-lg">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="size-5"><rect x="4" y="7" width="16" height="12" rx="3" /><circle cx="9" cy="13" r="1.4" fill="currentColor" stroke="none" /><circle cx="15" cy="13" r="1.4" fill="currentColor" stroke="none" /><path d="M12 7V4" /></svg>
          </span>
          智能体平台
        </div>
        <h2 className="text-2xl font-semibold mb-2.5">构建与运行 · 协作与治理 · 观测与评估</h2>
        <p className="text-white/70 text-sm leading-7">
          对标 AgentCore 的控制台分层：用户侧以工作空间组织 Agent 全生命周期，运维侧保留平台治理与发布能力。业务数据面直连运行时，不经过控制面。
        </p>
        <ul className="mt-4 space-y-2 text-white/85 text-[13px]">
          <li className="flex items-center gap-2"><span className="text-blue-300">✓</span>工作空间隔离资源，成员协作</li>
          <li className="flex items-center gap-2"><span className="text-blue-300">✓</span>多模式构建：托管 Harness 表单生成 OAF 包 / 包导入 / 纳管存量</li>
          <li className="flex items-center gap-2"><span className="text-blue-300">✓</span>资产统一管理：模型 · Skill · 工具 · 记忆 · 凭证</li>
          <li className="flex items-center gap-2"><span className="text-blue-300">✓</span>运维侧与用户侧同一入口、按角色分流</li>
        </ul>
      </div>
      <form onSubmit={submit} className="w-[330px] rounded-2xl bg-white p-6 shadow-2xl">
        <h3 className="text-base font-semibold">登录控制台</h3>
        <p className="text-xs text-gray-400 mb-4">首个管理员由 ADMIN_INIT_USER 环境变量种子创建</p>
        <label className="block text-[13px] font-medium mb-1.5">用户名</label>
        <input value={username} onChange={(e) => { setErr(""); setUsername(e.target.value); }} autoComplete="username"
          data-testid="login-username"
          className="w-full rounded-md border border-gray-300 px-2.5 py-2 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100 mb-3" />
        <label className="block text-[13px] font-medium mb-1.5">密码</label>
        <input type="password" value={password} onChange={(e) => { setErr(""); setPassword(e.target.value); }} autoComplete="current-password"
          data-testid="login-password"
          className="w-full rounded-md border border-gray-300 px-2.5 py-2 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100 mb-4" />
        {err && <div data-testid="login-error" className="mb-3 rounded-md bg-red-50 border border-red-200 px-2.5 py-1.5 text-xs text-red-700">{err}</div>}
        {/* 空白（纯空格）输入时按钮禁用并给中性提示（issue #153：旧错误残留误导） */}
        {(username.trim() !== "" && password.trim() === "") && !err &&
          <div className="mb-3 rounded-md bg-gray-50 border border-gray-200 px-2.5 py-1.5 text-xs text-gray-500">请输入密码</div>}
        <button type="submit" disabled={busy || !username.trim() || !password.trim()}
          data-testid="login-submit"
          className="w-full rounded-lg bg-blue-600 py-2 text-sm font-medium text-white transition hover:bg-blue-700 disabled:opacity-50">
          {busy ? "登录中…" : "登录"}
        </button>
      </form>
    </div>
  );
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
