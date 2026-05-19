'use client';
import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { api } from '@/lib/api';

export default function EditAgent() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [config, setConfig] = useState('');
  const [runtimeMode, setRuntimeMode] = useState<'build' | 'mount'>('build');
  const [image, setImage] = useState('');
  const [checkpointDSN, setCheckpointDSN] = useState('');
  const [availableImages, setAvailableImages] = useState<{ name: string; description: string }[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    Promise.all([
      api.agents.get(Number(id)),
      api.images.list(),
    ]).then(([agent, images]) => {
      setConfig(agent.config);
      if (typeof agent.config === 'object') setConfig(JSON.stringify(agent.config, null, 2));
      setRuntimeMode(agent.runtime_mode || 'build');
      setImage(agent.image || '');
      setCheckpointDSN(agent.checkpoint_dsn || '');
      setAvailableImages(images.items || []);
    }).catch(console.error).finally(() => setLoading(false));
  }, [id]);

  const handleSave = async () => {
    setSaving(true);
    try {
      await api.agents.update(Number(id), config, {
        runtimeMode,
        image,
        checkpointDSN,
      });
      router.push(`/agents/${id}`);
    } catch (e: any) { alert(e.message); }
    setSaving(false);
  };

  if (loading) return <div className="text-center py-20 text-gray-400">加载中...</div>;

  return (
    <div className="max-w-4xl mx-auto">
      <h1 className="text-2xl font-bold mb-4">编辑 Agent</h1>
      
      <div className="bg-white rounded-lg shadow p-6 mb-6 space-y-4">
        <h2 className="font-semibold text-lg">运行模式</h2>
        <div className="grid grid-cols-2 gap-4">
          <label className={`border-2 rounded-lg p-4 cursor-pointer transition-all ${runtimeMode === 'build' ? 'border-blue-500 bg-blue-50' : 'border-gray-200 hover:border-gray-300'}`}>
            <input type="radio" value="build" checked={runtimeMode === 'build'} onChange={() => setRuntimeMode('build')} className="hidden" />
            <div className="font-medium text-gray-900">构建模式</div>
            <div className="text-xs text-gray-500 mt-1">生成代码 → 构建镜像 → 部署</div>
          </label>
          <label className={`border-2 rounded-lg p-4 cursor-pointer transition-all ${runtimeMode === 'mount' ? 'border-blue-500 bg-blue-50' : 'border-gray-200 hover:border-gray-300'}`}>
            <input type="radio" value="mount" checked={runtimeMode === 'mount'} onChange={() => setRuntimeMode('mount')} className="hidden" />
            <div className="font-medium text-gray-900">挂载模式</div>
            <div className="text-xs text-gray-500 mt-1">预构建镜像 + 配置挂载</div>
          </label>
        </div>
        
        {runtimeMode === 'mount' && (
          <div className="mt-4 space-y-4 border-t pt-4">
            <div>
              <label className="block text-sm font-medium mb-1">选择镜像 *</label>
              <select 
                value={image} 
                onChange={e => setImage(e.target.value)} 
                className="w-full border rounded-lg px-3 py-2 text-sm"
              >
                <option value="">请选择镜像</option>
                {availableImages.map(img => (
                  <option key={img.name} value={img.name}>
                    {img.description || img.name}
                  </option>
                ))}
              </select>
            </div>
            
            <div>
              <label className="block text-sm font-medium mb-1">Checkpoint DSN（可选）</label>
              <input
                type="text"
                value={checkpointDSN}
                onChange={e => setCheckpointDSN(e.target.value)}
                className="w-full border rounded-lg px-3 py-2 text-sm font-mono"
                placeholder="留空则使用共用的 Checkpoint 数据库"
              />
            </div>
          </div>
        )}
      </div>
      
      <div className="bg-white rounded-lg shadow p-6 mb-6">
        <h2 className="font-semibold text-lg mb-3">配置内容</h2>
        <textarea 
          value={config} 
          onChange={e => setConfig(e.target.value)}
          className="w-full h-96 font-mono text-sm border rounded-lg p-4 bg-gray-900 text-green-400" 
        />
      </div>
      
      <div className="flex gap-3">
        <button onClick={handleSave} disabled={saving}
          className="bg-blue-600 text-white px-4 py-2 rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50">
          {saving ? '保存中...' : '保存'}
        </button>
        <button onClick={() => router.push(`/agents/${id}`)}
          className="bg-gray-200 text-gray-700 px-4 py-2 rounded-lg text-sm hover:bg-gray-300">取消</button>
      </div>
    </div>
  );
}
