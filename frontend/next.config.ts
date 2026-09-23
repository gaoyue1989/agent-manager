import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  // Next.js 16 默认阻止非 localhost 来源访问 dev 资源（HMR / RSC payload 等）。
  // 开发环境允许通过 100.66.1.5 访问；生产构建 standalone 不影响（仅 dev 模式生效）。
  allowedDevOrigins: ["100.66.1.5", "localhost", "127.0.0.1", "192.168.31.155"],
  // 同源反代（/api/v1、/agent/release-agent）已迁移至 src/proxy.ts：
  // rewrites() 在 next build 时求值并烘焙进 standalone 产物，运行时环境变量无法覆盖，
  // proxy 在请求期读取 process.env，后端地址可按部署环境配置。
};

export default nextConfig;
