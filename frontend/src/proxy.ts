import { NextResponse, type NextRequest } from "next/server";

// 同源反代默认目标(集群内固定地址)。取值在请求期读取环境变量(Next 16 proxy
// 默认 Node.js runtime,process.env 不被构建期烘焙),部署时通过 Deployment env
// 按环境覆盖即可,无需重建镜像。
const DEFAULT_BACKEND = "http://platform-backend.agent-platform.svc.cluster.local:8080";
const DEFAULT_RELEASE_AGENT = "http://oaf-release-agent-svc.agent-platform.svc.cluster.local:8100";

const RELEASE_AGENT_PREFIX = "/agent/release-agent";

// 拼接目标 URL:base 允许携带子路径(如经 ingress 的 /agent/release-agent)。
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
    return NextResponse.rewrite(joinTarget(releaseAgent, `${rest}${search}`));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/api/v1/:path*", "/agent/release-agent/:path*"],
};
