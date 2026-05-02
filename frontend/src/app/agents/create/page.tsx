'use client';
import { useState, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/api';

const DEEPAGENTS_TOOLS = [
  { name: 'write_todos', desc: '管理任务清单 (TodoListMiddleware)' },
  { name: 'ls', desc: '列出目录内容 (FilesystemMiddleware)' },
  { name: 'read_file', desc: '读取文件 (FilesystemMiddleware)' },
  { name: 'write_file', desc: '写入/创建文件 (FilesystemMiddleware)' },
  { name: 'edit_file', desc: '编辑文件,查找替换 (FilesystemMiddleware)' },
  { name: 'glob', desc: '按模式匹配文件 (FilesystemMiddleware)' },
  { name: 'grep', desc: '搜索文件内容 (FilesystemMiddleware)' },
  { name: 'execute', desc: '执行 Shell 命令 (FilesystemMiddleware, 需 Sandbox 后端)' },
  { name: 'task', desc: '调用子 Agent (SubAgentMiddleware)' },
];

const JSON_FULL_EXAMPLE = `{
  "name": "my-agent",
  "description": "一个支持 MCP 和多技能的 Agent",
  "model": "qwen3.6-plus",
  "model_endpoint": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "api_key": "sk-****",
  "system_prompt": "你是一个有用的 AI 助手。",
  "enabled_tools": ["write_todos", "ls", "read_file", "write_file", "edit_file", "glob", "grep", "task"],
  "excluded_tools": ["execute"],
  "mcp_config": {
    "url": "http://localhost:8001/sse",
    "transport": "sse",
    "headers": {}
  },
  "memory": true,
  "max_iterations": 50
}`;

const JSON_MIN_EXAMPLE = `{
  "name": "simple-agent",
  "description": "最简 Agent 配置",
  "model": "qwen3.6-plus",
  "system_prompt": "你是一个有用的 AI 助手。"
}`;

const YAML_FULL_EXAMPLE = `name: my-agent
description: 一个支持 MCP 和多技能的 Agent
model: qwen3.6-plus
model_endpoint: https://dashscope.aliyuncs.com/compatible-mode/v1
api_key: sk-xxx
system_prompt: 你是一个有用的 AI 助手。
enabled_tools:
  - write_todos
  - ls
  - read_file
  - write_file
  - edit_file
  - glob
  - grep
  - task
excluded_tools:
  - execute
mcp_config:
  url: http://localhost:8001/sse
  transport: sse
  headers: {}
memory: true
max_iterations: 50`;

const YAML_MIN_EXAMPLE = `name: simple-agent
description: 最简 Agent 配置
model: qwen3.6-plus
system_prompt: 你是一个有用的 AI 助手。`;

const defaultConfig = {
  name: '',
  description: '',
  model: process.env.NEXT_PUBLIC_LLM_MODEL || 'qwen3.6-plus',
  model_endpoint: process.env.NEXT_PUBLIC_LLM_ENDPOINT || 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  api_key: process.env.NEXT_PUBLIC_LLM_API_KEY || 'sk-****',
  system_prompt: '你是一个有用的 AI 助手。请用中文回答。',
  enabled_tools: DEEPAGENTS_TOOLS.map(t => t.name),
  excluded_tools: [] as string[],
  mcp_config: undefined as { url: string; transport: string; headers: Record<string, string> } | undefined,
  memory: true,
  max_iterations: 50,
};

export default function CreateAgent() {
  const router = useRouter();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [mode, setMode] = useState<'form' | 'json' | 'yaml'>('form');
  const [form, setForm] = useState(defaultConfig);
  const [textInput, setTextInput] = useState(JSON.stringify(defaultConfig, null, 2));
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const [mcpExpanded, setMcpExpanded] = useState(false);
  const [skillExpanded, setSkillExpanded] = useState(false);

  const toggleTool = (toolName: string) => {
    setForm(prev => {
      if (prev.enabled_tools.includes(toolName)) {
        return { ...prev, enabled_tools: prev.enabled_tools.filter(t => t !== toolName) };
      }
      return { ...prev, enabled_tools: [...prev.enabled_tools, toolName] };
    });
  };

  const toggleExcludedTool = (toolName: string) => {
    setForm(prev => {
      if (prev.excluded_tools.includes(toolName)) {
        return { ...prev, excluded_tools: prev.excluded_tools.filter(t => t !== toolName) };
      }
      return { ...prev, excluded_tools: [...prev.excluded_tools, toolName] };
    });
  };

  const handleSkillUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setError('');
    try {
      const result = await api.skills.upload(0, file);
      alert(`Skill 上传成功! 已解析 ${result.skills?.length || 0} 个技能。\n详细信息请查看浏览器控制台。`);
      console.log('Skills:', result.skills);
    } catch (err: any) {
      alert('Skill 上传失败: ' + (err.message || err));
    }
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const fillExample = (type: 'full' | 'min') => {
    if (mode === 'json') {
      setTextInput(type === 'full' ? JSON_FULL_EXAMPLE : JSON_MIN_EXAMPLE);
    } else if (mode === 'yaml') {
      setTextInput(type === 'full' ? YAML_FULL_EXAMPLE : YAML_MIN_EXAMPLE);
    } else {
      if (type === 'full') {
        setForm({
          name: 'my-agent',
          description: '支持 MCP 和多技能的 Agent',
          model: 'qwen3.6-plus',
          model_endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
          api_key: 'sk-****',
          system_prompt: '你是一个有用的 AI 助手。',
          enabled_tools: DEEPAGENTS_TOOLS.filter(t => t.name !== 'execute').map(t => t.name),
          excluded_tools: ['execute'],
          mcp_config: { url: 'http://localhost:8001/sse', transport: 'sse', headers: {} },
          memory: true,
          max_iterations: 50,
        });
      } else {
        setForm({ ...defaultConfig, name: 'simple-agent', description: '最简配置', enabled_tools: [] });
      }
    }
  };

  const handleSubmit = async () => {
    setLoading(true);
    setError('');
    try {
      let config: string;
      let configType: string;

      if (mode === 'form') {
        config = JSON.stringify({
          name: form.name,
          description: form.description,
          model: form.model,
          model_endpoint: form.model_endpoint,
          api_key: form.api_key,
          system_prompt: form.system_prompt,
          enabled_tools: form.enabled_tools,
          excluded_tools: form.excluded_tools,
          mcp_config: form.mcp_config,
          memory: form.memory,
          max_iterations: form.max_iterations,
        });
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
        <div className="space-y-4">
          {/* 基本信息 */}
          <div className="bg-white rounded-lg shadow p-6 space-y-4">
            <h2 className="font-semibold">基本信息</h2>
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

          {/* 默认工具选择 */}
          <div className="bg-white rounded-lg shadow p-6">
            <h2 className="font-semibold mb-3">DeepAgents 内置工具选择</h2>
            <p className="text-xs text-gray-500 mb-3">勾选启用的工具，取消勾选将从 Agent 中排除</p>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2">
              {DEEPAGENTS_TOOLS.map(tool => (
                <label key={tool.name} className={`flex items-start gap-2 p-2 rounded border cursor-pointer text-sm ${form.enabled_tools.includes(tool.name) ? 'border-blue-300 bg-blue-50' : 'border-gray-200 bg-gray-50 opacity-60'}`}>
                  <input type="checkbox" checked={form.enabled_tools.includes(tool.name)} onChange={() => toggleTool(tool.name)}
                    className="mt-0.5" />
                  <div>
                    <div className="font-medium text-xs font-mono">{tool.name}</div>
                    <div className="text-xs text-gray-500">{tool.desc}</div>
                  </div>
                </label>
              ))}
            </div>
            {form.enabled_tools.length === 0 && (
              <p className="text-xs text-yellow-600 mt-2">未启用任何工具, Agent 将无法执行文件操作和子 Agent 调用</p>
            )}
          </div>

          {/* MCP 配置 */}
          <div className="bg-white rounded-lg shadow p-6">
            <div className="flex items-center justify-between mb-3">
              <h2 className="font-semibold">MCP 配置 (可选)</h2>
              <button onClick={() => { setMcpExpanded(!mcpExpanded); if (!mcpExpanded && !form.mcp_config) setForm({ ...form, mcp_config: { url: '', transport: 'sse', headers: {} } }); }}
                className="text-xs text-blue-600 hover:underline">
                {mcpExpanded ? '收起' : '展开'}
              </button>
            </div>
            <p className="text-xs text-gray-500 mb-3">MCP (Model Context Protocol) 允许 Agent 通过标准化协议访问外部工具和数据源</p>
            {mcpExpanded && form.mcp_config && (
              <div className="space-y-3 pl-2 border-l-2 border-blue-200">
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">MCP Server URL</label>
                  <input type="text" value={form.mcp_config.url} placeholder="http://localhost:8001/sse"
                    onChange={e => setForm({ ...form, mcp_config: { ...form.mcp_config!, url: e.target.value } })}
                    className="w-full border rounded-lg px-3 py-2 text-sm" />
                  <p className="text-xs text-gray-400 mt-1">示例: http://localhost:8001/sse</p>
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">传输协议</label>
                  <select value={form.mcp_config.transport}
                    onChange={e => setForm({ ...form, mcp_config: { ...form.mcp_config!, transport: e.target.value } })}
                    className="w-full border rounded-lg px-3 py-2 text-sm">
                    <option value="sse">SSE (Server-Sent Events)</option>
                    <option value="stdio">STDIO</option>
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">自定义 Headers (JSON)</label>
                  <textarea rows={2} value={JSON.stringify(form.mcp_config.headers, null, 2)}
                    onChange={e => { try { const h = JSON.parse(e.target.value); setForm({ ...form, mcp_config: { ...form.mcp_config!, headers: h } }); } catch {} }}
                    className="w-full border rounded-lg px-3 py-2 text-sm font-mono text-xs" />
                </div>
              </div>
            )}
            {mcpExpanded && !form.mcp_config && (
              <button onClick={() => setForm({ ...form, mcp_config: { url: '', transport: 'sse', headers: {} } })}
                className="text-sm text-blue-600 hover:underline">+ 添加 MCP 配置</button>
            )}
          </div>

          {/* Skill 上传 */}
          <div className="bg-white rounded-lg shadow p-6">
            <div className="flex items-center justify-between mb-3">
              <h2 className="font-semibold">Skill 技能包上传</h2>
              <button onClick={() => setSkillExpanded(!skillExpanded)}
                className="text-xs text-blue-600 hover:underline">
                {skillExpanded ? '收起' : '展开'}
              </button>
            </div>
            <p className="text-xs text-gray-500 mb-3">上传 .zip 格式的 Skill 技能包，系统自动解析 SKILL.md 元数据 (name, description, license, allowed-tools)</p>
            {skillExpanded && (
              <div className="space-y-3 pl-2 border-l-2 border-green-200">
                <div className="border-2 border-dashed border-gray-300 rounded-lg p-6 text-center">
                  <input ref={fileInputRef} type="file" accept=".zip" onChange={handleSkillUpload} className="hidden" id="skill-upload" />
                  <label htmlFor="skill-upload" className="cursor-pointer">
                    <div className="text-3xl mb-2">📦</div>
                    <div className="text-sm text-gray-600">点击选择 .zip Skill 包</div>
                    <div className="text-xs text-gray-400 mt-1">或拖拽文件到此处</div>
                  </label>
                </div>
                <div className="bg-gray-50 rounded-lg p-3 text-xs text-gray-600">
                  <div className="font-medium mb-1">Skill 包结构示例:</div>
                  <pre className="text-xs">{`skills/
├── web-research/
│   ├── SKILL.md         # YAML frontmatter + Markdown
│   └── helper.py        # 可选辅助文件
├── code-review/
│   ├── SKILL.md
│   └── checklist.md`}</pre>
                </div>
              </div>
            )}
          </div>
        </div>
      ) : (
        <>
          <div className="flex gap-2 mb-3">
            <button onClick={() => fillExample('full')}
              className="px-3 py-1.5 rounded text-xs bg-green-600 text-white hover:bg-green-700">
              完整示例
            </button>
            <button onClick={() => fillExample('min')}
              className="px-3 py-1.5 rounded text-xs bg-gray-600 text-white hover:bg-gray-700">
              最小示例
            </button>
          </div>
          <textarea value={textInput} onChange={e => setTextInput(e.target.value)}
            className="w-full h-96 font-mono text-sm border rounded-lg p-4 bg-gray-900 text-green-400"
            placeholder={mode === 'json' ? '输入 JSON 配置...' : '输入 YAML 配置...'} />
        </>
      )}

      <button onClick={handleSubmit} disabled={loading}
        className="mt-4 bg-blue-600 text-white px-6 py-2.5 rounded-lg hover:bg-blue-700 disabled:opacity-50 text-sm">
        {loading ? '创建中...' : '创建 Agent'}
      </button>
    </div>
  );
}
