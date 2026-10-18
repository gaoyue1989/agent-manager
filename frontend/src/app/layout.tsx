import type { Metadata } from "next";
import "./globals.css";

// 根布局只承载 html/body（M0 拆分：导航分别由 (console)/(admin) 路由组布局提供）
export const metadata: Metadata = { title: "智能体平台", description: "上传 OAF 包 → K8s 发布 → A2A 注册 · 工作空间化控制台" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body className="min-h-screen bg-gray-50 text-gray-900">{children}</body>
    </html>
  );
}
