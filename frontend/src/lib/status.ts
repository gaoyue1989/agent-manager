// 平台状态徽标映射（M0 §10.2：收敛 page.tsx 与 services/[id] 两处重复定义）。
export const STATUS_STYLE: Record<string, string> = {
  running: "bg-green-100 text-green-800",
  deploying: "bg-blue-100 text-blue-800",
  created: "bg-blue-100 text-blue-800",
  register_failed: "bg-yellow-100 text-yellow-800",
  deploy_failed: "bg-red-100 text-red-800",
  stopped: "bg-gray-200 text-gray-700",
  error: "bg-red-100 text-red-800",
};

// 资产/连接类状态（模型连接 / MCP 连接器：verified | unverified | failed）
export const CONN_STATUS_STYLE: Record<string, string> = {
  verified: "bg-green-100 text-green-800",
  synced: "bg-green-100 text-green-800",
  active: "bg-green-100 text-green-800",
  run: "bg-green-100 text-green-800",
  unverified: "bg-gray-100 text-gray-600",
  pending: "bg-yellow-100 text-yellow-800",
  failed: "bg-red-100 text-red-800",
  removed: "bg-gray-100 text-gray-400",
  revoked: "bg-gray-200 text-gray-500",
  expired: "bg-gray-200 text-gray-500",
  disabled: "bg-gray-200 text-gray-500",
};

// MCP 三态权限徽标（allow / ask / deny）
export const PERM_STYLE: Record<string, string> = {
  allow: "bg-green-100 text-green-800",
  ask: "bg-yellow-100 text-yellow-800",
  deny: "bg-red-100 text-red-800",
};

export function badgeClass(status: string): string {
  return STATUS_STYLE[status] || CONN_STATUS_STYLE[status] || "bg-gray-100 text-gray-600";
}
