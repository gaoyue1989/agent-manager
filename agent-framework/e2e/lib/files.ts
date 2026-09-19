/**
 * F 组文件用例的现场构造与下载断言（不落二进制夹具进库）。
 */

/** 1x1 透明 PNG（固定字节，无随机性） */
export const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
);

export function pngBytes(targetKb: number): Buffer {
  const head = PNG_1PX;
  const pad = Buffer.alloc(targetKb * 1024, 0x61);
  return Buffer.concat([head, pad]);
}

export function textBytes(content: string): Buffer {
  return Buffer.from(content, 'utf8');
}

export interface UploadResult { file_id: string; file_name: string; mime_type: string; size: number }

export async function upload(
  base: string,
  bytes: Buffer,
  fileName: string,
  mime: string,
  opts: { userId?: string; sessionId?: string } = {},
): Promise<{ status: number; body: UploadResult | Record<string, unknown> }> {
  const fd = new FormData();
  fd.append('file', new Blob([new Uint8Array(bytes)], { type: mime }), fileName);
  if (opts.userId) fd.append('userId', opts.userId);
  if (opts.sessionId) fd.append('sessionId', opts.sessionId);
  const res = await fetch(`${base}/files/upload`, { method: 'POST', body: fd });
  let body: Record<string, unknown>;
  try { body = await res.json(); } catch { body = {}; }
  return { status: res.status, body: body as UploadResult | Record<string, unknown> };
}

export interface DownloadCheck {
  status: number;
  contentType?: string;
  contentLength?: number;
  disposition?: string;
  bytes?: Buffer;
}

export async function download(base: string, fileIdOrUrl: string, inline = false): Promise<DownloadCheck> {
  const url = fileIdOrUrl.startsWith('http')
    ? fileIdOrUrl
    : `${base}/files/${fileIdOrUrl}${inline ? '?inline=1' : ''}`;
  const res = await fetch(url);
  if (!res.ok) return { status: res.status };
  const bytes = Buffer.from(await res.arrayBuffer());
  return {
    status: res.status,
    contentType: res.headers.get('content-type') ?? undefined,
    contentLength: Number(res.headers.get('content-length') ?? bytes.length),
    disposition: res.headers.get('content-disposition') ?? undefined,
    bytes,
  };
}
