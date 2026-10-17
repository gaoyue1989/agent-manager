// 平台 REST API 客户端（对齐 backend /api/v1 契约）
// M0 起新增 auth / console / admin 三组方法；401 统一跳 /login（非登录页）。
const BASE = process.env.NEXT_PUBLIC_API_URL || "/api/v1";

async function request<T = any>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { ...init, headers: { "Content-Type": "application/json", ...(init?.headers || {}) } });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    if (res.status === 401 && typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
      window.location.href = "/login";
    }
    throw new Error(body?.message || `HTTP ${res.status}`);
  }
  return body?.data as T;
}

async function requestRaw<T = any>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { ...init, headers: { "Content-Type": "application/json", ...(init?.headers || {}) } });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body?.message || `HTTP ${res.status}`);
  return body?.data as T;
}

async function upload<T = any>(path: string, file: File): Promise<T> {
  const fd = new FormData();
  fd.append("file", file);
  const res = await fetch(`${BASE}${path}`, { method: "POST", body: fd });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body?.message || `HTTP ${res.status}`);
  return body?.data as T;
}

export interface PackageRec { id: number; name: string; slug: string; version: string; description: string; refCount: number; fileCount: number; totalSize?: number; sourcePackageId?: number; checksum?: string; workspaceId?: number; isTemplate?: boolean; createdAt: string; }
export interface PackageDetail { package: PackageRec; tree: any[]; agentsMd: string; }
export interface FileEntry { name: string; path: string; isDir: boolean; size?: number; children?: FileEntry[]; }
export interface PackageFileContent { path: string; size: number; binary: boolean; content?: string; }
export interface VersionUpsert { path: string; content: string; encoding?: string; }
export interface CreateVersionResult { package: PackageRec; warnings: string[]; }
export interface ServiceRec {
  id: number; k8sName: string; displayName: string; packageId: number; image: string;
  envJson: string; replicas: number; status: string; endpoint: string; clusterUrl: string;
  registeredName?: string; registeredVersion?: string; agentCardJson?: string;
  registeredAt?: string | null; createdAt: string;
  shortName?: string; skillsJson?: string; updatedAt?: string;
  workspaceId?: number; createdBy?: number; spec?: string; publicEndpoint?: boolean | null;
  // 服务级敏感键掩码视图（值永不回传，仅键名 + hasValue）
  envSecretKeys?: EnvSecretKey[];
}
export interface PodInfo { name: string; phase: string; ready: boolean; restarts: number; }
export interface ServiceEvent { id: number; fromStatus: string; toStatus: string; reason: string; createdAt: string; }
export interface ServiceDetail extends ServiceRec { pods?: PodInfo[]; events: ServiceEvent[]; }
export interface ImageOption { Image: string; Label: string }
// 平台默认配置（R3：仅作为发布/编辑 env 时的表单默认填入，不经 envFrom 注入、不影响存量服务）
export interface EnvSecretKey { key: string; hasValue: boolean; }
export interface PlatformConfigField {
  envKey: string; label: string; required: boolean; sensitive: boolean; multiline: boolean;
  placeholder?: string; hasValue: boolean; value?: string; updatedAt?: string;
}
export interface PlatformConfigGroup { name: string; title: string; fields: PlatformConfigField[]; }
export interface PlatformConfigView { groups: PlatformConfigGroup[]; updatedAt?: string; }

