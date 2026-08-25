// 平台 REST API 客户端（对齐 backend /api/v1 契约）
const BASE = process.env.NEXT_PUBLIC_API_URL || "/api/v1";

async function request<T = any>(path: string, init?: RequestInit): Promise<T> {
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

export interface PackageRec { id: number; name: string; slug: string; version: string; description: string; refCount: number; fileCount: number; createdAt: string; }
export interface PackageDetail { package: PackageRec; tree: any[]; agentsMd: string; }
export interface ServiceRec {
  id: number; k8sName: string; displayName: string; packageId: number; image: string;
  envJson: string; replicas: number; status: string; endpoint: string; clusterUrl: string;
  registeredName?: string; registeredVersion?: string; agentCardJson?: string;
  registeredAt?: string | null; createdAt: string;
}
export interface PodInfo { name: string; phase: string; ready: boolean; restarts: number; }
export interface ServiceEvent { id: number; fromStatus: string; toStatus: string; reason: string; createdAt: string; }
export interface ServiceDetail extends ServiceRec { pods?: PodInfo[]; events: ServiceEvent[]; }
export interface ImageOption { Image: string; Label: string }

export const api = {
  // 包
  listPackages: (keyword = "") => request<PackageRec[]>(`/packages?keyword=${encodeURIComponent(keyword)}`),
  getPackage: (id: number) => request<PackageDetail>(`/packages/${id}`),
  deletePackage: (id: number) => request(`/packages/${id}`, { method: "DELETE" }),
  uploadPackage: (file: File) => upload<PackageRec>(`/packages`, file),
  // 镜像
  listImages: () => request<ImageOption[]>(`/images`),
  // 服务
  listServices: (status = "", keyword = "") =>
    request<(ServiceRec & { pods?: PodInfo[] })[]>(`/services?status=${status}&keyword=${encodeURIComponent(keyword)}`),
  getService: (id: number) => request<ServiceDetail>(`/services/${id}`),
  publish: (body: { packageId: number; name?: string; image?: string; env?: Record<string, string>; replicas?: number }) =>
    request<ServiceRec>(`/services`, { method: "POST", body: JSON.stringify(body) }),
  updateEnv: (id: number, env: Record<string, string>) =>
    request<ServiceRec>(`/services/${id}/env`, { method: "PATCH", body: JSON.stringify({ env }) }),
  republish: (id: number, opt: { packageId?: number } = {}) =>
    request<ServiceRec>(`/services/${id}/republish`, { method: "POST", body: JSON.stringify(opt) }),
  startAgain: (id: number) => request<ServiceRec>(`/services/${id}/publish`, { method: "POST" }),
  unpublish: (id: number) => request<ServiceRec>(`/services/${id}/unpublish`, { method: "POST" }),
  reregister: (id: number) => request<ServiceRec>(`/services/${id}/register`, { method: "POST" }),
  deleteService: (id: number) => request(`/services/${id}`, { method: "DELETE" }),
};
