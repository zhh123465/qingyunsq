import { request } from './request';
import type { TutorialVO, TutorialDetailVO, LessonVO } from '@/types/learning';

export async function listTutorials(): Promise<TutorialVO[]> {
  const res = await request<TutorialVO[]>({ method: 'GET', url: '/learning/tutorials' });
  return res.data;
}

export async function getTutorial(id: string): Promise<TutorialDetailVO> {
  const res = await request<TutorialDetailVO>({ method: 'GET', url: `/learning/tutorials/${id}` });
  return res.data;
}

export async function getLesson(id: string): Promise<LessonVO> {
  const res = await request<LessonVO>({ method: 'GET', url: `/learning/lessons/${id}` });
  return res.data;
}

// ---- Admin：调整学习页展示顺序（管理员在学习页"编辑排序"提交时调用） ----

export async function reorderTutorials(ids: string[]): Promise<{ updated: number; total: number }> {
  const res = await request<{ updated: number; total: number }>({
    method: 'PUT',
    url: '/admin/learning/tutorials/reorder',
    data: { ids },
  });
  return res.data;
}

export async function reorderPublicNotes(ids: string[]): Promise<{ updated: number; total: number }> {
  const res = await request<{ updated: number; total: number }>({
    method: 'PUT',
    url: '/admin/learning/notes/reorder',
    data: { ids },
  });
  return res.data;
}