// ---- 控制面拆分新增类型（M0–M8）----
export interface UserView { id: number; username: string; displayName: string; email?: string; role: "admin" | "user"; status: string; lastLoginAt?: string | null; workspaces?: WorkspaceBrief[]; }
export interface WorkspaceBrief { id: number; slug: string; name: string; role: string; }
export interface MeResponse { user: UserView; workspaces: WorkspaceBrief[]; }
export interface LoginResponse { token: string; user: UserView; workspaces: WorkspaceBrief[]; }
export interface WorkspaceAdminView {
  id: number; slug: string; name: string; description?: string; status: string;
  owner?: UserView | null; memberCount: number; agentCount: number;
  quota: Record<string, number | undefined>; createdAt: string;
}
export interface AgentListItem {
  id: number; displayName: string; k8sName: string; shortName: string; status: string;
  endpoint: string; model: string; version: string; replicas: number;
  cardSummary: { name: string; description: string }; updatedAt: string;
}
export interface ConsoleSummary {
  agents: { total: number; running: number; error: number; stopped: number };
  recentEvents: { id: number; reason: string; toStatus: string; displayName: string; serviceId: number; createdAt: string }[];
  todaySessions: null;
}
export interface ModelConnectionView {
  id: number; workspaceId: number; name: string; provider: string; modelId: string; baseUrl: string;
  apiKeyMasked: string; temperature: number; maxTokens: number; timeoutSeconds: number;
  status: string; lastTestedAt?: string | null; deliveryCount: number;
}
export interface RuntimeModel { id: string; name: string; source: string; modelId: string; baseUrl?: string; isDefault: boolean; delivered: boolean; }
export interface ModelConnUsageRow {
  serviceId: number; displayName: string; defaultConnectionName: string;
  connections: { connectionId: number; name: string; status: string; remoteModelId: string; error: string; syncedAt?: string | null }[];
  /** Agent 运行时 /models 实际清单（含存量/手动配置；null = 业务面拉取失败） */
  runtimeModels?: RuntimeModel[] | null;
}
export interface McpConnectorView {
  id: number; workspaceId: number; name: string; transport: string; endpoint: string;
  authType: string; timeoutSec: number; toolScope: string; permissions: string;
  status: string; lastVerifiedAt?: string | null; refCount: number;
}
export type ToolPerm = Record<string, string>;
export interface IntegrationView {
  id: number; workspaceId: number; memberServiceId: number; name: string; endpointUrl: string;
  askPolicy: string; remoteStreaming: boolean; authHeaderKey: string; enabled: boolean;
  referencedBy: number[];
}
export interface IntegrationCandidate {
  serviceId: number; displayName: string; shortName: string; k8sName: string;
  protocolEnabled: boolean; probe: "online" | "unreachable" | "skipped"; endpointUrl: string;
}
export interface ApiKeyView {
  id: number; name: string; keyPrefix: string; agentId?: number | null; agentName?: string;
  status: string; lastUsedAt?: string | null; expiresAt?: string | null; createdAt: string;
}
export interface MemberView {
  id: number; workspaceId: number; userId: number; role: string; createdAt: string;
  user?: UserView | null;
}
export interface AgentFormPayload {
  mode?: string; name: string; description?: string; image?: string;
  modelConnections: { connectionId: number; isDefault: boolean }[];
  prompt: string;
  skills: { name: string; source: string; packageId?: number; version?: string }[];
  subAgents: { agent: string; role?: string; delegations?: string[]; endpoint?: string }[];
  mcpServers: { connectorId?: number; builtin?: string }[];
  spec: string; replicas: number; publicEndpoint?: boolean;
  remoteAuth?: Record<string, string>;
  importPackageId?: number;
  adoptServiceId?: number;
}
export interface EditFormView {
  name: string; k8sName: string; description: string; image: string;
  modelConnections: { connectionId: number; isDefault: boolean }[];
  prompt: string; skills: { name: string; source: string; packageId?: number; version?: string }[];
  subAgents: { agent: string; role?: string; delegations?: string[]; endpoint?: string }[];
  mcpServers: { connectorId?: number; builtin?: string }[];
  spec: string; replicas: number; publicEndpoint: boolean; version: string; packageId: number;
}
export interface AuditItem { time: string; actor: string; action: string; object: string; detail: string; }

