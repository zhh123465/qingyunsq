export interface UserBrief {
  id: number;
  nickname: string;
  avatarUrl: string;
}

export interface ResourceVO {
  id: number;
  uploaderId: number;
  uploader: UserBrief | null;
  spaceId: number | null;
  fileName: string;
  fileSize: number;
  fileType: string;
  visibility: string;
  college: string | null;
  major: string | null;
  course: string | null;
  semester: string | null;
  tags: string[];
  downloadCount: number;
  collectCount: number;
  version: string | null;
  description: string | null;
  /** 0=隐藏 1=已发布 2=待审核 3=已驳回 */
  status?: number;
  /** 驳回原因（status=3 时有值），仅上传者本人可见 */
  reviewReason?: string | null;
  reviewedAt?: string | null;
  createdAt: string;
}

export interface ResourcePreviewVO {
  id: number;
  fileName: string;
  fileType: string;
  content: string;
}

export interface UploadResourceRequest {
  spaceId?: number;
  visibility?: string;
  college?: string;
  major?: string;
  course?: string;
  semester?: string;
  tags?: string[];
  description?: string;
}
