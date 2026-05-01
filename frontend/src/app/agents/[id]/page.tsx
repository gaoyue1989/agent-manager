'use client';
import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { api } from '@/lib/api';

const statusMap: Record<string, string> = {
  draft: '草稿', generated: '已生成', built: '已构建',
  deployed: '已部署', published: '已发布', unpublished: '已下线', error: '异常',
};

export default function AgentDetail() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [agent, setAgent] = useState<any>(null);
  const [code, setCode] = useState('');
  const [deployments, setDeployments] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState('');

  const load = async () => {
    const a = await api.agents.get(Number(id));
    setAgent(a);
    if (a.status !== 'draft') {
      try {
        const r = await api.agents.getCode(Number(id));
        setCode(r.code);
      } catch {}
      try {
        const d = await api.agents.getDeployments(Number(id));
        setDeployments(d.items || []);
      } catch {}
    }
    setLoading(false);
  };
  useEffect(() => { load(); }, [id]);

  const doAction = async (action: string, fn: () => Promise<any>) => {
    setActionLoading(action);
    try { await fn(); await load(); } catch (e: any) { alert(e.message); }
    setActionLoading('');
  };

  if (loading) return <div className="text-center py-20 text-gray-400">加载中...</div>;
  if (!agent) return <div className="text-center py-20 text-gray-400">未找到 Agent</div>;

  const cfg = JSON.parse(agent.config || '{}');
  const isPublished = agent.status === 'published';

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">{agent.name}</h1>
        <span className="text-sm text-gray-500">状态: {statusMap[agent.status] || agent.status} (v{agent.version})</span>
      </div>

      <div className="grid grid-cols-2 gap-6 mb-6">
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="font-semibold mb-3">基本信息</h2>
          <div className="space-y-2 text-sm">
            <div><span className="text-gray-500">描述:</span> {agent.description || '-'}</div>
            <div><span className="text-gray-500">模型:</span> {cfg.model}</div>
            <div><span className="text-gray-500">提示词:</span> {cfg.system_prompt?.slice(0, 80)}...</div>
          </div>
        </div>
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="font-semibold mb-3">操作</h2>
          <div className="flex flex-wrap gap-2">
            {agent.status === 'draft' && (
              <ActionBtn label="生成代码" action="generate" loading={actionLoading} onClick={() =>
                doAction('generate', () => api.agents.generate(Number(id)))} />
            )}
            {(agent.status === 'generated' || agent.status === 'built') && (
              <ActionBtn label="构建镜像" action="build" loading={actionLoading} onClick={() =>
                doAction('build', () => api.agents.build(Number(id)))} />
            )}
            {(agent.status === 'built' || agent.status === 'deployed') && (
              <ActionBtn label="部署" action="deploy" loading={actionLoading} onClick={() =>
                doAction('deploy', () => api.agents.deploy(Number(id)))} />
            )}
            {!isPublished && agent.status !== 'draft' && agent.status !== 'error' && (
              <ActionBtn label="发布上线" action="publish" loading={actionLoading} onClick={() =>
                doAction('publish', () => api.agents.publish(Number(id)))} color="bg-green-600 hover:bg-green-700" />
            )}
            {isPublished && (
              <ActionBtn label="下线" action="unpublish" loading={actionLoading} onClick={() =>
                doAction('unpublish', () => api.agents.unpublish(Number(id)))} color="bg-red-600 hover:bg-red-700" />
            )}
            <button onClick={() => router.push(`/agents/${id}/edit`)}
              className="px-4 py-2 rounded-lg text-sm bg-gray-200 text-gray-700 hover:bg-gray-300">编辑</button>
          </div>
        </div>
      </div>

      {code && (
        <div className="bg-white rounded-lg shadow p-6 mb-6">
          <h2 className="font-semibold mb-3">生成代码预览</h2>
          <pre className="bg-gray-900 text-green-400 p-4 rounded-lg text-xs overflow-auto max-h-96">{code}</pre>
        </div>
      )}

      {deployments.length > 0 && (
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="font-semibold mb-3">部署历史</h2>
          <table className="w-full text-sm">
            <thead className="bg-gray-50"><tr>
              <th className="px-3 py-2 text-left">版本</th>
              <th className="px-3 py-2 text-left">Sandbox</th>
              <th className="px-3 py-2 text-left">状态</th>
              <th className="px-3 py-2 text-left">时间</th>
            </tr></thead>
            <tbody>{deployments.map(d => (
              <tr key={d.id} className="border-t">
                <td className="px-3 py-2">v{d.version}</td>
                <td className="px-3 py-2 font-mono text-xs">{d.sandbox_name}</td>
                <td className="px-3 py-2">{d.status}</td>
                <td className="px-3 py-2 text-gray-500">{new Date(d.created_at).toLocaleString('zh')}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function ActionBtn({ label, action, loading, onClick, color }: {
  label: string; action: string; loading: string;
  onClick: () => void; color?: string;
}) {
  return (
    <button onClick={onClick} disabled={loading === action}
      className={`px-4 py-2 rounded-lg text-sm text-white disabled:opacity-50 ${color || 'bg-blue-600 hover:bg-blue-700'}`}>
      {loading === action ? '处理中...' : label}
    </button>
  );
}
