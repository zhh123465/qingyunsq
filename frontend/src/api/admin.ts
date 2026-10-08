import { request } from './request';
import type {
  DashboardVO,
  AuditLogVO,
  AdminResourceVO,
  AdminNoteItem,
  AdminCommentVO,
  AdminAnnouncementItem,
  PageResult,
  BatchPurgeResult,
} from '@/types/admin';
import type { UserBrief } from '@/types/post';
import type { PostVO } from '@/types/post';
import type { SpaceVO } from '@/types/space';
import type { CheckinChallengeVO } from '@/types/checkin';

// Dashboard
export async function getDashboard(): Promise<DashboardVO> {
  const res = await request<DashboardVO>({ method: 'GET', url: '/admin/dashboard' });
  return res.data;
}

// ===== Users =====
export async function getAdminUsers(params: {
  keyword?: string;
  role?: string;
  status?: number;
  cursor?: number;
  limit?: number;
}): Promise<AdminUserItem[]> {
  const res = await request<AdminUserItem[]>({ method: 'GET', url: '/admin/users', params });
  return res.data;
}

export async function banUser(id: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/users/${id}/ban` });
}

export async function unbanUser(id: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/users/${id}/unban` });
}

export async function changeUserRole(id: number, role: string): Promise<void> {
  await request({ method: 'PUT', url: `/admin/users/${id}/role`, data: { role } });
}

export async function batchSetUserStatus(ids: number[], status: number): Promise<void> {
  await request({ method: 'PUT', url: '/admin/users/batch-status', data: { ids, status } });
}

// ===== Posts =====
export async function getAdminPosts(params: {
  keyword?: string;
  status?: number;
  scope?: string;
  trash?: boolean;
  page?: number;
  size?: number;
}): Promise<PageResult<PostVO>> {
  const res = await request<PageResult<PostVO>>({ method: 'GET', url: '/admin/posts', params });
  return res.data;
}

export async function togglePin(id: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/posts/${id}/pin` });
}

export async function toggleEssence(id: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/posts/${id}/essence` });
}

export async function setPostStatus(id: number, status: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/posts/${id}/status`, data: { status } });
}

export async function deleteAdminPost(id: number): Promise<void> {
  await request({ method: 'DELETE', url: `/admin/posts/${id}` });
}

export async function restorePost(id: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/posts/${id}/restore` });
}

export async function purgePost(id: number): Promise<Record<string, number>> {
  const res = await request<Record<string, number>>({
    method: 'DELETE',
    url: `/admin/posts/${id}/purge`,
  });
  return res.data;
}

export async function batchSetPostStatus(ids: number[], status: number): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/posts/batch-status', data: { ids, status } });
  return res.data;
}
export async function batchDeletePost(ids: number[]): Promise<number> {
  const res = await request<number>({ method: 'DELETE', url: '/admin/posts/batch', data: { ids } });
  return res.data;
}
export async function batchRestorePost(ids: number[]): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/posts/batch-restore', data: { ids } });
  return res.data;
}
export async function batchPurgePost(ids: number[]): Promise<BatchPurgeResult> {
  const res = await request<BatchPurgeResult>({ method: 'DELETE', url: '/admin/posts/batch-purge', data: { ids } });
  return res.data;
}

// ===== Spaces =====
export async function getAdminSpaces(params: {
  keyword?: string;
  category?: string;
  status?: number;
  trash?: boolean;
  page?: number;
  size?: number;
}): Promise<PageResult<SpaceVO>> {
  const res = await request<PageResult<SpaceVO>>({ method: 'GET', url: '/admin/spaces', params });
  return res.data;
}

export async function setSpaceStatus(id: number, status: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/spaces/${id}/status`, data: { status } });
}

export async function adminDeleteSpace(id: number): Promise<void> {
  await request({ method: 'DELETE', url: `/admin/spaces/${id}` });
}

export async function restoreSpace(id: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/spaces/${id}/restore` });
}

export async function purgeSpace(id: number): Promise<Record<string, number>> {
  const res = await request<Record<string, number>>({
    method: 'DELETE',
    url: `/admin/spaces/${id}/purge`,
  });
  return res.data;
}

