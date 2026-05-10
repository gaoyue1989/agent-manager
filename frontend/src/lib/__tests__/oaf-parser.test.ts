import { parseOAFYAML, serializeOAFToYAML, validateOAF, generateSlug, parseModel } from '../oaf-parser';
import { OAFConfig, DEFAULT_OAF } from '../oaf-types';

describe('parseOAFYAML', () => {
  it('parses minimal OAF YAML', () => {
    const content = `---
name: "Simple Assistant"
vendorKey: "acme"
agentKey: "simple"
version: "1.0.0"
slug: "acme/simple"
description: "A simple helpful assistant"
author: "@acme"
license: "MIT"
tags: []
---
I am a simple helpful assistant.`;

    const config = parseOAFYAML(content);
    expect(config.name).toBe('Simple Assistant');
    expect(config.vendorKey).toBe('acme');
    expect(config.agentKey).toBe('simple');
    expect(config.version).toBe('1.0.0');
    expect(config.description).toBe('A simple helpful assistant');
    expect(config.instructions).toBe('I am a simple helpful assistant.');
  });

  it('parses full OAF YAML with skills and MCP', () => {
    const content = `---
name: "Research Assistant"
vendorKey: "acme"
agentKey: "research"
version: "1.0.0"
slug: "acme/research"
description: "A research assistant"
author: "@acme"
license: "MIT"
tags: ["research"]
skills:
  - name: "web-search"
    source: "local"
    version: "1.0.0"
    required: true
mcpServers:
  - vendor: "block"
    server: "filesystem"
    version: "1.0.0"
    configDir: "mcp-configs/filesystem"
    required: true
tools: ["Read", "Edit", "Bash"]
---
You are a research assistant.`;

    const config = parseOAFYAML(content);
    expect(config.name).toBe('Research Assistant');
    expect(config.skills).toHaveLength(1);
    expect(config.skills?.[0].name).toBe('web-search');
    expect(config.skills?.[0].source).toBe('local');
    expect(config.mcpServers).toHaveLength(1);
    expect(config.mcpServers?.[0].vendor).toBe('block');
    expect(config.tools).toEqual(['Read', 'Edit', 'Bash']);
  });

  it('throws error for missing frontmatter', () => {
    expect(() => parseOAFYAML('no frontmatter')).toThrow('missing YAML frontmatter');
  });
});

describe('serializeOAFToYAML', () => {
  it('serializes OAF object to YAML', () => {
    const config: OAFConfig = {
      ...DEFAULT_OAF,
      name: 'Test Agent',
      vendorKey: 'acme',
      agentKey: 'test',
      version: '1.0.0',
      slug: 'acme/test',
      description: 'A test agent',
      author: '@acme',
      license: 'MIT',
      tags: ['test'],
    };

    const yaml = serializeOAFToYAML(config);
    expect(yaml).toContain('---');
    expect(yaml).toContain('name: "Test Agent"');
    expect(yaml).toContain('vendorKey: "acme"');
  });

  it('includes instructions in output', () => {
    const config: OAFConfig = {
      ...DEFAULT_OAF,
      name: 'Test',
      vendorKey: 'acme',
      agentKey: 'test',
      version: '1.0.0',
      description: 'Test',
      author: '@acme',
      license: 'MIT',
      tags: [],
      instructions: 'You are a test agent.',
    };

    const yaml = serializeOAFToYAML(config);
    expect(yaml).toContain('You are a test agent.');
  });
});

describe('validateOAF', () => {
  it('returns empty array for valid config', () => {
    const config: OAFConfig = {
      ...DEFAULT_OAF,
      name: 'Test',
      vendorKey: 'acme',
      agentKey: 'test',
      version: '1.0.0',
      description: 'Test description',
      author: '@acme',
      license: 'MIT',
      tags: [],
    };

    const errors = validateOAF(config);
    expect(errors).toHaveLength(0);
  });

  it('returns error for missing name', () => {
    const config: OAFConfig = {
      ...DEFAULT_OAF,
      name: '',
      vendorKey: 'acme',
      agentKey: 'test',
      version: '1.0.0',
      description: 'Test',
      author: '@acme',
      license: 'MIT',
      tags: [],
    };

    const errors = validateOAF(config);
    expect(errors).toContain('name is required');
  });

  it('returns error for invalid vendorKey', () => {
    const config: OAFConfig = {
      ...DEFAULT_OAF,
      name: 'Test',
      vendorKey: 'InvalidKey',
      agentKey: 'test',
      version: '1.0.0',
      description: 'Test',
      author: '@acme',
      license: 'MIT',
      tags: [],
    };

    const errors = validateOAF(config);
    expect(errors).toContain('vendorKey must be kebab-case');
  });

  it('returns error for invalid version', () => {
    const config: OAFConfig = {
      ...DEFAULT_OAF,
      name: 'Test',
      vendorKey: 'acme',
      agentKey: 'test',
      version: 'v1.0',
      description: 'Test',
      author: '@acme',
      license: 'MIT',
      tags: [],
    };

    const errors = validateOAF(config);
    expect(errors).toContain('version must be semver (e.g., 1.0.0)');
  });
});

describe('generateSlug', () => {
  it('generates slug from vendorKey and agentKey', () => {
    expect(generateSlug('acme', 'my-agent')).toBe('acme/my-agent');
    expect(generateSlug('mycompany', 'research')).toBe('mycompany/research');
  });
});

describe('parseModel', () => {
  it('parses model alias string', () => {
    const result = parseModel('sonnet');
    expect(result.isAlias).toBe(true);
    expect(result.alias).toBe('sonnet');
    expect(result.obj).toBeNull();
  });

  it('parses model object', () => {
    const result = parseModel({ provider: 'anthropic', name: 'claude-sonnet-4-5' });
    expect(result.isAlias).toBe(false);
    expect(result.alias).toBeNull();
    expect(result.obj).toEqual({ provider: 'anthropic', name: 'claude-sonnet-4-5' });
  });

  it('returns null for undefined', () => {
    const result = parseModel(undefined);
    expect(result.isAlias).toBe(false);
    expect(result.alias).toBeNull();
    expect(result.obj).toBeNull();
  });
});