export const api = {
  // 包
  listPackages: (keyword = "", slug = "") =>
    request<PackageRec[]>(`/packages?keyword=${encodeURIComponent(keyword)}&slug=${encodeURIComponent(slug)}`),
  getPackage: (id: number) => request<PackageDetail>(`/packages/${id}`),
  deletePackage: (id: number) => request(`/packages/${id}`, { method: "DELETE" }),
  uploadPackage: (file: File) => upload<PackageRec>(`/packages`, file),
  getPackageFile: (id: number, path: string) =>
    request<PackageFileContent>(`/packages/${id}/files?path=${encodeURIComponent(path)}`),
  downloadPackageFile: (id: number, path: string) =>
    `${BASE}/packages/${id}/files/download?path=${encodeURIComponent(path)}`,
  downloadPackage: (id: number) => `${BASE}/packages/${id}/download`,
  createPackageVersion: (id: number, body: { upserts?: VersionUpsert[]; deletes?: string[]; expectedBaseChecksum?: string }) =>
    request<CreateVersionResult>(`/packages/${id}/versions`, { method: "POST", body: JSON.stringify(body) }),
  setPackageTemplate: (id: number, isTemplate: boolean) =>
    request(`/packages/${id}/template`, { method: "PATCH", body: JSON.stringify({ isTemplate }) }),
  // 镜像
  listImages: () => request<ImageOption[]>(`/images`),
  // 服务
  listServices: (status = "", keyword = "", packageId = 0, workspaceId = 0) =>
    request<(ServiceRec & { pods?: PodInfo[] })[]>(`/services?status=${status}&keyword=${encodeURIComponent(keyword)}&packageId=${packageId}&workspaceId=${workspaceId}`),
  getService: (id: number) => request<ServiceDetail>(`/services/${id}`),
  publish: (body: { packageId: number; name?: string; image?: string; env?: Record<string, string>; secretKeys?: string[]; replicas?: number; workspaceId?: number }) =>
    request<ServiceRec>(`/services`, { method: "POST", body: JSON.stringify(body) }),
  updateEnv: (id: number, env: Record<string, string>, secretKeys?: string[]) =>
    request<ServiceRec>(`/services/${id}/env`, { method: "PATCH", body: JSON.stringify({ env, secretKeys }) }),
  // 平台默认配置
  getPlatformConfig: () => request<PlatformConfigView>(`/platform-config`),
  getPlatformDefaults: () => request<{ values: Record<string, string> }>(`/platform-config/defaults`),
  updatePlatformConfig: (values: Record<string, string>) =>
    request<PlatformConfigView>(`/platform-config`, { method: "PUT", body: JSON.stringify({ values }) }),
  republish: (id: number, opt: { packageId?: number } = {}) =>
    request<ServiceRec>(`/services/${id}/republish`, { method: "POST", body: JSON.stringify(opt) }),
  startAgain: (id: number) => request<ServiceRec>(`/services/${id}/publish`, { method: "POST" }),
  unpublish: (id: number) => request<ServiceRec>(`/services/${id}/unpublish`, { method: "POST" }),
  reregister: (id: number) => request<ServiceRec>(`/services/${id}/register`, { method: "POST" }),
  deleteService: (id: number) => request(`/services/${id}`, { method: "DELETE" }),

  // ---- 认证（M0）----
  auth: {
    login: (username: string, password: string) =>
      requestRaw<LoginResponse>(`/auth/login`, { method: "POST", body: JSON.stringify({ username, password }) }),
    logout: () => requestRaw(`/auth/logout`, { method: "POST" }),
    me: () => requestRaw<MeResponse>(`/auth/me`),
  },

  // ---- 用户侧 console（M2–M8；workspace 以 :ws 路径段指定）----
  console: {
    summary: (ws: number | string) => request<ConsoleSummary>(`/console/workspaces/${ws}/summary`),
    agents: (ws: number | string, status = "", keyword = "") =>
      request<AgentListItem[]>(`/console/workspaces/${ws}/agents?status=${encodeURIComponent(status)}&keyword=${encodeURIComponent(keyword)}`),
    agentDetail: (ws: number | string, id: number) => request<ServiceDetail>(`/console/workspaces/${ws}/agents/${id}`),
    agentPods: (ws: number | string, id: number) => request<PodInfo[]>(`/console/workspaces/${ws}/agents/${id}`),
    agentAction: (ws: number | string, id: number, action: "publish" | "unpublish" | "republish" | "register", opt?: object) =>
      request<ServiceRec>(`/console/workspaces/${ws}/agents/${id}/${action}`,
        { method: "POST", body: opt ? JSON.stringify(opt) : undefined }),
    deleteAgent: (ws: number | string, id: number) => request(`/console/workspaces/${ws}/agents/${id}`, { method: "DELETE" }),
    agentVersions: (ws: number | string, id: number) =>
      request<(PackageRec & { current: boolean })[]>(`/console/workspaces/${ws}/agents/${id}/versions`),
    upgradeVersion: (ws: number | string, id: number, pkgId: number) =>
      request<ServiceRec>(`/console/workspaces/${ws}/agents/${id}/versions/${pkgId}/upgrade`, { method: "POST" }),
    templates: (ws: number | string) => request<PackageRec[]>(`/console/workspaces/${ws}/templates`),
    createAgent: (ws: number | string, form: AgentFormPayload) =>
      requestRaw<{ service: ServiceRec; package: PackageRec }>(`/console/workspaces/${ws}/agents`, { method: "POST", body: JSON.stringify(form) }),
    updateAgent: (ws: number | string, id: number, form: AgentFormPayload) =>
      requestRaw<{ service: ServiceRec; package: PackageRec }>(`/console/workspaces/${ws}/agents/${id}`, { method: "PUT", body: JSON.stringify(form) }),
    editForm: (ws: number | string, id: number) => request<EditFormView>(`/console/workspaces/${ws}/agents/${id}/edit-form`),
    previewAgentName: (ws: number | string, name: string) =>
      request<{ agentKey: string; slug: string; k8sName: string; conflict: boolean }>(`/console/workspaces/${ws}/agents/preview-name?name=${encodeURIComponent(name)}`),
    modelConnections: {
      list: (ws: number | string) => request<ModelConnectionView[]>(`/console/workspaces/${ws}/model-connections`),
      create: (ws: number | string, body: object) =>
        requestRaw<ModelConnectionView>(`/console/workspaces/${ws}/model-connections`, { method: "POST", body: JSON.stringify(body) }),
      patch: (ws: number | string, id: number, body: object) =>
        request<ModelConnectionView>(`/console/workspaces/${ws}/model-connections/${id}`, { method: "PATCH", body: JSON.stringify(body) }),
      remove: (ws: number | string, id: number) => request(`/console/workspaces/${ws}/model-connections/${id}`, { method: "DELETE" }),
      test: (ws: number | string, id: number) =>
        request<{ ok: boolean; latencyMs: number; error?: string }>(`/console/workspaces/${ws}/model-connections/${id}/test`, { method: "POST" }),
      deliver: (ws: number | string, id: number, serviceId: number) =>
        requestRaw<unknown>(`/console/workspaces/${ws}/model-connections/${id}/deliver`, { method: "POST", body: JSON.stringify({ serviceId }) }),
      usage: (ws: number | string) => request<ModelConnUsageRow[]>(`/console/workspaces/${ws}/model-connections/usage`),
      agentModels: (ws: number | string, serviceId: number) =>
        request<{ default_model?: string; models: { id: string; name?: string; model_id?: string }[] }>(`/console/workspaces/${ws}/model-connections/models?serviceId=${serviceId}`),
    },
    tools: {
      list: (ws: number | string) => request<McpConnectorView[]>(`/console/workspaces/${ws}/tools`),
      create: (ws: number | string, body: object) =>
        requestRaw<McpConnectorView>(`/console/workspaces/${ws}/tools`, { method: "POST", body: JSON.stringify(body) }),
      patch: (ws: number | string, id: number, body: object) =>
        request<McpConnectorView>(`/console/workspaces/${ws}/tools/${id}`, { method: "PATCH", body: JSON.stringify(body) }),
      verify: (ws: number | string, id: number) =>
        request<{ ok: boolean; toolCount: number; tools: { name: string; description: string }[]; latencyMs: number; error?: string }>(`/console/workspaces/${ws}/tools/${id}/verify`, { method: "POST" }),
      remove: (ws: number | string, id: number) => request(`/console/workspaces/${ws}/tools/${id}`, { method: "DELETE" }),
    },
    integrations: {
      list: (ws: number | string) => request<{ relations: IntegrationView[]; candidates: IntegrationCandidate[] }>(`/console/workspaces/${ws}/integrations`),
      create: (ws: number | string, body: object) =>
        requestRaw<IntegrationView>(`/console/workspaces/${ws}/integrations`, { method: "POST", body: JSON.stringify(body) }),
      patch: (ws: number | string, id: number, body: object) =>
        request<IntegrationView>(`/console/workspaces/${ws}/integrations/${id}`, { method: "PATCH", body: JSON.stringify(body) }),
      remove: (ws: number | string, id: number) => request(`/console/workspaces/${ws}/integrations/${id}`, { method: "DELETE" }),
    },
    apiKeys: {
      list: (ws: number | string, agentId = 0) =>
        request<ApiKeyView[]>(`/console/workspaces/${ws}/api-keys${agentId ? `?agentId=${agentId}` : ""}`),
      create: (ws: number | string, body: { name: string; agentId?: number; expiresInDays?: number }) =>
        requestRaw<{ id: number; name: string; keyPrefix: string; plaintext: string }>(`/console/workspaces/${ws}/api-keys`, { method: "POST", body: JSON.stringify(body) }),
      revoke: (ws: number | string, id: number) => request(`/console/workspaces/${ws}/api-keys/${id}`, { method: "DELETE" }),
    },
    members: {
      list: (ws: number | string) => request<MemberView[]>(`/console/workspaces/${ws}/members`),
      invite: (ws: number | string, userId: number, role: string) =>
        requestRaw<MemberView>(`/console/workspaces/${ws}/members`, { method: "POST", body: JSON.stringify({ userId, role }) }),
      patchRole: (ws: number | string, userId: number, role: string) =>
        request(`/console/workspaces/${ws}/members/${userId}`, { method: "PATCH", body: JSON.stringify({ role }) }),
      remove: (ws: number | string, userId: number) => request(`/console/workspaces/${ws}/members/${userId}`, { method: "DELETE" }),
      invitable: (ws: number | string) => request<UserView[]>(`/console/workspaces/${ws}/invitable-users`),
    },
    // 空间包（import 模式数据源：公共包 + 本空间包，决议 7）
    packages: (ws: number | string, keyword = "") =>
      request<PackageRec[]>(`/console/workspaces/${ws}/packages?keyword=${encodeURIComponent(keyword)}`),
    uploadPackage: (ws: number | string, file: File) => {
      const fd = new FormData();
      fd.append("file", file);
      return fetch(`${BASE}/console/workspaces/${ws}/packages`, { method: "POST", body: fd })
        .then(async (res) => {
          const body = await res.json().catch(() => ({}));
          if (!res.ok) {
            if (res.status === 401 && typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
              window.location.href = "/login";
            }
            throw new Error(body?.message || `HTTP ${res.status}`);
          }
          return body?.data as PackageRec;
        });
    },
    // 镜像列表（console 用户不可调 legacy /images——AUTH_TOKEN 组；console 端点 JWT 可读）
    images: () => request<ImageOption[]>(`/console/images`),
  },

  // ---- 运维侧 admin（M1；JWT-admin 或 AUTH_TOKEN）----
  admin: {
    listWorkspaces: () => request<WorkspaceAdminView[]>(`/admin/workspaces`),
    createWorkspace: (body: { name: string; description?: string; ownerUserId?: number; quota?: object }) =>
      requestRaw<WorkspaceAdminView>(`/admin/workspaces`, { method: "POST", body: JSON.stringify(body) }),
    patchWorkspace: (id: number, body: object) =>
      request<WorkspaceAdminView>(`/admin/workspaces/${id}`, { method: "PATCH", body: JSON.stringify(body) }),
    deleteWorkspace: (id: number) => request(`/admin/workspaces/${id}`, { method: "DELETE" }),
    workspaceAgents: (id: number) => request<(ServiceRec & { pods?: PodInfo[] })[]>(`/admin/workspaces/${id}/agents`),
    bind: (id: number, serviceId: number) =>
      request<ServiceRec>(`/admin/workspaces/${id}/bind`, { method: "POST", body: JSON.stringify({ serviceId }) }),
    addMember: (id: number, userId: number, role: string) =>
      requestRaw(`/admin/workspaces/${id}/members`, { method: "POST", body: JSON.stringify({ userId, role }) }),
    listUsers: () => request<UserView[]>(`/admin/users`),
    createUser: (body: { username: string; password: string; displayName?: string; role?: string }) =>
      requestRaw<UserView>(`/admin/users`, { method: "POST", body: JSON.stringify(body) }),
    patchUser: (id: number, body: { role?: string; status?: string; password?: string }) =>
      request<UserView>(`/admin/users/${id}`, { method: "PATCH", body: JSON.stringify(body) }),
    auditLogs: (page = 1, size = 50) =>
      request<{ items: AuditItem[]; total: number }>(`/admin/audit-logs?page=${page}&size=${size}`),
  },
};
