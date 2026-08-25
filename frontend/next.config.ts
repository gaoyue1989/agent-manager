import type { NextConfig } from "next";

const BACKEND = process.env.BACKEND_INTERNAL_URL || "http://platform-backend.agent-platform.svc.cluster.local:8080";
const RELEASE_AGENT = process.env.AGENT_INTERNAL_URL || "http://oaf-release-agent-svc.agent-platform.svc.cluster.local:8100";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    return [
      // 前端同源反代后端 REST，浏览器无需直连
      { source: "/api/v1/:path*", destination: `${BACKEND}/api/v1/:path*` },
      // 发布助手对话：无状态单次流（POST /threads/{sid}/chat 与 /confirm-stream）
      // 经集群内 Service 直连 release-agent，任意前端入口(:8911/:30881)均同源可用
      { source: "/agent/release-agent/:path*", destination: `${RELEASE_AGENT}/:path*` },
    ];
  },
};

export default nextConfig;
