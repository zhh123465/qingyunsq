/** 前台公告 VO（对齐后端 AnnouncementVO）。 */
export interface AnnouncementVO {
  id: number;
  title: string;
  summary?: string;
  /** Markdown 源码；列表接口通常不带此字段 */
  content?: string;
  level: 'info' | 'warning' | 'critical';
  pinned: 0 | 1;
  status: 'draft' | 'published' | 'archived';
  publishTime?: string;
  expireTime?: string;
  publisherId: number;
  publisherName?: string;
  createdAt: string;
  updatedAt: string;
}
