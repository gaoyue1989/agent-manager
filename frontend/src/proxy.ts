import { NextResponse, type NextRequest } from "next/server";

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

export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;

  // 前端同源反代后端 REST,浏览器无需直连
  if (pathname.startsWith("/api/v1")) {
    const backend = process.env.BACKEND_INTERNAL_URL || DEFAULT_BACKEND;
    return NextResponse.rewrite(joinTarget(backend, `${pathname}${search}`));
  }

  // 发布助手对话:无状态单次流(POST /threads/chat 与 /threads/{sid}/confirm-stream)。
  // 去除 /agent/release-agent 前缀后转发到 release-agent 根路径
  if (pathname.startsWith(RELEASE_AGENT_PREFIX)) {
    const rest = pathname.slice(RELEASE_AGENT_PREFIX.length) || "/";
    const releaseAgent = process.env.AGENT_INTERNAL_URL || DEFAULT_RELEASE_AGENT;
    const headers = new Headers(request.headers);
    // 采集模式:显式开关开启且目标已设置时,目标改取 collector(带 /{ns} 前缀,业务服务
    // 路径整体后移,collector 按前缀路由 + 录制)并注入 X-Eval-Session(前端会话标识派生),
    // 使 collector 对外部依赖三协议(LLM/沙箱/MCP)的录制也能拿到与 HTTP 层一致的强关联主键
    if (process.env.EVAL_COLLECTOR_MODE === "1") {
      const collector = process.env.EVAL_COLLECTOR_AGENT_URL;
      if (collector) {
        const sid = request.cookies.get("oaf-assistant-sid")?.value;
        if (sid) headers.set("x-eval-session", sid);
        return NextResponse.rewrite(joinTarget(collector, `${rest}${search}`), { request: { headers } });
      }
      console.error("[proxy] EVAL_COLLECTOR_MODE=1 但 EVAL_COLLECTOR_AGENT_URL 未设置,退回普通代理且不注入 x-eval-session");
    }
    return NextResponse.rewrite(joinTarget(releaseAgent, `${rest}${search}`), { request: { headers } });
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/api/v1/:path*", "/agent/release-agent/:path*"],
};
