import { request } from './request';
import type {
  KnowledgeBaseVO,
  KbDocumentVO,
  QaPairVO,
  ConversationVO,
  MessageVO,
  KnowledgeStatsVO,
  NoteVO,
  PageResult,
  CreateKnowledgeBaseRequest,
  UpdateKnowledgeBaseRequest,
  CreateConversationRequest,
  SendMessageRequest,
  QaPairRequest,
  QaPairUpdateRequest,
  CreateNoteRequest,
  UpdateNoteRequest,
} from '@/types/ai-workspace';

// ======================== Knowledge Bases ========================

export async function listKnowledgeBases(params?: {
  keyword?: string;
  category?: string;
  tab?: string;
  type?: string;
  sort?: string;
  page?: number;
  pageSize?: number;
}): Promise<PageResult<KnowledgeBaseVO>> {
  const res = await request<PageResult<KnowledgeBaseVO>>({
    method: 'GET',
    url: '/ai/knowledge-bases',
    params,
  });
  return res.data;
}

export async function getKnowledgeBase(id: string): Promise<KnowledgeBaseVO> {
  const res = await request<KnowledgeBaseVO>({
    method: 'GET',
    url: `/ai/knowledge-bases/${id}`,
  });
  return res.data;
}

export async function createKnowledgeBase(data: CreateKnowledgeBaseRequest): Promise<KnowledgeBaseVO> {
  const res = await request<KnowledgeBaseVO>({
    method: 'POST',
    url: '/ai/knowledge-bases',
    data,
  });
  return res.data;
}

export async function updateKnowledgeBase(
  id: string,
  data: UpdateKnowledgeBaseRequest,
): Promise<KnowledgeBaseVO> {
  const res = await request<KnowledgeBaseVO>({
    method: 'PATCH',
    url: `/ai/knowledge-bases/${id}`,
    data,
  });
  return res.data;
}

export async function deleteKnowledgeBase(id: string): Promise<void> {
  await request({ method: 'DELETE', url: `/ai/knowledge-bases/${id}` });
}

export async function favoriteKnowledgeBase(id: string): Promise<void> {
  await request({ method: 'POST', url: `/ai/knowledge-bases/${id}/favorite` });
}

export async function unfavoriteKnowledgeBase(id: string): Promise<void> {
  await request({ method: 'DELETE', url: `/ai/knowledge-bases/${id}/favorite` });
}

export async function getKnowledgeStats(): Promise<KnowledgeStatsVO> {
  const res = await request<KnowledgeStatsVO>({
    method: 'GET',
    url: '/ai/knowledge-bases/stats',
  });
  return res.data;
}

// ======================== Documents ========================

export async function uploadDocuments(
  kbId: string,
  files: File[],
  tags?: string,
  parseMode?: string,
): Promise<{ taskId: string; uploaded: number }> {
  const formData = new FormData();
  files.forEach((f) => formData.append('files', f));
  if (tags) formData.append('tags', tags);
  if (parseMode) formData.append('parseMode', parseMode);
  const res = await request<{ taskId: string; uploaded: number }>({
    method: 'POST',
    url: `/ai/knowledge-bases/${kbId}/documents`,
    headers: { 'Content-Type': 'multipart/form-data' },
    data: formData,
  });
  return res.data;
}

export async function listDocuments(kbId: string): Promise<KbDocumentVO[]> {
  const res = await request<KbDocumentVO[]>({
    method: 'GET',
    url: `/ai/knowledge-bases/${kbId}/documents`,
  });
  return res.data;
}

export async function deleteDocument(kbId: string, docId: string): Promise<void> {
  await request({ method: 'DELETE', url: `/ai/knowledge-bases/${kbId}/documents/${docId}` });
}

// ======================== Q&A Pairs ========================

export async function listQaPairs(kbId: string): Promise<QaPairVO[]> {
  const res = await request<QaPairVO[]>({
    method: 'GET',
    url: `/ai/knowledge-bases/${kbId}/qa-pairs`,
  });
  return res.data;
}

