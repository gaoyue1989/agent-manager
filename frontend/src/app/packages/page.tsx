"use client";
// 配置包列表：OAF 包管理与入口（预览/编辑/发布/删除）
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, PackageRec } from "@/lib/api";

function fmtSize(n?: number) {
  if (!n && n !== 0) return "—";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

export default function PackagesPage() {
  const router = useRouter();
  const [packages, setPackages] = useState<PackageRec[]>([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState("");

  const load = useCallback(async () => {
    try {
      setPackages(await api.listPackages());
      setMsg("");
    } catch (e: any) {
      setMsg(`加载失败: ${e.message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const del = async (p: PackageRec) => {
    if (!confirm(`确认删除包 ${p.slug}@${p.version}？删除后不可恢复。`)) return;
    try {
      await api.deletePackage(p.id);
      setMsg("已删除");
      await load();
    } catch (e: any) {
      setMsg(`删除失败: ${e.message}`);
    }
  };

  return (
    <div data-testid="packages-page">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-xl font-semibold">配置包</h1>
        <Link href="/publish" className="bg-blue-600 hover:bg-blue-700 text-white text-sm rounded px-4 py-2">
          上传新包（发布页）
        </Link>
      </div>
      {msg && <div className="mb-3 text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded p-2">{msg}</div>}
      {loading ? (
        <p className="text-gray-500">加载中…</p>
      ) : packages.length === 0 ? (
        <p className="text-gray-500" data-testid="packages-empty">暂无配置包，请在发布页上传 zip 包。</p>
      ) : (
        <table className="w-full bg-white border border-gray-200 rounded text-sm" data-testid="packages-table">
          <thead>
            <tr className="text-left text-gray-500 border-b">
              <th className="p-2">包</th><th className="p-2">描述</th><th className="p-2">文件</th>
              <th className="p-2">大小</th><th className="p-2">被引用</th><th className="p-2">创建时间</th><th className="p-2">操作</th>
            </tr>
          </thead>
          <tbody>
            {packages.map((p) => (
              <tr key={p.id} className="border-b hover:bg-gray-50" data-testid={`pkg-row-${p.id}`}>
                <td className="p-2 font-medium">
                  <Link href={`/packages/${p.id}`} className="text-blue-600 hover:underline">{p.slug}</Link>
                  <span className="ml-1 text-gray-400">@{p.version}</span>
                  {p.sourcePackageId ? (
                    <span className="ml-2 text-xs text-gray-400">派生自 #{p.sourcePackageId}</span>
                  ) : null}
                </td>
                <td className="p-2 text-xs text-gray-600 max-w-[240px] truncate" title={p.description}>{p.description}</td>
                <td className="p-2">{p.fileCount}</td>
                <td className="p-2">{fmtSize(p.totalSize)}</td>
                <td className="p-2">
                  {p.refCount > 0
                    ? <span className="text-green-700">{p.refCount} 个服务</span>
                    : <span className="text-gray-400">未引用</span>}
                </td>
                <td className="p-2 text-xs">{new Date(p.createdAt).toLocaleString()}</td>
                <td className="p-2 space-x-1 whitespace-nowrap">
                  <Link href={`/packages/${p.id}`} data-testid={`pkg-view-${p.id}`}
                    className="text-xs px-2 py-1 border rounded hover:bg-gray-100 inline-block">预览</Link>
                  <Link href={`/packages/${p.id}/edit`} data-testid={`pkg-edit-${p.id}`}
                    className="text-xs px-2 py-1 border rounded hover:bg-gray-100 inline-block">编辑</Link>
                  <button onClick={() => router.push(`/publish?packageId=${p.id}`)}
                    className="text-xs px-2 py-1 border rounded hover:bg-gray-100">发布</button>
                  <a href={api.downloadPackage(p.id)}
                    className="text-xs px-2 py-1 border rounded hover:bg-gray-100 inline-block">下载</a>
                  <button disabled={p.refCount > 0} onClick={() => del(p)} title={p.refCount > 0 ? "被服务引用，禁止删除" : ""}
                    className="text-xs px-2 py-1 border rounded text-red-600 hover:bg-red-50 disabled:opacity-40 disabled:cursor-not-allowed">删除</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
