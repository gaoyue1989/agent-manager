'use client';
import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/api';

const statusMap: Record<string, string> = {
  draft: '草稿', generated: '已生成', built: '已构建',
  deployed: '已部署', published: '已发布', unpublished: '已下线', error: '异常',
};
const statusColor: Record<string, string> = {
  draft: 'bg-yellow-100 text-yellow-800', generated: 'bg-blue-100 text-blue-800',
  built: 'bg-indigo-100 text-indigo-800', deployed: 'bg-cyan-100 text-cyan-800',
  published: 'bg-green-100 text-green-800', unpublished: 'bg-gray-100 text-gray-800',
  error: 'bg-red-100 text-red-800',
};

export default function AgentList() {
  const [agents, setAgents] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleting, setDeleting] = useState<number | null>(null);
  const router = useRouter();

  const load = () => {
    setLoading(true);
    api.agents.list({ limit: 100 }).then(r => setAgents(r.items)).catch(console.error).finally(() => setLoading(false));
  };
  useEffect(() => { load(); }, []);

  const handleDelete = async (id: number, name: string) => {
    if (!confirm(`确定要删除 Agent "${name}" 吗？此操作不可撤销。`)) return;
    setDeleting(id);
    try {
      await api.agents.delete(id);
      load();
    } catch (e: any) {
      alert(e.message);
    }
    setDeleting(null);
  };

  if (loading) return <div className="text-center py-20 text-gray-400">加载中...</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold mb-4">Agent 列表</h1>
      <div className="bg-white rounded-lg shadow overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-gray-600">
            <tr>
              <th className="px-4 py-3">名称</th>
              <th className="px-4 py-3">状态</th>
              <th className="px-4 py-3">版本</th>
              <th className="px-4 py-3">更新时间</th>
              <th className="px-4 py-3">操作</th>
            </tr>
          </thead>
          <tbody>
            {agents.map(a => (
              <tr key={a.id} className="border-t hover:bg-gray-50">
                <td className="px-4 py-3 font-medium">{a.name}</td>
                <td className="px-4 py-3">
                  <span className={`px-2 py-0.5 rounded text-xs ${statusColor[a.status] || ''}`}>
                    {statusMap[a.status] || a.status}
                  </span>
                </td>
                <td className="px-4 py-3">v{a.version}</td>
                <td className="px-4 py-3 text-gray-500">{new Date(a.updated_at).toLocaleString('zh')}</td>
                <td className="px-4 py-3">
                  <Link href={`/agents/${a.id}`} className="text-blue-600 hover:underline mr-3">详情</Link>
                  <Link href={`/agents/${a.id}/edit`} className="text-gray-600 hover:underline mr-3">编辑</Link>
                  <button onClick={() => handleDelete(a.id, a.name)} disabled={deleting === a.id}
                    className="text-red-600 hover:underline disabled:opacity-50">
                    {deleting === a.id ? '删除中...' : '删除'}
                  </button>
                </td>
              </tr>
            ))}
            {agents.length === 0 && (
              <tr><td colSpan={5} className="text-center py-10 text-gray-400">暂无 Agent</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
