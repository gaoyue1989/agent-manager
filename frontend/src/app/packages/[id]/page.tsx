"use client";
// 配置包详情：文件树 + 内容预览（md 渲染/代码高亮/二进制下载）+ 版本历史 + 引用服务
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, FileEntry, PackageDetail, PackageFileContent, PackageRec, ServiceRec } from "@/lib/api";
import Markdown from "@/app/assistant/components/Markdown";
import { FileTreeView } from "@/lib/file-tree";

function extOf(path: string) {
  const i = path.lastIndexOf(".");
  return i < 0 ? "" : path.slice(i + 1).toLowerCase();
}

export default function PackageDetailPage() {
  const params = useParams<{ id: string }>();
  const id = Number(params.id);

  const [detail, setDetail] = useState<PackageDetail | null>(null);
  const [sel, setSel] = useState<string>("AGENTS.md");
  const [file, setFile] = useState<PackageFileContent | null>(null);
  const [fileLoading, setFileLoading] = useState(false);
  const [msg, setMsg] = useState("");

  // 版本历史（同 slug）
  const [versions, setVersions] = useState<PackageRec[]>([]);
  const [versionsErr, setVersionsErr] = useState(false);
  // 引用该包的服务
  const [services, setServices] = useState<ServiceRec[]>([]);
  const [servicesErr, setServicesErr] = useState(false);

  const load = useCallback(async () => {
    try {
      const d = await api.getPackage(id);
      setDetail(d);
      // 版本历史与引用服务并行加载（失败不阻塞主视图，但给出提示）
      api.listPackages("", d.package.slug).then(setVersions).catch(() => setVersionsErr(true));
      api.listServices("", "", id).then(setServices).catch(() => setServicesErr(true));
    } catch (e: any) {
      setMsg(`加载失败: ${e.message}`);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!sel) return;
    let cancelled = false;
    setFileLoading(true);
    api.getPackageFile(id, sel)
      .then((f) => { if (!cancelled) { setFile(f); setMsg(""); } })
      .catch((e: any) => { if (!cancelled) setFile(null); if (!cancelled) setMsg(`读取文件失败: ${e.message}`); })
      .finally(() => { if (!cancelled) setFileLoading(false); });
    return () => { cancelled = true; };
  }, [id, sel]);

  if (msg && !detail) return <p className="text-gray-500">{msg || "加载中…"}</p>;
  if (!detail) return <p className="text-gray-500">加载中…</p>;

  const pkg = detail.package;
  const tree: FileEntry[] = detail.tree || [];
  const ext = extOf(sel);

  const upgradeService = async (svc: ServiceRec) => {
    if (!confirm(`将服务 ${svc.displayName}（${svc.k8sName}）切换到包 ${pkg.slug}@${pkg.version}？将触发滚动重启。`)) return;
    try {
      await api.republish(svc.id, { packageId: pkg.id });
      setMsg(`服务 ${svc.displayName} 已开始切换，滚动完成后回到 running`);
      api.listServices("", "", id).then(setServices).catch(() => setServicesErr(true));
    } catch (e: any) {
      setMsg(`更新失败: ${e.message}`);
    }
  };

  return (
    <div data-testid="package-detail-page" className="space-y-5">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">
          {pkg.slug}<span className="text-gray-400">@{pkg.version}</span>
          {pkg.sourcePackageId ? (
            <span className="ml-3 text-xs text-gray-400">派生自 #{pkg.sourcePackageId}</span>
          ) : null}
        </h1>
        <div className="space-x-2">
          <Link href="/packages" className="text-sm text-blue-600 hover:underline">← 配置包列表</Link>
          <Link href={`/packages/${id}/edit`} data-testid="pkg-edit-btn"
            className="text-sm px-3 py-1.5 border rounded bg-blue-600 text-white hover:bg-blue-700">在线编辑</Link>
          <a href={api.downloadPackage(id)} data-testid="pkg-download-btn"
            className="text-sm px-3 py-1.5 border rounded hover:bg-gray-100 inline-block">下载 zip</a>
          <Link href={`/publish?packageId=${id}`}
            className="text-sm px-3 py-1.5 border rounded hover:bg-gray-100 inline-block">去发布</Link>
        </div>
      </div>
      {msg && <div data-testid="package-msg" className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded p-2">{msg}</div>}

      <section className="bg-white border rounded p-4 text-sm space-y-1">
        <div>名称：{pkg.name}</div>
        <div className="text-gray-600">{pkg.description}</div>
        <div className="text-xs text-gray-400">共 {pkg.fileCount} 个文件 · checksum: {pkg.checksum ? `${pkg.checksum.slice(0, 16)}…` : "—"}</div>
      </section>

        {/* 文件树 + 预览器（.md/.markdown 渲染，与后端 IsTextContent 白名单一致） */}
      <section className="grid grid-cols-[260px_1fr] gap-4">
        <div className="bg-white border rounded p-3 max-h-[480px] overflow-auto">
          <h2 className="font-medium mb-2 text-sm">文件</h2>
          <FileTreeView entries={tree} selected={sel} onSelect={setSel} />
        </div>
        <div className="bg-white border rounded p-3 max-h-[480px] overflow-auto" data-testid="pkg-file-viewer">
          <div className="flex items-center justify-between mb-2">
            <span className="text-sm font-medium break-all">{sel}</span>
            {file?.binary && (
              <a href={api.downloadPackageFile(id, sel)} data-testid="file-download-btn"
                className="text-xs px-2 py-1 border rounded hover:bg-gray-100">二进制/过大文件 · 下载</a>
            )}
          </div>
          {fileLoading ? (
            <p className="text-gray-400 text-sm">读取中…</p>
          ) : !file ? (
            <p className="text-gray-400 text-sm">选择左侧文件预览</p>
          ) : file.binary ? (
            <p className="text-gray-400 text-sm">二进制或超过 512KB 的文件不支持在线预览，请下载查看。</p>
          ) : ext === "md" || ext === "markdown" ? (
            <div data-testid="file-md-view" className="text-sm max-w-3xl">
              <Markdown text={file.content || ""} />
            </div>
          ) : (
            <pre data-testid="file-code-view" className="text-xs bg-gray-900 text-green-200 p-3 rounded overflow-auto max-h-[400px]">{file.content}</pre>
          )}
        </div>
      </section>

      {/* 版本历史 */}
      <section className="bg-white border rounded p-4">
        <h2 className="font-medium mb-2">版本历史（slug: {pkg.slug}）</h2>
        {versionsErr ? (
          <p className="text-xs text-amber-600">版本历史加载失败，请刷新重试。</p>
        ) : versions.length <= 1 ? (
          <p className="text-xs text-gray-400">仅此版本。通过「在线编辑」可生成新版本。</p>
        ) : (
          <ul className="text-sm space-y-1" data-testid="version-list">
            {versions.map((v) => (
              <li key={v.id} className="flex items-center gap-3">
                {v.id === id ? (
                  <span className="font-medium" data-testid={`version-current-${v.id}`}>v{v.version}（当前）</span>
                ) : (
                  <Link href={`/packages/${v.id}`} className="text-blue-600 hover:underline">v{v.version}</Link>
                )}
                <span className="text-xs text-gray-400">{v.refCount > 0 ? `被 ${v.refCount} 个服务引用` : "未被引用"}</span>
                <span className="text-xs text-gray-400">{new Date(v.createdAt).toLocaleString()}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* 引用服务 */}
      <section className="bg-white border rounded p-4">
        <h2 className="font-medium mb-2">引用该包的服务</h2>
        {servicesErr ? (
          <p className="text-xs text-amber-600">服务列表加载失败，请刷新重试。</p>
        ) : services.length === 0 ? (
          <p className="text-xs text-gray-400">暂无服务引用此包。</p>
        ) : (
          <ul className="text-sm space-y-1" data-testid="pkg-services">
            {services.map((s) => (
              <li key={s.id} className="flex items-center justify-between border-b last:border-0 py-1">
                <span>
                  <Link href={`/services/${s.id}`} className="text-blue-600 hover:underline">{s.displayName}</Link>
                  <span className={`ml-2 px-1.5 py-0.5 rounded-full text-xs ${s.status === "running" ? "bg-green-100 text-green-800" : "bg-gray-100"}`}>{s.status}</span>
                </span>
                <button onClick={() => upgradeService(s)} data-testid={`upgrade-svc-${s.id}`}
                  className="text-xs px-2 py-1 border rounded hover:bg-gray-100">重新发布到此版本</button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
