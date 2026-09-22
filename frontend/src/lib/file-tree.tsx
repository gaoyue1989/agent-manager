"use client";
// 包内文件树 + 查看器（服务详情/编辑页共用）
// 文件树来自 GET /packages/:id 的 tree；onSelect 回调向父级传所选路径
import { FileEntry } from "@/lib/api";
export function FileTreeView({ entries, selected, onSelect }: {
  entries: FileEntry[];
  selected?: string;
  onSelect?: (path: string) => void;
}) {
  const render = (list: FileEntry[], depth: number) =>
    list.map((e) => (
      <div key={e.path}>
        {e.isDir ? (
          <div className="py-0.5 font-medium text-gray-700" style={{ paddingLeft: depth * 14 }}>
            📁 {e.name}
          </div>
        ) : (
          <button
            onClick={() => onSelect?.(e.path)}
            data-testid={`tree-file-${e.path.replace(/\//g, "-")}`}
            className={`block w-full text-left py-0.5 truncate rounded hover:bg-blue-50 ${selected === e.path ? "bg-blue-100 text-blue-800" : "text-gray-600"}`}
            style={{ paddingLeft: depth * 14 + 6 }}
            title={e.path}
          >
            📄 {e.name}
          </button>
        )}
        {e.children?.length ? render(e.children, depth + 1) : null}
      </div>
    ));
  return <div data-testid="pkg-tree" className="text-sm">{render(entries, 0)}</div>;
}