export async function batchSetSpaceStatus(ids: number[], status: number): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/spaces/batch-status', data: { ids, status } });
  return res.data;
}
export async function batchDismissSpace(ids: number[]): Promise<number> {
  const res = await request<number>({ method: 'DELETE', url: '/admin/spaces/batch', data: { ids } });
  return res.data;
}
export async function batchRestoreSpace(ids: number[]): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/spaces/batch-restore', data: { ids } });
  return res.data;
}
export async function batchPurgeSpace(ids: number[]): Promise<BatchPurgeResult> {
  const res = await request<BatchPurgeResult>({ method: 'DELETE', url: '/admin/spaces/batch-purge', data: { ids } });
  return res.data;
}

// ===== Resources =====
export async function getAdminResources(params: {
  keyword?: string;
  visibility?: string;
  status?: number;
  trash?: boolean;
  page?: number;
  size?: number;
}): Promise<PageResult<AdminResourceVO>> {
  const res = await request<PageResult<AdminResourceVO>>({
    method: 'GET', url: '/admin/resources', params,
  });
  return res.data;
}

export async function setResourceStatus(id: number, status: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/resources/${id}/status`, data: { status } });
}

// ===== 资源审核（2026-07-13）=====
export async function approveResource(id: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/resources/${id}/approve` });
}

export async function rejectResource(id: number, reason: string): Promise<void> {
  await request({ method: 'PUT', url: `/admin/resources/${id}/reject`, data: { reason } });
}

export async function batchApproveResource(ids: number[]): Promise<{ success: number; failed: number[] }> {
  const res = await request<{ success: number; failed: number[] }>({
    method: 'PUT', url: '/admin/resources/batch-approve', data: { ids },
  });
  return res.data;
}

export async function deleteAdminResource(id: number): Promise<void> {
  await request({ method: 'DELETE', url: `/admin/resources/${id}` });
}

export async function restoreResource(id: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/resources/${id}/restore` });
}

export async function purgeResource(id: number): Promise<Record<string, number>> {
  const res = await request<Record<string, number>>({
    method: 'DELETE',
    url: `/admin/resources/${id}/purge`,
  });
  return res.data;
}

export async function batchSetResourceStatus(ids: number[], status: number): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/resources/batch-status', data: { ids, status } });
  return res.data;
}
export async function batchDeleteResource(ids: number[]): Promise<number> {
  const res = await request<number>({ method: 'DELETE', url: '/admin/resources/batch', data: { ids } });
  return res.data;
}
export async function batchRestoreResource(ids: number[]): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/resources/batch-restore', data: { ids } });
  return res.data;
}
export async function batchPurgeResource(ids: number[]): Promise<BatchPurgeResult> {
  const res = await request<BatchPurgeResult>({ method: 'DELETE', url: '/admin/resources/batch-purge', data: { ids } });
  return res.data;
}

// ===== Notes =====
export async function getAdminNotes(params: {
  keyword?: string;
  status?: string;
  ownerId?: number;
  trash?: boolean;
  page?: number;
  size?: number;
}): Promise<PageResult<AdminNoteItem>> {
  const res = await request<PageResult<AdminNoteItem>>({ method: 'GET', url: '/admin/notes', params });
  return res.data;
}

export async function setNoteStatus(id: string, status: string): Promise<void> {
  await request({ method: 'PUT', url: `/admin/notes/${id}/status`, data: { status } });
}

// 笔记审核流（2026-07-16），照资源审核同款
export async function approveNote(id: string): Promise<void> {
  await request({ method: 'PUT', url: `/admin/notes/${id}/approve` });
}

export async function rejectNote(id: string, reason: string): Promise<void> {
  await request({ method: 'PUT', url: `/admin/notes/${id}/reject`, data: { reason } });
}

export async function batchApproveNotes(ids: string[]): Promise<{ success: number; failed: string[] }> {
  const res = await request<{ success: number; failed: string[] }>({
    method: 'PUT', url: '/admin/notes/batch-approve', data: { ids },
  });
  return res.data;
}

export async function deleteAdminNote(id: string): Promise<void> {
  await request({ method: 'DELETE', url: `/admin/notes/${id}` });
}

export async function restoreNote(id: string): Promise<void> {
  await request({ method: 'PUT', url: `/admin/notes/${id}/restore` });
}

export async function purgeNote(id: string): Promise<Record<string, number>> {
  const res = await request<Record<string, number>>({
    method: 'DELETE',
    url: `/admin/notes/${id}/purge`,
  });
  return res.data;
}

export async function batchSetNoteStatus(ids: string[], status: string): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/notes/batch-status', data: { ids, status } });
  return res.data;
}
export async function batchDeleteNote(ids: string[]): Promise<number> {
  const res = await request<number>({ method: 'DELETE', url: '/admin/notes/batch', data: { ids } });
  return res.data;
}
export async function batchRestoreNote(ids: string[]): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/notes/batch-restore', data: { ids } });
  return res.data;
}
export async function batchPurgeNote(ids: string[]): Promise<BatchPurgeResult> {
  const res = await request<BatchPurgeResult>({ method: 'DELETE', url: '/admin/notes/batch-purge', data: { ids } });
  return res.data;
}

// 外部笔记同步（从 VitePress / cnblogs / 单页抓取，转 markdown 入库）
export interface SyncExternalNotesRequest {
  source: 'vitepress' | 'cnblogs' | 'single';
  rootUrl: string;
  recursive?: boolean;
  sourceName: string;
  sourceAuthor: string;
  tags?: string[];
  ownerId: number;
}

export interface SyncExternalNotesResult {
  synced: number;
  failed: { url: string; reason: string }[];
  noteIds: string[];
}

export async function syncExternalNotes(body: SyncExternalNotesRequest): Promise<SyncExternalNotesResult> {
  const res = await request<SyncExternalNotesResult>({
    method: 'POST', url: '/admin/notes/sync-external', data: body,
  });
  return res.data;
}

// ===== Checkin =====
export async function getAdminCheckins(params: {
  keyword?: string;
  status?: number;
  trash?: boolean;
  page?: number;
  size?: number;
}): Promise<PageResult<CheckinChallengeVO>> {
  const res = await request<PageResult<CheckinChallengeVO>>({
    method: 'GET',
    url: '/admin/checkin/challenges',
    params,
  });
  return res.data;
}

export async function setCheckinStatus(id: number, status: number): Promise<void> {
  await request({
    method: 'PUT',
    url: `/admin/checkin/challenges/${id}/status`,
    data: { status },
  });
}

export async function deleteAdminCheckin(id: number): Promise<void> {
  await request({ method: 'DELETE', url: `/admin/checkin/challenges/${id}` });
}

export async function restoreCheckin(id: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/checkin/challenges/${id}/restore` });
}

