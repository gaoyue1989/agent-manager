import Link from 'next/link';
import './globals.css';

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body className="min-h-screen bg-gray-50">
        <nav className="bg-white shadow-sm border-b">
          <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between">
            <Link href="/" className="text-lg font-bold text-gray-800">
              Agent Manager
            </Link>
            <div className="flex gap-6 text-sm text-gray-600">
              <Link href="/" className="hover:text-gray-900">首页</Link>
              <Link href="/agents" className="hover:text-gray-900">Agent 列表</Link>
              <Link href="/agents/create" className="hover:text-gray-900">创建 Agent</Link>
            </div>
          </div>
        </nav>
        <main className="max-w-6xl mx-auto px-4 py-6">{children}</main>
      </body>
    </html>
  );
}
