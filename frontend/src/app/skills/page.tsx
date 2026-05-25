'use client';
import { useState, useEffect, useRef } from 'react';
import { api } from '@/lib/api';

export default function SkillsLibrary() {
  const [skills, setSkills] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploadMsg, setUploadMsg] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);

  const load = async () => {
    try {
      const data = await api.skills.shared.list();
      setSkills(data.skills || []);
    } catch (e: any) {
      setSkills([]);
    }
  };

  useEffect(() => { load(); }, []);

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setLoading(true);
    setUploadMsg('');
    try {
      await api.skills.shared.upload(file);
      setUploadMsg('上传成功');
      load();
    } catch (err: any) {
      setUploadMsg('上传失败: ' + (err.message || err));
    } finally {
      setLoading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  const handleDelete = async (name: string) => {
    if (!confirm(`删除技能 "${name}"？`)) return;
    try {
      await api.skills.shared.delete(name);
      load();
    } catch (e: any) {
      alert('删除失败: ' + (e.message || e));
    }
  };

  return (
    <div className="max-w-2xl mx-auto">
      <h1 className="text-2xl font-bold mb-6">技能库管理</h1>

      <div className="bg-white rounded-lg shadow p-6 mb-6">
        <div className="flex items-center gap-3 mb-4">
          <input
            ref={fileRef}
            type="file"
            accept=".zip"
            className="hidden"
            onChange={handleUpload}
          />
          <button
            onClick={() => fileRef.current?.click()}
            disabled={loading}
            className="px-4 py-2 bg-green-600 text-white rounded-lg text-sm hover:bg-green-700 disabled:opacity-50"
          >
            {loading ? '上传中...' : '上传技能 ZIP'}
          </button>
          <span className="text-xs text-gray-400">支持 .zip，需包含 SKILL.md</span>
        </div>
        {uploadMsg && (
          <div className={`text-sm mb-3 ${uploadMsg.includes('失败') ? 'text-red-600' : 'text-green-600'}`}>
            {uploadMsg}
          </div>
        )}

        {skills.length === 0 && !loading && (
          <p className="text-sm text-gray-400">暂无共享技能，上传 ZIP 包开始</p>
        )}

        {skills.length > 0 && (
          <div className="space-y-2">
            {skills.map((sk, i) => (
              <div key={i} className="flex items-center justify-between border rounded-lg px-4 py-3">
                <div>
                  <div className="font-medium text-sm">{sk.name}</div>
                  {sk.description && <div className="text-xs text-gray-500 mt-0.5">{sk.description}</div>}
                  {sk.allowed_tools && Array.isArray(sk.allowed_tools) && sk.allowed_tools.length > 0 && (
                    <div className="flex gap-1 mt-1">
                      {sk.allowed_tools.map((t: string, j: number) => (
                        <span key={j} className="text-xs bg-gray-100 px-1.5 py-0.5 rounded">{t}</span>
                      ))}
                    </div>
                  )}
                </div>
                <button
                  onClick={() => handleDelete(sk.name)}
                  className="text-red-500 text-sm hover:underline"
                >
                  删除
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
