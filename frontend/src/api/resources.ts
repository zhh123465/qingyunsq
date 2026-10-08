import { request } from './request';
import type { ResourcePreviewVO, ResourceVO } from '@/types/resource';

// 审核流（2026-07-13）：支持上传任意类型文件（含可执行程序），不再限制扩展名；
// 上传后进入待审核队列，管理员审核通过才公开。
export const resourceAccept = '';

export async function uploadResource(
  file: File,
  data: Record<string, unknown>,
): Promise<ResourceVO> {
  const formData = new FormData();
  formData.append('file', file);
  for (const [key, value] of Object.entries(data)) {
    if (value !== undefined && value !== null) {
      if (Array.isArray(value)) {
        value.forEach((v) => formData.append(key, String(v)));
      } else {
        formData.append(key, String(value));
      }
    }
  }
  const res = await request<ResourceVO>({
    method: 'POST',
    url: '/resources',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return res.data;
}

export async function getResources(params: {
  spaceId?: number;
  college?: string;
  major?: string;
  course?: string;
  cursor?: number;
  limit?: number;
}): Promise<ResourceVO[]> {
  const res = await request<ResourceVO[]>({ method: 'GET', url: '/resources', params });
  return res.data;
}

export async function getResourceById(id: number): Promise<ResourceVO> {
  const res = await request<ResourceVO>({ method: 'GET', url: `/resources/${id}` });
  return res.data;
}

/** 我的上传：本人全部状态（含待审核/已驳回）的资源，追踪审核进度用。 */
export async function getMyResources(params?: { cursor?: number; limit?: number }): Promise<ResourceVO[]> {
  const res = await request<ResourceVO[]>({ method: 'GET', url: '/resources/mine', params });
  return res.data;
}

interface SignedUrlResponse {
  token: string;
  expiresAt: number;
}

export interface OfficePreviewMeta {
  kind: 'office';
  previewServiceUrl: string;
  downloadPath: string;
  expiresAt: number;
}

/**
 * 申请短期下载/预览签名 URL。
 *
 * <p>把会话 token 拼到 URL 会让 token 出现在 access log/Referer/浏览器历史里，因此改用：
 * <ol>
 *   <li>前端先调用此接口获取一次性 sig；</li>
 *   <li>用 ?sig= 拼到下载/预览 URL；</li>
 *   <li>服务端 HMAC 校验 + 短期过期。</li>
 * </ol>
 */
async function fetchSignedToken(id: number, action: 'download' | 'preview'): Promise<string> {
  const res = await request<SignedUrlResponse>({
    method: 'GET',
    url: `/resources/${id}/signed-url`,
    params: { action },
  });
  return res.data.token;
}

export async function getDownloadUrl(id: number): Promise<string> {
  const sig = await fetchSignedToken(id, 'download');
  const base = import.meta.env.VITE_API_BASE || '/api/v1';
  return `${base}/resources/${id}/download?sig=${encodeURIComponent(sig)}`;
}

export async function getPreviewUrl(id: number): Promise<string> {
  const sig = await fetchSignedToken(id, 'preview');
  const base = import.meta.env.VITE_API_BASE || '/api/v1';
  return `${base}/resources/${id}/preview?sig=${encodeURIComponent(sig)}`;
}

export async function getOfficePreviewMeta(id: number): Promise<OfficePreviewMeta> {
  const sig = await fetchSignedToken(id, 'preview');
  const res = await request<OfficePreviewMeta>({
    method: 'GET',
    url: `/resources/${id}/preview`,
    params: { sig },
  });
  return res.data;
}

export function buildOfficePreviewUrl(meta: OfficePreviewMeta): string {
  const absoluteDownloadUrl = new URL(meta.downloadPath, window.location.origin).toString();
  const encodedDownloadUrl = window.btoa(absoluteDownloadUrl);
  const separator = meta.previewServiceUrl.includes('?')
    ? meta.previewServiceUrl.endsWith('?') || meta.previewServiceUrl.endsWith('&')
      ? ''
      : '&'
    : '?';
  return `${meta.previewServiceUrl}${separator}url=${encodeURIComponent(encodedDownloadUrl)}`;
}

export async function getOfficePreviewUrl(id: number): Promise<string> {
  return buildOfficePreviewUrl(await getOfficePreviewMeta(id));
}

export async function getResourcePreviewText(id: number): Promise<ResourcePreviewVO> {
  const res = await request<ResourcePreviewVO>({ method: 'GET', url: `/resources/${id}/preview-text` });
  return res.data;
}

export async function deleteResource(id: number): Promise<void> {
  await request({ method: 'DELETE', url: `/resources/${id}` });
}
