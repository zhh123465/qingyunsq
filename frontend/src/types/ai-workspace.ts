// AI Workspace — TypeScript types matching backend kbView/docMap/qaMap/conversationView/messageMap DTOs.

export interface KnowledgeBaseVO {
  id: string;
  name: string;
  description: string;
  category: string;
  type: string;
  visibility: string;
  documentCount: number;
  qaPairCount: number;
  vectorCount: number;
  storageBytes: number;
  owner: string;
  isFavorite: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface KbDocumentVO {
  id: string;
  fileName: string;
  fileSize: number;
  tags: string[];
  parseMode: string;
  status: string;
  createdAt: string;
}

export interface QaPairVO {
  id: string;
  question: string;
  answer: string;
  tags: string[];
  createdAt: string;
}

export interface ConversationVO {
  id: string;
  title: string;
  model: string;
  knowledgeBaseIds: string[];
  messageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface MessageVO {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  model: string;
  knowledgeBaseIds: string[];
  attachments: string[];
  feedback: Record<string, unknown> | null;
  createdAt: string;
}

export interface KnowledgeStatsVO {
  knowledgeBaseCount: number;
  documentCount: number;
  vectorCount: number;
  storageUsedBytes: number;
  storageLimitBytes: number;
}

export interface PageResult<T> {
  items: T[];
  total: number;
}

// ---- Request bodies ----

export interface CreateKnowledgeBaseRequest {
  name: string;
  description?: string;
  category?: string;
  type?: string;
  visibility?: string;
}

export interface UpdateKnowledgeBaseRequest {
  name?: string;
  description?: string;
  category?: string;
  type?: string;
  visibility?: string;
}

// ---- Note types ----

export interface NoteVO {
  id: string;
  title: string;
  content: string;
  contentType: string;
  tags: string[];
  // pending=待审核 rejected=已驳回（审核流 2026-07-16）；hidden=管理员隐藏
  status: 'draft' | 'pending' | 'published' | 'rejected' | 'hidden';
  reviewReason?: string;
  reviewedBy?: number | null;
  reviewedAt?: string | null;
  viewCount: number;
  ownerId: number;
  ownerName: string;
  ownerAvatar: string;
  knowledgeBaseId: string;
  createdAt: string;
  updatedAt: string;
  // 外部同步笔记的出处元数据（空串表示本站原生笔记；非空展示 ↗ 角标 + 出处 banner）
  sourceUrl?: string;
  sourceName?: string;
  sourceAuthor?: string;
}

export interface CreateNoteRequest {
  title?: string;
  content?: string;
  contentType?: string;
  tags?: string[];
  status?: string;
  knowledgeBaseId?: string;
}

export interface UpdateNoteRequest {
  title?: string;
  content?: string;
  contentType?: string;
  tags?: string[];
  status?: string;
  knowledgeBaseId?: string;
}

// ---- Conversation types ----

export interface CreateConversationRequest {
  title?: string;
  model?: string;
  knowledgeBaseIds?: string[];
}

export interface SendMessageRequest {
  content: string;
  model?: string;
  knowledgeBaseIds?: string[];
  attachedContext?: string;
}

export interface QaPairRequest {
  question: string;
  answer: string;
  tags?: string[];
}

export interface QaPairUpdateRequest {
  question?: string;
  answer?: string;
  tags?: string[];
}
