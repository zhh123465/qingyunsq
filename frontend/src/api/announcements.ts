import { request } from './request';
import type { PageResult } from '@/types/admin';
import type { AnnouncementVO } from '@/types/announcement';

/** 当前生效公告（顶栏 banner 用，最多 10 条）。 */
export async function getActiveAnnouncements(): Promise<AnnouncementVO[]> {
  const res = await request<AnnouncementVO[]>({ method: 'GET', url: '/announcements/active' });
  return res.data;
}

/** 独立公告页分页列表（仅 published 且未过期）。 */
export async function getAnnouncements(params: {
  page?: number;
  size?: number;
}): Promise<PageResult<AnnouncementVO>> {
  const res = await request<PageResult<AnnouncementVO>>({
    method: 'GET', url: '/announcements', params,
  });
  return res.data;
}

/** 公告详情（含 markdown 正文）。 */
export async function getAnnouncement(id: number | string): Promise<AnnouncementVO> {
  const res = await request<AnnouncementVO>({ method: 'GET', url: `/announcements/${id}` });
  return res.data;
}
