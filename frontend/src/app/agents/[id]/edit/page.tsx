'use client';
import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { api } from '@/lib/api';

export default function EditAgent() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [config, setConfig] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api.agents.get(Number(id)).then(a => {
      setConfig(a.config);
      if (typeof a.config === 'object') setConfig(JSON.stringify(a.config, null, 2));
    }).catch(console.error).finally(() => setLoading(false));
  }, [id]);

  const handleSave = async () => {
    setSaving(true);
    try {
      await api.agents.update(Number(id), config);
      router.push(`/agents/${id}`);
    } catch (e: any) { alert(e.message); }
    setSaving(false);
  };

  if (loading) return <div className="text-center py-20 text-gray-400">加载中...</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold mb-4">编辑 Agent</h1>
      <textarea value={config} onChange={e => setConfig(e.target.value)}
        className="w-full h-96 font-mono text-sm border rounded-lg p-4 bg-gray-900 text-green-400" />
      <div className="mt-4 flex gap-3">
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
