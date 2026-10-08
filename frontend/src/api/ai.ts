import { request } from './request';
import type { AiResponse, PostAiCard, RateLimitStatus } from '@/types/ai';

export async function aiSummarize(content: string): Promise<AiResponse> {
  const res = await request<AiResponse>({ method: 'POST', url: '/ai/summarize', data: { content } });
  return res.data;
}

export async function aiModerate(content: string): Promise<AiResponse> {
  const res = await request<AiResponse>({ method: 'POST', url: '/ai/moderate', data: { content } });
  return res.data;
}

export async function aiRecommendTags(title: string, content: string): Promise<AiResponse> {
  const res = await request<AiResponse>({ method: 'POST', url: '/ai/tags', data: { title, content } });
  return res.data;
}

export async function aiChat(
  messages: { role: string; content: string }[],
  context?: string,
  model?: string,
  abilities?: string[],
): Promise<AiResponse> {
  const res = await request<AiResponse>({ method: 'POST', url: '/ai/chat', data: { messages, context, model, abilities } });
  return res.data;
}

export async function aiRagChat(
  messages: { role: string; content: string }[],
  context?: string,
  model?: string,
  abilities?: string[],
): Promise<AiResponse> {
  const res = await request<AiResponse>({ method: 'POST', url: '/ai/rag-chat', data: { messages, context, model, abilities } });
  return res.data;
}

export async function getPostAiCard(
  postId: number | string,
  options: { passive?: boolean } = {},
): Promise<PostAiCard | null> {
  const url = options.passive ? `/ai/post-card/${postId}?passive=true` : `/ai/post-card/${postId}`;
  const res = await request<PostAiCard | null>({ method: 'GET', url });
  return res.data ?? null;
}

/**
 * 批量读取帖子卡片缓存。仅返回有缓存的项。
 */
export async function getPostAiCardsBatch(postIds: Array<number | string>): Promise<Record<string, PostAiCard>> {
  if (!postIds || postIds.length === 0) return {};
  const ids = postIds.map((id) => Number(id)).filter((id) => Number.isFinite(id));
  if (ids.length === 0) return {};
  const res = await request<Record<string, PostAiCard>>({
    method: 'POST',
    url: '/ai/post-cards/batch',
    data: ids,
  });
  return res.data ?? {};
}

export async function getRateLimitStatus(): Promise<RateLimitStatus> {
  const res = await request<RateLimitStatus>({ method: 'GET', url: '/ai/rate-limit-status' });
  return res.data;
}
