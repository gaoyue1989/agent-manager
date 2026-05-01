'use client';
import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/api';

const defaultConfig = {
  name: '',
  description: '',
  model: 'qwen3.6-plus',
  model_endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  api_key: 'sk-0440b76852944f019bb142a715bc2cab',
  system_prompt: '你是一个有用的 AI 助手。请用中文回答。',
  tools: [],
  sub_agents: [],
  memory: true,
  max_iterations: 50,
};

export default function CreateAgent() {
  const router = useRouter();
  const [mode, setMode] = useState<'form' | 'json' | 'yaml'>('form');
  const [form, setForm] = useState(defaultConfig);
  const [textInput, setTextInput] = useState(JSON.stringify(defaultConfig, null, 2));
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async () => {
    setLoading(true);
    setError('');
    try {
      let config: string;
      let configType: string;

      if (mode === 'form') {
        config = JSON.stringify(form);
        configType = 'form';
      } else if (mode === 'json') {
        JSON.parse(textInput);
        config = textInput;
        configType = 'json';
      } else {
        config = textInput;
        configType = 'yaml';
      }

      const agent = await api.agents.create(config, configType);
      router.push(`/agents/${agent.id}`);
    } catch (e: any) {
      setError(e.message || '创建失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">创建 Agent</h1>

      <div className="flex gap-2 mb-6">
        {(['form', 'json', 'yaml'] as const).map(m => (
          <button key={m} onClick={() => setMode(m)}
            className={`px-4 py-2 rounded-lg text-sm ${mode === m ? 'bg-blue-600 text-white' : 'bg-gray-200 text-gray-700'}`}>
            {m === 'form' ? '表单' : m.toUpperCase()}
          </button>
        ))}
      </div>

      {error && <div className="bg-red-50 text-red-600 p-3 rounded-lg mb-4 text-sm">{error}</div>}

      {mode === 'form' ? (
        <div className="bg-white rounded-lg shadow p-6 space-y-4">
          {[
            { key: 'name', label: 'Agent 名称', type: 'text' },
            { key: 'description', label: '描述', type: 'text' },
            { key: 'model', label: 'LLM 模型', type: 'text' },
            { key: 'model_endpoint', label: 'LLM 端点', type: 'text' },
            { key: 'api_key', label: 'API Key', type: 'text' },
            { key: 'system_prompt', label: '系统提示词', type: 'textarea' as const },
          ].map(f => (
            <div key={f.key}>
              <label className="block text-sm font-medium text-gray-700 mb-1">{f.label}</label>
              {f.type === 'textarea' ? (
                <textarea rows={3} value={(form as any)[f.key]} onChange={e => setForm({ ...form, [f.key]: e.target.value })}
                  className="w-full border rounded-lg px-3 py-2 text-sm" />
              ) : (
                <input type="text" value={(form as any)[f.key]} onChange={e => setForm({ ...form, [f.key]: e.target.value })}
                  className="w-full border rounded-lg px-3 py-2 text-sm" />
              )}
            </div>
          ))}
        </div>
      ) : (
        <textarea value={textInput} onChange={e => setTextInput(e.target.value)}
          className="w-full h-96 font-mono text-sm border rounded-lg p-4 bg-gray-900 text-green-400"
          placeholder={mode === 'json' ? '输入 JSON 配置...' : '输入 YAML 配置...'} />
      )}

      <button onClick={handleSubmit} disabled={loading}
        className="mt-4 bg-blue-600 text-white px-6 py-2.5 rounded-lg hover:bg-blue-700 disabled:opacity-50 text-sm">
        {loading ? '创建中...' : '创建 Agent'}
      </button>
    </div>
  );
}
