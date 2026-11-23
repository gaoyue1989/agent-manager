// viking:// URI 目录导航语义（记忆浏览器 issue #433）：
// 后端返回的目录条目 uri 可能自带尾斜杠，父路径推导与条目名展示
// 都须先归一，否则第一次「上一级」会停在原目录、条目名渲染为空。
export const VIKING_MEMORY_ROOT = "viking://~/memories";

export function vikingParentUri(uri: string): string {
  const trimmed = uri.replace(/\/+$/, "");
  if (trimmed === VIKING_MEMORY_ROOT) return VIKING_MEMORY_ROOT;
  return trimmed.split("/").slice(0, -1).join("/") || VIKING_MEMORY_ROOT;
}

export function vikingEntryName(uri: string): string {
  return uri.split("/").filter(Boolean).pop() ?? uri;
}
