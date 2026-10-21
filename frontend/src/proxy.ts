import { NextResponse, type NextRequest } from "next/server";
import { evalCollectorTarget } from "./lib/eval-session";
import { isValidAgentShortName } from "./lib/agent-short";

// 同源反代默认目标(集群内固定地址)。取值在请求期读取环境变量(Next 16 proxy
// 默认 Node.js runtime,process.env 不被构建期烘焙),部署时通过 Deployment env
// 按环境覆盖即可,无需重建镜像。
const DEFAULT_BACKEND = "http://platform-backend.agent-platform.svc.cluster.local:8080";
const DEFAULT_RELEASE_AGENT = "http://oaf-release-agent-svc.agent-platform.svc.cluster.local:8100";

// 评测采集模式（设计 docs/design/agent-framework-eval-offline-record-replay-design.md 附录 C）：
// 显式开关 EVAL_COLLECTOR_MODE=1（请求期求值，同下方 env 约定，无需重建镜像；默认关闭）
// 时，发布助手流量改经 eval-collector 反代口（目标取 EVAL_COLLECTOR_AGENT_URL，
// 如 http://<collector>:18203/{ns}）透传+录制，并注入 x-eval-session（cookie
// oaf-assistant-sid 派生，assistant 页 localStorage/cookie 双写）做强会话关联。
// 开关开启但目标未设置：console.error 告警并退回普通代理（不注入），不打流量到未定义地址；
// 采集结束删除/置 0 开关即还原。其余取值（含未设置）均视为关闭，行为与普通代理完全一致。

const RELEASE_AGENT_PREFIX = "/agent/release-agent";

// 拼接目标 URL:base 允许携带子路径(如经 ingress 的 /agent/release-agent 或 collector 的 /{ns})。
// 必须字符串拼接而非 new URL(path, base)——绝对 path 会整体覆盖 base 的路径部分。
function joinTarget(base: string, pathAndQuery: string): string {
  return `${base.replace(/\/+$/, "")}${pathAndQuery}`;
}

// oaf_session（JWT）payload 解析（仅 base64 解码，不验签——权威鉴权在 backend，
// 控制台路径身份可信即可；M2 §3.2 / 设计 §5.4 一期口径）。
function readJwtPayload(token: string): { uid?: number; role?: string } | null {
  try {
    const parts = token.split(".");
    if (parts.length !== 3) return null;
    const json = Buffer.from(parts[1].replace(/-/g, "+").replace(/_/g, "/"), "base64").toString("utf8");
    return JSON.parse(json);
  } catch {
    return null;
  }
}

function readUidFromJwtCookie(request: NextRequest): string | null {
  const token = request.cookies.get("oaf_session")?.value;
  if (!token) return null;
  const payload = readJwtPayload(token);
  return payload?.uid != null ? String(payload.uid) : null;
}

// 页面路由守卫（Next 16 proxy 取代 middleware；M0 §3.1 UX 层，权威裁决在 backend）：
//   无 oaf_session cookie → /login（带 next 回跳）；/admin/* 非 admin → /console。
// 数据面（/api、/agent、/mcp）与静态资源由 matcher 排除，不走本守卫。
function guardPage(request: NextRequest): NextResponse | null {
  const { pathname } = request.nextUrl;
  if (pathname.startsWith("/api/") || pathname.startsWith("/agent/") || pathname.startsWith("/mcp")) {
    return null;
  }
  // 登录页无条件放行（漏掉会让未登录访问 /login 循环 302——部署实测 kubelet 探针
  // "stopped after 10 redirects" 暴露）
  if (pathname === "/login") {
    return null;
  }
  const session = request.cookies.get("oaf_session")?.value;
  if (!session) {
    const url = request.nextUrl.clone();
    url.pathname = "/login";
    url.search = pathname === "/" ? "" : `?next=${encodeURIComponent(pathname)}`;
    return NextResponse.redirect(url);
  }
  if (pathname === "/admin" || pathname.startsWith("/admin/")) {
    const payload = readJwtPayload(session);
    if (payload && payload.role !== "admin") {
      const url = request.nextUrl.clone();
      url.pathname = "/console";
      url.search = "";
      return NextResponse.redirect(url);
    }
  }
  return null;
}

