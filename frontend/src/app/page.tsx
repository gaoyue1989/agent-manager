'use client';
import { useEffect, useState } from 'react';
import Link from 'next/link';
import { api } from '@/lib/api';

export default function Dashboard() {
  const [stats, setStats] = useState({ total: 0, published: 0, unpublished: 0, draft: 0, error: 0 });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.agents.list(),
      api.agents.list({ status: 'published' }),
      api.agents.list({ status: 'unpublished' }),
      api.agents.list({ status: 'draft' }),
      api.agents.list({ status: 'error' }),
    ]).then(([a, p, u, d, e]) => {
      setStats({ total: a.total, published: p.total, unpublished: u.total, draft: d.total, error: e.total });
    }).catch(console.error).finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="text-center py-20 text-gray-400">加载中...</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Agent 管理概览</h1>
      <div className="grid grid-cols-5 gap-4 mb-8">
        {[
          { label: '总数', value: stats.total, color: 'bg-blue-100 text-blue-800' },
          { label: '已发布', value: stats.published, color: 'bg-green-100 text-green-800' },
          { label: '已下线', value: stats.unpublished, color: 'bg-gray-100 text-gray-800' },
          { label: '草稿', value: stats.draft, color: 'bg-yellow-100 text-yellow-800' },
          { label: '异常', value: stats.error, color: 'bg-red-100 text-red-800' },
        ].map(s => (
          <div key={s.label} className={`${s.color} rounded-lg p-4 text-center`}>
            <div className="text-3xl font-bold">{s.value}</div>
            <div className="text-sm mt-1">{s.label}</div>
          </div>
        ))}
      </div>
      <Link href="/agents/create" className="inline-block bg-blue-600 text-white px-5 py-2.5 rounded-lg hover:bg-blue-700">
        创建 Agent
      </Link>
    </div>
  );
}
