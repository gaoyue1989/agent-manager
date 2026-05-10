'use client';
import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/api';
import {
  OAFConfig,
  OAFSkill,
  OAFMCPServer,
  OAFSubAgent,
  DEFAULT_OAF,
  AVAILABLE_TOOLS,
} from '@/lib/oaf-types';
import { serializeOAFToYAML, validateOAF, generateSlug } from '@/lib/oaf-parser';

export default function CreateAgent() {
  const router = useRouter();
  const [mode, setMode] = useState<'form' | 'yaml'>('form');
  const [config, setConfig] = useState<OAFConfig>({ ...DEFAULT_OAF });
  const [yamlInput, setYamlInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);

  const updateField = <K extends keyof OAFConfig>(key: K, value: OAFConfig[K]) => {
    setConfig(prev => ({ ...prev, [key]: value }));
  };

  const addSkill = () => {
    const skill: OAFSkill = { name: '', source: 'local', version: '1.0.0', required: false };
    updateField('skills', [...(config.skills || []), skill]);
  };

  const removeSkill = (index: number) => {
    updateField('skills', config.skills?.filter((_, i) => i !== index));
  };

  const updateSkill = (index: number, field: keyof OAFSkill, value: string | boolean) => {
    const skills = [...(config.skills || [])];
    skills[index] = { ...skills[index], [field]: value };
    updateField('skills', skills);
  };

  const addMCPServer = () => {
    const mcp: OAFMCPServer = {
      vendor: '',
      server: '',
      version: '1.0.0',
      configDir: '',
      required: false,
    };
    updateField('mcpServers', [...(config.mcpServers || []), mcp]);
  };

  const removeMCPServer = (index: number) => {
    updateField('mcpServers', config.mcpServers?.filter((_, i) => i !== index));
  };

  const updateMCPServer = (index: number, field: keyof OAFMCPServer, value: string | boolean) => {
    const servers = [...(config.mcpServers || [])];
    servers[index] = { ...servers[index], [field]: value };
    updateField('mcpServers', servers);
  };

  const addSubAgent = () => {
    const agent: OAFSubAgent = {
      vendor: '',
      agent: '',
      version: '1.0.0',
      role: '',
      required: false,
    };
    updateField('agents', [...(config.agents || []), agent]);
  };

  const removeSubAgent = (index: number) => {
    updateField('agents', config.agents?.filter((_, i) => i !== index));
  };

  const updateSubAgent = (index: number, field: keyof OAFSubAgent, value: string | boolean | string[]) => {
    const agents = [...(config.agents || [])];
    agents[index] = { ...agents[index], [field]: value };
    updateField('agents', agents);
  };

  const toggleTool = (toolName: string) => {
    const tools = config.tools || [];
    if (tools.includes(toolName)) {
      updateField('tools', tools.filter(t => t !== toolName));
    } else {
      updateField('tools', [...tools, toolName]);
    }
  };

  const handleSubmit = async () => {
    setLoading(true);
    setErrors([]);

    try {
      let oafContent: string;

      if (mode === 'yaml') {
        oafContent = yamlInput;
      } else {
        const slug = generateSlug(config.vendorKey, config.agentKey);
        const finalConfig = { ...config, slug };
        const validationErrors = validateOAF(finalConfig);
        if (validationErrors.length > 0) {
          setErrors(validationErrors);
          setLoading(false);
          return;
        }
        oafContent = serializeOAFToYAML(finalConfig);
      }

      const agent = await api.agents.create(oafContent, 'oaf');
      router.push(`/agents/${agent.id}`);
    } catch (e: any) {
      setErrors([e.message || '创建失败']);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-4xl mx-auto">
      <h1 className="text-2xl font-bold mb-6">创建 Agent (OAF v0.8.0)</h1>

      <div className="flex gap-2 mb-6">
        <button
          onClick={() => setMode('form')}
          className={`px-4 py-2 rounded-lg text-sm ${mode === 'form' ? 'bg-blue-600 text-white' : 'bg-gray-200'}`}
        >
          表单模式
        </button>
        <button
          onClick={() => setMode('yaml')}
          className={`px-4 py-2 rounded-lg text-sm ${mode === 'yaml' ? 'bg-blue-600 text-white' : 'bg-gray-200'}`}
        >
          YAML 模式
        </button>
      </div>

      {errors.length > 0 && (
        <div className="bg-red-50 text-red-600 p-3 rounded-lg mb-4 text-sm">
          {errors.map((e, i) => (
            <div key={i}>{e}</div>
          ))}
        </div>
      )}

      {mode === 'form' ? (
        <div className="space-y-6">
          {/* Identity */}
          <div className="bg-white rounded-lg shadow p-6 space-y-4">
            <h2 className="font-semibold text-lg">身份标识 (Identity)</h2>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium mb-1">名称 *</label>
                <input
                  type="text"
                  value={config.name}
                  onChange={e => updateField('name', e.target.value)}
                  className="w-full border rounded-lg px-3 py-2 text-sm"
                  placeholder="My Agent"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">版本 *</label>
                <input
                  type="text"
                  value={config.version}
                  onChange={e => updateField('version', e.target.value)}
                  className="w-full border rounded-lg px-3 py-2 text-sm"
                  placeholder="1.0.0"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">Vendor Key *</label>
                <input
                  type="text"
                  value={config.vendorKey}
                  onChange={e => updateField('vendorKey', e.target.value)}
                  className="w-full border rounded-lg px-3 py-2 text-sm"
                  placeholder="mycompany"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">Agent Key *</label>
                <input
                  type="text"
                  value={config.agentKey}
                  onChange={e => updateField('agentKey', e.target.value)}
                  className="w-full border rounded-lg px-3 py-2 text-sm"
                  placeholder="my-agent"
                />
              </div>
            </div>
            {config.vendorKey && config.agentKey && (
              <p className="text-xs text-gray-500">
                Slug: <code className="bg-gray-100 px-1 rounded">{config.vendorKey}/{config.agentKey}</code>
              </p>
            )}
          </div>

          {/* Metadata */}
          <div className="bg-white rounded-lg shadow p-6 space-y-4">
            <h2 className="font-semibold text-lg">元数据 (Metadata)</h2>
            <div>
              <label className="block text-sm font-medium mb-1">描述 *</label>
              <textarea
                value={config.description}
                onChange={e => updateField('description', e.target.value)}
                className="w-full border rounded-lg px-3 py-2 text-sm"
                rows={2}
                placeholder="Agent 描述..."
              />
            </div>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="block text-sm font-medium mb-1">作者 *</label>
                <input
                  type="text"
                  value={config.author}
                  onChange={e => updateField('author', e.target.value)}
                  className="w-full border rounded-lg px-3 py-2 text-sm"
                  placeholder="@mycompany"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">许可证 *</label>
                <select
                  value={config.license}
                  onChange={e => updateField('license', e.target.value)}
                  className="w-full border rounded-lg px-3 py-2 text-sm"
                >
                  <option value="MIT">MIT</option>
                  <option value="Apache-2.0">Apache-2.0</option>
                  <option value="GPL-3.0">GPL-3.0</option>
                  <option value="BSD-3-Clause">BSD-3-Clause</option>
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">标签</label>
                <input
                  type="text"
                  value={config.tags?.join(', ') || ''}
                  onChange={e => updateField('tags', e.target.value.split(',').map(s => s.trim()).filter(Boolean))}
                  className="w-full border rounded-lg px-3 py-2 text-sm"
                  placeholder="tag1, tag2"
                />
              </div>
            </div>
          </div>

          {/* Skills */}
          <div className="bg-white rounded-lg shadow p-6 space-y-4">
            <div className="flex justify-between items-center">
              <h2 className="font-semibold text-lg">技能 (Skills)</h2>
              <button onClick={addSkill} className="text-sm text-blue-600 hover:underline">+ 添加技能</button>
            </div>
            {config.skills?.map((skill, i) => (
              <div key={i} className="border rounded-lg p-4 space-y-2">
                <div className="flex justify-between">
                  <span className="text-sm font-medium">Skill #{i + 1}</span>
                  <button onClick={() => removeSkill(i)} className="text-red-500 text-sm">删除</button>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <input
                    type="text"
                    value={skill.name}
                    onChange={e => updateSkill(i, 'name', e.target.value)}
                    className="border rounded px-2 py-1 text-sm"
                    placeholder="skill-name"
                  />
                  <select
                    value={skill.source}
                    onChange={e => updateSkill(i, 'source', e.target.value)}
                    className="border rounded px-2 py-1 text-sm"
                  >
                    <option value="local">local (本地)</option>
                    <option value="remote">remote (远程 URL)</option>
                  </select>
                  {skill.source !== 'local' && (
                    <input
                      type="text"
                      value={skill.source}
                      onChange={e => updateSkill(i, 'source', e.target.value)}
                      className="border rounded px-2 py-1 text-sm col-span-2"
                      placeholder="https://example.com/.well-known/skills/..."
                    />
                  )}
                </div>
                <label className="flex items-center gap-2 text-xs">
                  <input
                    type="checkbox"
                    checked={skill.required}
                    onChange={e => updateSkill(i, 'required', e.target.checked)}
                  />
                  必需
                </label>
              </div>
            ))}
          </div>

          {/* MCP Servers */}
          <div className="bg-white rounded-lg shadow p-6 space-y-4">
            <div className="flex justify-between items-center">
              <h2 className="font-semibold text-lg">MCP 服务器</h2>
              <button onClick={addMCPServer} className="text-sm text-blue-600 hover:underline">+ 添加 MCP</button>
            </div>
            {config.mcpServers?.map((mcp, i) => (
              <div key={i} className="border rounded-lg p-4 space-y-2">
                <div className="flex justify-between">
                  <span className="text-sm font-medium">MCP #{i + 1}</span>
                  <button onClick={() => removeMCPServer(i)} className="text-red-500 text-sm">删除</button>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <input
                    type="text"
                    value={mcp.vendor}
                    onChange={e => updateMCPServer(i, 'vendor', e.target.value)}
                    className="border rounded px-2 py-1 text-sm"
                    placeholder="vendor"
                  />
                  <input
                    type="text"
                    value={mcp.server}
                    onChange={e => updateMCPServer(i, 'server', e.target.value)}
                    className="border rounded px-2 py-1 text-sm"
                    placeholder="server-name"
                  />
                </div>
              </div>
            ))}
          </div>

          {/* Tools */}
          <div className="bg-white rounded-lg shadow p-6">
            <h2 className="font-semibold text-lg mb-3">工具 (Tools)</h2>
            <div className="flex flex-wrap gap-2">
              {AVAILABLE_TOOLS.map(tool => (
                <label
                  key={tool.name}
                  className={`flex items-center gap-2 px-3 py-2 rounded-lg border cursor-pointer text-sm ${
                    config.tools?.includes(tool.name) ? 'border-blue-500 bg-blue-50' : 'border-gray-200'
                  }`}
                >
                  <input
                    type="checkbox"
                    checked={config.tools?.includes(tool.name)}
                    onChange={() => toggleTool(tool.name)}
                    className="hidden"
                  />
                  <span className="font-mono">{tool.name}</span>
                  <span className="text-xs text-gray-500">{tool.desc}</span>
                </label>
              ))}
            </div>
          </div>

          {/* Config */}
          <div className="bg-white rounded-lg shadow p-6 space-y-4">
            <h2 className="font-semibold text-lg">运行时配置</h2>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium mb-1">Temperature</label>
                <input
                  type="number"
                  step="0.1"
                  min="0"
                  max="1"
                  value={config.config?.temperature ?? 0.7}
                  onChange={e => updateField('config', { ...config.config, temperature: parseFloat(e.target.value) })}
                  className="w-full border rounded-lg px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">Max Tokens</label>
                <input
                  type="number"
                  value={config.config?.max_tokens ?? 4096}
                  onChange={e => updateField('config', { ...config.config, max_tokens: parseInt(e.target.value) })}
                  className="w-full border rounded-lg px-3 py-2 text-sm"
                />
              </div>
            </div>
          </div>

          {/* Instructions */}
          <div className="bg-white rounded-lg shadow p-6 space-y-4">
            <h2 className="font-semibold text-lg">系统指令 (Instructions)</h2>
            <textarea
              value={config.instructions || ''}
              onChange={e => updateField('instructions', e.target.value)}
              className="w-full border rounded-lg px-3 py-2 text-sm font-mono"
              rows={6}
              placeholder="# Agent Purpose&#10;&#10;You are a helpful AI assistant..."
            />
          </div>

          {/* LLM Config Notice */}
          <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-4 text-sm">
            <p className="font-medium text-yellow-800">LLM 配置</p>
            <p className="text-yellow-700 mt-1">
              LLM API Key、Model、Endpoint 通过环境变量配置，不在 Agent 配置中存储：
            </p>
            <ul className="text-yellow-700 mt-1 ml-4 list-disc">
              <li><code>LLM_API_KEY</code></li>
              <li><code>LLM_MODEL_ID</code></li>
              <li><code>LLM_BASE_URL</code></li>
            </ul>
          </div>
        </div>
      ) : (
        <div>
          <p className="text-sm text-gray-500 mb-2">
            直接编辑 OAF YAML 配置（AGENTS.md 格式）
          </p>
          <textarea
            value={yamlInput}
            onChange={e => setYamlInput(e.target.value)}
            className="w-full h-[600px] font-mono text-sm border rounded-lg p-4 bg-gray-900 text-green-400"
            placeholder={`---
name: "My Agent"
vendorKey: "mycompany"
agentKey: "my-agent"
version: "1.0.0"
slug: "mycompany/my-agent"
description: "Agent description"
author: "@mycompany"
license: "MIT"
tags: ["assistant"]
---
# Agent Purpose

You are a helpful AI assistant.`}
          />
        </div>
      )}

      <button
        onClick={handleSubmit}
        disabled={loading}
        className="mt-6 bg-blue-600 text-white px-6 py-2.5 rounded-lg hover:bg-blue-700 disabled:opacity-50 text-sm"
      >
        {loading ? '创建中...' : '创建 Agent'}
      </button>
    </div>
  );
}