export async function purgeCheckin(id: number): Promise<Record<string, number>> {
  const res = await request<Record<string, number>>({
    method: 'DELETE',
    url: `/admin/checkin/challenges/${id}/purge`,
  });
  return res.data;
}

export async function batchSetCheckinStatus(ids: number[], status: number): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/checkin/challenges/batch-status', data: { ids, status } });
  return res.data;
}
export async function batchDeleteCheckin(ids: number[]): Promise<number> {
  const res = await request<number>({ method: 'DELETE', url: '/admin/checkin/challenges/batch', data: { ids } });
  return res.data;
}
export async function batchRestoreCheckin(ids: number[]): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/checkin/challenges/batch-restore', data: { ids } });
  return res.data;
}
export async function batchPurgeCheckin(ids: number[]): Promise<BatchPurgeResult> {
  const res = await request<BatchPurgeResult>({ method: 'DELETE', url: '/admin/checkin/challenges/batch-purge', data: { ids } });
  return res.data;
}

// ===== Comments =====
export async function getAdminComments(params: {
  keyword?: string;
  postId?: number;
  authorId?: number;
  status?: number;
  trash?: boolean;
  page?: number;
  size?: number;
}): Promise<PageResult<AdminCommentVO>> {
  const res = await request<PageResult<AdminCommentVO>>({ method: 'GET', url: '/admin/comments', params });
  return res.data;
}

export async function setCommentStatus(id: number, status: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/comments/${id}/status`, data: { status } });
}

export async function deleteAdminComment(id: number): Promise<void> {
  await request({ method: 'DELETE', url: `/admin/comments/${id}` });
}

export async function restoreComment(id: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/comments/${id}/restore` });
}

export async function purgeComment(id: number): Promise<Record<string, number>> {
  const res = await request<Record<string, number>>({
    method: 'DELETE',
    url: `/admin/comments/${id}/purge`,
  });
  return res.data;
}

