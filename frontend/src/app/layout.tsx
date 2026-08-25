import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = { title: "OAF 服务发布平台", description: "上传 OAF 包 → K8s 发布 → A2A 注册" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body className="min-h-screen bg-gray-50 text-gray-900">
        <nav className="bg-white border-b border-gray-200 px-6 py-3 flex items-center gap-6">
          <span className="font-bold text-lg">OAF 发布平台</span>
          <Link href="/" className="text-sm text-gray-700 hover:text-blue-600">服务列表</Link>
          <Link href="/publish" className="text-sm text-gray-700 hover:text-blue-600">发布新服务</Link>
          <Link href="/assistant" className="text-sm text-gray-700 hover:text-blue-600">发布助手</Link>
        </nav>
        <main className="max-w-6xl mx-auto p-6">{children}</main>
      </body>
    </html>
  );
}
