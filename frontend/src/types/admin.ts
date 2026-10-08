/** 管理端分页响应统一格式（对齐后端 PageResult<T>）。 */
export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
  pages: number;
}

/** 批量彻底删除的返回结构（对齐后端 purgeBatchForAdmin）。 */
export interface BatchPurgeResult {
  success: number;
  failed: { id: number | string; reason: string }[];
  counts: Record<string, number>;
}

export interface DailyStat {
  date: string;      // ISO YYYY-MM-DD
  newPosts: number;
  newUsers: number;
  newComments: number;
}

export interface CategoryStat {
  category: string;
  count: number;
}

export interface AuditLogVO {
  id: number;
  operatorId: number;
  operatorName: string;
  action: string;
  targetType: string;
  targetId: number;
  detail: string;
  ipAddress: string;
  createdAt: string;
}

export interface DashboardVO {
  tenantId?: number;
  tenantCode?: string;
  userCount: number;
  postCount: number;
  spaceCount: number;
  commentCount: number;
  todayPostCount: number;
  todayUserCount: number;
  weeklyTrend?: DailyStat[];
  spaceCategoryDist?: CategoryStat[];
  recentAuditLogs?: AuditLogVO[];
}

// 管理端表格独有 VO（不与前台复用），只列出前端需要的字段
export interface AdminResourceVO {
  id: number;
  uploaderId: number;
  uploader?: { id: number; nickname: string; avatarUrl?: string };
  spaceId?: number;
  fileName: string;
  fileSize: number;
  fileType: string;
  visibility: string;
  college?: string;
  major?: string;
  course?: string;
  tags?: string[];
  downloadCount: number;
  collectCount: number;
  description?: string;
  /** 0=隐藏 1=已发布 2=待审核 3=已驳回 */
  status: number;
  reviewReason?: string | null;
  reviewedAt?: string | null;
  createdAt: string;
}

export interface AdminNoteItem {
  id: string;
  title: string;
  contentType?: string;
  tags?: string[];
  status: string;      // draft / pending / published / rejected / hidden
  reviewReason?: string;
  reviewedBy?: number | null;
  reviewedAt?: string | null;
  viewCount: number;
  ownerId: number;
  ownerName?: string;
  ownerAvatar?: string;
  knowledgeBaseId?: string;
  createdAt: string;
  updatedAt: string;
}

export interface AdminCommentVO {
  id: number;
  postId: number;
  postTitle?: string;
  parentId?: number;
  replyToId?: number;
  authorId: number;
  author?: { id: number; nickname: string; avatarUrl?: string };
  content: string;
  likeCount: number;
  status: number;
  createdAt: string;
}

export interface AdminAnnouncementItem {
  id: number;
  title: string;
  summary?: string;
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
