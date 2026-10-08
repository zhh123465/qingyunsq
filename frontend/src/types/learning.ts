export interface TutorialVO {
  id: string;
  title: string;
  slug: string;
  description: string;
  source: string;
  sourceUrl: string;
  category: string;
  icon: string;
  lessonCount: number;
}

export interface TutorialDetailVO extends TutorialVO {
  chapters: LessonBrief[];
}

export interface LessonBrief {
  id: string;
  title: string;
  sourceUrl: string;
  orderIndex: number;
}

export interface LessonVO {
  id: string;
  tutorialId: string;
  title: string;
  content: string;
  sourceUrl: string;
  orderIndex: number;
}
