export interface OAFConfig {
  name: string;
  vendorKey: string;
  agentKey: string;
  version: string;
  slug: string;

  description: string;
  author: string;
  license: string;
  tags: string[];

  skills?: OAFSkill[];
  packs?: OAFPack[];
  weblets?: OAFWeblet[];
  mcpServers?: OAFMCPServer[];
  agents?: OAFSubAgent[];

  orchestration?: OAFOrchestration;

  tools?: string[];

  model?: OAFModel | string;
  config?: OAFRuntimeConfig;
  memory?: OAFMemory;

  harnessConfig?: Record<string, unknown>;

  instructions?: string;
}

export interface OAFSkill {
  name: string;
  source: string;
  version: string;
  required: boolean;
}

export interface OAFPack {
  vendor: string;
  pack: string;
  version: string;
  required: boolean;
}

export interface OAFWeblet {
  vendor: string;
  weblet: string;
  version: string;
  launch: 'onDemand' | 'background' | 'foreground';
}

export interface OAFMCPServer {
  vendor: string;
  server: string;
  version: string;
  configDir: string;
  required: boolean;
}

export interface OAFSubAgent {
  vendor: string;
  agent: string;
  version: string;
  role: string;
  delegations?: string[];
  required: boolean;
}

export interface OAFOrchestration {
  entrypoint: string;
  fallback?: string;
  triggers?: OAFTrigger[];
}

export interface OAFTrigger {
  event: string;
  action: string;
}

export interface OAFModel {
  provider: string;
  name: string;
  embedding?: string;
}

export interface OAFRuntimeConfig {
  temperature?: number;
  max_tokens?: number;
  require_confirmation?: boolean;
}

export interface OAFMemory {
  type: 'editable' | 'read-only';
  blocks?: Record<string, string>;
}

export const DEFAULT_OAF: OAFConfig = {
  name: '',
  vendorKey: '',
  agentKey: '',
  version: '1.0.0',
  slug: '',
  description: '',
  author: '',
  license: 'MIT',
  tags: [],
  skills: [],
  mcpServers: [],
  agents: [],
  tools: ['Read', 'Edit', 'Bash', 'Glob', 'Grep'],
  config: {
    temperature: 0.7,
    max_tokens: 4096,
  },
  instructions: '',
};

export const AVAILABLE_TOOLS = [
  { name: 'Read', desc: '读取文件内容' },
  { name: 'Edit', desc: '编辑文件（查找替换）' },
  { name: 'Bash', desc: '执行 Shell 命令' },
  { name: 'Glob', desc: '按模式匹配文件' },
  { name: 'Grep', desc: '搜索文件内容' },
];

export const MODEL_ALIASES = ['sonnet', 'opus', 'haiku'] as const;
export type ModelAlias = typeof MODEL_ALIASES[number];
