import type { ResourceVO } from '@/types/resource';

export type ResourcePreviewKind =
  | 'pdf'
  | 'image'
  | 'text'
  | 'office'
  | 'video'
  | 'audio'
  | 'unsupported';

const IMAGE_TYPES = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp']);
const VIDEO_TYPES = new Set(['mp4', 'webm', 'mov', 'avi']);
const AUDIO_TYPES = new Set(['mp3', 'wav', 'm4a', 'ogg']);
const OFFICE_TEXT_TYPES = new Set(['docx', 'pptx', 'xlsx']);
const OFFICE_EMBED_TYPES = new Set(['doc', 'ppt', 'xls']);
const TEXT_TYPES = new Set([
  'md',
  'markdown',
  'txt',
  'log',
  'csv',
  'json',
  'xml',
  'yml',
  'yaml',
  'sql',
  'java',
  'py',
  'js',
  'jsx',
  'ts',
  'tsx',
  'vue',
  'css',
  'scss',
  'html',
  'htm',
]);

export function normalizeResourceType(fileType: string | null | undefined) {
  return (fileType || '').toLowerCase();
}

export function getResourcePreviewKind(resource: Pick<ResourceVO, 'fileType'>): ResourcePreviewKind {
  const fileType = normalizeResourceType(resource.fileType);
  if (fileType === 'pdf') return 'pdf';
  if (IMAGE_TYPES.has(fileType)) return 'image';
  if (VIDEO_TYPES.has(fileType)) return 'video';
  if (AUDIO_TYPES.has(fileType)) return 'audio';
  if (TEXT_TYPES.has(fileType) || OFFICE_TEXT_TYPES.has(fileType)) return 'text';
  if (OFFICE_EMBED_TYPES.has(fileType)) return 'office';
  return 'unsupported';
}

export function isTextLikePreview(resource: Pick<ResourceVO, 'fileType'>) {
  return getResourcePreviewKind(resource) === 'text';
}

export function isDirectUrlPreview(resource: Pick<ResourceVO, 'fileType'>) {
  return ['pdf', 'image', 'video', 'audio', 'office'].includes(getResourcePreviewKind(resource));
}
