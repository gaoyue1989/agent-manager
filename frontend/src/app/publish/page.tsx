"use client";
// 发布向导：上传/选择 OAF 包 → 选镜像 → 编辑环境变量 → 提交发布
import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ImageOption, PackageRec } from "@/lib/api";

type EnvRow = { key: string; value: string };

export default function PublishPage() {
  const router = useRouter();
  const fileRef = useRef<HTMLInputElement>(null);
  const [packages, setPackages] = useState<PackageRec[]>([]);
  const [images, setImages] = useState<ImageOption[]>([]);
  const [uploading, setUploading] = useState(false);
  const [selectedPkg, setSelectedPkg] = useState<number | null>(null);
  const [pkgSummary, setPkgSummary] = useState("");
  const [image, setImage] = useState("");
  const [name, setName] = useState("");
  const [replicas, setReplicas] = useState(1);
  const [envRows, setEnvRows] = useState<EnvRow[]>([
    { key: "LLM_API_KEY", value: "" },
    { key: "LLM_MODEL_ID", value: "" },
    { key: "LLM_BASE_URL", value: "" },
    { key: "CHECKPOINT_JDBC_URL", value: "jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" },
    { key: "CHECKPOINT_USERNAME", value: "oaf" },
    { key: "CHECKPOINT_PASSWORD", value: "" },
  ]);
  const [submitting, setSubmitting] = useState(false);
  const [msg, setMsg] = useState("");

  const load = useCallback(async () => {
    try {
      setPackages(await api.listPackages());
      const imgs = await api.listImages();
      setImages(imgs);
      if (imgs.length > 0) setImage((cur) => cur || imgs[0].Image);
    } catch (e: any) {
      setMsg(`加载失败: ${e.message}`);
    }
  }, []);
  useEffect(() => { load(); }, [load]);

  const onUpload = async (file: File) => {
    setUploading(true);
    setMsg("");
    try {
      const rec = await api.uploadPackage(file);
      await load();
      setSelectedPkg(rec.id);
      setPkgSummary(`${rec.slug}@${rec.version}（${rec.fileCount} 个文件）`);
      setName(rec.name);
      if ((rec as any).warningsJson && (rec as any).warningsJson !== "[]") {
        setMsg(`上传成功，但有校验警告：${(rec as any).warningsJson}`);
      } else {
        setMsg("上传成功");
      }
    } catch (e: any) {
      setMsg(`上传失败: ${e.message}`);
    } finally {
      setUploading(false);
    }
  };

  const choosePkg = async (id: number) => {
    setSelectedPkg(id);
    try {
      const d = await api.getPackage(id);
      setPkgSummary(`${d.package.slug}@${d.package.version}（${d.package.fileCount} 个文件）`);
      setName(d.package.name);
    } catch (e: any) {
      setMsg(`读取包失败: ${e.message}`);
    }
  };

  const submit = async () => {
    if (!selectedPkg) { setMsg("请先选择或上传 OAF 配置包"); return; }
    const env: Record<string, string> = {};
    for (const r of envRows) if (r.key.trim()) env[r.key.trim()] = r.value;
    setSubmitting(true);
    setMsg("");
    try {
      const svc = await api.publish({ packageId: selectedPkg!, name: name || undefined, image, env, replicas });
      router.push(`/services/${svc.id}`);
    } catch (e: any) {
      setMsg(`发布失败: ${e.message}`);
      setSubmitting(false);
    }
  };

  return (
    <div data-testid="publish-page" className="space-y-6">
      <h1 className="text-xl font-semibold">发布新服务</h1>
      {msg && <div data-testid="publish-msg" className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded p-2">{msg}</div>}

      {/* 步骤 1：配置包 */}
      <section className="bg-white border rounded p-4">
        <h2 className="font-medium mb-2">1. OAF 配置包</h2>
        <input ref={fileRef} type="file" accept=".zip" className="hidden" data-testid="zip-input"
          onChange={(e) => e.target.files?.[0] && onUpload(e.target.files[0])} />
        <button onClick={() => fileRef.current?.click()} disabled={uploading} data-testid="upload-btn"
          className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white text-sm rounded px-4 py-2">
          {uploading ? "上传中…" : "上传 zip 包"}
        </button>
        <span className="mx-3 text-gray-400 text-xs">或选择已有包</span>
        <select value={selectedPkg ?? ""} onChange={(e) => choosePkg(Number(e.target.value))} data-testid="pkg-select"
          className="border rounded p-1.5 text-sm min-w-[240px]">
          <option value="">— 选择配置包 —</option>
          {packages.map((p) => (
            <option key={p.id} value={p.id}>{p.slug}@{p.version} ({p.fileCount} files)</option>
          ))}
        </select>
        {pkgSummary && <p className="mt-2 text-xs text-gray-500" data-testid="pkg-summary">{pkgSummary}</p>}
      </section>

      {/* 步骤 2：镜像与实例 */}
      <section className="bg-white border rounded p-4">
        <h2 className="font-medium mb-2">2. 运行镜像</h2>
        <select value={image} onChange={(e) => setImage(e.target.value)} data-testid="image-select"
          className="border rounded p-1.5 text-sm w-full max-w-md">
          {images.map((im) => (
            <option key={im.Image} value={im.Image}>{im.Label ? `${im.Label} (${im.Image})` : im.Image}</option>
          ))}
        </select>
        <label className="block mt-3 text-sm">服务名（可选，缺省取包 slug）
          <input value={name} onChange={(e) => setName(e.target.value)} data-testid="name-input"
            className="border rounded p-1.5 text-sm block mt-1 w-full max-w-md" placeholder="留空自动生成" />
        </label>
        <label className="block mt-3 text-sm">副本数
          <input type="number" min={1} value={replicas} onChange={(e) => setReplicas(Math.max(1, Number(e.target.value)))}
            data-testid="replicas-input" className="border rounded p-1.5 text-sm block mt-1 w-24" />
        </label>
      </section>

      {/* 步骤 3：环境变量 */}
      <section className="bg-white border rounded p-4">
        <div className="flex items-center justify-between mb-2">
          <h2 className="font-medium">3. 环境变量</h2>
          <button onClick={() => setEnvRows([...envRows, { key: "", value: "" }])} data-testid="add-env-row"
            className="text-xs px-2 py-1 border rounded hover:bg-gray-100">+ 添加变量</button>
        </div>
        <p className="text-xs text-gray-400 mb-2">任意增删改；AGENT_CONFIG_DIR / SERVER_HOST / SERVER_PORT 为平台保留键。</p>
        <table className="w-full text-sm">
          <tbody data-testid="env-table">
            {envRows.map((r, i) => (
              <tr key={i}>
                <td className="pr-2 pb-2 w-2/5">
                  <input value={r.key} onChange={(e) => setEnvRows(envRows.map((x, j) => j === i ? { ...x, key: e.target.value } : x))}
                    placeholder="KEY" data-testid={`env-key-${i}`} className="border rounded p-1.5 w-full" />
                </td>
                <td className="pr-2 pb-2">
                  <input value={r.value} onChange={(e) => setEnvRows(envRows.map((x, j) => j === i ? { ...x, value: e.target.value } : x))}
                    placeholder="VALUE" data-testid={`env-value-${i}`} className="border rounded p-1.5 w-full" />
                </td>
                <td className="pb-2">
                  <button onClick={() => setEnvRows(envRows.filter((_, j) => j !== i))} data-testid={`env-del-${i}`}
                    className="text-red-500 hover:text-red-700 px-2">×</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <button onClick={submit} disabled={submitting || !selectedPkg} data-testid="submit-publish"
        className="bg-green-600 hover:bg-green-700 disabled:opacity-50 text-white rounded px-6 py-2 text-sm">
        {submitting ? "发布中…" : "确认发布"}
      </button>
    </div>
  );
}
