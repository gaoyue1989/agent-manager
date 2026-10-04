import { NextResponse, type NextRequest } from "next/server";

// 同源反代默认目标(集群内固定地址)。取值在请求期读取环境变量(Next 16 proxy
// 默认 Node.js runtime,process.env 不被构建期烘焙),部署时通过 Deployment env
// 按环境覆盖即可,无需重建镜像。
const DEFAULT_BACKEND = "http://platform-backend.agent-platform.svc.cluster.local:8080";
const DEFAULT_RELEASE_AGENT = "http://oaf-release-agent-svc.agent-platform.svc.cluster.local:8100";

// 评测采集模式(设计 docs/design/agent-framework-eval-offline-record-replay-design.md 附录 C):
// AGENT_INTERNAL_URL 指向 eval-collector 业务服务反代口(:18203/{ns})时启用——
// 前端流量经 collector 透传+录制,sessionId 由 collector 从 path/body 提取做强会话关联。
// 仅测试环境按需开启;采集结束还原 AGENT_INTERNAL_URL 指回业务服务即可。
const EVAL_COLLECTOR_MODE = process.env.EVAL_COLLECTOR_AGENT_URL !== "";

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
    // 采集模式:目标带 /{ns} 前缀,业务服务路径整体后移(collector 按前缀路由 + 录制)
    const releaseAgent = process.env.AGENT_INTERNAL_URL || DEFAULT_RELEASE_AGENT;
    const headers = new Headers(request.headers);
    // 采集模式下注入 X-Eval-Session(前端会话标识派生),使 collector 对外部依赖三协议
    // (LLM/沙箱/MCP)的录制也能拿到与 HTTP 层一致的强关联主键
    if (EVAL_COLLECTOR_MODE) {
      const sid = request.cookies.get("oaf-assistant-sid")?.value;
      if (sid) headers.set("x-eval-session", sid);
    }
    return NextResponse.rewrite(joinTarget(releaseAgent, `${rest}${search}`), { request: { headers } });
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/api/v1/:path*", "/agent/release-agent/:path*"],
};