export function proxy(request: NextRequest) {
  // 1) 页面守卫（matcher 覆盖页面路径；命中数据面前缀时直接放行进下方反代分支）
  const guarded = guardPage(request);
  if (guarded) return guarded;

  const { pathname, search } = request.nextUrl;

  // 前端同源反代后端 REST,浏览器无需直连
  if (pathname.startsWith("/api/v1")) {
    const backend = process.env.BACKEND_INTERNAL_URL || DEFAULT_BACKEND;
    return NextResponse.rewrite(joinTarget(backend, `${pathname}${search}`));
  }

  // 发布助手对话:无状态单次流(POST /threads/chat 与 /threads/{sid}/confirm-stream)。
  // 去除 /agent/release-agent 前缀后转发到 release-agent 根路径（专属规则在前，注入逻辑不动）
  if (pathname.startsWith(RELEASE_AGENT_PREFIX)) {
    const rest = pathname.slice(RELEASE_AGENT_PREFIX.length) || "/";
    const releaseAgent = process.env.AGENT_INTERNAL_URL || DEFAULT_RELEASE_AGENT;
    const headers = new Headers(request.headers);
    // 采集模式:显式开关开启且目标已设置时,目标改取 collector(带 /{ns} 前缀,业务服务
    // 路径整体后移,collector 按前缀路由 + 录制)并注入 X-Eval-Session(前端会话标识派生),
    // 使 collector 对外部依赖三协议(LLM/沙箱/MCP)的录制也能拿到与 HTTP 层一致的强关联主键
    // 判定/取目标逻辑见 lib/eval-session.ts（单测钉住 issue #121 的判空语义）
    const collector = evalCollectorTarget(
      process.env.EVAL_COLLECTOR_MODE,
      process.env.EVAL_COLLECTOR_AGENT_URL,
    );
    if (collector) {
      const sid = request.cookies.get("oaf-assistant-sid")?.value;
      if (sid) headers.set("x-eval-session", sid);
      return NextResponse.rewrite(joinTarget(collector, `${rest}${search}`), { request: { headers } });
    }
    if (process.env.EVAL_COLLECTOR_MODE === "1" && !process.env.EVAL_COLLECTOR_AGENT_URL) {
      console.error("[proxy] EVAL_COLLECTOR_MODE=1 但 EVAL_COLLECTOR_AGENT_URL 未设置,退回普通代理且不注入 x-eval-session");
    }
    return NextResponse.rewrite(joinTarget(releaseAgent, `${rest}${search}`), { request: { headers } });
  }

  // 控制台调试/会话数据面（M2 §3.2）：/agent/{short}/* → oaf-{short}-svc（与 platform-router
  // 同名规则）。身份：解 oaf_session payload 注入 X-User-Id: u-{uid}（替代历史硬编码 "webui"）；
  // 无 cookie/解不出 uid 不注入放行（业务侧按现状匿名处理——直连暴露面与今天一致，决议 3）。
  if (pathname.startsWith("/agent/")) {
    const rest = pathname.slice("/agent/".length);
    const short = rest.split("/")[0];
    if (isValidAgentShortName(short)) {
      const headers = new Headers(request.headers);
      const session = request.cookies.get("oaf_session")?.value;
      const uid = readUidFromJwtCookie(request);
      if (uid) headers.set("X-User-Id", `u-${uid}`);
      // M9 §4 调用方同步：oaf_session cookie → Authorization 头（目标若经 router enforce
      // 模式，auth_request 读 Authorization；目标直连 svc 时该头无副作用）
      if (session) headers.set("Authorization", `Bearer ${session}`);
      const target = process.env.AGENT_INTERNAL_URL_TEMPLATE
        ? process.env.AGENT_INTERNAL_URL_TEMPLATE.replace("{short}", short)
        : `http://oaf-${short}-svc.agent-platform.svc.cluster.local:8100`;
      const sub = rest.slice(short.length) || "/";
      return NextResponse.rewrite(joinTarget(target, `${sub}${search}`), { request: { headers } });
    }
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    // 数据面反代
    "/api/v1/:path*",
    "/agent/:path*",
    // 页面路径守卫（排除 API/数据面/静态资源）
    "/((?!api|agent|mcp|_next/static|_next/image|favicon.ico|.*\\.(?:png|jpg|jpeg|svg|ico|webp|txt|xml)$).*)",
  ],
};
