"use client";
// OAF 包在线编辑：左树右编辑器 + 变更集跟踪 + 生成新版本（copy-on-write，不写原包）
// 编辑结果一次性以 upserts/deletes 提交 POST /packages/:id/versions，成功后跳新包详情
import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import CodeMirror from "@uiw/react-codemirror";
import { yaml as cmYaml } from "@codemirror/lang-yaml";
import { json as cmJson } from "@codemirror/lang-json";
import { markdown as cmMarkdown } from "@codemirror/lang-markdown";
import { api, FileEntry, PackageDetail } from "@/lib/api";
import { FileTreeView } from "@/lib/file-tree";

const DELETABLE_ROOT = "AGENTS.md"; // 根级 AGENTS.md 禁止删除（后端同样校验）

// 编辑态文件：原内容 + 当前内容
type EditFile = { path: string; original: string; current: string; isNew: boolean; binary?: boolean };

function langExt(path: string) {
  const ext = path.slice(path.lastIndexOf(".") + 1).toLowerCase();
  if (ext === "yaml" || ext === "yml") return [cmYaml()];
  if (ext === "json") return [cmJson()];
  if (ext === "md" || ext === "markdown") return [cmMarkdown()];
  return [];
}

export default function PackageEditPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const id = Number(params.id);

  const [detail, setDetail] = useState<PackageDetail | null>(null);
  const [files, setFiles] = useState<Record<string, EditFile>>({});
  const [active, setActive] = useState<string>("AGENTS.md");
  const [msg, setMsg] = useState("");
  const [saving, setSaving] = useState(false);
  const [confirming, setConfirming] = useState(false);

  const load = useCallback(async () => {
    try {
      const d = await api.getPackage(id);
      setDetail(d);
      // 拉取全部文本文件内容构造编辑态（二进制文件不进入编辑器，标记只读下载）
      const entries: Record<string, EditFile> = {};
      const walk = async (list: FileEntry[]) => {
        for (const e of list) {
          if (e.isDir) { await walk(e.children || []); continue; }
          try {
            const f = await api.getPackageFile(id, e.path);
            entries[e.path] = {
              path: e.path,
              original: f.binary ? "" : (f.content || ""),
              current: f.binary ? "" : (f.content || ""),
              isNew: false,
              binary: f.binary,
            };
          } catch {
            entries[e.path] = { path: e.path, original: "", current: "", isNew: false, binary: true };
          }
        }
      };
      await walk(d.tree || []);
      if (entries[DELETABLE_ROOT] === undefined && d.agentsMd) {
        entries[DELETABLE_ROOT] = { path: DELETABLE_ROOT, original: d.agentsMd, current: d.agentsMd, isNew: false };
      }
      setFiles(entries);
    } catch (e: any) {
      setMsg(`加载失败: ${e.message}`);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const setCur = (path: string, content: string) =>
    setFiles((m) => ({ ...m, [path]: { ...m[path], current: content } }));

  const addFile = () => {
    const name = prompt("新文件路径（相对包根，如 skills/xxx/SKILL.md）：");
    if (!name) return;
    const path = name.replace(/^\/+|\/+$/g, "");
    if (!path || path.includes("..") || path.includes("\\")) { setMsg("非法路径"); return; }
    if (files[path]) { setActive(path); return; }
    setFiles((m) => ({ ...m, [path]: { path, original: "", current: "", isNew: true } }));
    setActive(path);
  };

  const removeFile = (path: string) => {
    if (path === DELETABLE_ROOT) { setMsg("根级 AGENTS.md 不可删除"); return; }
    if (!confirm(`标记删除文件 ${path}？（保存时才真正生成新版本）`)) return;
    setFiles((m) => {
      const next = { ...m };
      const f = next[path];
      if (f && f.isNew) {
        delete next[path]; // 新建未保存文件直接移除
      } else if (f) {
        next[path] = { ...f, current: "" }; // 置空即删除标记
      }
      return next;
    });
    if (active === path) setActive(DELETABLE_ROOT);
  };

  // 变更集
  const { upserts, deletes } = useMemo(() => {
    const ups: { path: string; content: string }[] = [];
    const dels: string[] = [];
    Object.values(files).forEach((f) => {
      if (f.binary) return;
      if (f.isNew && f.current !== "") ups.push({ path: f.path, content: f.current });
      else if (!f.isNew && f.current === "") dels.push(f.path);
      else if (!f.isNew && f.current !== f.original) ups.push({ path: f.path, content: f.current });
    });
    return { upserts: ups, deletes: dels };
  }, [files]);

  const dirty = upserts.length > 0 || deletes.length > 0;

  const submit = async () => {
    setConfirming(false);
    setSaving(true);
    setMsg("");
    try {
      const res = await api.createPackageVersion(id, { upserts, deletes });
      router.push(`/packages/${res.package.id}`);
    } catch (e: any) {
      setMsg(`生成新版本失败: ${e.message}`);
      setSaving(false);
    }
  };

  const tree: FileEntry[] = useMemo(() => {
    if (!detail) return [];
    // 在原树基础上叠加新建文件节点
    const clone: FileEntry[] = JSON.parse(JSON.stringify(detail.tree || []));
    Object.values(files).filter((f) => f.isNew).forEach((f) => {
      const parts = f.path.split("/");
      let level = clone;
      parts.forEach((p, i) => {
        const found = level.find((x) => x.name === p);
        if (found) {
          if (i < parts.length - 1) level = found.children || (found.children = []);
        } else {
          const node: FileEntry = { name: p, path: parts.slice(0, i + 1).join("/"), isDir: i < parts.length - 1 };
          level.push(node);
          if (i < parts.length - 1) level = node.children!;
        }
      });
    });
    return clone;
  }, [detail, files]);

  const activeFile = files[active];

  if (msg && !detail) return <p className="text-gray-500">{msg}</p>;
  if (!detail) return <p className="text-gray-500">加载中…</p>;

  return (
    <div data-testid="package-edit-page" className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">
          在线编辑：{detail.package.slug}<span className="text-gray-400">@{detail.package.version}</span>
        </h1>
        <div className="space-x-2">
          <Link href={`/packages/${id}`} className="text-sm text-blue-600 hover:underline">← 返回预览</Link>
          <button onClick={addFile} data-testid="edit-add-file"
            className="text-sm px-3 py-1.5 border rounded hover:bg-gray-100">+ 新建文件</button>
          <button onClick={() => setConfirming(true)} disabled={!dirty || saving} data-testid="edit-save-btn"
            className="text-sm px-4 py-1.5 rounded bg-green-600 text-white hover:bg-green-700 disabled:opacity-40">
            {saving ? "生成中…" : `生成新版本（${upserts.length} 改 / ${deletes.length} 删）`}
          </button>
        </div>
      </div>
      {msg && <div data-testid="edit-msg" className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded p-2 whitespace-pre-wrap">{msg}</div>}
      <p className="text-xs text-gray-400">
        编辑不会修改当前包：保存时将基于本包生成新版本包（copy-on-write），发布服务时可切换到新版本。
      </p>

      <section className="grid grid-cols-[240px_1fr] gap-4">
        <div className="bg-white border rounded p-3 max-h-[560px] overflow-auto">
          <FileTreeView entries={tree} selected={active}
            onSelect={(p) => setActive(p)} />
          {/* 删除当前文件 */}
          {activeFile && !activeFile.isNew && active !== DELETABLE_ROOT && (
            <button onClick={() => removeFile(active)} data-testid="edit-del-file"
              className="mt-2 text-xs px-2 py-1 border rounded text-red-600 hover:bg-red-50">删除当前文件</button>
          )}
        </div>
        <div className="bg-white border rounded p-2" data-testid="edit-editor-wrap">
          {!activeFile ? (
            <p className="text-gray-400 text-sm p-3">选择或新建文件</p>
          ) : activeFile.binary ? (
            <p className="text-gray-400 text-sm p-3">二进制文件不支持在线编辑。
              <a href={api.downloadPackageFile(id, active)} className="ml-1 text-blue-600 underline">下载</a></p>
          ) : (
            <CodeMirror
              value={activeFile.current}
              height="520px"
              extensions={langExt(active)}
              onChange={(v) => setCur(active, v)}
              basicSetup={{ foldGutter: true, highlightActiveLine: true }}
            />
          )}
        </div>
      </section>

      {/* 变更确认弹窗 */}
      {confirming && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center" data-testid="edit-confirm-modal">
          <div className="bg-white rounded-lg p-5 w-[520px] max-h-[80vh] overflow-auto">
            <h2 className="font-medium mb-3">确认变更（生成新版本包）</h2>
            <ul className="text-sm space-y-1 mb-4" data-testid="edit-change-list">
              {upserts.map((u) => (
                <li key={u.path} className={files[u.path]?.isNew ? "text-green-700" : "text-blue-700"}>
                  {files[u.path]?.isNew ? "新增" : "修改"} {u.path}
                </li>
              ))}
              {deletes.map((d) => (
                <li key={d} className="text-red-600">删除 {d}</li>
              ))}
            </ul>
            <div className="flex justify-end space-x-2">
              <button onClick={() => setConfirming(false)} className="text-sm px-3 py-1.5 border rounded">取消</button>
              <button onClick={submit} data-testid="edit-confirm-btn"
                className="text-sm px-4 py-1.5 rounded bg-green-600 text-white hover:bg-green-700">确认生成新版本</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
