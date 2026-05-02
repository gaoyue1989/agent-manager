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
  const [imageInfo, setImageInfo] = useState<any>(null);
  const [podStatus, setPodStatus] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState('');
  const [podLoading, setPodLoading] = useState(false);

  const [chatInput, setChatInput] = useState('');
  const [chatHistory, setChatHistory] = useState<{ role: string; content: string }[]>([]);
  const [chatLoading, setChatLoading] = useState(false);
  const [chatLatency, setChatLatency] = useState<number | null>(null);

  const load = async () => {
    const a = await api.agents.get(Number(id));
    setAgent(a);
    if (a.status !== 'draft') {
      try { const r = await api.agents.getCode(Number(id)); setCode(r.code); } catch {}
      try { const d = await api.agents.getDeployments(Number(id)); setDeployments(d.items || []); } catch {}
    }
    try { setImageInfo(await api.agents.imageInfo(Number(id))); } catch {}
    if (a.status === 'published') {
      try { setPodStatus(await api.agents.podStatus(Number(id))); } catch {}
    }
    setLoading(false);
  };
  useEffect(() => { load(); }, [id]);

  const doAction = async (action: string, fn: () => Promise<any>) => {
    setActionLoading(action);
    try { await fn(); await load(); } catch (e: any) { alert(e.message); }
    setActionLoading('');
  };

  const refreshPodStatus = async () => {
    setPodLoading(true);
    try { setPodStatus(await api.agents.podStatus(Number(id))); } catch (e: any) { alert(e.message); }
    setPodLoading(false);
  };

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text).then(() => alert('已复制到剪贴板')).catch(() => alert('复制失败'));
  };

  const sendChat = async () => {
    if (!chatInput.trim()) return;
    setChatLoading(true);
    const history = chatHistory.map(h => ({ role: h.role, content: h.content }));
    try {
      const resp = await api.agents.chat(Number(id), { message: chatInput, history });
      setChatHistory([...chatHistory, { role: 'user', content: chatInput }]);
      if (resp.success && resp.data?.response) {
        setChatHistory(prev => [...prev, { role: 'assistant', content: resp.data.response }]);
      } else if (resp.response) {
        setChatHistory(prev => [...prev, { role: 'assistant', content: resp.response }]);
      } else {
        setChatHistory(prev => [...prev, { role: 'assistant', content: resp.error || '请求失败' }]);
      }
      setChatLatency(resp.latency_ms || null);
      setChatInput('');
    } catch (e: any) {
      alert('聊天请求失败: ' + e.message);
    }
    setChatLoading(false);
  };

  if (loading) return <div className="text-center py-20 text-gray-400">加载中...</div>;
  if (!agent) return <div className="text-center py-20 text-gray-400">未找到 Agent</div>;

  const cfg = JSON.parse(agent.config || '{}');
  const isPublished = agent.status === 'published';
  const hasImage = agent.status === 'built' || agent.status === 'deployed' || isPublished || agent.status === 'unpublished';

  const enabledTools: string[] = cfg.enabled_tools || [];
  const excludedTools: string[] = cfg.excluded_tools || [];
  const mcpConfig = cfg.mcp_config;
  const endpointURL = deployments.length > 0 ? (deployments[0].endpoint_url || '') : '';

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
            {endpointURL && (
              <div><span className="text-gray-500">对外地址:</span> <code className="bg-green-50 text-green-700 px-2 py-0.5 rounded text-xs">{endpointURL}</code></div>
            )}
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

      {(enabledTools.length > 0 || excludedTools.length > 0 || mcpConfig) && (
        <div className="bg-white rounded-lg shadow p-6 mb-6">
          <h2 className="font-semibold mb-3">Agent 配置详情</h2>
          <div className="space-y-3">
            {enabledTools.length > 0 && (
              <div>
                <span className="text-xs font-medium text-gray-500">启用工具 ({enabledTools.length}):</span>
                <div className="flex flex-wrap gap-1 mt-1">
                  {enabledTools.map(t => (
                    <span key={t} className="px-2 py-0.5 bg-blue-100 text-blue-700 rounded text-xs font-mono">{t}</span>
                  ))}
                </div>
              </div>
            )}
            {excludedTools.length > 0 && (
              <div>
                <span className="text-xs font-medium text-gray-500">排除工具:</span>
                <div className="flex flex-wrap gap-1 mt-1">
                  {excludedTools.map(t => (
                    <span key={t} className="px-2 py-0.5 bg-red-100 text-red-700 rounded text-xs font-mono">{t}</span>
                  ))}
                </div>
              </div>
            )}
            {mcpConfig && mcpConfig.url && (
              <div>
                <span className="text-xs font-medium text-gray-500">MCP 配置:</span>
                <div className="text-xs mt-1">
                  <code className="bg-gray-100 px-2 py-0.5 rounded">{mcpConfig.url}</code>
                  <span className="text-gray-400 ml-2">({mcpConfig.transport})</span>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {hasImage && imageInfo?.image_tag && (
        <div className="bg-white rounded-lg shadow p-6 mb-6">
          <h2 className="font-semibold mb-3">镜像信息</h2>
          <div className="space-y-2 text-sm">
            <div className="flex items-center gap-2">
              <span className="text-gray-500 w-24">镜像地址:</span>
              <code className="bg-gray-100 px-2 py-0.5 rounded text-xs font-mono flex-1 break-all">{imageInfo.image_tag}</code>
              <button onClick={() => copyToClipboard(imageInfo.image_tag)} className="text-blue-600 hover:underline text-xs whitespace-nowrap">复制</button>
            </div>
            <div><span className="text-gray-500 w-24 inline-block">镜像名称:</span> {imageInfo.image_name}</div>
            <div><span className="text-gray-500 w-24 inline-block">仓库地址:</span> {imageInfo.registry}</div>
            <div><span className="text-gray-500 w-24 inline-block">版本:</span> {imageInfo.version}</div>
            <div><span className="text-gray-500 w-24 inline-block">构建状态:</span> <span className={imageInfo.build_status === 'success' ? 'text-green-600' : 'text-red-600'}>{imageInfo.build_status}</span></div>
            {imageInfo.build_time && <div><span className="text-gray-500 w-24 inline-block">构建时间:</span> {new Date(imageInfo.build_time).toLocaleString('zh')}</div>}
          </div>
        </div>
      )}

      {isPublished && (
        <div className="bg-white rounded-lg shadow p-6 mb-6">
          <div className="flex items-center justify-between mb-3">
            <h2 className="font-semibold">Pod 状态</h2>
            <button onClick={refreshPodStatus} disabled={podLoading}
              className="px-3 py-1 rounded text-xs bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50">
              {podLoading ? '刷新中...' : '刷新'}
            </button>
          </div>
          {podStatus ? (
            <div className="space-y-2 text-sm">
              <div><span className="text-gray-500 w-24 inline-block">Pod 名称:</span> <span className="font-mono">{podStatus.pod_name || '-'}</span></div>
              <div><span className="text-gray-500 w-24 inline-block">运行状态:</span> <span className={podStatus.status === 'Running' ? 'text-green-600 font-semibold' : podStatus.status === 'error' ? 'text-red-600' : 'text-yellow-600'}>{podStatus.pod_status || podStatus.status || '-'}</span></div>
              <div><span className="text-gray-500 w-24 inline-block">就绪:</span> {podStatus.ready === 'true' || podStatus.ready === true ? <span className="text-green-600">Ready</span> : <span className="text-red-600">Not Ready</span>}</div>
              <div><span className="text-gray-500 w-24 inline-block">重启次数:</span> {podStatus.restarts ?? '-'}</div>
              <div><span className="text-gray-500 w-24 inline-block">Pod IP:</span> <span className="font-mono">{podStatus.pod_ip || '-'}</span></div>
              {endpointURL && <div><span className="text-gray-500 w-24 inline-block">对外地址:</span> <code className="bg-green-50 text-green-700 px-2 py-0.5 rounded text-xs">{endpointURL}</code></div>}
            </div>
          ) : (
            <p className="text-gray-400 text-sm">点击"刷新"获取 Pod 状态</p>
          )}
        </div>
      )}

      {isPublished && (
        <div className="bg-white rounded-lg shadow p-6 mb-6">
          <h2 className="font-semibold mb-3">Agent 聊天测试</h2>
          <div className="border rounded-lg p-4 mb-3 max-h-64 overflow-y-auto bg-gray-50">
            {chatHistory.length === 0 ? (
              <p className="text-gray-400 text-sm text-center py-4">发送消息开始测试 Agent</p>
            ) : (
              chatHistory.map((msg, i) => (
                <div key={i} className={`mb-2 ${msg.role === 'user' ? 'text-right' : 'text-left'}`}>
                  <span className="text-xs text-gray-400 mr-2">{msg.role === 'user' ? '用户' : 'Agent'}</span>
                  <span className={`inline-block px-3 py-1.5 rounded-lg text-sm max-w-[80%] ${msg.role === 'user' ? 'bg-blue-600 text-white' : 'bg-white border text-gray-800'}`}>
                    {msg.content}
                  </span>
                </div>
              ))
            )}
          </div>
          {chatLatency !== null && (
            <div className="text-xs text-gray-400 mb-2">响应时间: {(chatLatency / 1000).toFixed(2)}s</div>
          )}
          <div className="flex gap-2">
            <input value={chatInput} onChange={e => setChatInput(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && sendChat()}
              placeholder="输入消息测试 Agent..."
              className="flex-1 border rounded-lg px-3 py-2 text-sm" />
            <button onClick={sendChat} disabled={chatLoading || !chatInput.trim()}
              className="px-4 py-2 rounded-lg text-sm bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50">
              {chatLoading ? '发送中...' : '发送'}
            </button>
          </div>
        </div>
      )}

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
              <th className="px-3 py-2 text-left">对外地址</th>
              <th className="px-3 py-2 text-left">时间</th>
            </tr></thead>
            <tbody>{deployments.map(d => (
              <tr key={d.id} className="border-t">
                <td className="px-3 py-2">v{d.version}</td>
                <td className="px-3 py-2 font-mono text-xs">{d.sandbox_name}</td>
                <td className="px-3 py-2">{d.status}</td>
                <td className="px-3 py-2 font-mono text-xs">{d.endpoint_url || '-'}</td>
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