export async function batchSetCommentStatus(ids: number[], status: number): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/comments/batch-status', data: { ids, status } });
  return res.data;
}
export async function batchDeleteComment(ids: number[]): Promise<number> {
  const res = await request<number>({ method: 'DELETE', url: '/admin/comments/batch', data: { ids } });
  return res.data;
}
export async function batchRestoreComment(ids: number[]): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: '/admin/comments/batch-restore', data: { ids } });
  return res.data;
}
export async function batchPurgeComment(ids: number[]): Promise<BatchPurgeResult> {
  const res = await request<BatchPurgeResult>({ method: 'DELETE', url: '/admin/comments/batch-purge', data: { ids } });
  return res.data;
}

// ===== Announcements =====
export interface AnnouncementUpsertBody {
  title: string;
  summary?: string;
  content: string;
  level?: 'info' | 'warning' | 'critical';
  pinned?: 0 | 1;
  status?: 'draft' | 'published' | 'archived';
  publishTime?: string | null;
  expireTime?: string | null;
}

export async function getAdminAnnouncements(params: {
  keyword?: string;
  status?: string;
  level?: string;
  trash?: boolean;
  page?: number;
  size?: number;
}): Promise<PageResult<AdminAnnouncementItem>> {
  const res = await request<PageResult<AdminAnnouncementItem>>({
    method: 'GET', url: '/admin/announcements', params,
  });
  return res.data;
}

export async function createAnnouncement(body: AnnouncementUpsertBody): Promise<AdminAnnouncementItem> {
  const res = await request<AdminAnnouncementItem>({
    method: 'POST', url: '/admin/announcements', data: body,
  });
  return res.data;
}

export async function updateAnnouncement(id: number, body: Partial<AnnouncementUpsertBody>): Promise<AdminAnnouncementItem> {
  const res = await request<AdminAnnouncementItem>({
    method: 'PUT', url: `/admin/announcements/${id}`, data: body,
  });
  return res.data;
}

export async function setAnnouncementStatus(id: number, status: string): Promise<void> {
  await request({ method: 'PUT', url: `/admin/announcements/${id}/status`, data: { status } });
}

export async function toggleAnnouncementPin(id: number): Promise<number> {
  const res = await request<number>({ method: 'PUT', url: `/admin/announcements/${id}/pin` });
  return res.data;
}

export async function deleteAdminAnnouncement(id: number): Promise<void> {
  await request({ method: 'DELETE', url: `/admin/announcements/${id}` });
}

export async function restoreAnnouncement(id: number): Promise<void> {
  await request({ method: 'PUT', url: `/admin/announcements/${id}/restore` });
}

export async function purgeAnnouncement(id: number): Promise<Record<string, number>> {
  const res = await request<Record<string, number>>({
    method: 'DELETE', url: `/admin/announcements/${id}/purge`,
  });
  return res.data;
}

export async function batchSetAnnouncementStatus(ids: number[], status: string): Promise<number> {
  const res = await request<number>({
    method: 'PUT', url: '/admin/announcements/batch-status', data: { ids, status },
  });
  return res.data;
}

export async function batchDeleteAnnouncement(ids: number[]): Promise<number> {
  const res = await request<number>({
    method: 'DELETE', url: '/admin/announcements/batch', data: { ids },
  });
  return res.data;
}

export async function batchRestoreAnnouncement(ids: number[]): Promise<number> {
  const res = await request<number>({
    method: 'PUT', url: '/admin/announcements/batch-restore', data: { ids },
  });
  return res.data;
}

export async function batchPurgeAnnouncement(ids: number[]): Promise<BatchPurgeResult> {
  const res = await request<BatchPurgeResult>({
    method: 'DELETE', url: '/admin/announcements/batch-purge', data: { ids },
  });
  return res.data;
}

// ===== Audit Logs =====
export async function getAuditLogs(params: {
  operatorId?: number;
  action?: string;
  cursor?: number;
  limit?: number;
}): Promise<AuditLogVO[]> {
  const res = await request<AuditLogVO[]>({ method: 'GET', url: '/admin/audit-logs', params });
  return res.data;
}

export interface AdminUserItem extends UserBrief {
  studentNo: string;
  email: string;
  role: string;
  status: number;
  lastLoginAt: string;
  createdAt: string;
}
