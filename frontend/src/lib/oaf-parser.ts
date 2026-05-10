import { OAFConfig, OAFModel } from './oaf-types';

export function parseOAFYAML(content: string): OAFConfig {
  const parts = content.split('---');
  if (parts.length < 3) {
    throw new Error('Invalid AGENTS.md: missing YAML frontmatter');
  }

  const frontmatter = parts[1].trim();
  const instructions = parts.slice(2).join('---').trim();

  const config = parseYAML(frontmatter) as OAFConfig;
  config.instructions = instructions;

  if (!config.slug && config.vendorKey && config.agentKey) {
    config.slug = `${config.vendorKey}/${config.agentKey}`;
  }

  return config;
}

export function serializeOAFToYAML(config: OAFConfig): string {
  const { instructions, ...frontmatter } = config;
  const yaml = toYAML(frontmatter);

  if (instructions) {
    return `---\n${yaml}---\n\n${instructions}`;
  }
  return `---\n${yaml}---`;
}

export function validateOAF(config: OAFConfig): string[] {
  const errors: string[] = [];

  if (!config.name?.trim()) {
    errors.push('name is required');
  }
  if (!config.vendorKey?.trim()) {
    errors.push('vendorKey is required');
  } else if (!isKebabCase(config.vendorKey)) {
    errors.push('vendorKey must be kebab-case');
  }
  if (!config.agentKey?.trim()) {
    errors.push('agentKey is required');
  } else if (!isKebabCase(config.agentKey)) {
    errors.push('agentKey must be kebab-case');
  }
  if (!config.version?.trim()) {
    errors.push('version is required');
  } else if (!isSemver(config.version)) {
    errors.push('version must be semver (e.g., 1.0.0)');
  }
  if (!config.description?.trim()) {
    errors.push('description is required');
  }
  if (!config.author?.trim()) {
    errors.push('author is required');
  }
  if (!config.license?.trim()) {
    errors.push('license is required');
  }

  return errors;
}

export function generateSlug(vendorKey: string, agentKey: string): string {
  return `${vendorKey}/${agentKey}`;
}

export function parseModel(model: OAFModel | string | undefined): {
  isAlias: boolean;
  alias: string | null;
  obj: OAFModel | null;
} {
  if (!model) {
    return { isAlias: false, alias: null, obj: null };
  }

  if (typeof model === 'string') {
    return { isAlias: true, alias: model, obj: null };
  }

  return { isAlias: false, alias: null, obj: model };
}

function isKebabCase(s: string): boolean {
  return /^[a-z][a-z0-9-]*$/.test(s);
}

function isSemver(s: string): boolean {
  return /^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$/.test(s);
}

function parseYAML(yaml: string): unknown {
  const result: Record<string, unknown> = {};
  const lines = yaml.split('\n');
  let currentKey = '';
  let currentArray: unknown[] | null = null;
  let currentObj: Record<string, unknown> | null = null;
  let inArray = false;
  let arrayIndent = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;

    const indent = line.search(/\S/);

    if (inArray && indent <= arrayIndent) {
      if (currentArray && currentKey) {
        result[currentKey] = currentArray;
      }
      inArray = false;
      currentArray = null;
    }

    if (trimmed.startsWith('- ')) {
      if (!inArray) {
        inArray = true;
        arrayIndent = indent;
        currentArray = [];
      }

      const value = trimmed.slice(2).trim();

      if (value.includes(':')) {
        const obj: Record<string, unknown> = {};
        const [key, val] = value.split(':').map(s => s.trim());
        if (val) {
          obj[key] = parseValue(val);
        } else {
          obj[key] = '';
        }
        currentArray!.push(obj);
        currentObj = obj;
      } else {
        currentArray!.push(parseValue(value));
        currentObj = null;
      }
      continue;
    }

    if (trimmed.includes(':')) {
      const colonIdx = trimmed.indexOf(':');
      const key = trimmed.slice(0, colonIdx).trim();
      const value = trimmed.slice(colonIdx + 1).trim();

      if (currentObj && indent > arrayIndent + 2) {
        if (value) {
          currentObj[key] = parseValue(value);
        } else {
          currentObj[key] = '';
        }
      } else {
        currentKey = key;
        if (value) {
          result[key] = parseValue(value);
          inArray = false;
          currentArray = null;
        }
      }
    }
  }

  if (inArray && currentArray && currentKey) {
    result[currentKey] = currentArray;
  }

  return result;
}

function parseValue(value: string): unknown {
  if (value.startsWith('"') && value.endsWith('"')) {
    return value.slice(1, -1);
  }
  if (value.startsWith("'") && value.endsWith("'")) {
    return value.slice(1, -1);
  }
  if (value === 'true') return true;
  if (value === 'false') return false;
  if (value === 'null') return null;
  if (!isNaN(Number(value))) return Number(value);
  if (value.startsWith('[') && value.endsWith(']')) {
    return value.slice(1, -1).split(',').map(s => s.trim());
  }
  return value;
}

function toYAML(obj: unknown, indent = 0): string {
  if (obj === null || obj === undefined) return '';
  if (typeof obj === 'string') return `"${obj}"`;
  if (typeof obj === 'number' || typeof obj === 'boolean') return String(obj);
  if (Array.isArray(obj)) {
    if (obj.length === 0) return '[]';
    return obj.map(item => {
      const prefix = '  '.repeat(indent) + '- ';
      if (typeof item === 'object' && item !== null) {
        const entries = Object.entries(item as Record<string, unknown>);
        if (entries.length === 0) return prefix + '{}';
        const [firstKey, firstVal] = entries[0];
        const rest = entries.slice(1);
        let result = prefix + `${firstKey}: ${toYAML(firstVal, indent + 2)}`;
        for (const [k, v] of rest) {
          result += `\n${'  '.repeat(indent + 1)}${k}: ${toYAML(v, indent + 2)}`;
        }
        return result;
      }
      return prefix + toYAML(item, indent + 1);
    }).join('\n');
  }
  if (typeof obj === 'object') {
    const entries = Object.entries(obj as Record<string, unknown>).filter(([_, v]) => v !== undefined);
    if (entries.length === 0) return '{}';
    return entries.map(([key, value]) => {
      const prefix = '  '.repeat(indent);
      if (value === null) return `${prefix}${key}: null`;
      if (value === '') return `${prefix}${key}: ""`;
      if (Array.isArray(value) && value.length === 0) return `${prefix}${key}: []`;
      if (typeof value === 'object' && value !== null && Object.keys(value as object).length === 0) {
        return `${prefix}${key}: {}`;
      }
      if (typeof value === 'object' && value !== null) {
        return `${prefix}${key}:\n${toYAML(value, indent + 1)}`;
      }
      return `${prefix}${key}: ${toYAML(value, indent)}`;
    }).join('\n');
  }
  return String(obj);
}
