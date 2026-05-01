const BASE = process.env.NEXT_PUBLIC_API_URL || 'http://100.66.1.5:8080/api/v1';

async function request(path: string, options?: RequestInit) {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...options?.headers },
    ...options,
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export const api = {
  agents: {
    list: (params?: { status?: string; offset?: number; limit?: number }) => {
      const q = new URLSearchParams();
      if (params?.status) q.set('status', params.status);
      if (params?.offset !== undefined) q.set('offset', String(params.offset));
      if (params?.limit !== undefined) q.set('limit', String(params.limit));
      return request(`/agents?${q}`);
    },
    get: (id: number) => request(`/agents/${id}`),
    create: (config: string, configType: string) =>
      request('/agents', { method: 'POST', body: JSON.stringify({ config, config_type: configType }) }),
    update: (id: number, config: string) =>
      request(`/agents/${id}`, { method: 'PUT', body: JSON.stringify({ config }) }),
    delete: (id: number) => request(`/agents/${id}`, { method: 'DELETE' }),
    generate: (id: number) => request(`/agents/${id}/generate`, { method: 'POST' }),
    getCode: (id: number) => request(`/agents/${id}/code`),
    getDeployments: (id: number) => request(`/agents/${id}/deployments`),
    build: (id: number) => request(`/agents/${id}/build`, { method: 'POST' }),
    deploy: (id: number) => request(`/agents/${id}/deploy`, { method: 'POST' }),
    publish: (id: number) => request(`/agents/${id}/publish`, { method: 'POST' }),
    unpublish: (id: number) => request(`/agents/${id}/unpublish`, { method: 'POST' }),
  },
};