export async function createQaPair(kbId: string, data: QaPairRequest): Promise<QaPairVO> {
  const res = await request<QaPairVO>({
    method: 'POST',
    url: `/ai/knowledge-bases/${kbId}/qa-pairs`,
    data,
  });
  return res.data;
}

export async function updateQaPair(
  kbId: string,
  qaId: string,
  data: QaPairUpdateRequest,
): Promise<QaPairVO> {
  const res = await request<QaPairVO>({
    method: 'PATCH',
    url: `/ai/knowledge-bases/${kbId}/qa-pairs/${qaId}`,
    data,
  });
  return res.data;
}

export async function deleteQaPair(kbId: string, qaId: string): Promise<void> {
  await request({ method: 'DELETE', url: `/ai/knowledge-bases/${kbId}/qa-pairs/${qaId}` });
}

// ======================== Conversations ========================

export async function createConversation(
  data: CreateConversationRequest,
): Promise<ConversationVO> {
  const res = await request<ConversationVO>({
    method: 'POST',
    url: '/ai/conversations',
    data,
  });
  return res.data;
}

export async function listConversations(
  page = 1,
  pageSize = 20,
): Promise<PageResult<ConversationVO>> {
  const res = await request<PageResult<ConversationVO>>({
    method: 'GET',
    url: '/ai/conversations',
    params: { page, pageSize },
  });
  return res.data;
}

export async function getConversationMessages(convId: string): Promise<MessageVO[]> {
  const res = await request<MessageVO[]>({
    method: 'GET',
    url: `/ai/conversations/${convId}/messages`,
  });
  return res.data;
}

export async function sendMessage(
  convId: string,
  data: SendMessageRequest,
): Promise<{
  conversation: ConversationVO;
  userMessage: MessageVO;
  assistantMessage: MessageVO;
}> {
  const res = await request<{
    conversation: ConversationVO;
    userMessage: MessageVO;
    assistantMessage: MessageVO;
  }>({
    method: 'POST',
    url: `/ai/conversations/${convId}/messages`,
    data,
  });
  return res.data;
}

// ======================== Notes ========================

export async function listNotes(params?: {
  keyword?: string;
  status?: string;
  page?: number;
  pageSize?: number;
}): Promise<PageResult<NoteVO>> {
  const res = await request<PageResult<NoteVO>>({
    method: 'GET',
    url: '/ai/notes',
    params,
  });
  return res.data;
}

export async function getNote(id: string): Promise<NoteVO> {
  const res = await request<NoteVO>({ method: 'GET', url: `/ai/notes/${id}` });
  return res.data;
}

export async function createNote(data: CreateNoteRequest): Promise<NoteVO> {
  const res = await request<NoteVO>({ method: 'POST', url: '/ai/notes', data });
  return res.data;
}

export async function updateNote(id: string, data: UpdateNoteRequest): Promise<NoteVO> {
  const res = await request<NoteVO>({ method: 'PUT', url: `/ai/notes/${id}`, data });
  return res.data;
}

export async function deleteNote(id: string): Promise<void> {
  await request({ method: 'DELETE', url: `/ai/notes/${id}` });
}

// ---- Public notes ----

export async function listPublicNotes(params?: {
  keyword?: string;
  tag?: string;
  mine?: boolean;
  page?: number;
  pageSize?: number;
}): Promise<PageResult<NoteVO>> {
  const res = await request<PageResult<NoteVO>>({
    method: 'GET',
    url: '/ai/notes/public',
    params,
  });
  return res.data;
}

export async function getNoteTags(): Promise<string[]> {
  const res = await request<string[]>({
    method: 'GET',
    url: '/ai/notes/tags',
  });
  return res.data;
}

export async function getPublicNote(id: string): Promise<NoteVO> {
  const res = await request<NoteVO>({ method: 'GET', url: `/ai/notes/public/${id}` });
  return res.data;
}

export async function addMessageFeedback(
  msgId: string,
  helpful: boolean,
  reason?: string,
): Promise<void> {
  await request({
    method: 'POST',
    url: `/ai/messages/${msgId}/feedback`,
    data: { helpful, reason },
  });
}
