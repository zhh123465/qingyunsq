export interface AiRequest {
  content?: string;
  title?: string;
  messages?: ChatMessage[];
  context?: string;
  model?: string;
  abilities?: string[];
}

export interface ChatMessage {
  role: string;
  content: string;
}

export interface AiCitation {
  type: string;
  id: number;
  title: string;
  snippet: string;
  url: string;
}

export interface AiResponse {
  summary: string;
  riskLevel: number;
  riskReason: string;
  tags: string[];
  reply: string;
  citations?: AiCitation[];
}

export interface PostAiCard {
  tldr: string | null;
  audience: string | null;
  valueType: string | null;
  readMinutes: number | null;
  commentConsensus: string | null;
  commentDisputes: string | null;
  hotCommentId: number | null;
  hotCommentExcerpt: string | null;
  highlights: string[] | null;
}

// ---- AI Model config ----

export interface AiModelOption {
  id: string;
  name: string;
  provider: string;
  tier: 'normal' | 'pro';
}

export const AI_MODELS: AiModelOption[] = [
  { id: 'mimo-v2.5', name: 'MiMo 2.5', provider: 'MiMo', tier: 'normal' },
  { id: 'deepseek-v4-flash', name: 'DeepSeek V4 Flash', provider: 'DeepSeek', tier: 'normal' },
  { id: 'deepseek-v4-pro', name: 'DeepSeek V4 Pro', provider: 'DeepSeek', tier: 'pro' },
  { id: 'mimo-v2.5-pro', name: 'MiMo 2.5 Pro', provider: 'MiMo', tier: 'pro' },
];

export const DEFAULT_MODEL = 'mimo-v2.5';

export interface RateLimitStatus {
  normal: { limit: number; used: number; remaining: number };
  pro: { limit: number; used: number; remaining: number };
}
