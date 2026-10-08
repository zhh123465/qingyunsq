<script setup lang="ts">
import { computed, nextTick, ref, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NDynamicTags, NModal, NSpin, NIcon, useMessage } from 'naive-ui';
import {
  SparklesOutline,
  LibraryOutline,
  CheckmarkCircleOutline,
  NotificationsOutline,
  CopyOutline,
  RefreshOutline,
  SearchOutline,
  MenuOutline,
  ShareSocialOutline,
  ThumbsUpOutline,
  ChatboxOutline,
  ArrowBackOutline,
  PeopleOutline,
  SettingsOutline,
  PersonOutline,
  DocumentTextOutline
} from '@vicons/ionicons5';
import { getSpaceById, getSpaceMembers, getSpacePosts, joinSpace, leaveSpace, updateSpace } from '@/api/spaces';
import { createPost, getPostById, toggleReaction } from '@/api/posts';
import { createComment, getComments, toggleCommentReaction } from '@/api/comments';
import { getResources, uploadResource } from '@/api/resources';
import { createChallenge, getChallenges } from '@/api/checkin';
import { getMyProfile, getUserById, updateProfile, uploadProfileAsset } from '@/api/users';
import { follow, getFollowCounts, getUserFollowers, getUserFollowing, isFollowing, unfollow } from '@/api/follows';
import { getUserAchievements } from '@/api/achievement';
import { getNotifications, getUnreadCount, markRead } from '@/api/notifications';
import { acceptAnswer, getQaInfo } from '@/api/qa';
import { validateNickname } from '@/utils/authValidation';
import { copyTextToClipboard } from '@/utils/clipboard';
import BackToTopButton from '@/components/BackToTopButton.vue';
import PostAiCardLine from '@/components/PostAiCardLine.vue';
import { getPostAiCardsBatch, getPostAiCard } from '@/api/ai';
import type { PostAiCard } from '@/types/ai';
import type { SpaceVO, SpaceMemberVO } from '@/types/space';
import type { CommentVO, PostVO } from '@/types/post';
import type { ResourceVO } from '@/types/resource';
import type { UserVO } from '@/types/user';
import type { CheckinChallengeVO } from '@/types/checkin';
import type { AchievementVO } from '@/types/achievement';
import type { NotificationVO } from '@/types/notification';
import type { QaQuestionVO } from '@/types/qa';
import auroraBg from '@/assets/images/aurora_bg.png';

const route = useRoute();
const router = useRouter();
const message = useMessage();

const space = ref<SpaceVO | null>(null);
const members = ref<SpaceMemberVO[]>([]);
const posts = ref<PostVO[]>([]);
const aiCards = ref<Record<string, PostAiCard>>({});

async function loadSpaceAiCards(postIds: number[]) {
  if (!postIds || postIds.length === 0) return;
  try {
    const batch = await getPostAiCardsBatch(postIds);
    aiCards.value = { ...aiCards.value, ...batch };
    for (const idStr of Object.keys(batch)) {
      aiRequestedSet.value.add(Number(idStr));
    }
  } catch {
    // 静默：列表卡片是增强信息，失败时不显示即可
  }
}

// ===== AI 卡片懒加载请求队列（与 Square.vue 同样的模式）=====
const aiVisibleSet = ref(new Set<number>());
const aiRequestedSet = ref(new Set<number>());
const aiRequestingSet = ref(new Set<number>());
const aiQueueProcessing = ref(false);

function handleAiCardVisible(postId: number) {
  aiVisibleSet.value.add(postId);
  void processAiCardQueue();
}

function handleAiCardHidden(postId: number) {
  aiVisibleSet.value.delete(postId);
}

async function processAiCardQueue() {
  if (aiQueueProcessing.value) return;
  const next = [...aiVisibleSet.value].find(
    (id) => !aiCards.value[String(id)] && !aiRequestedSet.value.has(id) && !aiRequestingSet.value.has(id),
  );
  if (next === undefined) return;

  aiQueueProcessing.value = true;
  aiRequestingSet.value.add(next);
  try {
    const card = await getPostAiCard(next);
    if (card) {
      aiCards.value = { ...aiCards.value, [String(next)]: card };
    }
  } catch {
    // 失败不重试
  } finally {
    aiRequestingSet.value.delete(next);
    aiRequestedSet.value.add(next);
    aiQueueProcessing.value = false;
    if (aiVisibleSet.value.size > 0) {
      setTimeout(() => void processAiCardQueue(), 200);
    }
  }
}
const spaceResources = ref<ResourceVO[]>([]);
const checkinChallenges = ref<CheckinChallengeVO[]>([]);
const notifications = ref<NotificationVO[]>([]);
const unreadCount = ref(0);
const myProfile = ref<UserVO | null>(null);
const profileFollowCounts = ref({ followers: 0, following: 0 });
const profileAchievements = ref<AchievementVO[]>([]);
const loading = ref(true);
const searchKeyword = ref('');
const searchResultsRef = ref<HTMLElement | null>(null);
const contentScrollRef = ref<HTMLElement | null>(null);
const memberKeyword = ref('');
const postSort = ref<'latest' | 'hot' | 'essence'>('latest');
const composeVisible = ref(false);
const composeTitle = ref('');
const composeContent = ref('');
const composeType = ref<'NORMAL' | 'QA'>('NORMAL');
const composeTopicInput = ref('');
const composeTopics = ref<string[]>([]);
const composeSubmitting = ref(false);
const paperSheetRef = ref<HTMLElement | null>(null);
const paperRipple = ref<{ x: number; y: number; key: number } | null>(null);
const writingPen = ref(false);
const writingPenPoint = ref({ x: 520, y: 84 });
const inkStroke = ref<{ char: string; x: number; y: number; key: number } | null>(null);
const actionMenuVisible = ref(false);
const notificationVisible = ref(false);
const notificationLoading = ref(false);
const noticeVisible = ref(false);
const activeMembersVisible = ref(false);
const uploadVisible = ref(false);
const uploadInputRef = ref<HTMLInputElement | null>(null);
const uploadFile = ref<File | null>(null);
const uploadDescription = ref('');
const uploadTags = ref<string[]>([]);
const uploadDropActive = ref(false);
const uploadSubmitting = ref(false);
const profilePanelVisible = ref(false);
const profileTab = ref('动态');
const profileEditVisible = ref(false);
const profileSaving = ref(false);
const profileAssetUploading = ref<'avatar' | 'cover' | null>(null);
const profileAvatarInputRef = ref<HTMLInputElement | null>(null);
const profileCoverInputRef = ref<HTMLInputElement | null>(null);
const profileForm = ref({
  nickname: '',
  avatarUrl: '',
  profileCoverUrl: '',
  bio: '',
  college: '',
  major: '',
  grade: '',
});
const profileNicknameState = ref({ active: false, touched: false, error: '', shaking: false });
const profileFollowsVisible = ref(false);
const profileFollowsTab = ref<'followers' | 'following'>('following');
const profileFollowsLoading = ref(false);
const profileFollowUsers = ref<UserVO[]>([]);
const profileLikesVisible = ref(false);
const postDetailVisible = ref(false);
const postDetailLoading = ref(false);
const postDetail = ref<PostVO | null>(null);
const postDetailComments = ref<CommentVO[]>([]);
const postDetailQa = ref<QaQuestionVO | null>(null);
const postDetailCommentText = ref('');
const postDetailCommentInputRef = ref<HTMLTextAreaElement | null>(null);
const postDetailReplyTo = ref<{ parentId: number; replyToId: number; nickname: string } | null>(null);
const postDetailSubmitting = ref(false);
const postDetailLikeSubmitting = ref(false);
const postDetailAcceptingId = ref<number | null>(null);
const postListLikeSubmitting = ref<number | null>(null);
const postActionVisible = ref(false);
const postActionTarget = ref<PostVO | null>(null);
const postActionSubmitting = ref<number | null>(null);
const postShareVisible = ref(false);
const postShareTarget = ref<PostVO | null>(null);
const memberProfileVisible = ref(false);
const memberProfileLoading = ref(false);
const memberProfile = ref<UserVO | null>(null);
const memberProfileFollowCounts = ref({ followers: 0, following: 0 });
const memberProfileFollowing = ref(false);
const memberProfileFollowSubmitting = ref(false);
const memberProfileAchievements = ref<AchievementVO[]>([]);
const settingName = ref('');
const settingDescription = ref('');
const settingVisibility = ref('PUBLIC');
const settingPostNotice = ref('');
const settingSensitiveWords = ref('');
const settingSubmitting = ref(false);
const spaceActionSubmitting = ref(false);
const challengeFormVisible = ref(false);
const challengeName = ref('');
const challengeDescription = ref('');
const challengeStartDate = ref('');
const challengeEndDate = ref('');
const challengeSubmitting = ref(false);
let writingTimer: number | undefined;

const tabs = ['首页', '帖子', '精华', '文件', '成员', '打卡', '设置'];
const activeTab = ref('首页');
const profileTabs = ['动态', '帖子', '打卡', '成就'];
const uploadAccept = '.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.zip,.rar,.7z,.jpg,.jpeg,.png,.gif,.webp,.md,.markdown';
const maxUploadSize = 50 * 1024 * 1024;
type ProfileFeedItem = {
  id: string;
  title: string;
  description: string;
  meta: string;
  type: 'post' | 'resource' | 'challenge' | 'achievement' | 'empty';
  targetId?: number;
};

const spaceId = computed(() => Number(route.params.id));
const sortedPosts = computed(() => {
  const list = [...posts.value];
  if (postSort.value === 'hot') {
    return list.sort((a, b) => b.likeCount + b.commentCount - (a.likeCount + a.commentCount));
  }
  if (postSort.value === 'essence') {
    const essencePosts = list.filter((item) => item.isEssence === 1);
    return (essencePosts.length ? essencePosts : list).sort((a, b) => b.likeCount - a.likeCount);
  }
  return list.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
});
const essencePosts = computed(() =>
  posts.value
    .filter((item) => item.isEssence === 1)
    .sort((a, b) => b.likeCount + b.commentCount - (a.likeCount + a.commentCount)),
);
const visiblePosts = computed(() => (activeTab.value === '精华' ? essencePosts.value : sortedPosts.value));
const latestPosts = computed(() =>
  [...posts.value].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()).slice(0, 4),
);
const recentResources = computed(() =>
  [...spaceResources.value].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()).slice(0, 4),
);
const activeChallenges = computed(() => {
  const today = todayString();
  return checkinChallenges.value.filter((item) => item.startDate <= today && item.endDate >= today);
});
const filteredMembers = computed(() => {
  const keyword = memberKeyword.value.trim().toLowerCase();
  if (!keyword) return members.value;
  return members.value.filter((member) => {
    const nickname = member.user?.nickname?.toLowerCase() || '';
    const role = member.role?.toLowerCase() || '';
    return nickname.includes(keyword) || role.includes(keyword);
  });
});
const activeMemberList = computed(() => {
  return members.value.map((member) => ({
    id: member.userId,
    nickname: member.user?.nickname || '未知用户',
    avatarUrl: member.user?.avatarUrl || '',
    role: member.role === 'OWNER' ? '圈主' : member.role === 'ADMIN' ? '管理员' : '成员',
  }));
});
const canManageSpace = computed(() =>
  ['OWNER', 'ADMIN'].includes(space.value?.memberRole || '') || space.value?.ownerId === myProfile.value?.id,
);
const canLeaveSpace = computed(() => Boolean(space.value?.isMember && space.value.memberRole !== 'OWNER'));
const visibilityLabel = computed(() => {
  const map: Record<string, string> = {
    PUBLIC: '公开圈子',
    REVIEW: '审核加入',
    PRIVATE: '私密圈子',
  };
  return map[space.value?.visibility || ''] || '公开圈子';
});
const memberRoleLabel = computed(() => {
  if (!space.value?.isMember) return '未加入';
  const role = space.value.memberRole || 'MEMBER';
  if (role === 'OWNER') return '圈主';
  if (role === 'ADMIN') return '管理员';
  return '成员';
});
const spaceStatusLabel = computed(() => (space.value?.status === 1 ? '正常' : '已停用'));
const noticeText = computed(() => space.value?.postNotice?.trim() || '圈主暂未发布公告。');
const sensitiveWordList = computed(() =>
  (space.value?.sensitiveWords || '')
    .split(/[,，\n]/)
    .map((item) => item.trim())
    .filter(Boolean),
);
const activityTrend = computed(() => {
  const buckets = Array.from({ length: 7 }, (_, index) => {
    const day = new Date();
    day.setDate(day.getDate() - (6 - index));
    return { label: day.toISOString().slice(0, 10), value: 0 };
  });
  const addActivity = (value?: string, weight = 1) => {
    if (!value) return;
    const key = value.slice(0, 10);
    const bucket = buckets.find((item) => item.label === key);
    if (bucket) bucket.value += weight;
  };

  posts.value.forEach((post) => addActivity(post.createdAt, 1 + Math.min(5, post.commentCount)));
  spaceResources.value.forEach((resource) => addActivity(resource.createdAt, 2));
  checkinChallenges.value.forEach((challenge) => addActivity(challenge.createdAt, 3));
  return buckets;
});
const activityScore = computed(() => activityTrend.value.reduce((sum, item) => sum + item.value, 0));
const activityChange = computed(() => {
  const trend = activityTrend.value;
  const previous = trend.slice(0, 3).reduce((sum, item) => sum + item.value, 0);
  const current = trend.slice(4).reduce((sum, item) => sum + item.value, 0);
  if (previous === 0 && current === 0) return '0%';
  if (previous === 0) return '+100%';
  const delta = ((current - previous) / previous) * 100;
  return `${delta >= 0 ? '+' : ''}${delta.toFixed(1)}%`;
});
const activityTrendPath = computed(() => {
  const max = Math.max(1, ...activityTrend.value.map((item) => item.value));
  return activityTrend.value
    .map((item, index) => {
      const x = (index / 6) * 100;
      const y = 26 - (item.value / max) * 22;
      return `${index === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
});
const activityTrendPoints = computed(() => {
  const max = Math.max(1, ...activityTrend.value.map((item) => item.value));
  return activityTrend.value.map((item, index) => ({
    x: (index / 6) * 100,
    y: 26 - (item.value / max) * 22,
  }));
});
const profileSpacePosts = computed(() => {
  const userId = myProfile.value?.id;
  if (!userId) return [];
  return posts.value.filter((post) => post.authorId === userId);
});
const profileSpaceResources = computed(() => {
  const userId = myProfile.value?.id;
  if (!userId) return [];
  return spaceResources.value.filter((resource) => resource.uploaderId === userId);
});
const profileLikeCount = computed(() =>
  profileSpacePosts.value.reduce((sum, post) => sum + post.likeCount, 0),
);
const profileLikedPosts = computed(() =>
  [...profileSpacePosts.value]
    .filter((post) => post.likeCount > 0)
    .sort((a, b) => b.likeCount - a.likeCount || new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()),
);
const awardedAchievements = computed(() => profileAchievements.value.filter((item) => item.awarded));
const profileCheckinChallenges = computed(() => {
  const userId = myProfile.value?.id;
  return checkinChallenges.value
    .filter((item) => item.isMember || item.creatorId === userId)
    .sort((a, b) => (b.myConsecutiveDays || 0) - (a.myConsecutiveDays || 0) || new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
});
const profileCheckinStreak = computed(() =>
  Math.max(0, ...profileCheckinChallenges.value.map((item) => item.myConsecutiveDays || 0)),
);
const profileCoverImage = computed(() => myProfile.value?.profileCoverUrl?.trim() || auroraBg);
const profileFeedItems = computed<ProfileFeedItem[]>(() => {
  if (profileTab.value === '帖子') {
    return profileSpacePosts.value.slice(0, 4).map((post) => ({
      id: `post-${post.id}`,
      title: postTitle(post),
      description: postPreview(post.content),
      meta: `${formatTime(post.createdAt)} · ${post.likeCount} 赞 · ${post.commentCount} 评论`,
      type: 'post',
      targetId: post.id,
    }));
  }
  if (profileTab.value === '打卡') {
    return profileCheckinChallenges.value
      .slice(0, 4)
      .map((challenge) => ({
        id: `challenge-${challenge.id}`,
        title: challenge.name,
        description: challenge.description || '这个打卡挑战还没有简介。',
        meta: `累计 ${challenge.myTotalDays || 0} 天 · 连续 ${challenge.myConsecutiveDays || 0} 天`,
        type: 'challenge',
        targetId: challenge.id,
      }));
  }
  if (profileTab.value === '成就') {
    return profileAchievements.value.slice(0, 5).map((achievement) => ({
      id: `achievement-${achievement.id}`,
      title: achievement.name,
      description: achievement.description,
      meta: achievement.awarded ? '已解锁' : '待解锁',
      type: 'achievement',
    }));
  }
  if (profileTab.value === '动态') {
    const postItems = profileSpacePosts.value.slice(0, 2).map((post) => ({
      id: `post-${post.id}`,
      title: postTitle(post),
      description: postPreview(post.content),
      meta: `${formatTime(post.createdAt)} · 来自 ${space.value?.name || '当前学习圈'}`,
      type: 'post' as const,
      targetId: post.id,
    }));
    const resourceItems = profileSpaceResources.value.slice(0, 2).map((resource) => ({
      id: `resource-${resource.id}`,
      title: resource.fileName,
      description: resource.description || '圈内资料更新',
      meta: `${formatFileSize(resource.fileSize)} · ${formatTime(resource.createdAt)}`,
      type: 'resource' as const,
      targetId: resource.id,
    }));
    return [...postItems, ...resourceItems];
  }
  return [];
});
const profileDisplay = computed(() => {
  const user = myProfile.value;
  return {
    nickname: user?.nickname || '校园学习者',
    avatarUrl: user?.avatarUrl || '',
    initial: (user?.nickname || '学').charAt(0).toUpperCase(),
    title: [user?.college, user?.major, user?.grade].filter(Boolean).join(' · ') || '正在完善学习档案',
    bio: user?.bio || '还没有写下个人简介。',
    role: user?.role || 'USER',
    email: user?.email || '未绑定邮箱',
  };
});
const searchTerm = computed(() => searchKeyword.value.trim());
const searchQuery = computed(() => searchTerm.value.toLowerCase());
const searchPosts = computed(() => {
  const query = searchQuery.value;
  if (!query) return [];
  return posts.value
    .filter((post) => {
      const fields = [
        post.title || '',
        post.content || '',
        post.author?.nickname || '',
        post.tags.join(' '),
        post.topics.join(' '),
        post.type || '',
      ];
      return fields.join(' ').toLowerCase().includes(query);
    })
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
});
const searchResources = computed(() => {
  const query = searchQuery.value;
  if (!query) return [];
  return spaceResources.value
    .filter((resource) => {
      const fields = [
        resource.fileName || '',
        resource.description || '',
        resource.uploader?.nickname || '',
        resource.tags.join(' '),
        resource.college || '',
        resource.major || '',
        resource.course || '',
        resource.semester || '',
      ];
      return fields.join(' ').toLowerCase().includes(query);
    })
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
});
const searchMembers = computed(() => {
  const query = searchQuery.value;
  if (!query) return [];
  return members.value
    .filter((member) => {
      const fields = [
        member.user?.nickname || '',
        member.user?.email || '',
        member.role || '',
        member.user?.id ? String(member.user.id) : '',
      ];
      return fields.join(' ').toLowerCase().includes(query);
    })
    .sort((a, b) => new Date(b.joinedAt).getTime() - new Date(a.joinedAt).getTime());
});
const searchChallenges = computed(() => {
  const query = searchQuery.value;
  if (!query) return [];
  return checkinChallenges.value
    .filter((challenge) => {
      const fields = [
        challenge.name || '',
        challenge.description || '',
        challenge.creator?.nickname || '',
        challenge.rule || '',
      ];
      return fields.join(' ').toLowerCase().includes(query);
    })
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
});
const searchTotalCount = computed(
  () => searchPosts.value.length + searchResources.value.length + searchMembers.value.length + searchChallenges.value.length,
);
const isPostDetailAuthor = computed(() => Boolean(postDetail.value && myProfile.value?.id === postDetail.value.authorId));
const postDetailAuthorName = computed(() => postDetail.value?.author?.nickname || '匿名用户');
const memberProfileDisplay = computed(() => {
  const user = memberProfile.value;
  return {
    nickname: user?.nickname || '校园学习者',
    avatarUrl: user?.avatarUrl || '',
    coverUrl: user?.profileCoverUrl?.trim() || auroraBg,
    initial: (user?.nickname || '学').charAt(0).toUpperCase(),
    title: [user?.college, user?.major, user?.grade].filter(Boolean).join(' · ') || '正在完善学习档案',
    bio: user?.bio || '还没有写下个人简介。',
    role: user?.role || 'USER',
    joinedAt: user?.createdAt,
  };
});
const memberProfilePosts = computed(() => {
  const userId = memberProfile.value?.id;
  if (!userId) return [];
  return posts.value
    .filter((post) => post.authorId === userId)
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
});
const memberProfileLikeCount = computed(() =>
  memberProfilePosts.value.reduce((sum, post) => sum + post.likeCount, 0),
);
const spaceLink = computed(() => {
  if (typeof window === 'undefined') {
    return `/spaces/${spaceId.value}`;
  }
  return `${window.location.origin}/spaces/${spaceId.value}`;
});

async function loadSpace() {
  loading.value = true;
  try {
    const id = spaceId.value;
    const [spaceResult, memberResult, postResult, profileResult, resourceResult, challengeResult] = await Promise.all([
      getSpaceById(id),
      getSpaceMembers(id, undefined, 100).catch(() => []),
      getSpacePosts(id, undefined, 50).catch(() => []),
      getMyProfile().catch(() => null),
      getResources({ spaceId: id, limit: 50 }).catch(() => []),
      getChallenges({ spaceId: id, limit: 50 }).catch(() => []),
    ]);

    space.value = spaceResult;
    members.value = memberResult;
    posts.value = postResult;
    aiCards.value = {};
    aiRequestedSet.value.clear();
    aiVisibleSet.value.clear();
    void loadSpaceAiCards(postResult.map((p) => p.id));
    myProfile.value = profileResult;
    spaceResources.value = resourceResult;
    checkinChallenges.value = challengeResult;
    syncSettingForm();
    syncProfileForm();
    // 批量拉取这些帖子的 AI 卡片
    void loadSpaceAiCards(postResult.map((p) => p.id));
    if (profileResult?.id) {
      await loadProfileExtras(profileResult.id);
    }
    unreadCount.value = await getUnreadCount().catch(() => 0);
  } catch (error) {
    console.error(error);
    spaceResources.value = [];
    checkinChallenges.value = [];
    members.value = [];
    posts.value = [];
    space.value = null;
    message.error('学习圈数据加载失败');
  }
  loading.value = false;
}

async function loadProfileExtras(userId: number) {
  const [counts, achievements] = await Promise.all([
    getFollowCounts(userId).catch(() => ({ followers: 0, following: 0 })),
    getUserAchievements(userId).catch(() => []),
  ]);
  profileFollowCounts.value = counts;
  profileAchievements.value = achievements;
}

async function loadNotifications() {
  notificationLoading.value = true;
  try {
    notifications.value = await getNotifications(undefined, 10);
  } catch {
    notifications.value = [];
  } finally {
    notificationLoading.value = false;
  }
}

function syncSettingForm() {
  if (!space.value) return;
  settingName.value = space.value.name || '';
  settingDescription.value = space.value.description || '';
  settingVisibility.value = space.value.visibility || 'PUBLIC';
  settingPostNotice.value = space.value.postNotice || '';
  settingSensitiveWords.value = space.value.sensitiveWords || '';
}

function syncProfileForm() {
  const profile = myProfile.value;
  if (!profile) return;
  profileForm.value = {
    nickname: profile.nickname || '',
    avatarUrl: profile.avatarUrl || '',
    profileCoverUrl: profile.profileCoverUrl || '',
    bio: profile.bio || '',
    college: profile.college || '',
    major: profile.major || '',
    grade: profile.grade || '',
  };
  profileNicknameState.value = { active: false, touched: false, error: '', shaking: false };
}

function shakeProfileNickname() {
  profileNicknameState.value.shaking = false;
  window.setTimeout(() => {
    profileNicknameState.value.shaking = true;
    window.setTimeout(() => {
      profileNicknameState.value.shaking = false;
    }, 520);
  }, 0);
}

function validateProfileNickname() {
  profileNicknameState.value.error = validateNickname(profileForm.value.nickname);
}

function focusProfileNickname() {
  profileNicknameState.value.active = true;
  validateProfileNickname();
}

function blurProfileNickname() {
  profileNicknameState.value.active = false;
  profileNicknameState.value.touched = true;
  validateProfileNickname();
  if (profileNicknameState.value.error) {
    shakeProfileNickname();
  }
}

function closeProfileEditor() {
  syncProfileForm();
  profileEditVisible.value = false;
}

// 圈内随笔草稿保存/恢复
const COMPOSE_DRAFT_KEY = 'campus_compose_draft';

function saveComposeDraft() {
  if (!composeTitle.value.trim() && !composeContent.value.trim()) {
    localStorage.removeItem(COMPOSE_DRAFT_KEY);
    return;
  }
  const draft = {
    title: composeTitle.value,
    content: composeContent.value,
    type: composeType.value,
    topics: composeTopics.value,
    spaceId: space.value?.id,
    savedAt: Date.now(),
  };
  localStorage.setItem(COMPOSE_DRAFT_KEY, JSON.stringify(draft));
}

function loadComposeDraft() {
  try {
    const raw = localStorage.getItem(COMPOSE_DRAFT_KEY);
    if (!raw) return;
    const draft = JSON.parse(raw);
    // 草稿超过 7 天或不属于当前圈子则丢弃
    if (Date.now() - draft.savedAt > 7 * 24 * 60 * 60 * 1000) {
      localStorage.removeItem(COMPOSE_DRAFT_KEY);
      return;
    }
    if (draft.spaceId && draft.spaceId !== space.value?.id) return;
    composeTitle.value = draft.title || '';
    composeContent.value = draft.content || '';
    composeType.value = draft.type || 'NORMAL';
    composeTopics.value = draft.topics || [];
    if (draft.title || draft.content) {
      message.info('已恢复上次未发布的草稿');
    }
  } catch {
    localStorage.removeItem(COMPOSE_DRAFT_KEY);
  }
}

function clearComposeDraft() {
  localStorage.removeItem(COMPOSE_DRAFT_KEY);
}

function openCompose() {
  if (!space.value) return;
  composeVisible.value = true;
  actionMenuVisible.value = false;
  // 恢复圈内草稿
  loadComposeDraft();
}

function resetCompose() {
  composeTitle.value = '';
  composeContent.value = '';
  composeType.value = 'NORMAL';
  composeTopicInput.value = '';
  composeTopics.value = [];
  // 清除圈内草稿
  clearComposeDraft();
}

function closeCompose() {
  composeVisible.value = false;
  paperRipple.value = null;
  inkStroke.value = null;
  writingPen.value = false;
  // 关闭时保存草稿（有内容时）
  saveComposeDraft();
}

function addComposeTopic() {
  const topic = composeTopicInput.value.trim();
  if (!topic || composeTopics.value.includes(topic)) return;
  composeTopics.value.push(topic);
  composeTopicInput.value = '';
}

function removeComposeTopic(topic: string) {
  composeTopics.value = composeTopics.value.filter((item) => item !== topic);
}

function createPaperRipple(event: PointerEvent) {
  const target = event.target as HTMLElement;
  if (target.closest('button')) return;
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  const key = Date.now();
  paperRipple.value = {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top,
    key,
  };
  window.setTimeout(() => {
    if (paperRipple.value?.key === key) {
      paperRipple.value = null;
    }
  }, 720);
}

function handlePaperInput(event: Event) {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement;
  const sheet = paperSheetRef.value;
  if (!sheet) return;

  const inputEvent = event as InputEvent;
  const value = target.value || '';
  const typedChar = inputEvent.data || Array.from(value).at(-1) || '墨';
  const sheetRect = sheet.getBoundingClientRect();
  const targetRect = target.getBoundingClientRect();
  const style = window.getComputedStyle(target);
  const fontSize = Number.parseFloat(style.fontSize) || 16;
  const lineHeight = Number.parseFloat(style.lineHeight) || fontSize * 1.8;
  const caretIndex = target.selectionStart ?? value.length;
  const beforeCaret = value.slice(0, caretIndex);
  const logicalLines = beforeCaret.split('\n');
  const lastLine = logicalLines.at(-1) || '';
  const usableWidth = Math.max(120, target.clientWidth - 24);
  const charsPerLine = Math.max(1, Math.floor(usableWidth / (fontSize * 0.92)));
  const wrappedLineCount = logicalLines.slice(0, -1).reduce((sum, line) => sum + Math.max(1, Math.ceil(line.length / charsPerLine)), 0);
  const currentWrapLine = Math.floor(lastLine.length / charsPerLine);
  const currentColumn = lastLine.length % charsPerLine;
  const x = targetRect.left - sheetRect.left + 8 + Math.min(usableWidth - 10, currentColumn * fontSize * 0.92);
  const y = targetRect.top - sheetRect.top + lineHeight * (wrappedLineCount + currentWrapLine + 0.68);

  writingPenPoint.value = {
    x: Math.min(sheet.clientWidth - 80, Math.max(78, x + 28)),
    y: Math.min(sheet.clientHeight - 128, Math.max(18, y - 86)),
  };
  inkStroke.value = {
    char: typedChar,
    x: Math.min(sheet.clientWidth - 52, Math.max(52, x)),
    y: Math.min(sheet.clientHeight - 40, Math.max(32, y - fontSize * 1.2)),
    key: Date.now(),
  };
  writingPen.value = true;

  if (writingTimer) {
    window.clearTimeout(writingTimer);
  }
  writingTimer = window.setTimeout(() => {
    writingPen.value = false;
  }, 520);
}

async function submitCompose() {
  if (!space.value || composeSubmitting.value) return;
  if (!composeContent.value.trim()) {
    message.warning('先写一点内容再发布');
    return;
  }
  if (composeType.value === 'QA' && !composeTitle.value.trim()) {
    message.warning('问答帖需要一个清晰标题');
    return;
  }

  composeSubmitting.value = true;
  try {
    const post = await createPost({
      scope: 'SPACE',
      spaceId: space.value.id,
      type: composeType.value,
      title: composeTitle.value.trim() || undefined,
      content: composeContent.value.trim(),
      topics: composeTopics.value.length ? composeTopics.value : undefined,
    });
    posts.value = [post, ...posts.value];
    space.value.postCount += 1;
    message.success('已发布到当前学习圈');
    closeCompose();
    resetCompose();
  } catch {
    message.error('发布失败');
  } finally {
    composeSubmitting.value = false;
  }
}

function goCreatePost() {
  openCompose();
}

function goUploadResource() {
  if (!space.value) return;
  uploadVisible.value = true;
  actionMenuVisible.value = false;
}

function resetUploadForm() {
  uploadFile.value = null;
  uploadDescription.value = '';
  uploadTags.value = [];
  uploadDropActive.value = false;
  if (uploadInputRef.value) {
    uploadInputRef.value.value = '';
  }
}

function closeUploadResource() {
  if (uploadSubmitting.value) return;
  uploadVisible.value = false;
  resetUploadForm();
}

function openUploadFilePicker() {
  uploadInputRef.value?.click();
}

function applyUploadFile(file?: File | null) {
  if (!file) return;
  if (file.size > maxUploadSize) {
    message.warning('文件不能超过 50MB');
    return;
  }
  uploadFile.value = file;
}

function handleUploadFileChange(event: Event) {
  const target = event.target as HTMLInputElement;
  applyUploadFile(target.files?.[0]);
}

function handleUploadDrop(event: DragEvent) {
  uploadDropActive.value = false;
  applyUploadFile(event.dataTransfer?.files?.[0]);
}

async function submitSpaceResource() {
  if (!space.value || uploadSubmitting.value) return;
  if (!uploadFile.value) {
    message.warning('请先选择一个文件');
    return;
  }
  const tags = normalizeUploadTags(uploadTags.value);
  if (tags.length === 0) {
    message.warning('请至少填写一个资源标签');
    return;
  }

  uploadSubmitting.value = true;
  try {
    const resource = await uploadResource(uploadFile.value, {
      visibility: 'SPACE',
      spaceId: space.value.id,
      tags,
      description: uploadDescription.value.trim() || undefined,
    });
    if (resource.status === 2) {
      // 审核流：待审核资源不混入空间文件列表，审核通过后自然出现
      message.success('已提交审核，通过后会出现在学习圈文件里');
    } else {
      spaceResources.value = [resource, ...spaceResources.value.filter((item) => item.id !== resource.id)];
      message.success('已上传到当前学习圈');
    }
    activeTab.value = '文件';
    uploadVisible.value = false;
    resetUploadForm();
  } catch {
    message.error('上传失败');
  } finally {
    uploadSubmitting.value = false;
  }
}

function normalizeUploadTags(values: string[]) {
  return [...new Set(
    values
      .flatMap((value) => value.split(/[\s,，、;；#]+/))
      .map((item) => item.trim())
      .filter(Boolean),
  )].slice(0, 8);
}

function handleUploadTagsUpdate(value: string[]) {
  uploadTags.value = normalizeUploadTags(value);
}

async function toggleSpaceMembership() {
  if (!space.value || spaceActionSubmitting.value) return;
  spaceActionSubmitting.value = true;
  try {
    if (space.value.isMember && canLeaveSpace.value) {
      await leaveSpace(space.value.id);
      message.success('已退出学习圈');
    } else if (!space.value.isMember) {
      await joinSpace(space.value.id);
      message.success(space.value.visibility === 'REVIEW' ? '已提交加入申请' : '已加入学习圈');
    }
    space.value = await getSpaceById(space.value.id);
    members.value = await getSpaceMembers(space.value.id, undefined, 100).catch(() => members.value);
    syncSettingForm();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败');
  } finally {
    spaceActionSubmitting.value = false;
  }
}

async function submitSpaceSettings() {
  if (!space.value || !canManageSpace.value || settingSubmitting.value) return;
  if (!settingName.value.trim()) {
    message.warning('圈子名称不能为空');
    return;
  }

  settingSubmitting.value = true;
  try {
    space.value = await updateSpace(space.value.id, {
      name: settingName.value.trim(),
      description: settingDescription.value.trim() || undefined,
      visibility: settingVisibility.value,
      postNotice: settingPostNotice.value.trim(),
      sensitiveWords: settingSensitiveWords.value.trim(),
    });
    syncSettingForm();
    message.success('圈子设置已保存');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    settingSubmitting.value = false;
  }
}

function openChallengeForm() {
  activeTab.value = '打卡';
  const today = todayString();
  const end = new Date();
  end.setDate(end.getDate() + 21);
  challengeStartDate.value = today;
  challengeEndDate.value = end.toISOString().slice(0, 10);
  challengeName.value = '';
  challengeDescription.value = '';
  challengeFormVisible.value = true;
}

async function submitChallenge() {
  if (!space.value || challengeSubmitting.value) return;
  if (!challengeName.value.trim() || !challengeStartDate.value || !challengeEndDate.value) {
    message.warning('请填写挑战名称和日期范围');
    return;
  }
  if (challengeEndDate.value < challengeStartDate.value) {
    message.warning('结束日期不能早于开始日期');
    return;
  }

  challengeSubmitting.value = true;
  try {
    const challenge = await createChallenge({
      name: challengeName.value.trim(),
      description: challengeDescription.value.trim() || undefined,
      spaceId: space.value.id,
      startDate: challengeStartDate.value,
      endDate: challengeEndDate.value,
    });
    checkinChallenges.value = [challenge, ...checkinChallenges.value.filter((item) => item.id !== challenge.id)];
    challengeFormVisible.value = false;
    message.success('圈内打卡挑战已创建');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '创建失败');
  } finally {
    challengeSubmitting.value = false;
  }
}

function goNotifications() {
  notificationVisible.value = true;
  loadNotifications();
}

function goProfile() {
  profilePanelVisible.value = true;
  actionMenuVisible.value = false;
  if (myProfile.value?.id) {
    loadProfileExtras(myProfile.value.id);
  }
}

async function openMemberProfile(userId?: number | null) {
  if (!userId) {
    message.warning('暂时没有该成员的主页信息');
    return;
  }
  activeMembersVisible.value = false;
  profileFollowsVisible.value = false;
  if (myProfile.value?.id === userId) {
    goProfile();
    return;
  }

  memberProfileVisible.value = true;
  memberProfileLoading.value = true;
  try {
    const [user, counts, achievements, following] = await Promise.all([
      getUserById(userId),
      getFollowCounts(userId).catch(() => ({ followers: 0, following: 0 })),
      getUserAchievements(userId).catch(() => []),
      isFollowing(userId).catch(() => false),
    ]);
    memberProfile.value = user;
    memberProfileFollowCounts.value = counts;
    memberProfileAchievements.value = achievements;
    memberProfileFollowing.value = following;
  } catch (error) {
    memberProfile.value = null;
    message.error(error instanceof Error ? error.message : '成员资料加载失败');
  } finally {
    memberProfileLoading.value = false;
  }
}

async function openMemberProfileFromPostDetail(userId?: number | null) {
  postDetailVisible.value = false;
  await nextTick();
  await openMemberProfile(userId);
}

async function toggleMemberProfileFollow() {
  const userId = memberProfile.value?.id;
  if (!userId || memberProfileFollowSubmitting.value) return;
  memberProfileFollowSubmitting.value = true;
  try {
    if (memberProfileFollowing.value) {
      await unfollow(userId);
      memberProfileFollowing.value = false;
      memberProfileFollowCounts.value = {
        ...memberProfileFollowCounts.value,
        followers: Math.max(0, memberProfileFollowCounts.value.followers - 1),
      };
      message.success('已取消关注');
    } else {
      await follow(userId);
      memberProfileFollowing.value = true;
      memberProfileFollowCounts.value = {
        ...memberProfileFollowCounts.value,
        followers: memberProfileFollowCounts.value.followers + 1,
      };
      message.success('已关注');
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '关注操作失败');
  } finally {
    memberProfileFollowSubmitting.value = false;
  }
}

async function openProfileFollows(tab: 'followers' | 'following') {
  if (!myProfile.value?.id) return;
  profileFollowsTab.value = tab;
  profileFollowsVisible.value = true;
  profileFollowsLoading.value = true;
  try {
    profileFollowUsers.value =
      tab === 'followers'
        ? await getUserFollowers(myProfile.value.id, undefined, 30)
        : await getUserFollowing(myProfile.value.id, undefined, 30);
  } catch {
    profileFollowUsers.value = [];
    message.error('关注列表加载失败');
  } finally {
    profileFollowsLoading.value = false;
  }
}

function openProfileLikes() {
  profileLikesVisible.value = true;
}

function syncPostInLists(updatedPost: PostVO) {
  posts.value = posts.value.map((item) => (item.id === updatedPost.id ? { ...item, ...updatedPost } : item));
  if (postDetail.value?.id === updatedPost.id) {
    postDetail.value = { ...postDetail.value, ...updatedPost };
  }
  if (postActionTarget.value?.id === updatedPost.id) {
    postActionTarget.value = { ...postActionTarget.value, ...updatedPost };
  }
  if (postShareTarget.value?.id === updatedPost.id) {
    postShareTarget.value = { ...postShareTarget.value, ...updatedPost };
  }
}

async function refreshPostComments(postId: number) {
  postDetailComments.value = await getComments(postId, undefined, 30, postDetail.value?.type === 'QA');
}

async function goPost(postId?: number) {
  if (!postId) return;
  postActionVisible.value = false;
  postShareVisible.value = false;
  postDetailVisible.value = true;
  postDetailLoading.value = true;
  postDetail.value = null;
  postDetailComments.value = [];
  postDetailQa.value = null;
  postDetailCommentText.value = '';
  postDetailReplyTo.value = null;
  try {
    const detail = await getPostById(postId);
    postDetail.value = detail;
    syncPostInLists(detail);
    const [comments, qa] = await Promise.all([
      getComments(postId, undefined, 30, detail.type === 'QA').catch(() => []),
      detail.type === 'QA' ? getQaInfo(postId).catch(() => null) : Promise.resolve(null),
    ]);
    postDetailComments.value = comments;
    postDetailQa.value = qa;
  } catch (error) {
    postDetail.value = null;
    message.error(error instanceof Error ? error.message : '帖子详情加载失败');
  } finally {
    postDetailLoading.value = false;
  }
}

async function togglePostListLike(post?: PostVO | null) {
  if (!post || postListLikeSubmitting.value === post.id) return;
  postListLikeSubmitting.value = post.id;
  try {
    const liked = await toggleReaction(post.id, 'LIKE');
    const delta = liked === post.liked ? 0 : liked ? 1 : -1;
    syncPostInLists({
      ...post,
      liked,
      likeCount: Math.max(0, post.likeCount + delta),
    });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '点赞失败');
  } finally {
    postListLikeSubmitting.value = null;
  }
}

async function togglePostCollect(post?: PostVO | null) {
  if (!post || postActionSubmitting.value === post.id) return;
  postActionSubmitting.value = post.id;
  try {
    const collected = await toggleReaction(post.id, 'COLLECT');
    syncPostInLists({ ...post, collected });
    message.success(collected ? '已收藏帖子' : '已取消收藏');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '收藏操作失败');
  } finally {
    postActionSubmitting.value = null;
  }
}

function openPostActions(post: PostVO) {
  postActionTarget.value = post;
  postActionVisible.value = true;
  postShareVisible.value = false;
}

function openPostDetailFromActions(post?: PostVO | null) {
  if (!post) return;
  postActionVisible.value = false;
  postShareVisible.value = false;
  goPost(post.id);
}

async function togglePostDetailLike() {
  const detail = postDetail.value;
  if (!detail || postDetailLikeSubmitting.value) return;
  postDetailLikeSubmitting.value = true;
  try {
    const liked = await toggleReaction(detail.id, 'LIKE');
    const delta = liked === detail.liked ? 0 : liked ? 1 : -1;
    postDetail.value = {
      ...detail,
      liked,
      likeCount: Math.max(0, detail.likeCount + delta),
    };
    syncPostInLists(postDetail.value);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '点赞失败');
  } finally {
    postDetailLikeSubmitting.value = false;
  }
}

function replyPostDetailComment(comment: CommentVO) {
  postDetailReplyTo.value = {
    parentId: comment.parentId ?? comment.id,
    replyToId: comment.id,
    nickname: comment.author?.nickname || '匿名用户',
  };
  postDetailCommentText.value = '';
  nextTick(() => postDetailCommentInputRef.value?.focus());
}

function cancelPostDetailReply() {
  postDetailReplyTo.value = null;
  postDetailCommentText.value = '';
}

async function submitPostDetailComment() {
  const detail = postDetail.value;
  const content = postDetailCommentText.value.trim();
  if (!detail || !content || postDetailSubmitting.value) return;
  postDetailSubmitting.value = true;
  try {
    await createComment({
      postId: detail.id,
      parentId: postDetailReplyTo.value?.parentId,
      replyToId: postDetailReplyTo.value?.replyToId,
      content,
    });
    postDetailCommentText.value = '';
    postDetailReplyTo.value = null;
    await refreshPostComments(detail.id);
    postDetail.value = {
      ...detail,
      commentCount: detail.commentCount + 1,
    };
    syncPostInLists(postDetail.value);
    message.success('评论已发布');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '评论失败');
  } finally {
    postDetailSubmitting.value = false;
  }
}

async function likePostDetailComment(comment: CommentVO) {
  try {
    const liked = await toggleCommentReaction(comment.id, 'LIKE');
    const nextCount = Math.max(0, (comment.likeCount || 0) + (liked ? 1 : -1));
    const patchComment = (item: CommentVO): CommentVO => ({
      ...item,
      likeCount: item.id === comment.id ? nextCount : item.likeCount,
      replies: item.replies?.map(patchComment) || [],
    });
    postDetailComments.value = postDetailComments.value.map(patchComment);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '评论点赞失败');
  }
}

async function acceptPostDetailAnswer(commentId: number) {
  const detail = postDetail.value;
  if (!detail || postDetailAcceptingId.value) return;
  postDetailAcceptingId.value = commentId;
  try {
    postDetailQa.value = await acceptAnswer(detail.id, commentId);
    message.success('已采纳该回答');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '采纳失败');
  } finally {
    postDetailAcceptingId.value = null;
  }
}

function goResource(resourceId: number) {
  router.push(`/resources/${resourceId}`);
}

function goChallenge(challengeId: number) {
  router.push(`/checkin/${challengeId}`);
}

function openProfileFeedItem(item: ProfileFeedItem) {
  if (!profileFeedItemActionable(item)) return;
  if (item.type === 'post' && item.targetId) {
    profilePanelVisible.value = false;
    goPost(item.targetId);
    return;
  }
  profilePanelVisible.value = false;
  if (item.type === 'resource') {
    activeTab.value = '文件';
  } else if (item.type === 'challenge') {
    activeTab.value = '打卡';
  }
}

function openProfileFeedMore(item: ProfileFeedItem) {
  if (!profileFeedItemActionable(item)) return;
  if (item.type === 'post' && item.targetId) {
    const post = posts.value.find((entry) => entry.id === item.targetId);
    if (post) {
      openPostActions(post);
      return;
    }
  }
  openProfileFeedItem(item);
}

function openMemberPostInSpace(postId: number) {
  memberProfileVisible.value = false;
  goPost(postId);
}

function profileTabCount(tab: string) {
  if (tab === '帖子') return profileSpacePosts.value.length;
  if (tab === '打卡') return profileCheckinChallenges.value.length;
  if (tab === '成就') return profileAchievements.value.length;
  return profileSpacePosts.value.length + profileSpaceResources.value.length + profileCheckinChallenges.value.length;
}

function profileFeedItemActionable(item: ProfileFeedItem) {
  return Boolean(item.targetId && ['post', 'resource', 'challenge'].includes(item.type));
}

function profileItemTypeLabel(type: ProfileFeedItem['type']) {
  const map: Record<ProfileFeedItem['type'], string> = {
    post: '帖子',
    resource: '资料',
    challenge: '打卡',
    achievement: '成就',
    empty: '动态',
  };
  return map[type];
}

function profileItemSourceLabel(item: ProfileFeedItem) {
  if (profileFeedItemActionable(item)) return '点击查看详情';
  if (item.type === 'achievement') return item.meta;
  return '来自当前学习圈';
}

function startProfileCompose() {
  profilePanelVisible.value = false;
  goCreatePost();
}

function viewProfileCheckins() {
  profilePanelVisible.value = false;
  activeTab.value = '打卡';
}

function openProfileChallengeInSpace() {
  viewProfileCheckins();
}

function openProfileEditor() {
  syncProfileForm();
  profileEditVisible.value = true;
}

function openProfileAssetPicker(target: 'avatar' | 'cover') {
  if (profileAssetUploading.value) return;
  if (target === 'avatar') {
    profileAvatarInputRef.value?.click();
  } else {
    profileCoverInputRef.value?.click();
  }
}

async function handleProfileAssetChange(event: Event, target: 'avatar' | 'cover') {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || profileAssetUploading.value) return;
  if (!file.type.startsWith('image/')) {
    message.warning('请选择图片文件');
    return;
  }
  if (file.size > 5 * 1024 * 1024) {
    message.warning('图片不能超过 5MB');
    return;
  }

  profileAssetUploading.value = target;
  try {
    const asset = await uploadProfileAsset(file);
    if (target === 'avatar') {
      profileForm.value.avatarUrl = asset.url;
    } else {
      profileForm.value.profileCoverUrl = asset.url;
    }
    message.success(target === 'avatar' ? '头像已上传' : '主页封面已上传');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '图片上传失败');
  } finally {
    profileAssetUploading.value = null;
  }
}

async function submitProfileEdit() {
  if (!myProfile.value || profileSaving.value) return;
  profileNicknameState.value.touched = true;
  validateProfileNickname();
  if (profileNicknameState.value.error) {
    shakeProfileNickname();
    message.warning('请按提示修正昵称');
    return;
  }

  profileSaving.value = true;
  try {
    myProfile.value = await updateProfile({
      nickname: profileForm.value.nickname.trim(),
      avatarUrl: profileForm.value.avatarUrl.trim(),
      profileCoverUrl: profileForm.value.profileCoverUrl.trim(),
      bio: profileForm.value.bio.trim(),
      college: profileForm.value.college.trim(),
      major: profileForm.value.major.trim(),
      grade: profileForm.value.grade.trim(),
    });
    syncProfileForm();
    profileEditVisible.value = false;
    message.success('个人资料已更新');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存个人资料失败');
  } finally {
    profileSaving.value = false;
  }
}

async function refreshProfilePanel() {
  try {
    const profile = await getMyProfile();
    myProfile.value = profile;
    syncProfileForm();
    await loadProfileExtras(profile.id);
    message.success('个人主页已刷新');
  } catch {
    message.error('个人主页刷新失败');
  }
}

async function openNotification(notification: NotificationVO) {
  try {
    if (!notification.isRead) {
      await markRead(notification.id);
      notification.isRead = true;
      unreadCount.value = Math.max(0, unreadCount.value - 1);
    }
  } catch {
    // 已读状态失败不阻断跳转，通知内容本身仍可继续打开。
  }
  if (notification.redirectUrl) {
    notificationVisible.value = false;
    const postMatch = notification.redirectUrl.match(/^\/posts\/(\d+)$/);
    if (postMatch) {
      await goPost(Number(postMatch[1]));
      return;
    }
    router.push(notification.redirectUrl);
  }
}

function openActionMenu() {
  actionMenuVisible.value = true;
}

function openNoticeList() {
  actionMenuVisible.value = false;
  noticeVisible.value = true;
}

function openActiveMembers() {
  actionMenuVisible.value = false;
  activeMembersVisible.value = true;
}

async function refreshSpaceData() {
  await loadSpace();
  if (space.value) {
    message.success('圈内数据已刷新');
  }
}

async function copySpaceLink() {
  if (await copyTextToClipboard(spaceLink.value)) {
    message.success('圈子链接已复制');
  } else {
    message.warning(`复制失败，请手动复制：${spaceLink.value}`);
  }
}

function handleSearch() {
  if (!searchTerm.value) return;
  nextTick(() => {
    searchResultsRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
}

function clearSearch() {
  searchKeyword.value = '';
}

function todayString() {
  return new Date().toISOString().slice(0, 10);
}

function formatTime(value?: string) {
  if (!value) return '刚刚';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '刚刚';
  return date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function formatDate(value?: string) {
  if (!value) return '持续更新';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '持续更新';
  return date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' });
}

function formatCompactNumber(value?: number | null) {
  const count = value ?? 0;
  if (count >= 10000) return `${(count / 10000).toFixed(1)}万`;
  if (count >= 1000) return `${(count / 1000).toFixed(1)}k`;
  return String(count);
}

function postTitle(post: PostVO) {
  return post.title || '无标题帖子';
}

function postPreview(content: string) {
  const normalized = content.replace(/\s+/g, ' ').trim();
  return normalized.length > 130 ? `${normalized.slice(0, 130)}...` : normalized;
}

function memberInitial(member: SpaceMemberVO) {
  return member.user?.nickname?.charAt(0)?.toUpperCase() || 'U';
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function postShareUrl(postId?: number) {
  if (!postId) return '';
  if (typeof window === 'undefined') return `/posts/${postId}`;
  return `${window.location.origin}/posts/${postId}`;
}

function openPostShare(post: PostVO) {
  postShareTarget.value = post;
  postShareVisible.value = true;
  postActionVisible.value = false;
}

function askXiaoqingAboutPost(post?: PostVO | null) {
  if (!post) return;
  router.push({ path: '/ai', query: { mode: 'qa', postId: String(post.id) } });
}

async function copyPostLink(post?: PostVO | null) {
  const url = postShareUrl(post?.id);
  if (!url) return;
  if (await copyTextToClipboard(url)) {
    message.success('帖子链接已复制');
  } else {
    message.warning(`复制失败，请手动复制：${url}`);
  }
}

onMounted(loadSpace);
watch(() => route.params.id, () => loadSpace());
</script>

<template>
  <div class="layout-container">
    <div class="main-wrapper">
      <!-- 顶部栏 -->
      <header class="top-header">
        <div class="header-left" @click="router.push('/spaces')">
          <n-icon size="20">
            <ArrowBackOutline />
          </n-icon>
          <span>返回学习圈广场</span>
        </div>
        <div class="search-bar">
          <n-icon
            size="18"
            color="#8b949e"
          >
            <SearchOutline />
          </n-icon>
          <input
            v-model="searchKeyword"
            type="text"
            placeholder="搜索圈内内容"
            @keyup.enter="handleSearch"
          />
        </div>
        <div class="header-actions">
          <button
            class="header-action-btn"
            title="通知"
            @click="goNotifications"
          >
            <n-icon size="22">
              <NotificationsOutline />
            </n-icon>
            <span
              v-if="unreadCount > 0"
              class="header-unread-dot"
            >
              {{ unreadCount > 99 ? '99+' : unreadCount }}
            </span>
          </button>
          <button
            class="header-action-btn"
            title="页面菜单"
            @click="openActionMenu"
          >
            <n-icon size="22">
              <MenuOutline />
            </n-icon>
          </button>
          <button
            class="avatar profile-entry"
            title="个人主页"
            @click="goProfile"
          >
            <n-icon size="17">
              <PersonOutline />
            </n-icon>
          </button>
        </div>
      </header>

      <Transition name="profile-panel">
        <section
          v-if="profilePanelVisible"
          class="embedded-profile"
        >
          <header class="embedded-profile__top">
            <button
              class="embedded-profile__back"
              @click="profilePanelVisible = false"
            >
              <n-icon size="20"><ArrowBackOutline /></n-icon>
              返回学习圈
            </button>
            <div class="embedded-profile__actions">
              <button
                title="刷新个人主页"
                @click="refreshProfilePanel"
              >
                <n-icon size="18"><RefreshOutline /></n-icon>
              </button>
              <button
                title="编辑资料"
                @click="openProfileEditor"
              >
                <n-icon size="18"><SettingsOutline /></n-icon>
              </button>
            </div>
          </header>

          <div class="embedded-profile__scroll">
            <div class="embedded-profile__container">
              <section class="profile-cover-card">
                <img
                  :src="profileCoverImage"
                  alt="个人主页封面"
                  @click="openProfileEditor"
                />
                <div class="profile-cover-card__shade" />
                <div class="profile-identity">
                  <button
                    class="profile-avatar-large"
                    type="button"
                    title="修改头像"
                    @click="openProfileEditor"
                  >
                    <img
                      v-if="profileDisplay.avatarUrl"
                      :src="profileDisplay.avatarUrl"
                      :alt="profileDisplay.nickname"
                    />
                    <span v-else>{{ profileDisplay.initial }}</span>
                  </button>
                  <div class="profile-copy">
                    <div class="profile-name-row">
                      <h2>{{ profileDisplay.nickname }}</h2>
                      <span>{{ profileDisplay.role }}</span>
                    </div>
                    <p>{{ profileDisplay.title }}</p>
                    <button
                      class="profile-bio-edit"
                      type="button"
                      title="修改个人简介"
                      @click="openProfileEditor"
                    >
                      {{ profileDisplay.bio }}
                    </button>
                  </div>
                </div>
              </section>

              <div class="profile-metrics">
                <button
                  type="button"
                  @click="openProfileFollows('following')"
                >
                  <span>关注</span>
                  <strong>{{ formatCompactNumber(profileFollowCounts.following) }}</strong>
                </button>
                <button
                  type="button"
                  @click="openProfileFollows('followers')"
                >
                  <span>粉丝</span>
                  <strong>{{ formatCompactNumber(profileFollowCounts.followers) }}</strong>
                </button>
                <button
                  type="button"
                  @click="openProfileLikes"
                >
                  <span>获赞</span>
                  <strong>{{ formatCompactNumber(profileLikeCount) }}</strong>
                </button>
              </div>

              <div class="profile-content-grid">
                <main class="profile-feed">
                  <nav class="profile-tabs">
                    <button
                      v-for="tab in profileTabs"
                      :key="tab"
                      :class="{ active: profileTab === tab }"
                      @click="profileTab = tab"
                    >
                      <span>{{ tab }}</span>
                      <small>{{ profileTabCount(tab) }}</small>
                    </button>
                  </nav>

                  <article
                    v-for="item in profileFeedItems"
                    :key="item.id"
                    class="profile-feed-card glass-card"
                    :class="{ actionable: profileFeedItemActionable(item) }"
                    @click="openProfileFeedItem(item)"
                  >
                    <div class="profile-feed-card__head">
                      <div class="profile-avatar-small">
                        {{ profileDisplay.initial }}
                      </div>
                      <div>
                        <strong>{{ item.title }}</strong>
                        <span>{{ item.meta }}</span>
                      </div>
                      <span class="profile-feed-card__type">{{ profileItemTypeLabel(item.type) }}</span>
                      <button
                        v-if="profileFeedItemActionable(item)"
                        class="profile-feed-card__more"
                        type="button"
                        title="更多操作"
                        @click.stop="openProfileFeedMore(item)"
                      >
                        <n-icon>
                          <MenuOutline />
                        </n-icon>
                      </button>
                    </div>
                    <p>{{ item.description }}</p>
                    <div class="profile-attachment">
                      <n-icon size="22">
                        <DocumentTextOutline />
                      </n-icon>
                      <div>
                        <strong>{{ space?.name || '当前学习圈' }}</strong>
                        <span>{{ profileItemSourceLabel(item) }}</span>
                      </div>
                    </div>
                  </article>
                  <div
                    v-if="profileFeedItems.length === 0"
                    class="empty-inline glass-card"
                  >
                    <p>当前没有可展示的 {{ profileTab }} 数据。</p>
                    <button
                      v-if="profileTab === '帖子' || profileTab === '动态'"
                      class="outline-action"
                      @click="startProfileCompose"
                    >
                      发布圈内帖子
                    </button>
                    <button
                      v-else-if="profileTab === '打卡'"
                      class="outline-action"
                      @click="viewProfileCheckins"
                    >
                      查看圈内打卡
                    </button>
                  </div>
                </main>

                <aside class="profile-side">
                  <section class="profile-widget glass-card">
                    <div class="profile-widget__header">
                      <h3>个人成就</h3>
                      <span>{{ awardedAchievements.length }}/{{ profileAchievements.length || 0 }}</span>
                    </div>
                    <div class="profile-badges">
                      <button
                        v-for="achievement in profileAchievements.slice(0, 4)"
                        :key="achievement.id"
                        :title="achievement.name"
                        type="button"
                        :class="{ locked: !achievement.awarded }"
                        @click="profileTab = '成就'"
                      >
                        {{ achievement.name.charAt(0) }}
                      </button>
                      <div
                        v-if="profileAchievements.length === 0"
                        class="profile-widget-empty"
                      >
                        暂无成就数据
                      </div>
                    </div>
                  </section>

                  <section class="profile-widget glass-card">
                    <div class="profile-widget__header">
                      <h3>近期打卡</h3>
                      <span>连续 {{ profileCheckinStreak }} 天</span>
                    </div>
                    <div
                      v-if="profileCheckinChallenges.length"
                      class="profile-checkin-list"
                    >
                      <button
                        v-for="challenge in profileCheckinChallenges.slice(0, 3)"
                        :key="challenge.id"
                        type="button"
                        @click="openProfileChallengeInSpace"
                      >
                        <strong>{{ challenge.name }}</strong>
                        <span>累计 {{ challenge.myTotalDays || 0 }} 天 · 连续 {{ challenge.myConsecutiveDays || 0 }} 天</span>
                      </button>
                    </div>
                    <div
                      v-else
                      class="profile-widget-empty"
                    >
                      暂无圈内打卡记录
                    </div>
                  </section>
                </aside>
              </div>
            </div>
          </div>
        </section>
      </Transition>

      <NModal
        v-model:show="profileEditVisible"
        preset="card"
        title="编辑个人主页"
        class="space-modal profile-edit-modal"
        transform-origin="center"
        :style="{ width: 'min(92vw, 620px)' }"
      >
        <section class="profile-edit-panel">
          <div class="profile-edit-preview">
            <img
              :src="profileForm.profileCoverUrl || auroraBg"
              alt="个人主页封面预览"
            />
            <button
              class="profile-edit-cover-action"
              type="button"
              :disabled="Boolean(profileAssetUploading)"
              @click="openProfileAssetPicker('cover')"
            >
              {{ profileAssetUploading === 'cover' ? '上传中...' : '更换封面' }}
            </button>
            <button
              class="profile-edit-avatar"
              type="button"
              :disabled="Boolean(profileAssetUploading)"
              @click="openProfileAssetPicker('avatar')"
            >
              <img
                v-if="profileForm.avatarUrl"
                :src="profileForm.avatarUrl"
                alt="头像预览"
              />
              <span v-else>{{ profileDisplay.initial }}</span>
              <small>{{ profileAssetUploading === 'avatar' ? '上传中' : '更换头像' }}</small>
            </button>
          </div>
          <input
            ref="profileAvatarInputRef"
            class="profile-asset-input"
            type="file"
            accept="image/png,image/jpeg,image/gif,image/webp"
            @change="handleProfileAssetChange($event, 'avatar')"
          />
          <input
            ref="profileCoverInputRef"
            class="profile-asset-input"
            type="file"
            accept="image/png,image/jpeg,image/gif,image/webp"
            @change="handleProfileAssetChange($event, 'cover')"
          />
          <div class="profile-edit-grid">
            <label
              class="settings-field validated-field"
              :class="{ invalid: profileNicknameState.touched && profileNicknameState.error, shake: profileNicknameState.shaking }"
            >
              <span>昵称</span>
              <input
                v-model="profileForm.nickname"
                maxlength="12"
                @focus="focusProfileNickname"
                @blur="blurProfileNickname"
                @input="validateProfileNickname"
              />
              <small
                v-if="profileNicknameState.touched && profileNicknameState.error"
                class="field-hint error"
              >
                {{ profileNicknameState.error }}
              </small>
            </label>
            <div class="settings-field profile-upload-field">
              <span>头像</span>
              <button
                type="button"
                :disabled="Boolean(profileAssetUploading)"
                @click="openProfileAssetPicker('avatar')"
              >
                {{ profileForm.avatarUrl ? '重新选择头像' : '选择头像图片' }}
              </button>
            </div>
            <div class="settings-field wide profile-upload-field">
              <span>主页封面</span>
              <button
                type="button"
                :disabled="Boolean(profileAssetUploading)"
                @click="openProfileAssetPicker('cover')"
              >
                {{ profileForm.profileCoverUrl ? '重新选择封面' : '选择封面图片' }}
              </button>
            </div>
            <label class="settings-field">
              <span>学院</span>
              <input
                v-model="profileForm.college"
                maxlength="64"
              />
            </label>
            <label class="settings-field">
              <span>专业</span>
              <input
                v-model="profileForm.major"
                maxlength="64"
              />
            </label>
            <label class="settings-field">
              <span>年级</span>
              <input
                v-model="profileForm.grade"
                maxlength="8"
              />
            </label>
            <label class="settings-field wide">
              <span>个人简介</span>
              <textarea
                v-model="profileForm.bio"
                maxlength="255"
                placeholder="写下你的学习方向、兴趣或近期目标"
              />
            </label>
          </div>
          <div class="settings-actions">
            <button
              class="outline-action"
              type="button"
              @click="closeProfileEditor"
            >
              取消
            </button>
            <button
              class="neon-btn"
              type="button"
              :disabled="profileSaving"
              @click="submitProfileEdit"
            >
              {{ profileSaving ? '保存中...' : '保存个人主页' }}
            </button>
          </div>
        </section>
      </NModal>

      <NModal
        v-model:show="profileFollowsVisible"
        preset="card"
        :title="profileFollowsTab === 'followers' ? '粉丝' : '关注'"
        class="space-modal compact-modal"
        transform-origin="center"
        :style="{ width: '360px' }"
      >
        <div class="profile-follow-switch">
          <button
            :class="{ active: profileFollowsTab === 'following' }"
            type="button"
            @click="openProfileFollows('following')"
          >
            关注
          </button>
          <button
            :class="{ active: profileFollowsTab === 'followers' }"
            type="button"
            @click="openProfileFollows('followers')"
          >
            粉丝
          </button>
        </div>
        <div class="active-member-list">
          <article
            v-if="profileFollowsLoading"
            class="notice-modal-item"
          >
            <p>列表加载中...</p>
          </article>
          <article
            v-else-if="profileFollowUsers.length === 0"
            class="notice-modal-item"
          >
            <strong>{{ profileFollowsTab === 'followers' ? '暂无粉丝' : '暂无关注' }}</strong>
            <p>新的关系会在当前窗口内展示。</p>
          </article>
          <button
            v-for="followUser in profileFollowUsers"
            v-else
            :key="followUser.id"
            class="active-member-item"
            type="button"
            @click="openMemberProfile(followUser.id)"
          >
            <img
              v-if="followUser.avatarUrl"
              :src="followUser.avatarUrl"
              :alt="followUser.nickname"
            />
            <span v-else class="member-avatar-fallback">{{ followUser.nickname.charAt(0).toUpperCase() }}</span>
            <span class="member-copy">
              <strong>{{ followUser.nickname }}</strong>
              <small>{{ [followUser.college, followUser.major].filter(Boolean).join(' · ') || followUser.bio || '校园学习者' }}</small>
            </span>
          </button>
        </div>
      </NModal>

      <NModal
        v-model:show="profileLikesVisible"
        preset="card"
        title="获赞明细"
        class="space-modal compact-modal"
        transform-origin="center"
        :style="{ width: '420px' }"
      >
        <div class="profile-stat-summary">
          <span>当前学习圈获赞</span>
          <strong>{{ formatCompactNumber(profileLikeCount) }}</strong>
          <p>统计你在本学习圈发布帖子的点赞数。</p>
        </div>
        <div class="profile-stat-list">
          <article
            v-if="profileLikedPosts.length === 0"
            class="notice-modal-item"
          >
            <strong>暂无获赞记录</strong>
            <p>发布圈内帖子并获得点赞后，会在这里展示明细。</p>
          </article>
          <button
            v-for="post in profileLikedPosts"
            v-else
            :key="post.id"
            class="profile-stat-item"
            type="button"
            @click="profileLikesVisible = false; goPost(post.id)"
          >
            <span class="stat-item-copy">
              <strong>{{ postTitle(post) }}</strong>
              <small>{{ formatTime(post.createdAt) }} · {{ post.commentCount }} 评论</small>
            </span>
            <span class="stat-item-value">{{ formatCompactNumber(post.likeCount) }} 赞</span>
          </button>
        </div>
      </NModal>

      <NModal
        v-model:show="memberProfileVisible"
        preset="card"
        title="圈内成员资料"
        class="space-modal member-profile-modal"
        transform-origin="center"
        :style="{ width: 'min(92vw, 760px)' }"
      >
        <section class="member-profile-panel">
          <div
            v-if="memberProfileLoading"
            class="member-profile-loading"
          >
            <n-spin size="small" />
            <span>正在拉取成员资料...</span>
          </div>
          <template v-else-if="memberProfile">
            <div class="member-profile-cover">
              <img
                :src="memberProfileDisplay.coverUrl"
                alt="成员主页封面"
              />
              <div class="member-profile-cover__shade" />
              <div class="member-profile-identity">
                <div class="member-profile-avatar">
                  <img
                    v-if="memberProfileDisplay.avatarUrl"
                    :src="memberProfileDisplay.avatarUrl"
                    :alt="memberProfileDisplay.nickname"
                  />
                  <span v-else>{{ memberProfileDisplay.initial }}</span>
                </div>
                <div>
                  <div class="member-profile-name">
                    <h3>{{ memberProfileDisplay.nickname }}</h3>
                    <span>{{ memberProfileDisplay.role }}</span>
                  </div>
                  <p>{{ memberProfileDisplay.title }}</p>
                </div>
              </div>
            </div>

            <div class="member-profile-metrics">
              <article>
                <span>关注</span>
                <strong>{{ formatCompactNumber(memberProfileFollowCounts.following) }}</strong>
              </article>
              <article>
                <span>粉丝</span>
                <strong>{{ formatCompactNumber(memberProfileFollowCounts.followers) }}</strong>
              </article>
              <article>
                <span>圈内获赞</span>
                <strong>{{ formatCompactNumber(memberProfileLikeCount) }}</strong>
              </article>
            </div>

            <div class="member-profile-body">
              <main>
                <section class="member-profile-section">
                  <div class="member-profile-section__head">
                    <h4>资料简介</h4>
                    <button
                      type="button"
                      :disabled="memberProfileFollowSubmitting"
                      @click="toggleMemberProfileFollow"
                    >
                      {{ memberProfileFollowSubmitting ? '处理中...' : (memberProfileFollowing ? '已关注' : '关注') }}
                    </button>
                  </div>
                  <p>{{ memberProfileDisplay.bio }}</p>
                  <small>加入时间 {{ formatDate(memberProfileDisplay.joinedAt) }}</small>
                </section>

                <section class="member-profile-section">
                  <div class="member-profile-section__head">
                    <h4>圈内帖子</h4>
                    <span>{{ memberProfilePosts.length }}</span>
                  </div>
                  <button
                    v-for="post in memberProfilePosts.slice(0, 3)"
                    :key="post.id"
                    class="member-profile-post"
                    type="button"
                    @click="openMemberPostInSpace(post.id)"
                  >
                    <strong>{{ postTitle(post) }}</strong>
                    <span>{{ formatTime(post.createdAt) }} · {{ post.likeCount }} 赞 · {{ post.commentCount }} 评论</span>
                  </button>
                  <div
                    v-if="memberProfilePosts.length === 0"
                    class="profile-widget-empty"
                  >
                    这个成员还没有在当前学习圈发帖
                  </div>
                </section>
              </main>

              <aside class="member-profile-section">
                <div class="member-profile-section__head">
                  <h4>成就</h4>
                  <span>{{ memberProfileAchievements.filter((item) => item.awarded).length }}/{{ memberProfileAchievements.length }}</span>
                </div>
                <div class="member-profile-badges">
                  <button
                    v-for="achievement in memberProfileAchievements.slice(0, 6)"
                    :key="achievement.id"
                    type="button"
                    :class="{ locked: !achievement.awarded }"
                    :title="achievement.name"
                  >
                    {{ achievement.name.charAt(0) }}
                  </button>
                  <div
                    v-if="memberProfileAchievements.length === 0"
                    class="profile-widget-empty"
                  >
                    暂无成就数据
                  </div>
                </div>
              </aside>
            </div>
          </template>
          <div
            v-else
            class="profile-widget-empty"
          >
            暂时无法加载这个成员的资料
          </div>
        </section>
      </NModal>

      <NModal
        v-model:show="postDetailVisible"
        preset="card"
        title="圈内帖子详情"
        class="space-modal post-detail-modal"
        transform-origin="center"
        :style="{ width: 'min(94vw, 860px)' }"
      >
        <section class="post-detail-panel">
          <div
            v-if="postDetailLoading"
            class="profile-widget-empty detail-loading"
          >
            <n-spin size="medium" />
            <span>帖子加载中...</span>
          </div>
          <template v-else-if="postDetail">
            <article class="detail-post-card">
              <header class="detail-post-head">
                <button
                  class="detail-author"
                  type="button"
                  @click="openMemberProfileFromPostDetail(postDetail.authorId)"
                >
                  <span class="detail-avatar">{{ postDetailAuthorName.charAt(0).toUpperCase() }}</span>
                  <span>
                    <strong>{{ postDetailAuthorName }}</strong>
                    <small>{{ formatTime(postDetail.createdAt) }} · 阅读 {{ postDetail.viewCount }}</small>
                  </span>
                </button>
                <div class="detail-badges">
                  <span v-if="postDetail.isEssence === 1">精华</span>
                  <span v-if="postDetail.type === 'QA'">问答</span>
                </div>
              </header>
              <h3>{{ postTitle(postDetail) }}</h3>
              <p class="detail-content">{{ postDetail.content }}</p>
              <div
                v-if="postDetail.topics?.length || postDetail.tags?.length"
                class="detail-tags"
              >
                <span
                  v-for="topic in postDetail.topics"
                  :key="`topic-${topic}`"
                >
                  #{{ topic }}
                </span>
                <span
                  v-for="tag in postDetail.tags"
                  :key="`tag-${tag}`"
                >
                  {{ tag }}
                </span>
              </div>
              <div
                v-if="postDetail.type === 'QA' && postDetailQa"
                class="detail-qa-strip"
              >
                <strong>问答</strong>
                <span>{{ postDetailQa.isSolved ? '已采纳答案' : '等待回答' }}</span>
              </div>
              <div class="detail-actions">
                <button
                  type="button"
                  :class="{ active: postDetail.liked }"
                  :disabled="postDetailLikeSubmitting"
                  @click="togglePostDetailLike"
                >
                  <n-icon><ThumbsUpOutline /></n-icon>
                  {{ postDetail.liked ? '已赞' : '点赞' }} {{ postDetail.likeCount }}
                </button>
                <button
                  type="button"
                  @click="nextTick(() => postDetailCommentInputRef?.focus())"
                >
                  <n-icon><ChatboxOutline /></n-icon>
                  评论 {{ postDetail.commentCount }}
                </button>
                <button
                  type="button"
                  @click="askXiaoqingAboutPost(postDetail)"
                >
                  <n-icon><DocumentTextOutline /></n-icon>
                  问小青
                </button>
              </div>
            </article>

            <section class="detail-comment-editor">
              <div
                v-if="postDetailReplyTo"
                class="detail-reply-banner"
              >
                正在回复 @{{ postDetailReplyTo.nickname }}
                <button
                  type="button"
                  @click="cancelPostDetailReply"
                >
                  取消
                </button>
              </div>
              <textarea
                ref="postDetailCommentInputRef"
                v-model="postDetailCommentText"
                placeholder="写下你的想法，参与当前学习圈讨论"
                maxlength="2000"
              />
              <div class="detail-editor-actions">
                <button
                  class="outline-action"
                  type="button"
                  @click="cancelPostDetailReply"
                >
                  清空
                </button>
                <button
                  class="neon-btn"
                  type="button"
                  :disabled="postDetailSubmitting || !postDetailCommentText.trim()"
                  @click="submitPostDetailComment"
                >
                  {{ postDetailSubmitting ? '发布中...' : '发布评论' }}
                </button>
              </div>
            </section>

            <section class="detail-comments">
              <header>
                <h4>评论</h4>
                <span>{{ postDetailComments.length }} 条</span>
              </header>
              <article
                v-if="postDetailComments.length === 0"
                class="profile-widget-empty"
              >
                还没有评论，来抢首评吧
              </article>
              <article
                v-for="comment in postDetailComments"
                v-else
                :key="comment.id"
                class="detail-comment"
              >
                <div class="detail-comment-main">
                  <button
                    class="detail-avatar small"
                    type="button"
                    @click="openMemberProfileFromPostDetail(comment.authorId)"
                  >
                    {{ (comment.author?.nickname || '匿').charAt(0).toUpperCase() }}
                  </button>
                  <div class="detail-comment-body">
                    <div class="detail-comment-head">
                      <strong>{{ comment.author?.nickname || '匿名用户' }}</strong>
                      <span>{{ formatTime(comment.createdAt) }}</span>
                    </div>
                    <p>{{ comment.content }}</p>
                    <div class="detail-comment-actions">
                      <button
                        type="button"
                        @click="replyPostDetailComment(comment)"
                      >
                        回复
                      </button>
                      <button
                        type="button"
                        @click="likePostDetailComment(comment)"
                      >
                        点赞 {{ comment.likeCount || 0 }}
                      </button>
                      <button
                        v-if="isPostDetailAuthor && postDetail.type === 'QA' && !postDetailQa?.isSolved"
                        type="button"
                        :disabled="postDetailAcceptingId === comment.id"
                        @click="acceptPostDetailAnswer(comment.id)"
                      >
                        {{ postDetailAcceptingId === comment.id ? '采纳中...' : '采纳' }}
                      </button>
                    </div>
                    <div
                      v-if="comment.replies?.length"
                      class="detail-replies"
                    >
                      <article
                        v-for="reply in comment.replies"
                        :key="reply.id"
                        class="detail-reply"
                      >
                        <strong>{{ reply.author?.nickname || '匿名用户' }}</strong>
                        <span>{{ formatTime(reply.createdAt) }}</span>
                        <p>{{ reply.content }}</p>
                        <button
                          type="button"
                          @click="replyPostDetailComment(reply)"
                        >
                          回复
                        </button>
                      </article>
                    </div>
                  </div>
                </div>
              </article>
            </section>
          </template>
          <div
            v-else
            class="profile-widget-empty"
          >
            暂时无法加载这个帖子
          </div>
        </section>
      </NModal>

      <NModal
        v-model:show="actionMenuVisible"
        preset="card"
        title="圈子快捷操作"
        class="space-modal compact-modal"
        transform-origin="center"
        :style="{ width: '320px' }"
      >
        <div class="quick-actions">
          <button @click="openCompose">
            <n-icon><SparklesOutline /></n-icon>
            发布圈内帖子
          </button>
          <button @click="goUploadResource">
            <n-icon><DocumentTextOutline /></n-icon>
            上传圈内文件
          </button>
          <button @click="openActiveMembers">
            <n-icon><PeopleOutline /></n-icon>
            查看活跃成员
          </button>
          <button @click="activeTab = '设置'; actionMenuVisible = false">
            <n-icon><SettingsOutline /></n-icon>
            圈子设置
          </button>
        </div>
      </NModal>

      <NModal
        v-model:show="postActionVisible"
        preset="card"
        title="帖子操作"
        class="space-modal compact-modal"
        transform-origin="center"
        :style="{ width: '320px' }"
      >
        <div class="quick-actions">
          <button
            :disabled="!postActionTarget"
            @click="openPostDetailFromActions(postActionTarget)"
          >
            <n-icon>
              <ChatboxOutline />
            </n-icon>
            查看帖子详情
          </button>
          <button
            :disabled="!postActionTarget || postActionSubmitting === postActionTarget?.id"
            @click="togglePostCollect(postActionTarget)"
          >
            <n-icon>
              <LibraryOutline />
            </n-icon>
            {{ postActionTarget?.collected ? '取消收藏' : '收藏帖子' }}
          </button>
          <button
            :disabled="!postActionTarget"
            @click="postActionTarget && openPostShare(postActionTarget)"
          >
            <n-icon>
              <ShareSocialOutline />
            </n-icon>
            分享帖子
          </button>
          <button
            :disabled="!postActionTarget"
            @click="askXiaoqingAboutPost(postActionTarget)"
          >
            <n-icon>
              <DocumentTextOutline />
            </n-icon>
            问小青
          </button>
          <button
            :disabled="!postActionTarget"
            @click="copyPostLink(postActionTarget)"
          >
            <n-icon>
              <CopyOutline />
            </n-icon>
            复制链接
          </button>
        </div>
      </NModal>

      <NModal
        v-model:show="postShareVisible"
        preset="card"
        title="分享帖子"
        class="space-modal tiny-modal"
        transform-origin="center"
        :style="{ width: '320px' }"
      >
        <div class="notice-modal-list">
          <article class="post-share-preview">
            <strong>{{ postShareTarget?.title || '无标题帖子' }}</strong>
            <p>{{ postPreview(postShareTarget?.content || '') }}</p>
            <span>{{ postShareUrl(postShareTarget?.id) }}</span>
          </article>
          <button
            class="outline-action"
            type="button"
            :disabled="!postShareTarget"
            @click="copyPostLink(postShareTarget)"
          >
            复制链接
          </button>
          <button
            class="neon-btn"
            type="button"
            :disabled="!postShareTarget"
            @click="openPostDetailFromActions(postShareTarget)"
          >
            打开帖子详情
          </button>
        </div>
      </NModal>

      <NModal
        v-model:show="notificationVisible"
        preset="card"
        title="通知"
        class="space-modal tiny-modal"
        transform-origin="center"
        :style="{ width: '300px' }"
      >
        <div class="notice-modal-list">
          <article
            v-if="notificationLoading"
            class="notice-modal-item"
          >
            <p>通知加载中...</p>
          </article>
          <article
            v-for="notification in notifications"
            :key="notification.id"
            class="notice-modal-item"
            :class="{ unread: !notification.isRead }"
            @click="openNotification(notification)"
          >
            <strong>{{ notification.title }}</strong>
            <p>{{ notification.content }}</p>
            <span>{{ formatDate(notification.createdAt) }}</span>
          </article>
          <article
            v-if="!notificationLoading && notifications.length === 0"
            class="notice-modal-item"
          >
            <strong>暂无新的圈内通知</strong>
            <p>评论、回复与成员动态会汇总在这里。</p>
            <button
              class="mini-link-btn"
              @click="router.push('/notifications')"
            >
              打开通知中心
            </button>
          </article>
        </div>
      </NModal>

      <NModal
        v-model:show="noticeVisible"
        preset="card"
        title="圈子公告"
        class="space-modal compact-modal"
        transform-origin="center"
        :style="{ width: '320px' }"
      >
        <div class="notice-modal-list">
          <article class="notice-modal-item">
            <strong>圈子公告</strong>
            <p>{{ noticeText }}</p>
            <span>{{ formatDate(space?.createdAt) }}</span>
          </article>
          <article
            v-if="sensitiveWordList.length > 0"
            class="notice-modal-item"
          >
            <strong>发帖敏感词</strong>
            <p>{{ sensitiveWordList.join('、') }}</p>
            <span>仅圈内管理可修改</span>
          </article>
          <article
            v-else
            class="notice-modal-item"
          >
            <strong>暂无更多公告</strong>
            <p>新的圈子公告会在这里集中展示。</p>
            <span>持续更新</span>
          </article>
        </div>
      </NModal>

      <NModal
        v-model:show="activeMembersVisible"
        preset="card"
        title="活跃成员"
        class="space-modal compact-modal"
        transform-origin="center"
        :style="{ width: '330px' }"
      >
        <div class="active-member-list">
          <article
            v-if="activeMemberList.length === 0"
            class="notice-modal-item"
          >
            <strong>暂无成员数据</strong>
            <p>成员列表会在加入学习圈后同步展示。</p>
          </article>
          <button
            v-for="member in activeMemberList"
            :key="member.id"
            class="active-member-item"
            @click="openMemberProfile(member.id)"
          >
            <img
              v-if="member.avatarUrl"
              :src="member.avatarUrl"
              :alt="member.nickname"
            />
            <span v-else class="member-avatar-fallback">{{ member.nickname.charAt(0).toUpperCase() }}</span>
            <span class="member-copy">
              <strong>{{ member.nickname }}</strong>
              <small>{{ member.role }}</small>
            </span>
          </button>
        </div>
      </NModal>

      <NModal
        v-model:show="uploadVisible"
        :show-icon="false"
        :mask-closable="!uploadSubmitting"
        :auto-focus="false"
        preset="card"
        class="upload-modal"
        transform-origin="center"
        :style="{ width: 'min(92vw, 540px)' }"
      >
        <section class="upload-panel">
          <div class="upload-panel__glow" />
          <header class="upload-panel__header">
            <div>
              <span class="upload-eyebrow">{{ space?.name || '当前学习圈' }}</span>
              <h2>圈内资料夹</h2>
            </div>
            <button
              type="button"
              class="upload-close"
              :disabled="uploadSubmitting"
              @click="closeUploadResource"
            >
              ×
            </button>
          </header>

          <input
            ref="uploadInputRef"
            class="upload-native-input"
            type="file"
            :accept="uploadAccept"
            @change="handleUploadFileChange"
          />
          <button
            type="button"
            class="upload-dropzone"
            :class="{ active: uploadDropActive, selected: uploadFile }"
            @click="openUploadFilePicker"
            @dragenter.prevent="uploadDropActive = true"
            @dragover.prevent="uploadDropActive = true"
            @dragleave.prevent="uploadDropActive = false"
            @drop.prevent="handleUploadDrop"
          >
            <span class="upload-folder">
              <n-icon size="28"><DocumentTextOutline /></n-icon>
            </span>
            <span class="upload-drop-copy">
              <strong>{{ uploadFile ? uploadFile.name : '选择或拖入一份资料' }}</strong>
              <small>{{ uploadFile ? formatFileSize(uploadFile.size) : 'PDF、Office、压缩包与常见图片，最大 50MB' }}</small>
            </span>
          </button>

          <div class="upload-form-grid">
            <label class="upload-field wide">
              <span>标签 / 主题</span>
              <NDynamicTags
                :value="uploadTags"
                :max="8"
                type="info"
                round
                class="space-upload-tags"
                :input-props="{ placeholder: '输入标签后回车' }"
                @update:value="handleUploadTagsUpdate"
              />
            </label>
            <label class="upload-field wide">
              <span>备注</span>
              <textarea
                v-model="uploadDescription"
                maxlength="500"
                placeholder="这份资料适合谁、覆盖哪些重点..."
              />
            </label>
          </div>
          <div class="tag-hint">
            最多 8 个标签；粘贴空格、逗号或 # 分隔的文本会自动拆分。
          </div>

          <footer class="upload-actions">
            <button
              type="button"
              class="upload-secondary"
              :disabled="uploadSubmitting"
              @click="closeUploadResource"
            >
              收起
            </button>
            <button
              type="button"
              class="upload-primary"
              :disabled="uploadSubmitting"
              @click="submitSpaceResource"
            >
              {{ uploadSubmitting ? '上传中...' : '上传到圈子' }}
            </button>
          </footer>
        </section>
      </NModal>

      <NModal
        v-model:show="composeVisible"
        :show-icon="false"
        :mask-closable="false"
        :auto-focus="false"
        :block-scroll="true"
        preset="card"
        class="compose-modal"
        transform-origin="center"
        :style="{ width: 'min(92vw, 640px)' }"
      >
        <div class="paper-stage">
          <div class="pen-illustration resting-pen" aria-hidden="true">
            <span class="pen-cap" />
            <span class="pen-body" />
            <span class="pen-tip" />
          </div>
          <section
            ref="paperSheetRef"
            class="paper-sheet"
            :class="{ 'is-rippling': paperRipple }"
            @pointerdown="createPaperRipple"
          >
            <div
              class="pen-illustration writing-pen"
              :class="{ 'is-writing': writingPen }"
              :style="{ left: `${writingPenPoint.x}px`, top: `${writingPenPoint.y}px` }"
              aria-hidden="true"
            >
              <span class="pen-cap" />
              <span class="pen-body" />
              <span class="pen-tip" />
            </div>
            <span
              v-if="paperRipple"
              :key="paperRipple.key"
              class="paper-ripple"
              :style="{ left: `${paperRipple.x}px`, top: `${paperRipple.y}px` }"
            />
            <span
              v-if="inkStroke"
              :key="inkStroke.key"
              class="ink-stroke"
              :style="{ left: `${inkStroke.x}px`, top: `${inkStroke.y}px` }"
            >
              {{ inkStroke.char }}
            </span>
            <div class="paper-header">
              <div>
                <span class="handwritten-label">A fresh page for {{ space?.name || 'this circle' }}</span>
                <h2>写给这个学习圈</h2>
              </div>
              <button
                type="button"
                class="paper-close"
                @pointerdown.stop.prevent="closeCompose"
                @click.stop.prevent="closeCompose"
              >
                ×
              </button>
            </div>

            <div class="paper-mode">
              <button
                :class="{ active: composeType === 'NORMAL' }"
                @click="composeType = 'NORMAL'"
              >
                随笔
              </button>
              <button
                :class="{ active: composeType === 'QA' }"
                @click="composeType = 'QA'"
              >
                问答
              </button>
            </div>

            <input
              v-model="composeTitle"
              class="paper-title-input"
              placeholder="给这页纸写个标题"
              @input="handlePaperInput"
            />
            <textarea
              v-model="composeContent"
              class="paper-content-input"
              placeholder="把问题、想法或学习记录写在这里..."
              @input="handlePaperInput"
            />

            <div class="paper-topics">
              <input
                v-model="composeTopicInput"
                placeholder="添加话题后回车"
                @input="handlePaperInput"
                @keyup.enter="addComposeTopic"
              />
              <button @click="addComposeTopic">
                写上
              </button>
            </div>
            <div
              v-if="composeTopics.length"
              class="paper-topic-list"
            >
              <button
                v-for="topic in composeTopics"
                :key="topic"
                @click="removeComposeTopic(topic)"
              >
                #{{ topic }}
              </button>
            </div>

            <div class="paper-actions">
              <button
                class="paper-cancel"
                @click="closeCompose"
              >
                收起纸页
              </button>
              <button
                class="paper-submit"
                :disabled="composeSubmitting"
                @click="submitCompose"
              >
                {{ composeSubmitting ? '墨迹风干中...' : '发布到圈子' }}
              </button>
            </div>
          </section>
        </div>
      </NModal>

      <div
        ref="contentScrollRef"
        class="content-scroll"
      >
        <template v-if="loading">
          <div class="loading">
            <n-spin size="large" />
          </div>
        </template>
        <template v-else-if="space">
          <div class="space-layout">
            <!-- 主内容列 -->
            <div class="center-col">
              <!-- 圈子头图 -->
              <div class="space-banner glass-card">
                <div class="banner-bg" />
                <div class="banner-content">
                  <div class="space-icon">
                    <n-icon
                      size="36"
                      color="white"
                    >
                      <LibraryOutline />
                    </n-icon>
                  </div>
                  <div class="space-info">
                    <div class="title-row">
                      <h2>{{ space.name }}</h2>
                      <span class="tag">{{ visibilityLabel }}</span>
                    </div>
                    <p class="desc">
                      {{ space.description || '圈主还没有填写简介。' }}
                    </p>
                    <div class="stats">
                      成员 {{ formatCompactNumber(space.memberCount) }} <span class="dot">·</span>
                      帖子 {{ formatCompactNumber(space.postCount) }} <span class="dot">·</span>
                      文件 {{ formatCompactNumber(spaceResources.length) }} <span class="dot">·</span>
                      打卡 {{ formatCompactNumber(checkinChallenges.length) }}
                    </div>
                  </div>
                  <div class="banner-actions">
                    <button
                      v-if="!space.isMember"
                      class="outline-action header-btn"
                      :disabled="spaceActionSubmitting"
                      @click="toggleSpaceMembership"
                    >
                      {{ spaceActionSubmitting ? '处理中...' : '加入圈子' }}
                    </button>
                    <button
                      v-else-if="canLeaveSpace"
                      class="outline-action header-btn"
                      :disabled="spaceActionSubmitting"
                      @click="toggleSpaceMembership"
                    >
                      {{ spaceActionSubmitting ? '处理中...' : '退出圈子' }}
                    </button>
                    <button
                      class="neon-btn header-btn"
                      @click="goCreatePost"
                    >
                      + 发布帖子
                    </button>
                  </div>
                </div>
              </div>

              <section
                v-if="searchTerm"
                ref="searchResultsRef"
                class="space-search-panel glass-card"
              >
                <header class="space-search-head">
                  <div>
                    <h3>圈内搜索结果</h3>
                    <p>关键词「{{ searchTerm }}」，当前学习圈内共找到 {{ searchTotalCount }} 项。</p>
                  </div>
                  <button
                    class="outline-action"
                    type="button"
                    @click="clearSearch"
                  >
                    清空搜索
                  </button>
                </header>

                <div class="space-search-summary">
                  <article>
                    <span>帖子</span>
                    <strong>{{ searchPosts.length }}</strong>
                  </article>
                  <article>
                    <span>文件</span>
                    <strong>{{ searchResources.length }}</strong>
                  </article>
                  <article>
                    <span>成员</span>
                    <strong>{{ searchMembers.length }}</strong>
                  </article>
                  <article>
                    <span>打卡</span>
                    <strong>{{ searchChallenges.length }}</strong>
                  </article>
                </div>

                <div
                  v-if="searchTotalCount === 0"
                  class="empty-inline glass-card"
                >
                  <p>当前学习圈里没有匹配内容。</p>
                  <button
                    class="neon-btn"
                    type="button"
                    @click="clearSearch"
                  >
                    换个关键词
                  </button>
                </div>

                <div
                  v-else
                  class="space-search-groups"
                >
                  <section
                    v-if="searchPosts.length"
                    class="space-search-group"
                  >
                    <header>
                      <h4>匹配帖子</h4>
                      <span>{{ searchPosts.length }} 条</span>
                    </header>
                    <article
                      v-for="post in searchPosts"
                      :key="`post-${post.id}`"
                      class="compact-data-row search-result-row"
                      @click="goPost(post.id)"
                    >
                      <div>
                        <strong>{{ postTitle(post) }}</strong>
                        <span>{{ post.author?.nickname || '匿名用户' }} · {{ formatTime(post.createdAt) }}</span>
                      </div>
                      <small>{{ post.likeCount }} 赞 / {{ post.commentCount }} 评</small>
                    </article>
                  </section>

                  <section
                    v-if="searchResources.length"
                    class="space-search-group"
                  >
                    <header>
                      <h4>匹配文件</h4>
                      <span>{{ searchResources.length }} 份</span>
                    </header>
                    <article
                      v-for="resource in searchResources"
                      :key="`resource-${resource.id}`"
                      class="compact-data-row search-result-row"
                      @click="goResource(resource.id)"
                    >
                      <div>
                        <strong>{{ resource.fileName }}</strong>
                        <span>{{ resource.uploader?.nickname || '未知上传者' }} · {{ formatTime(resource.createdAt) }}</span>
                      </div>
                      <small>{{ formatFileSize(resource.fileSize) }}</small>
                    </article>
                  </section>

                  <section
                    v-if="searchMembers.length"
                    class="space-search-group"
                  >
                    <header>
                      <h4>匹配成员</h4>
                      <span>{{ searchMembers.length }} 人</span>
                    </header>
                    <button
                      v-for="member in searchMembers"
                      :key="`member-${member.userId}`"
                      class="active-member-item search-member-row"
                      type="button"
                      @click="openMemberProfile(member.userId)"
                    >
                      <img
                        v-if="member.user?.avatarUrl"
                        :src="member.user.avatarUrl"
                        :alt="member.user?.nickname || '圈内成员'"
                      />
                      <span v-else class="member-avatar-fallback">{{ memberInitial(member) }}</span>
                      <span class="member-copy">
                        <strong>{{ member.user?.nickname || '未知用户' }}</strong>
                        <small>{{ member.role === 'OWNER' ? '圈主' : (member.role === 'ADMIN' ? '管理员' : '成员') }} · {{ formatDate(member.joinedAt) }} 加入</small>
                      </span>
                    </button>
                  </section>

                  <section
                    v-if="searchChallenges.length"
                    class="space-search-group"
                  >
                    <header>
                      <h4>匹配打卡</h4>
                      <span>{{ searchChallenges.length }} 个</span>
                    </header>
                    <article
                      v-for="challenge in searchChallenges"
                      :key="`challenge-${challenge.id}`"
                      class="compact-data-row search-result-row"
                      @click="goChallenge(challenge.id)"
                    >
                      <div>
                        <strong>{{ challenge.name }}</strong>
                        <span>{{ challenge.description || '这个打卡挑战还没有简介。' }}</span>
                      </div>
                      <small>{{ challenge.memberCount }} 人参与</small>
                    </article>
                  </section>
                </div>
              </section>

              <!-- 圈子分页 -->
              <div class="space-tabs">
                <div
                  v-for="tab in tabs"
                  :key="tab"
                  class="tab-item"
                  :class="{ active: activeTab === tab }"
                  @click="activeTab = tab"
                >
                  {{ tab }}
                </div>
              </div>

              <div
                v-if="activeTab === '首页'"
                class="home-view"
              >
                <div class="overview-grid">
                  <button
                    class="overview-card"
                    @click="activeTab = '帖子'"
                  >
                    <span>帖子</span>
                    <strong>{{ formatCompactNumber(posts.length) }}</strong>
                    <small>当前已加载讨论</small>
                  </button>
                  <button
                    class="overview-card"
                    @click="activeTab = '文件'"
                  >
                    <span>文件</span>
                    <strong>{{ formatCompactNumber(spaceResources.length) }}</strong>
                    <small>圈内资料</small>
                  </button>
                  <button
                    class="overview-card"
                    @click="activeTab = '成员'"
                  >
                    <span>成员</span>
                    <strong>{{ formatCompactNumber(space.memberCount) }}</strong>
                    <small>活跃成员数据</small>
                  </button>
                  <button
                    class="overview-card"
                    @click="activeTab = '打卡'"
                  >
                    <span>打卡</span>
                    <strong>{{ formatCompactNumber(activeChallenges.length) }}</strong>
                    <small>进行中的挑战</small>
                  </button>
                </div>

                <section class="home-section">
                  <header>
                    <div>
                      <h3>最新讨论</h3>
                      <span>圈内最新帖子</span>
                    </div>
                    <button @click="activeTab = '帖子'">
                      查看全部
                    </button>
                  </header>
                  <article
                    v-for="post in latestPosts"
                    :key="post.id"
                    class="compact-data-row"
                    @click="goPost(post.id)"
                  >
                    <div>
                      <strong>{{ postTitle(post) }}</strong>
                      <span>{{ post.author?.nickname || '匿名用户' }} · {{ formatTime(post.createdAt) }}</span>
                    </div>
                    <small>{{ post.likeCount }} 赞 / {{ post.commentCount }} 评</small>
                  </article>
                  <div
                    v-if="latestPosts.length === 0"
                    class="empty-inline glass-card"
                  >
                    <p>这个圈子还没有帖子。</p>
                    <button
                      class="neon-btn"
                      @click="goCreatePost"
                    >
                      发布第一篇帖子
                    </button>
                  </div>
                </section>

                <section class="home-section">
                  <header>
                    <div>
                      <h3>资料更新</h3>
                      <span>按上传时间展示</span>
                    </div>
                    <button @click="activeTab = '文件'">
                      进入文件
                    </button>
                  </header>
                  <article
                    v-for="resource in recentResources"
                    :key="resource.id"
                    class="compact-data-row"
                    @click="goResource(resource.id)"
                  >
                    <div>
                      <strong>{{ resource.fileName }}</strong>
                      <span>{{ resource.uploader?.nickname || '未知上传者' }} · {{ formatTime(resource.createdAt) }}</span>
                    </div>
                    <small>{{ formatFileSize(resource.fileSize) }}</small>
                  </article>
                  <div
                    v-if="recentResources.length === 0"
                    class="empty-inline glass-card"
                  >
                    <p>圈内暂无文件资料。</p>
                    <button
                      class="neon-btn"
                      @click="goUploadResource"
                    >
                      上传资料
                    </button>
                  </div>
                </section>

                <section class="home-section">
                  <header>
                    <div>
                      <h3>进行中打卡</h3>
                      <span>绑定当前学习圈的打卡挑战</span>
                    </div>
                    <button @click="activeTab = '打卡'">
                      查看打卡
                    </button>
                  </header>
                  <article
                    v-for="challenge in activeChallenges.slice(0, 3)"
                    :key="challenge.id"
                    class="compact-data-row"
                    @click="goChallenge(challenge.id)"
                  >
                    <div>
                      <strong>{{ challenge.name }}</strong>
                      <span>{{ challenge.startDate }} ~ {{ challenge.endDate }}</span>
                    </div>
                    <small>{{ challenge.memberCount }} 人参与</small>
                  </article>
                  <div
                    v-if="activeChallenges.length === 0"
                    class="empty-inline glass-card"
                  >
                    <p>圈内暂无进行中的打卡挑战。</p>
                    <button
                      class="neon-btn"
                      @click="openChallengeForm"
                    >
                      创建挑战
                    </button>
                  </div>
                </section>
              </div>

              <div v-if="activeTab === '帖子'" class="post-filters">
                <span
                  :class="{ active: postSort === 'latest' }"
                  @click="postSort = 'latest'"
                >
                  最新
                </span>
                <span
                  :class="{ active: postSort === 'hot' }"
                  @click="postSort = 'hot'"
                >
                  热门
                </span>
                <span
                  :class="{ active: postSort === 'essence' }"
                  @click="postSort = 'essence'"
                >
                  精华
                </span>
              </div>

              <div v-if="['帖子', '精华'].includes(activeTab)" class="post-list">
                <div
                  v-for="post in visiblePosts"
                  :key="post.id"
                  class="post-item glass-card"
                  @click="goPost(post.id)"
                >
                  <div class="post-author">
                    <button
                      class="avatar author-avatar"
                      @click.stop="openMemberProfile(post.authorId)"
                    >
                      {{ post.author?.nickname?.charAt(0)?.toUpperCase() || 'U' }}
                    </button>
                    <div class="author-info">
                      <span
                        class="name"
                        @click.stop="openMemberProfile(post.authorId)"
                      >
                        {{ post.author?.nickname || '匿名用户' }}
                      </span>
                      <span class="time">{{ formatTime(post.createdAt) }}</span>
                    </div>
                    <button
                      class="post-more-btn"
                      type="button"
                      title="帖子操作"
                      @click.stop="openPostActions(post)"
                    >
                      <n-icon>
                        <MenuOutline />
                      </n-icon>
                    </button>
                  </div>
                  <h3 class="post-title">
                    {{ postTitle(post) }}
                    <span
                      v-if="post.type === 'QA'"
                      class="tag blue"
                    >
                      求助
                    </span>
                    <span
                      v-if="post.isEssence === 1"
                      class="tag purple"
                    >
                      精华
                    </span>
                  </h3>
                  <p class="post-preview">
                    {{ postPreview(post.content) }}
                  </p>
                  <PostAiCardLine
                    :post-id="post.id"
                    :card="aiCards[String(post.id)]"
                    :post="post"
                    @visible="handleAiCardVisible"
                    @hidden="handleAiCardHidden"
                  />
                  <div
                    class="post-actions"
                    @click.stop
                  >
                    <button
                      class="action"
                      type="button"
                      :class="{ active: post.liked }"
                      :disabled="postListLikeSubmitting === post.id"
                      :aria-pressed="post.liked"
                      @click.stop="togglePostListLike(post)"
                    >
                      <n-icon><ThumbsUpOutline /></n-icon>
                      {{ post.likeCount }}
                    </button>
                    <button
                      class="action"
                      type="button"
                      @click.stop="goPost(post.id)"
                    >
                      <n-icon><ChatboxOutline /></n-icon>
                      {{ post.commentCount }}
                    </button>
                    <button
                      class="action right"
                      type="button"
                      @click.stop="openPostShare(post)"
                    >
                      <n-icon><ShareSocialOutline /></n-icon> 分享
                    </button>
                    <button
                      class="action right"
                      type="button"
                      @click.stop="askXiaoqingAboutPost(post)"
                    >
                      <n-icon><DocumentTextOutline /></n-icon> 问小青
                    </button>
                  </div>
                </div>

                <div
                  v-if="visiblePosts.length === 0"
                  class="empty-inline glass-card"
                >
                  <p>{{ activeTab === '精华' ? '圈内暂时没有精华帖。' : '圈内暂时没有帖子。' }}</p>
                  <button
                    class="neon-btn"
                    @click="goCreatePost"
                  >
                    发布第一篇帖子
                  </button>
                </div>
              </div>

              <div v-if="activeTab === '成员'" class="members-view">
                <div class="member-header">
                  <h3>圈子成员 · {{ formatCompactNumber(space.memberCount) }}</h3>
                  <div class="search-member">
                    <input
                      v-model="memberKeyword"
                      type="text"
                      placeholder="搜索成员昵称或角色"
                    />
                  </div>
                </div>
                <div class="member-grid">
                  <div
                    v-for="m in filteredMembers"
                    :key="m.userId"
                    class="member-card glass-card"
                    @click="openMemberProfile(m.userId)"
                  >
                    <button
                      class="avatar"
                      @click.stop="openMemberProfile(m.userId)"
                    >
                      {{ memberInitial(m) }}
                    </button>
                    <div class="info">
                      <span class="name">{{ m.user?.nickname || '未知用户' }}</span>
                      <span class="role">{{ m.role === 'OWNER' ? '圈主' : (m.role === 'ADMIN' ? '管理员' : '成员') }}</span>
                    </div>
                  </div>
                </div>
                <div
                  v-if="filteredMembers.length === 0"
                  class="empty-inline glass-card"
                >
                  <p>没有找到匹配的成员。</p>
                </div>
              </div>

              <div v-if="activeTab === '文件'" class="files-view glass-card">
                <div
                  v-if="spaceResources.length === 0"
                  class="empty-state"
                >
                  <n-icon size="48" color="rgba(255,255,255,0.2)"><DocumentTextOutline /></n-icon>
                  <p>暂无文件，快来分享第一份资料吧~</p>
                  <button
                    class="neon-btn"
                    @click="goUploadResource"
                  >
                    上传文件
                  </button>
                </div>
                <div
                  v-else
                  class="resource-list"
                >
                  <div class="resource-list-header">
                    <div>
                      <h3>圈内文件</h3>
                      <span>{{ spaceResources.length }} 份资料已收纳</span>
                    </div>
                    <button
                      class="neon-btn"
                      @click="goUploadResource"
                    >
                      上传文件
                    </button>
                  </div>
                  <article
                    v-for="resource in spaceResources"
                    :key="resource.id"
                    class="resource-card"
                    @click="goResource(resource.id)"
                  >
                    <div class="resource-icon">
                      <n-icon size="22"><DocumentTextOutline /></n-icon>
                    </div>
                    <div class="resource-info">
                      <strong>{{ resource.fileName }}</strong>
                      <span>
                        {{ formatFileSize(resource.fileSize) }}
                        <template v-if="resource.fileType"> · {{ resource.fileType.toUpperCase() }}</template>
                        <template v-if="resource.uploader?.nickname"> · {{ resource.uploader.nickname }}</template>
                      </span>
                      <div
                        v-if="resource.tags?.length"
                        class="resource-tags"
                      >
                        <span
                          v-for="tag in resource.tags.slice(0, 4)"
                          :key="`${resource.id}-${tag}`"
                        >
                          {{ tag }}
                        </span>
                      </div>
                      <p v-if="resource.description">
                        {{ resource.description }}
                      </p>
                    </div>
                  </article>
                </div>
              </div>

              <div v-if="activeTab === '打卡'" class="checkin-view">
                <div class="resource-list-header">
                  <div>
                    <h3>圈内打卡</h3>
                    <span>{{ checkinChallenges.length }} 个挑战已绑定当前学习圈</span>
                  </div>
                  <button
                    class="neon-btn"
                    @click="openChallengeForm"
                  >
                    创建挑战
                  </button>
                </div>

                <NModal
                  v-model:show="challengeFormVisible"
                  preset="card"
                  title="创建挑战"
                  class="space-modal challenge-modal"
                  transform-origin="center"
                  :style="{ width: 'min(92vw, 620px)' }"
                >
                  <div class="settings-panel challenge-form-panel">
                    <div class="settings-form-grid">
                      <label class="settings-field">
                        <span>挑战名称</span>
                        <input
                          v-model="challengeName"
                          maxlength="64"
                          placeholder="例如：Java 每日刷题"
                        />
                      </label>
                      <label class="settings-field">
                        <span>开始日期</span>
                        <input
                          v-model="challengeStartDate"
                          type="date"
                        />
                      </label>
                      <label class="settings-field">
                        <span>结束日期</span>
                        <input
                          v-model="challengeEndDate"
                          type="date"
                        />
                      </label>
                      <label class="settings-field wide">
                        <span>简介</span>
                        <textarea
                          v-model="challengeDescription"
                          maxlength="500"
                          placeholder="说明打卡规则、适合人群和目标..."
                        />
                      </label>
                    </div>
                    <div class="settings-actions">
                      <button
                        class="outline-action"
                        @click="challengeFormVisible = false"
                      >
                        取消
                      </button>
                      <button
                        class="neon-btn"
                        :disabled="challengeSubmitting"
                        @click="submitChallenge"
                      >
                        {{ challengeSubmitting ? '创建中...' : '保存挑战' }}
                      </button>
                    </div>
                  </div>
                </NModal>

                <div
                  v-if="checkinChallenges.length === 0"
                  class="files-view glass-card"
                >
                  <div class="empty-state">
                    <n-icon size="48" color="rgba(255,255,255,0.2)">
                      <CheckmarkCircleOutline />
                    </n-icon>
                    <p>当前学习圈暂无打卡挑战。</p>
                    <button
                      class="neon-btn"
                      @click="openChallengeForm"
                    >
                      创建第一个挑战
                    </button>
                  </div>
                </div>
                <div
                  v-else
                  class="challenge-grid"
                >
                  <article
                    v-for="challenge in checkinChallenges"
                    :key="challenge.id"
                    class="challenge-card glass-card"
                    @click="goChallenge(challenge.id)"
                  >
                    <div class="challenge-card__top">
                      <strong>{{ challenge.name }}</strong>
                      <span :class="{ active: challenge.startDate <= todayString() && challenge.endDate >= todayString() }">
                        {{ challenge.startDate <= todayString() && challenge.endDate >= todayString() ? '进行中' : '未进行' }}
                      </span>
                    </div>
                    <p>{{ challenge.description || '这个打卡挑战还没有简介。' }}</p>
                    <div class="challenge-card__meta">
                      <span>{{ challenge.startDate }} ~ {{ challenge.endDate }}</span>
                      <span>{{ challenge.memberCount }} 人参与</span>
                      <span v-if="challenge.isMember">我的连续 {{ challenge.myConsecutiveDays }} 天</span>
                    </div>
                  </article>
                </div>
              </div>

              <div v-if="activeTab === '设置'" class="settings-view">
                <section class="settings-panel glass-card">
                  <header>
                    <div>
                      <h3>圈子设置</h3>
                      <span>{{ canManageSpace ? '管理当前学习圈配置与公告' : '查看圈子信息并管理自己的加入状态' }}</span>
                    </div>
                  </header>

                  <div class="settings-summary-grid">
                    <article>
                      <span>成员身份</span>
                      <strong>{{ memberRoleLabel }}</strong>
                    </article>
                    <article>
                      <span>加入方式</span>
                      <strong>{{ visibilityLabel }}</strong>
                    </article>
                    <article>
                      <span>圈子状态</span>
                      <strong>{{ spaceStatusLabel }}</strong>
                    </article>
                    <article>
                      <span>创建时间</span>
                      <strong>{{ formatDate(space.createdAt) }}</strong>
                    </article>
                  </div>

                  <div class="settings-action-grid">
                    <button
                      class="settings-action-card"
                      @click="copySpaceLink"
                    >
                      <n-icon><CopyOutline /></n-icon>
                      <span>
                        <strong>复制圈子链接</strong>
                        <small>{{ spaceLink }}</small>
                      </span>
                    </button>
                    <button
                      class="settings-action-card"
                      @click="refreshSpaceData"
                    >
                      <n-icon><RefreshOutline /></n-icon>
                      <span>
                        <strong>刷新圈内数据</strong>
                        <small>重新同步圈子、成员、帖子、资料和打卡数据</small>
                      </span>
                    </button>
                    <button
                      class="settings-action-card"
                      @click="openNoticeList"
                    >
                      <n-icon><NotificationsOutline /></n-icon>
                      <span>
                        <strong>查看圈子公告</strong>
                        <small>{{ noticeText }}</small>
                      </span>
                    </button>
                    <button
                      class="settings-action-card"
                      @click="openActiveMembers"
                    >
                      <n-icon><PeopleOutline /></n-icon>
                      <span>
                        <strong>查看成员</strong>
                        <small>{{ formatCompactNumber(space.memberCount) }} 名成员</small>
                      </span>
                    </button>
                    <button
                      class="settings-action-card"
                      @click="goCreatePost"
                    >
                      <n-icon><SparklesOutline /></n-icon>
                      <span>
                        <strong>发布帖子</strong>
                        <small>直接向当前学习圈发帖</small>
                      </span>
                    </button>
                    <button
                      class="settings-action-card"
                      @click="goUploadResource"
                    >
                      <n-icon><DocumentTextOutline /></n-icon>
                      <span>
                        <strong>上传资料</strong>
                        <small>{{ formatCompactNumber(spaceResources.length) }} 份资料已收纳</small>
                      </span>
                    </button>
                    <button
                      class="settings-action-card"
                      @click="openChallengeForm"
                    >
                      <n-icon><CheckmarkCircleOutline /></n-icon>
                      <span>
                        <strong>创建打卡</strong>
                        <small>{{ formatCompactNumber(checkinChallenges.length) }} 个圈内挑战</small>
                      </span>
                    </button>
                  </div>

                  <div class="settings-actions left">
                    <button
                      v-if="!space.isMember"
                      class="outline-action"
                      :disabled="spaceActionSubmitting"
                      @click="toggleSpaceMembership"
                    >
                      {{ spaceActionSubmitting ? '处理中...' : '加入圈子' }}
                    </button>
                    <button
                      v-else-if="canLeaveSpace"
                      class="outline-action"
                      :disabled="spaceActionSubmitting"
                      @click="toggleSpaceMembership"
                    >
                      {{ spaceActionSubmitting ? '处理中...' : '退出圈子' }}
                    </button>
                  </div>

                  <template v-if="canManageSpace">
                    <div class="settings-form-grid">
                      <label class="settings-field">
                        <span>圈子名称</span>
                        <input
                          v-model="settingName"
                          maxlength="64"
                        />
                      </label>
                      <label class="settings-field">
                        <span>加入方式</span>
                        <select v-model="settingVisibility">
                          <option value="PUBLIC">公开加入</option>
                          <option value="REVIEW">申请审核</option>
                        </select>
                      </label>
                      <label class="settings-field wide">
                        <span>简介</span>
                        <textarea
                          v-model="settingDescription"
                          maxlength="255"
                          placeholder="一句话说明这个学习圈的方向"
                        />
                      </label>
                      <label class="settings-field wide">
                        <span>公告</span>
                        <textarea
                          v-model="settingPostNotice"
                          maxlength="2000"
                          placeholder="同步给成员的圈子公告"
                        />
                      </label>
                      <label class="settings-field wide">
                        <span>敏感词</span>
                        <textarea
                          v-model="settingSensitiveWords"
                          maxlength="2000"
                          placeholder="可用逗号或换行分隔"
                        />
                      </label>
                    </div>
                    <div class="settings-actions">
                      <button
                        class="outline-action"
                        @click="syncSettingForm"
                      >
                        重置表单
                      </button>
                      <button
                        class="neon-btn"
                        :disabled="settingSubmitting"
                        @click="submitSpaceSettings"
                      >
                        {{ settingSubmitting ? '保存中...' : '保存设置' }}
                      </button>
                    </div>
                  </template>

                  <template v-else>
                    <div class="settings-readonly-grid">
                      <article>
                        <span>圈子简介</span>
                        <p>{{ space.description || '圈主暂未填写简介。' }}</p>
                      </article>
                      <article>
                        <span>圈子公告</span>
                        <p>{{ noticeText }}</p>
                      </article>
                      <article>
                        <span>发帖敏感词</span>
                        <p>{{ sensitiveWordList.length ? sensitiveWordList.join('、') : '圈主暂未配置敏感词。' }}</p>
                      </article>
                    </div>
                  </template>
                </section>
              </div>
            </div>

            <!-- 右侧数据列 -->
            <div class="right-col">
              <!-- 圈子公告 -->
              <div class="widget-card glass-card">
                <div class="widget-header">
                  <h3>圈子公告</h3>
                  <span
                    class="more"
                    @click="openNoticeList"
                  >
                    更多 >
                  </span>
                </div>
                <div class="notice-content">
                  <p>{{ noticeText }}</p>
                  <div class="date">
                    {{ formatDate(space.createdAt) }}
                  </div>
                </div>
              </div>

              <div class="widget-card glass-card">
                <div class="widget-header">
                  <h3>圈子数据</h3>
                </div>
                <div class="data-content">
                  <div class="data-row">
                    <span class="label">活跃趋势 (7日)</span>
                    <span class="value">
                      {{ activityScore }}
                      <span class="up">{{ activityChange }}</span>
                    </span>
                  </div>
                  <div class="mock-chart">
                    <svg
                      viewBox="0 0 100 30"
                      class="chart-svg"
                    >
                      <path
                        :d="activityTrendPath"
                        fill="none"
                        stroke="#6366f1"
                        stroke-width="2"
                      />
                      <circle
                        v-for="point in activityTrendPoints"
                        :key="`${point.x}-${point.y}`"
                        :cx="point.x"
                        :cy="point.y"
                        r="2"
                        fill="#6366f1"
                      />
                    </svg>
                  </div>
                  <div class="data-row mt">
                    <span class="label">活跃成员</span>
                    <button
                      class="members-avatars"
                      @click="openActiveMembers"
                    >
                      <div
                        v-for="member in activeMemberList.slice(0, 3)"
                        :key="member.id"
                        class="avatar"
                      >
                        {{ member.nickname.charAt(0).toUpperCase() }}
                      </div>
                      <span class="count">{{ formatCompactNumber(space.memberCount) }}</span>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="empty-inline glass-card">
            <p>没有找到这个学习圈，或当前账号没有访问权限。</p>
            <button
              class="neon-btn"
              @click="router.push('/spaces')"
            >
              返回学习圈广场
            </button>
          </div>
        </template>
      </div>

      <BackToTopButton :target="contentScrollRef" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.layout-container {
  display: flex;
  height: 100vh;
  background-color: transparent;
  color: var(--cf-text-primary);
  overflow: hidden;
}

.main-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  max-width: 1400px;
  margin: 0 auto;
  width: 100%;
}

.top-header {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  border-bottom: 1px solid var(--cf-border-glass);
  background:
    linear-gradient(180deg, var(--cf-surface-highlight), transparent 72%),
    linear-gradient(90deg, var(--cf-bg-glass-strong), var(--cf-bg-glass-soft));
  backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(136%);
  -webkit-backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(136%);
  box-shadow: 0 20px 70px color-mix(in srgb, var(--cf-text-primary) 9%, transparent), 0 -1px 0
    color-mix(in srgb, #ffffff 36%, transparent) inset;
  position: sticky;
  top: 0;
  z-index: 10;

  .header-left {
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    font-weight: 500;
    color: var(--cf-text-primary);
    transition: color 0.22s ease, transform 0.24s var(--cf-motion-ease);
    &:hover {
      color: var(--cf-primary);
      transform: translate3d(-2px, -1px, 0);
    }
  }

  .search-bar {
    display: flex;
    align-items: center;
    background: var(--cf-bg-glass);
    border: 1px solid var(--cf-border-glass);
    border-radius: 20px;
    padding: 8px 16px;
    width: 300px;
    gap: 8px;
    box-shadow: 0 14px 34px color-mix(in srgb, var(--cf-text-primary) 7%, transparent);
    transition: border-color 0.22s ease, box-shadow 0.22s ease, background 0.22s ease;

    &:focus-within {
      background: var(--cf-bg-readable);
      border-color: var(--cf-border-strong);
      box-shadow: 0 0 0 4px color-mix(in srgb, var(--cf-primary) 12%, transparent), var(--cf-shadow-soft);
    }

    input {
      background: transparent;
      border: none;
      color: var(--cf-text-primary);
      outline: none;
      width: 100%;
      &::placeholder { color: var(--cf-text-muted); }
    }
  }

  .header-actions {
    display: flex;
    align-items: center;
    gap: 14px;
    color: var(--cf-text-secondary);

    .header-action-btn,
    .avatar {
      transition: transform 0.24s var(--cf-motion-ease), color 0.22s ease, box-shadow 0.24s var(--cf-motion-ease),
        border-color 0.22s ease;
    }

    .header-action-btn {
      position: relative;
      width: 40px;
      height: 40px;
      padding: 8px;
      border: 1px solid var(--cf-border-glass);
      border-radius: 12px;
      background: var(--cf-bg-glass);
      box-shadow: 0 12px 30px color-mix(in srgb, var(--cf-text-primary) 7%, transparent);
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      color: var(--cf-text-secondary);

      &:hover {
        color: var(--cf-primary);
        border-color: var(--cf-border-strong);
        box-shadow: var(--cf-shadow-soft);
        transform: translate3d(0, -2px, 0);
      }
    }

    .avatar {
      width: 32px;
      height: 32px;
      border-radius: 50%;
      background: linear-gradient(135deg, var(--cf-secondary), var(--cf-primary));
      border: 1px solid color-mix(in srgb, #ffffff 54%, transparent);
      box-shadow: 0 12px 28px color-mix(in srgb, var(--cf-secondary) 22%, transparent);
      color: var(--cf-text-inverse);
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;

      &:hover {
        transform: translate3d(0, -2px, 0);
        box-shadow: var(--cf-shadow-soft);
      }
    }
  }
}

.content-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 32px;
  scroll-behavior: smooth;
}

.header-unread-dot {
  position: absolute;
  top: -5px;
  right: -5px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 10px;
  font-weight: 900;
  line-height: 18px;
  box-shadow: 0 8px 18px color-mix(in srgb, #ef4444 35%, transparent);
}

.outline-action {
  border: 1px solid var(--cf-border-glass);
  border-radius: 12px;
  background: var(--cf-bg-glass);
  color: var(--cf-text-primary);
  cursor: pointer;
  font-weight: 800;
  transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.22s ease, opacity 0.22s ease;

  &:hover:not(:disabled) {
    transform: translate3d(0, -1px, 0);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-soft);
  }

  &:disabled {
    cursor: wait;
    opacity: 0.58;
  }
}

.embedded-profile {
  position: absolute;
  inset: 0;
  z-index: 28;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: var(--cf-text-primary);
  background-color: var(--cf-bg-base);
  background-image: var(--cf-bg-environment);
  background-position: 0 0, center, center;
  background-size: 32px 32px, 140% 140%, 140% 140%;

  &::before,
  &::after {
    content: '';
    position: absolute;
    inset: 0;
    z-index: 0;
    pointer-events: none;
  }

  &::before {
    background:
      linear-gradient(180deg, transparent, color-mix(in srgb, var(--cf-bg-base) 74%, transparent)),
      repeating-linear-gradient(90deg, transparent 0 74px, color-mix(in srgb, var(--cf-text-primary) 4%, transparent) 74px 75px);
    opacity: 0.72;
  }

  &::after {
    background: linear-gradient(120deg, transparent 0%, color-mix(in srgb, var(--cf-primary) 8%, transparent) 48%, transparent 100%);
    opacity: 0.55;
    transform: translate3d(-18%, -12%, 0);
  }
}

.profile-panel-enter-active,
.profile-panel-leave-active {
  transition: opacity 0.28s ease, transform 0.32s var(--cf-motion-ease);
}

.profile-panel-enter-from,
.profile-panel-leave-to {
  opacity: 0;
  transform: translate3d(0, 18px, 0) scale(0.985);
}

.embedded-profile__top {
  height: 64px;
  flex: 0 0 auto;
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 32px;
  border-bottom: 1px solid var(--cf-border-glass);
  background:
    linear-gradient(180deg, var(--cf-surface-highlight), transparent 72%),
    linear-gradient(90deg, var(--cf-bg-glass-strong), var(--cf-bg-glass-soft));
  box-shadow: 0 20px 70px color-mix(in srgb, var(--cf-text-primary) 9%, transparent), 0 -1px 0
    color-mix(in srgb, #ffffff 36%, transparent) inset;
  backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(136%);
  -webkit-backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(136%);
}

.embedded-profile__back,
.embedded-profile__actions button {
  border: 1px solid var(--cf-border-glass);
  background: var(--cf-bg-glass);
  color: var(--cf-text-primary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.22s ease;

  &:hover {
    transform: translate3d(0, -1px, 0);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-soft);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.56;
    transform: none;
    box-shadow: none;
  }
}

.embedded-profile__back {
  gap: 8px;
  padding: 10px 14px;
  border-radius: 999px;
  font-weight: 800;
}

.embedded-profile__actions {
  display: flex;
  gap: 10px;

  button {
    width: 40px;
    height: 40px;
    border-radius: 13px;
  }
}

.embedded-profile__scroll {
  position: relative;
  z-index: 1;
  flex: 1;
  overflow-y: auto;
  padding: 32px;
  scroll-behavior: smooth;
}

.embedded-profile__container {
  width: min(100%, 1200px);
  margin: 0 auto 48px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 32px;
  align-items: start;
}

.profile-cover-card {
  position: relative;
  overflow: hidden;
  min-height: 164px;
  grid-column: 1;
  border: 1px solid var(--cf-border-glass);
  border-radius: 16px;
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--cf-secondary) 16%, transparent), transparent 58%),
    linear-gradient(315deg, color-mix(in srgb, var(--cf-primary) 14%, transparent), transparent 52%),
    var(--cf-bg-glass);
  box-shadow: var(--cf-shadow-card);
  transition: transform 0.3s var(--cf-motion-ease), box-shadow 0.3s var(--cf-motion-ease), border-color 0.3s ease;

  &:hover {
    transform: translate3d(0, -4px, 0);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-card-hover);
  }

  > img {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
    cursor: pointer;
    opacity: 0.34;
    filter: saturate(112%) contrast(0.96);
    transform: scale(1.08) translate3d(-1.4%, -1.2%, 0);
    transform-origin: center;
    animation: profileCoverDrift 24s ease-in-out infinite alternate;
    will-change: transform;
  }
}

.profile-cover-card__shade {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 12% 16%, color-mix(in srgb, var(--cf-primary) 22%, transparent), transparent 28%),
    radial-gradient(circle at 78% 62%, color-mix(in srgb, var(--cf-secondary) 18%, transparent), transparent 30%);
  background-size: 130% 130%, 125% 125%;
  opacity: 0.72;
  animation: profileCoverLight 18s ease-in-out infinite alternate;
  pointer-events: none;
}

.profile-identity {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 32px;
}

.profile-avatar-large {
  border: 1px solid color-mix(in srgb, #ffffff 54%, transparent);
  width: 80px;
  height: 80px;
  border-radius: 50%;
  overflow: hidden;
  flex: 0 0 auto;
  background: var(--cf-gradient-primary);
  color: var(--cf-text-inverse);
  cursor: pointer;
  box-shadow: 0 12px 28px color-mix(in srgb, var(--cf-secondary) 22%, transparent);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  font-weight: 900;
  padding: 0;
  transition: transform 0.22s var(--cf-motion-ease), box-shadow 0.22s ease;

  &:hover {
    transform: translate3d(0, -2px, 0) scale(1.015);
    box-shadow: var(--cf-shadow-card-hover);
  }

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.profile-copy {
  min-width: 0;

  p {
    margin: 0;
    color: var(--cf-text-secondary);
  }
}

.profile-bio-edit {
  display: block;
  margin-top: 8px;
  max-width: 620px;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: var(--cf-text-secondary);
  cursor: pointer;
  padding: 0;
  text-align: left;
  line-height: 1.55;
  transition: color 0.22s ease, border-color 0.22s ease, background 0.22s ease;

  &:hover {
    color: var(--cf-primary);
    background: transparent;
  }
}

.profile-name-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 8px;

  h2 {
    margin: 0;
    color: var(--cf-text-primary);
    font-size: 24px;
    line-height: 1.15;
  }

  span {
    border: 1px solid color-mix(in srgb, var(--cf-warning) 48%, transparent);
    border-radius: 999px;
    background: color-mix(in srgb, var(--cf-warning) 13%, transparent);
    color: var(--cf-warning);
    padding: 4px 10px;
    font-size: 12px;
    font-weight: 900;
  }
}

.profile-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  grid-column: 1;
  margin: 14px 0 16px;

  article,
  button {
    min-height: 112px;
    border: 1px solid var(--cf-border-glass);
    border-radius: 16px;
    background:
      linear-gradient(180deg, var(--cf-surface-highlight), transparent 58%),
      var(--cf-bg-glass);
    box-shadow: var(--cf-shadow-card);
    padding: 16px;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    text-align: left;
    color: inherit;
    font: inherit;
  }

  button {
    cursor: pointer;
    transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.22s ease;

    &:hover {
      transform: translate3d(0, -2px, 0);
      border-color: var(--cf-border-strong);
      box-shadow: var(--cf-shadow-soft);
    }
  }

  span {
    color: var(--cf-text-secondary);
    font-size: 13px;
  }

  strong {
    color: var(--cf-text-primary);
    font-size: 28px;
    line-height: 1;
  }
}

.profile-content-grid {
  display: contents;
}

.profile-feed {
  grid-column: 1;
  min-width: 0;
}

.profile-tabs {
  display: flex;
  gap: 12px;
  border-bottom: 1px solid var(--cf-border-glass);
  margin-bottom: 16px;
  background: var(--cf-bg-glass-soft);
  border-radius: 16px 16px 0 0;
  padding: 0 10px;
  box-shadow: 0 14px 38px color-mix(in srgb, var(--cf-text-primary) 5%, transparent);

  button {
    border: none;
    border-radius: 0;
    background: transparent;
    color: var(--cf-text-secondary);
    cursor: pointer;
    position: relative;
    display: inline-flex;
    align-items: center;
    gap: 7px;
    padding: 13px 10px;
    font-weight: 800;
    transition: color 0.22s ease, background 0.22s ease, transform 0.22s var(--cf-motion-ease);

    small {
      min-width: 18px;
      border-radius: 999px;
      background: color-mix(in srgb, var(--cf-text-primary) 8%, transparent);
      color: inherit;
      padding: 2px 6px;
      font-size: 11px;
      line-height: 1.2;
    }

    &:hover {
      color: var(--cf-text-primary);
      transform: translate3d(0, -1px, 0);
    }

    &.active {
      background: transparent;
      color: var(--cf-text-primary);
      box-shadow: none;

      &::after {
        content: '';
        position: absolute;
        bottom: -1px;
        left: 10px;
        width: calc(100% - 20px);
        height: 2px;
        background: var(--cf-primary);
        box-shadow: 0 -2px 10px color-mix(in srgb, var(--cf-primary) 52%, transparent);
      }

      small {
        background: color-mix(in srgb, var(--cf-text-primary) 8%, transparent);
      }
    }
  }
}

.profile-feed-card,
.profile-widget {
  padding: 20px;
  background:
    linear-gradient(180deg, var(--cf-surface-highlight), transparent 62%),
    var(--cf-bg-glass);
  border: 1px solid var(--cf-border-glass);
  border-radius: 16px;
  box-shadow: var(--cf-shadow-card);
}

.profile-feed-card {
  cursor: default;

  &.actionable {
    cursor: pointer;
    transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.22s ease;

    &:hover {
      transform: translate3d(0, -2px, 0);
      border-color: var(--cf-border-strong);
      box-shadow: var(--cf-shadow-card-hover);
    }
  }

  & + .profile-feed-card {
    margin-top: 16px;
  }
}

.profile-feed-card__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;

  > div:not(.profile-avatar-small) {
    display: flex;
    flex-direction: column;
    gap: 3px;
    min-width: 0;
  }

  strong {
    color: var(--cf-text-primary);
  }

  span {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.profile-avatar-small {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  flex: 0 0 auto;
  background: linear-gradient(135deg, var(--cf-secondary), var(--cf-primary));
  color: var(--cf-text-inverse);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 900;
}

.profile-feed-card__more {
  width: 32px;
  height: 32px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  margin-left: auto;
  color: var(--cf-text-secondary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, background 0.22s ease, color 0.22s ease;

  &:hover {
    transform: translate3d(0, -1px, 0);
    border-color: var(--cf-border-glass);
    background: var(--cf-bg-glass-soft);
    color: var(--cf-primary);
  }
}

.profile-feed-card__type {
  margin-left: auto;
  border: 1px solid var(--cf-border-glass);
  border-radius: 7px;
  background: var(--cf-bg-glass);
  color: var(--cf-text-secondary);
  padding: 3px 9px;
  font-size: 12px;
  font-weight: 800;

  + .profile-feed-card__more {
    margin-left: 6px;
  }
}

.profile-feed-card p {
  color: var(--cf-text-secondary);
  line-height: 1.65;
  margin: 0 0 16px;
}

.profile-attachment {
  display: flex;
  gap: 12px;
  align-items: center;
  border: 1px solid var(--cf-border-glass);
  border-radius: 13px;
  background: var(--cf-bg-glass-soft);
  padding: 14px;
  color: var(--cf-primary);

  div {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  strong {
    color: var(--cf-text-primary);
  }

  span {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.profile-feed-card__actions {
  display: flex;
  gap: 26px;
  margin-top: 16px;
  color: var(--cf-text-secondary);

  span {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    cursor: pointer;
    transition: color 0.22s ease, transform 0.22s var(--cf-motion-ease);

    &:hover {
      color: var(--cf-primary);
      transform: translate3d(0, -1px, 0);
    }

    &:last-child {
      margin-left: auto;
    }
  }
}

.profile-side {
  grid-column: 2;
  grid-row: 1 / span 3;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.profile-widget__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 16px;

  h3 {
    margin: 0;
    color: var(--cf-text-primary);
    font-size: 16px;
  }

  span {
    color: var(--cf-primary);
    font-size: 12px;
    font-weight: 800;
  }
}

.profile-badges {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;

  button {
    aspect-ratio: 1;
    border: 1px solid color-mix(in srgb, var(--cf-primary) 26%, transparent);
    border-radius: 12px;
    background: color-mix(in srgb, var(--cf-primary) 12%, var(--cf-bg-glass));
    color: var(--cf-primary);
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font: inherit;
    font-weight: 900;
    box-shadow: inset 0 0 18px color-mix(in srgb, var(--cf-primary) 8%, transparent);
    transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, opacity 0.22s ease;

    &:hover {
      transform: translate3d(0, -2px, 0);
      border-color: var(--cf-border-strong);
    }

    &.locked {
      opacity: 0.5;
      filter: grayscale(0.55);
    }
  }
}

.profile-checkin-list {
  display: flex;
  flex-direction: column;
  gap: 9px;

  button {
    border: 1px solid var(--cf-border-glass);
    border-radius: 13px;
    background: var(--cf-bg-glass-soft);
    color: var(--cf-text-primary);
    cursor: pointer;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 11px 12px;
    text-align: left;
    transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.22s ease;

    &:hover {
      transform: translate3d(0, -1px, 0);
      border-color: var(--cf-border-strong);
      box-shadow: var(--cf-shadow-soft);
    }
  }

  strong {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.profile-widget-empty {
  grid-column: 1 / -1;
  border: 1px dashed var(--cf-border-glass);
  border-radius: 12px;
  background: var(--cf-bg-glass-soft);
  color: var(--cf-text-muted);
  padding: 16px;
  text-align: center;
  font-size: 13px;
}

.profile-edit-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.profile-edit-preview {
  position: relative;
  overflow: hidden;
  min-height: 200px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.04);
  box-shadow: 0 18px 50px rgba(0, 0, 0, 0.2);

  > img {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &::after {
    content: '';
    position: absolute;
    inset: 0;
    background: linear-gradient(180deg, transparent 20%, color-mix(in srgb, var(--cf-bg-base) 52%, transparent));
  }
}

.profile-edit-avatar {
  position: absolute;
  z-index: 1;
  left: 22px;
  bottom: 18px;
  width: 84px;
  height: 84px;
  border-radius: 50%;
  overflow: hidden;
  border: 3px solid color-mix(in srgb, var(--cf-bg-readable) 88%, transparent);
  background: var(--cf-gradient-primary);
  color: var(--cf-text-inverse);
  box-shadow: 0 16px 34px color-mix(in srgb, var(--cf-text-primary) 18%, transparent);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  font-weight: 900;
  cursor: pointer;
  padding: 0;

  small {
    position: absolute;
    inset: auto 0 0;
    background: color-mix(in srgb, #000 54%, transparent);
    color: #fff;
    font-size: 11px;
    line-height: 24px;
    text-align: center;
  }

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.profile-edit-cover-action,
.profile-upload-field button {
  border: 1px solid var(--cf-border-glass);
  border-radius: 12px;
  background: var(--cf-bg-glass);
  color: var(--cf-primary);
  cursor: pointer;
  font-weight: 900;
  transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.22s ease, opacity 0.22s ease;

  &:hover:not(:disabled) {
    transform: translate3d(0, -1px, 0);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-soft);
  }

  &:disabled {
    cursor: wait;
    opacity: 0.62;
  }
}

.profile-edit-cover-action {
  position: absolute;
  right: 18px;
  bottom: 18px;
  z-index: 1;
  padding: 9px 13px;
}

.profile-asset-input {
  display: none;
}

.profile-upload-field {
  button {
    min-height: 42px;
    padding: 9px 12px;
    text-align: left;
  }
}

.profile-edit-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.profile-follow-switch {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 14px;

  button {
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.04);
    color: var(--cf-text-secondary);
    cursor: pointer;
    padding: 10px 12px;
    font-weight: 900;
    transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease;

    &:hover {
      transform: translate3d(0, -1px, 0);
      border-color: var(--cf-border-strong);
    }

    &.active {
      background: var(--cf-primary);
      color: var(--cf-text-inverse);
      box-shadow: var(--cf-shadow-glow);
    }
  }
}

.space-layout {
  display: flex;
  gap: 32px;
  max-width: 1200px;
  margin: 0 auto;

  .center-col {
    flex: 1;
    min-width: 0;

    .space-banner {
      position: relative;
      padding: 32px;
      overflow: hidden;
      margin-bottom: 24px;
      background:
        linear-gradient(135deg, color-mix(in srgb, var(--cf-secondary) 16%, transparent), transparent 58%),
        linear-gradient(315deg, color-mix(in srgb, var(--cf-primary) 14%, transparent), transparent 52%),
        var(--cf-bg-glass);
      border: 1px solid var(--cf-border-glass);
      box-shadow: var(--cf-shadow-card);
      transition: transform 0.3s var(--cf-motion-ease), box-shadow 0.3s var(--cf-motion-ease), border-color 0.3s ease;

      &:hover {
        transform: translate3d(0, -4px, 0);
        border-color: var(--cf-border-strong);
        box-shadow: var(--cf-shadow-card-hover);
      }

      .banner-bg {
        position: absolute;
        top: 0; left: 0; right: 0; bottom: 0;
        background:
          radial-gradient(circle at 12% 16%, color-mix(in srgb, var(--cf-primary) 22%, transparent), transparent 28%),
          radial-gradient(circle at 78% 62%, color-mix(in srgb, var(--cf-secondary) 18%, transparent), transparent 30%);
        z-index: 0;
        opacity: 0.72;
        pointer-events: none;
      }

      .banner-content {
        position: relative;
        z-index: 1;
        display: flex;
        align-items: center;
        gap: 24px;

        .space-icon {
          width: 80px; height: 80px;
          border-radius: 16px;
          background: var(--cf-primary);
          display: flex;
          align-items: center;
          justify-content: center;
          box-shadow: var(--cf-shadow-glow);
        }

        .space-info {
          flex: 1;
          .title-row {
            display: flex;
            align-items: center;
            gap: 12px;
            h2 { margin: 0; font-size: 24px; color: var(--cf-text-primary); }
            .tag {
              font-size: 12px;
              padding: 3px 9px;
              border-radius: 7px;
              border: 1px solid color-mix(in srgb, var(--cf-warning) 58%, transparent);
              background: color-mix(in srgb, var(--cf-warning) 12%, transparent);
              color: var(--cf-warning);
            }
          }
          .desc { color: var(--cf-text-secondary); margin: 8px 0; font-size: 14px; }
          .stats { color: var(--cf-text-muted); font-size: 13px; .dot { margin: 0 8px; } }
        }

        .banner-actions {
          display: flex;
          flex-wrap: wrap;
          justify-content: flex-end;
          gap: 10px;
        }

        .header-btn {
          min-height: 42px;
          padding: 10px 20px;
          white-space: nowrap;
        }
      }
    }

    .space-tabs {
      display: flex;
      gap: 12px;
      border-bottom: 1px solid var(--cf-border-glass);
      margin-bottom: 24px;
      background: var(--cf-bg-glass-soft);
      border-radius: 16px 16px 0 0;
      padding: 0 10px;
      box-shadow: 0 14px 38px color-mix(in srgb, var(--cf-text-primary) 5%, transparent);

      .tab-item {
        padding: 13px 10px;
        color: var(--cf-text-secondary);
        cursor: pointer;
        position: relative;
        transition: color 0.22s ease, transform 0.24s var(--cf-motion-ease);

        &:hover {
          color: var(--cf-text-primary);
          transform: translate3d(0, -1px, 0);
        }

        &.active {
          color: var(--cf-text-primary);
          &::after {
            content: '';
            position: absolute;
            bottom: -1px; left: 10px; width: calc(100% - 20px); height: 2px;
            background: var(--cf-primary);
            box-shadow: 0 -2px 10px color-mix(in srgb, var(--cf-primary) 52%, transparent);
          }
        }
      }
    }

    .space-search-panel {
      padding: 18px;
      margin: 18px 0 24px;
    }

    .space-search-head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 14px;
      margin-bottom: 14px;

      h3,
      p {
        margin: 0;
      }

      h3 {
        color: var(--cf-text-primary);
        font-size: 20px;
      }

      p {
        color: var(--cf-text-secondary);
        font-size: 13px;
        margin-top: 5px;
      }
    }

    .space-search-summary {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 10px;
      margin-bottom: 14px;

      article {
        border: 1px solid var(--cf-border-glass);
        border-radius: 12px;
        background: var(--cf-bg-glass-soft);
        min-height: 72px;
        padding: 12px;
        display: flex;
        flex-direction: column;
        justify-content: space-between;
      }

      span {
        color: var(--cf-text-secondary);
        font-size: 12px;
      }

      strong {
        color: var(--cf-text-primary);
        font-size: 22px;
        line-height: 1;
      }
    }

    .space-search-groups {
      display: grid;
      gap: 14px;
    }

    .space-search-group {
      display: flex;
      flex-direction: column;
      gap: 9px;

      > header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 0 2px;

        h4 {
          margin: 0;
          color: var(--cf-text-primary);
          font-size: 15px;
        }

        span {
          color: var(--cf-text-muted);
          font-size: 12px;
        }
      }
    }

    .search-result-row,
    .search-member-row {
      background: var(--cf-bg-glass-soft);
    }

    .post-filters {
      display: flex;
      gap: 16px;
      margin-bottom: 20px;
      span {
        padding: 7px 14px; border-radius: 16px; background: var(--cf-bg-glass);
        border: 1px solid transparent;
        color: var(--cf-text-secondary); font-size: 14px; cursor: pointer;
        transition: transform 0.22s var(--cf-motion-ease), box-shadow 0.22s ease, border-color 0.22s ease, background 0.22s ease;
        &:hover {
          border-color: var(--cf-border-glass);
          color: var(--cf-text-primary);
          transform: translate3d(0, -1px, 0);
        }
        &.active {
          background: var(--cf-primary);
          color: var(--cf-text-inverse);
          box-shadow: var(--cf-shadow-glow);
        }
      }
    }

    .post-list {
      display: flex;
      flex-direction: column;
      gap: 16px;

      .post-item {
        padding: 24px;
        background:
          linear-gradient(180deg, var(--cf-surface-highlight), transparent 54%),
          var(--cf-bg-glass);
        border: 1px solid var(--cf-border-glass);
        border-radius: 16px;
        box-shadow: var(--cf-shadow-card);
        cursor: pointer;
        transition: transform 0.3s var(--cf-motion-ease), box-shadow 0.3s var(--cf-motion-ease), border-color 0.3s ease;

        &:hover {
          transform: translate3d(0, -5px, 0);
          border-color: var(--cf-border-strong);
          box-shadow: var(--cf-shadow-card-hover);
        }

        .post-author {
          display: flex;
          align-items: center;
          gap: 12px;
          margin-bottom: 16px;

          .avatar {
            width: 36px;
            height: 36px;
            border-radius: 50%;
            border: none;
            background: var(--cf-gradient-primary);
            color: var(--cf-text-inverse);
            cursor: pointer;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            font-weight: 800;
            box-shadow: 0 10px 22px color-mix(in srgb, var(--cf-text-primary) 10%, transparent);
          }
          .admin-avatar { background: #38bdf8; }
          .default-avatar { background: #10b981; }

          .author-info {
            display: flex;
            flex-direction: column;
            .name { font-size: 14px; color: var(--cf-text-primary); font-weight: 500; cursor: pointer; }
            .time { font-size: 12px; color: var(--cf-text-muted); }
          }
          .post-more-btn {
            margin-left: auto;
            width: 34px;
            height: 34px;
            border: 1px solid transparent;
            border-radius: 10px;
            background: transparent;
            color: var(--cf-text-secondary);
            cursor: pointer;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, background 0.22s ease, color 0.22s ease;

            &:hover {
              transform: translate3d(0, -1px, 0);
              border-color: var(--cf-border-glass);
              background: var(--cf-bg-glass-soft);
              color: var(--cf-primary);
            }
          }
        }

        .post-title {
          margin: 0 0 12px;
          font-size: 18px;
          color: var(--cf-text-primary);
          display: flex;
          align-items: center;
          gap: 12px;

          .tag {
            font-size: 12px; padding: 2px 8px; border-radius: 4px; font-weight: normal;
            &.blue { background: rgba(56,189,248,0.1); color: #38bdf8; border: 1px solid rgba(56,189,248,0.3); }
            &.purple { background: rgba(192,132,252,0.1); color: #c084fc; border: 1px solid rgba(192,132,252,0.3); }
          }
        }

        .post-preview {
          color: var(--cf-text-secondary);
          font-size: 15px;
          line-height: 1.6;
          margin-bottom: 20px;
        }

        .post-actions {
          display: flex;
          gap: 24px;
          .action {
            border: none;
            background: transparent;
            padding: 0;
            display: flex; align-items: center; gap: 6px; color: var(--cf-text-secondary); font-size: 14px; cursor: pointer;
            font: inherit;
            transition: color 0.22s ease, transform 0.22s var(--cf-motion-ease);
            &:hover {
              color: var(--cf-primary);
              transform: translate3d(0, -1px, 0);
            }
            &.active {
              color: var(--cf-primary);
            }
            &:disabled {
              cursor: wait;
              opacity: 0.62;
              transform: none;
            }
            &.right { margin-left: auto; }
          }
        }
      }
    }
  }

  .right-col {
    width: 320px;
    display: flex;
    flex-direction: column;
    gap: 24px;

    .widget-card {
      padding: 24px;
      background:
        linear-gradient(180deg, var(--cf-surface-highlight), transparent 54%),
        var(--cf-bg-glass);
      border: 1px solid var(--cf-border-glass);
      border-radius: 16px;
      box-shadow: var(--cf-shadow-card);
      transition: transform 0.3s var(--cf-motion-ease), box-shadow 0.3s var(--cf-motion-ease), border-color 0.3s ease;

      &:hover {
        transform: translate3d(0, -4px, 0);
        border-color: var(--cf-border-strong);
        box-shadow: var(--cf-shadow-card-hover);
      }

      .widget-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 16px;
        h3 { margin: 0; font-size: 16px; color: var(--cf-text-primary); }
        .more {
          font-size: 13px;
          color: var(--cf-text-secondary);
          cursor: pointer;
          transition: color 0.22s ease;
          &:hover { color: var(--cf-primary); }
        }
      }

      .notice-content {
        p { margin: 0 0 12px; color: var(--cf-text-secondary); font-size: 14px; line-height: 1.6; }
        .date { font-size: 12px; color: var(--cf-text-muted); }
      }

      .data-content {
        .data-row {
          display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
          .label { font-size: 14px; color: var(--cf-text-secondary); }
          .value { font-size: 18px; font-weight: bold; color: var(--cf-text-primary); }
          .up { color: #10b981; font-size: 12px; margin-left: 8px; font-weight: normal; }

          &.mt { margin-top: 24px; margin-bottom: 0; }
        }

        .mock-chart {
          height: 60px;
          width: 100%;
          .chart-svg { width: 100%; height: 100%; overflow: visible; }
        }

        .members-avatars {
          display: flex; align-items: center;
          border: none;
          background: transparent;
          padding: 0;
          cursor: pointer;
          transition: transform 0.22s var(--cf-motion-ease);

          &:hover {
            transform: translate3d(0, -1px, 0);
          }

          .avatar {
            width: 28px;
            height: 28px;
            border-radius: 50%;
            border: 2px solid color-mix(in srgb, var(--cf-bg-base) 72%, transparent);
            background: var(--cf-gradient-primary);
            color: var(--cf-text-inverse);
            display: inline-flex;
            align-items: center;
            justify-content: center;
            font-size: 12px;
            font-weight: 900;
            margin-left: -8px;
            box-shadow: 0 8px 18px color-mix(in srgb, var(--cf-text-primary) 10%, transparent);
            &:first-child { margin-left: 0; }
          }
          .count { font-size: 13px; color: var(--cf-text-secondary); margin-left: 8px; }
        }
      }
    }
  }
}

.home-view,
.checkin-view,
.settings-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.overview-card {
  min-height: 112px;
  border: 1px solid var(--cf-border-glass);
  border-radius: 16px;
  background:
    linear-gradient(180deg, var(--cf-surface-highlight), transparent 58%),
    var(--cf-bg-glass);
  color: var(--cf-text-primary);
  cursor: pointer;
  padding: 16px;
  text-align: left;
  box-shadow: var(--cf-shadow-card);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  transition: transform 0.24s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.24s ease;

  &:hover {
    transform: translate3d(0, -3px, 0);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-card-hover);
  }

  span,
  small {
    color: var(--cf-text-secondary);
  }

  strong {
    font-size: 28px;
    line-height: 1;
  }
}

.home-section,
.settings-panel {
  border: 1px solid var(--cf-border-glass);
  border-radius: 16px;
  background:
    linear-gradient(180deg, var(--cf-surface-highlight), transparent 62%),
    var(--cf-bg-glass);
  box-shadow: var(--cf-shadow-card);
  padding: 20px;
}

.home-section header,
.settings-panel header,
.resource-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 14px;

  h3 {
    margin: 0 0 4px;
    color: var(--cf-text-primary);
    font-size: 18px;
  }

  span {
    color: var(--cf-text-muted);
    font-size: 13px;
  }

  button {
    border: 1px solid var(--cf-border-glass);
    border-radius: 999px;
    background: var(--cf-bg-glass);
    color: var(--cf-primary);
    cursor: pointer;
    padding: 8px 12px;
    font-weight: 800;
  }
}

.compact-data-row {
  min-height: 68px;
  border: 1px solid var(--cf-border-glass);
  border-radius: 13px;
  background: var(--cf-bg-glass-soft);
  color: var(--cf-text-primary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 13px 14px;
  transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.22s ease;

  & + .compact-data-row {
    margin-top: 9px;
  }

  &:hover {
    transform: translate3d(0, -2px, 0);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-soft);
  }

  div {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  strong {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span,
  small {
    color: var(--cf-text-secondary);
    font-size: 12px;
  }

  small {
    flex: 0 0 auto;
  }
}

.challenge-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 14px;
}

.challenge-card {
  padding: 18px;
  border: 1px solid var(--cf-border-glass);
  border-radius: 16px;
  background:
    linear-gradient(180deg, var(--cf-surface-highlight), transparent 58%),
    var(--cf-bg-glass);
  cursor: pointer;
  transition: transform 0.24s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.24s ease;

  &:hover {
    transform: translate3d(0, -3px, 0);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-card-hover);
  }

  p {
    min-height: 44px;
    margin: 12px 0;
    color: var(--cf-text-secondary);
    line-height: 1.55;
  }
}

.challenge-card__top,
.challenge-card__meta {
  display: flex;
  gap: 10px;
}

.challenge-card__top {
  align-items: center;
  justify-content: space-between;

  strong {
    color: var(--cf-text-primary);
    font-size: 16px;
  }

  span {
    border: 1px solid var(--cf-border-glass);
    border-radius: 999px;
    color: var(--cf-text-muted);
    padding: 3px 8px;
    font-size: 12px;

    &.active {
      border-color: color-mix(in srgb, var(--cf-primary) 44%, transparent);
      color: var(--cf-primary);
      background: var(--cf-primary-soft);
    }
  }
}

.challenge-card__meta {
  flex-wrap: wrap;

  span {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.settings-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.settings-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;

  article {
    border: 1px solid var(--cf-border-glass);
    border-radius: 14px;
    background: var(--cf-bg-glass-soft);
    padding: 14px;
    min-height: 84px;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
  }

  span {
    color: var(--cf-text-secondary);
    font-size: 12px;
  }

  strong {
    color: var(--cf-text-primary);
    font-size: 16px;
  }
}

.settings-action-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.settings-action-card {
  min-height: 76px;
  border: 1px solid var(--cf-border-glass);
  border-radius: 14px;
  background: var(--cf-bg-glass);
  color: var(--cf-text-primary);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  text-align: left;
  transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.22s ease;

  &:hover {
    transform: translate3d(0, -2px, 0);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-soft);
  }

  .n-icon {
    flex: 0 0 auto;
    color: var(--cf-primary);
    font-size: 22px;
  }

  span {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  strong {
    color: var(--cf-text-primary);
    font-size: 14px;
  }

  small {
    color: var(--cf-text-muted);
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.settings-readonly-grid {
  display: grid;
  gap: 12px;
  margin-top: 16px;

  article {
    border: 1px solid var(--cf-border-glass);
    border-radius: 14px;
    background: var(--cf-bg-glass-soft);
    padding: 14px;
  }

  span {
    display: block;
    margin-bottom: 8px;
    color: var(--cf-text-secondary);
    font-size: 13px;
    font-weight: 800;
  }

  p {
    margin: 0;
    color: var(--cf-text-primary);
    line-height: 1.65;
    white-space: pre-wrap;
  }
}

.profile-stat-summary {
  border: 1px solid var(--cf-border-glass);
  border-radius: 14px;
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--cf-primary) 14%, transparent), transparent 68%),
    var(--cf-bg-glass);
  padding: 16px;
  margin-bottom: 12px;

  span {
    color: var(--cf-text-secondary);
    display: block;
    font-size: 12px;
    margin-bottom: 6px;
  }

  strong {
    color: var(--cf-text-primary);
    display: block;
    font-size: 34px;
    line-height: 1;
  }

  p {
    color: var(--cf-text-muted);
    font-size: 13px;
    line-height: 1.55;
    margin: 9px 0 0;
  }
}

.profile-stat-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.profile-stat-item {
  width: 100%;
  border: 1px solid var(--cf-border-glass);
  border-radius: 12px;
  background: var(--cf-bg-glass);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px;
  text-align: left;
}

.profile-stat-item {
  color: inherit;
  cursor: pointer;
  font: inherit;
  transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.22s ease;

  &:hover {
    transform: translate3d(0, -1px, 0);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-soft);
  }
}

.post-detail-modal {
  --modal-width: min(94vw, 860px);
}

.post-detail-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.detail-post-card,
.detail-comment-editor,
.detail-comments {
  border: 1px solid var(--cf-border-glass);
  border-radius: 16px;
  background: var(--cf-bg-glass);
  padding: 16px;
}

.detail-post-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.detail-author {
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0;
  text-align: left;

  strong,
  small {
    display: block;
  }

  strong {
    color: var(--cf-text-primary);
    font-size: 15px;
  }

  small {
    color: var(--cf-text-muted);
    font-size: 12px;
    margin-top: 4px;
  }
}

.detail-avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 1px solid color-mix(in srgb, var(--cf-primary) 22%, transparent);
  background: var(--cf-gradient-primary);
  color: var(--cf-text-inverse);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 900;
  flex: 0 0 auto;

  &.small {
    width: 34px;
    height: 34px;
    font-size: 13px;
  }
}

.detail-badges,
.detail-tags,
.detail-actions,
.detail-comment-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.detail-badges span,
.detail-tags span {
  border: 1px solid var(--cf-border-glass);
  border-radius: 999px;
  background: var(--cf-bg-glass-soft);
  color: var(--cf-text-secondary);
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 700;
}

.detail-post-card h3 {
  margin: 0 0 10px;
  color: var(--cf-text-primary);
  font-size: 20px;
}

.detail-content {
  color: var(--cf-text-primary);
  line-height: 1.7;
  white-space: pre-wrap;
  margin: 0;
}

.detail-qa-strip {
  margin-top: 14px;
  border: 1px solid color-mix(in srgb, var(--cf-primary) 28%, transparent);
  border-radius: 14px;
  background: color-mix(in srgb, var(--cf-primary) 8%, var(--cf-bg-glass));
  padding: 12px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.detail-actions {
  margin-top: 14px;

  button {
    border: 1px solid var(--cf-border-glass);
    border-radius: 12px;
    background: var(--cf-bg-glass-soft);
    color: var(--cf-text-secondary);
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 10px 12px;
    font: inherit;

    &.active {
      border-color: color-mix(in srgb, var(--cf-primary) 40%, transparent);
      background: color-mix(in srgb, var(--cf-primary) 10%, var(--cf-bg-glass-soft));
      color: var(--cf-primary);
    }
  }
}

.post-share-preview {
  border: 1px solid var(--cf-border-glass);
  border-radius: 14px;
  background: var(--cf-bg-glass);
  padding: 14px;

  strong,
  p,
  span {
    display: block;
  }

  strong {
    color: var(--cf-text-primary);
    font-size: 15px;
    margin-bottom: 6px;
  }

  p {
    color: var(--cf-text-secondary);
    margin: 0 0 10px;
    line-height: 1.6;
  }

  span {
    color: var(--cf-text-muted);
    font-size: 12px;
    word-break: break-all;
  }
}

.detail-comment-editor textarea {
  width: 100%;
  min-height: 110px;
  border: 1px solid var(--cf-border-glass);
  border-radius: 14px;
  background: var(--cf-bg-readable);
  color: var(--cf-text-primary);
  padding: 12px 14px;
  resize: vertical;
  outline: none;
}

.detail-reply-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  color: var(--cf-text-secondary);
  font-size: 13px;

  button {
    border: none;
    background: transparent;
    color: var(--cf-primary);
    cursor: pointer;
    font: inherit;
  }
}

.detail-editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

.detail-comments {
  display: flex;
  flex-direction: column;
  gap: 12px;

  > header {
    display: flex;
    align-items: center;
    justify-content: space-between;

    h4 {
      margin: 0;
      color: var(--cf-text-primary);
      font-size: 16px;
    }

    span {
      color: var(--cf-text-muted);
      font-size: 12px;
    }
  }
}

.detail-comment {
  border: 1px solid var(--cf-border-glass);
  border-radius: 14px;
  background: var(--cf-bg-glass-soft);
  padding: 12px;
}

.detail-comment-main {
  display: flex;
  gap: 12px;
}

.detail-comment-body {
  min-width: 0;
  flex: 1;
}

.detail-comment-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  strong {
    color: var(--cf-text-primary);
    font-size: 14px;
  }

  span {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.detail-comment-body p {
  margin: 8px 0 0;
  color: var(--cf-text-primary);
  line-height: 1.65;
}

.detail-comment-actions {
  margin-top: 10px;

  button {
    border: none;
    background: transparent;
    color: var(--cf-text-secondary);
    cursor: pointer;
    padding: 0;
    font: inherit;

    &:hover {
      color: var(--cf-primary);
    }
  }
}

.detail-replies {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
  padding-left: 14px;
  border-left: 1px solid var(--cf-border-glass);
}

.detail-reply {
  border: 1px solid var(--cf-border-glass);
  border-radius: 12px;
  background: var(--cf-bg-glass);
  padding: 10px 12px;

  strong {
    color: var(--cf-text-primary);
    font-size: 13px;
  }

  span {
    color: var(--cf-text-muted);
    font-size: 11px;
    margin-left: 6px;
  }

  p {
    color: var(--cf-text-primary);
    line-height: 1.6;
    margin: 6px 0 0;
  }

  button {
    border: none;
    background: transparent;
    color: var(--cf-primary);
    cursor: pointer;
    margin-top: 8px;
    padding: 0;
    font: inherit;
  }
}

.stat-item-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: var(--cf-text-primary);
    font-size: 14px;
  }

  small {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.stat-item-value {
  flex: 0 0 auto;
  border-radius: 999px;
  background: color-mix(in srgb, var(--cf-primary) 12%, transparent);
  color: var(--cf-primary);
  font-size: 12px;
  font-weight: 900;
  padding: 5px 9px;

  &.negative {
    background: color-mix(in srgb, var(--cf-danger) 12%, transparent);
    color: var(--cf-danger);
  }
}

.settings-field {
  display: flex;
  flex-direction: column;
  gap: 7px;

  &.wide {
    grid-column: 1 / -1;
  }

  span {
    color: var(--cf-text-secondary);
    font-size: 13px;
    font-weight: 800;
  }

  input,
  select,
  textarea {
    width: 100%;
    border: 1px solid var(--cf-border-glass);
    border-radius: 12px;
    background: var(--cf-bg-readable);
    color: var(--cf-text-primary);
    outline: none;
    padding: 10px 12px;
    transition: border-color 0.22s ease, box-shadow 0.22s ease;

    &:focus {
      border-color: var(--cf-border-strong);
      box-shadow: 0 0 0 4px color-mix(in srgb, var(--cf-primary) 10%, transparent);
    }

    &:disabled {
      opacity: 0.72;
      cursor: not-allowed;
    }
  }

  &.validated-field.invalid {
    input {
      border-color: var(--cf-danger);
      box-shadow: 0 0 0 4px color-mix(in srgb, var(--cf-danger) 12%, transparent);
    }
  }

  &.validated-field.shake {
    animation: field-shake 0.48s ease;
  }

  textarea {
    min-height: 96px;
    resize: vertical;
  }
}

.field-hint {
  min-height: 18px;
  font-size: 12px;
  line-height: 1.5;

  &.error {
    color: var(--cf-danger);
  }
}

@keyframes field-shake {
  0%, 100% {
    transform: translateX(0);
  }
  20%, 60% {
    transform: translateX(-4px);
  }
  40%, 80% {
    transform: translateX(4px);
  }
}

.settings-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;

  &.left {
    justify-content: flex-start;
    margin-top: 0;
    margin-bottom: 16px;
  }

  button {
    min-height: 40px;
    padding: 8px 18px;
  }
}

.members-view {
  .member-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 24px;
    h3 { margin: 0; color: var(--cf-text-primary); font-size: 18px; }
    .search-member {
      background: rgba(255, 255, 255, 0.05);
      border-radius: 20px;
      padding: 6px 16px;
      input {
        background: transparent;
        border: none;
        color: var(--cf-text-primary);
        outline: none;
        font-size: 14px;
        &::placeholder { color: var(--cf-text-muted); }
      }
    }
  }

  .member-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 16px;

    .member-card {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 16px;
      background: var(--cf-bg-elevated);
      border: 1px solid var(--cf-border);
      border-radius: 12px;
      cursor: pointer;
      transition: transform 0.24s var(--cf-motion-ease), box-shadow 0.24s var(--cf-motion-ease), border-color 0.22s ease;

      &:hover {
        transform: translate3d(0, -3px, 0);
        border-color: var(--cf-border-strong);
        box-shadow: var(--cf-shadow-card-hover);
      }

      .avatar {
        width: 48px;
        height: 48px;
        border-radius: 50%;
        background: #6366f1;
        border: none;
        display: flex;
        align-items: center;
        justify-content: center;
        color: white;
        font-size: 18px;
        font-weight: bold;
        cursor: pointer;
      }

      .info {
        display: flex;
        flex-direction: column;
        .name { color: var(--cf-text-primary); font-size: 15px; font-weight: 500; }
        .role { color: var(--cf-text-secondary); font-size: 12px; margin-top: 4px; }
      }
    }
  }
}

.files-view {
  padding: 42px;
  .empty-state {
    min-height: 220px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    p { margin: 16px 0 24px; color: var(--cf-text-secondary); }
    button { padding: 8px 24px; }
  }

  .resource-list {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .resource-list-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 14px;

    h3 {
      margin: 0 0 4px;
      color: var(--cf-text-primary);
      font-size: 18px;
    }

    span {
      color: var(--cf-text-muted);
      font-size: 13px;
    }
  }

  .resource-card {
    display: flex;
    align-items: flex-start;
    gap: 14px;
    padding: 15px;
    border: 1px solid var(--cf-border-glass);
    border-radius: 14px;
    background:
      linear-gradient(180deg, var(--cf-surface-highlight), transparent 64%),
      var(--cf-bg-glass);
    box-shadow: 0 14px 36px color-mix(in srgb, var(--cf-text-primary) 7%, transparent);
    cursor: pointer;
    transition: transform 0.24s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.24s ease;

    &:hover {
      transform: translate3d(0, -3px, 0);
      border-color: var(--cf-border-strong);
      box-shadow: var(--cf-shadow-card-hover);
    }
  }

  .resource-icon {
    width: 42px;
    height: 42px;
    border-radius: 12px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex: 0 0 auto;
    color: var(--cf-primary);
    background: color-mix(in srgb, var(--cf-primary) 13%, var(--cf-bg-glass));
    border: 1px solid color-mix(in srgb, var(--cf-primary) 22%, transparent);
  }

  .resource-info {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 5px;

    strong {
      color: var(--cf-text-primary);
      font-size: 15px;
      overflow-wrap: anywhere;
    }

    span,
    p {
      color: var(--cf-text-secondary);
      font-size: 13px;
      line-height: 1.45;
    }

    p {
      margin: 2px 0 0;
    }
  }

  .resource-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;

    span {
      display: inline-flex;
      align-items: center;
      min-height: 24px;
      padding: 0 10px;
      border-radius: 999px;
      border: 1px solid var(--cf-border-glass);
      background: var(--cf-bg-glass-soft);
      color: var(--cf-text-secondary);
      font-size: 12px;
      line-height: 1;
      white-space: nowrap;
    }
  }
}

.empty-inline {
  padding: 30px;
  text-align: center;

  p {
    margin: 0 0 16px;
    color: var(--cf-text-secondary);
  }
}

.quick-actions,
.notice-modal-list,
.active-member-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.quick-actions button,
.active-member-item {
  width: 100%;
  border: 1px solid var(--cf-border-glass);
  border-radius: 12px;
  background: var(--cf-bg-glass);
  color: var(--cf-text-primary);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  text-align: left;
  font-size: 13px;
  transition: transform 0.24s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.24s ease;

  &:hover {
    transform: translate3d(0, -1px, 0);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-soft);
  }
}

.notice-modal-item {
  border: 1px solid var(--cf-border-glass);
  border-radius: 12px;
  background: var(--cf-bg-glass);
  padding: 12px;
  cursor: default;

  &.unread {
    border-color: color-mix(in srgb, var(--cf-primary) 38%, transparent);
    background: color-mix(in srgb, var(--cf-primary) 8%, var(--cf-bg-glass));
  }

  strong {
    display: block;
    margin-bottom: 6px;
    font-size: 14px;
  }

  p {
    margin: 0 0 8px;
    color: var(--cf-text-secondary);
    font-size: 13px;
    line-height: 1.55;
  }

  span {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.active-member-item img,
.member-avatar-fallback {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  flex: 0 0 auto;
}

.member-avatar-fallback {
  background: var(--cf-gradient-primary);
  color: var(--cf-text-inverse);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
}

.member-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;

  small {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.mini-link-btn {
  border: 1px solid color-mix(in srgb, var(--cf-primary) 34%, transparent);
  border-radius: 10px;
  background: var(--cf-primary-soft);
  color: var(--cf-primary);
  cursor: pointer;
  padding: 8px 10px;
  font-size: 12px;
  font-weight: 700;
}

:global(.upload-modal.n-card) {
  width: min(92vw, 540px);
  max-width: 540px;
  overflow: hidden;
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--cf-bg-base) 16%, transparent), transparent 58%),
    color-mix(in srgb, var(--cf-bg-base) 72%, transparent);
  border: 1px solid var(--cf-border-glass);
  border-radius: 20px;
  box-shadow:
    0 30px 84px color-mix(in srgb, var(--cf-text-primary) 20%, transparent),
    0 10px 30px color-mix(in srgb, var(--cf-primary) 10%, transparent);
  backdrop-filter: blur(calc(var(--cf-backdrop-blur) + 6px)) saturate(150%);
  -webkit-backdrop-filter: blur(calc(var(--cf-backdrop-blur) + 6px)) saturate(150%);
}

:global(.upload-modal .n-card-header) {
  display: none;
}

:global(.upload-modal .n-card__content) {
  padding: 0;
}

.member-profile-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.member-profile-loading {
  min-height: 260px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--cf-text-secondary);
}

.member-profile-cover {
  position: relative;
  overflow: hidden;
  min-height: 220px;
  border: 1px solid var(--cf-border-glass);
  border-radius: 18px;
  background: var(--cf-bg-glass);
  box-shadow: var(--cf-shadow-card);

  > img {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
    opacity: 0.9;
    transform: scale(1.08) translate3d(-1.2%, -1%, 0);
    transform-origin: center;
    animation: profileCoverDrift 26s ease-in-out infinite alternate;
    will-change: transform;
  }
}

.member-profile-cover__shade {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, transparent 18%, color-mix(in srgb, var(--cf-bg-base) 42%, transparent) 68%, var(--cf-bg-readable)),
    radial-gradient(circle at 18% 24%, color-mix(in srgb, var(--cf-primary) 20%, transparent), transparent 34%),
    radial-gradient(circle at 82% 24%, color-mix(in srgb, var(--cf-secondary) 16%, transparent), transparent 32%);
  background-size: 100% 100%, 128% 128%, 124% 124%;
  animation: profileCoverLight 20s ease-in-out infinite alternate;
}

.member-profile-identity {
  position: absolute;
  left: 22px;
  right: 22px;
  bottom: 20px;
  display: flex;
  align-items: flex-end;
  gap: 16px;

  p {
    margin: 6px 0 0;
    color: var(--cf-text-secondary);
  }
}

.member-profile-avatar {
  width: 86px;
  height: 86px;
  border: 3px solid color-mix(in srgb, var(--cf-bg-readable) 92%, transparent);
  border-radius: 50%;
  overflow: hidden;
  flex: 0 0 auto;
  background: var(--cf-gradient-primary);
  color: var(--cf-text-inverse);
  box-shadow: 0 16px 38px color-mix(in srgb, var(--cf-text-primary) 22%, transparent);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  font-weight: 900;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.member-profile-name {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 9px;

  h3 {
    margin: 0;
    color: var(--cf-text-primary);
    font-size: 24px;
    line-height: 1.15;
  }

  span {
    border: 1px solid color-mix(in srgb, var(--cf-warning) 46%, transparent);
    border-radius: 999px;
    background: color-mix(in srgb, var(--cf-warning) 12%, transparent);
    color: var(--cf-warning);
    padding: 3px 9px;
    font-size: 11px;
    font-weight: 900;
  }
}

.member-profile-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;

  article {
    border: 1px solid var(--cf-border-glass);
    border-radius: 14px;
    background: var(--cf-bg-glass);
    padding: 13px;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  span {
    color: var(--cf-text-muted);
    font-size: 12px;
  }

  strong {
    color: var(--cf-text-primary);
    font-size: 20px;
  }
}

.member-profile-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 230px;
  gap: 14px;

  main {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
}

.member-profile-section {
  border: 1px solid var(--cf-border-glass);
  border-radius: 16px;
  background:
    linear-gradient(180deg, var(--cf-surface-highlight), transparent 58%),
    var(--cf-bg-glass);
  padding: 16px;

  p {
    margin: 0 0 10px;
    color: var(--cf-text-secondary);
    line-height: 1.65;
  }

  small {
    color: var(--cf-text-muted);
  }
}

.member-profile-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;

  h4 {
    margin: 0;
    color: var(--cf-text-primary);
    font-size: 16px;
  }

  > span {
    color: var(--cf-primary);
    font-size: 12px;
    font-weight: 900;
  }

  button {
    border: 1px solid color-mix(in srgb, var(--cf-primary) 32%, transparent);
    border-radius: 999px;
    background: var(--cf-primary-soft);
    color: var(--cf-primary);
    cursor: pointer;
    padding: 7px 13px;
    font-weight: 900;
    transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.22s ease;

    &:hover:not(:disabled) {
      transform: translate3d(0, -1px, 0);
      border-color: var(--cf-border-strong);
      box-shadow: var(--cf-shadow-soft);
    }

    &:disabled {
      cursor: not-allowed;
      opacity: 0.68;
    }
  }
}

.member-profile-post {
  width: 100%;
  border: 1px solid var(--cf-border-glass);
  border-radius: 12px;
  background: color-mix(in srgb, var(--cf-bg-glass) 82%, transparent);
  color: var(--cf-text-primary);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: 9px;
  padding: 11px 12px;
  text-align: left;
  transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.22s ease;

  &:hover {
    transform: translate3d(0, -1px, 0);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-soft);
  }

  span {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.member-profile-badges {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 9px;

  button {
    aspect-ratio: 1;
    border: 1px solid color-mix(in srgb, var(--cf-primary) 26%, transparent);
    border-radius: 12px;
    background: color-mix(in srgb, var(--cf-primary) 12%, var(--cf-bg-glass));
    color: var(--cf-primary);
    cursor: default;
    font: inherit;
    font-weight: 900;

    &.locked {
      opacity: 0.48;
      filter: grayscale(0.5);
    }
  }
}

.upload-panel {
  position: relative;
  overflow: hidden;
  padding: 24px;
  color: var(--cf-text-primary);
}

.upload-panel__glow {
  position: absolute;
  inset: -80px -80px auto auto;
  width: 210px;
  height: 210px;
  border-radius: 50%;
  background: radial-gradient(circle, color-mix(in srgb, var(--cf-primary) 18%, transparent), transparent 68%);
  pointer-events: none;
}

.upload-panel__header {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 18px;
  margin-bottom: 18px;

  h2 {
    margin: 3px 0 0;
    font-size: 22px;
    line-height: 1.2;
  }
}

.upload-eyebrow {
  color: var(--cf-text-muted);
  font-size: 12px;
  font-weight: 800;
}

.upload-close {
  width: 38px;
  height: 38px;
  border: 1px solid var(--cf-border-glass);
  border-radius: 50%;
  background: var(--cf-bg-glass);
  color: var(--cf-text-secondary);
  cursor: pointer;
  font-size: 22px;
  line-height: 1;
  transition: transform 0.22s var(--cf-motion-ease), border-color 0.22s ease, color 0.22s ease;

  &:hover:not(:disabled) {
    color: var(--cf-primary);
    border-color: var(--cf-border-strong);
    transform: translate3d(0, -1px, 0);
  }

  &:disabled {
    cursor: wait;
    opacity: 0.55;
  }
}

.upload-native-input {
  display: none;
}

.upload-dropzone {
  position: relative;
  z-index: 1;
  width: 100%;
  min-height: 112px;
  border: 1px dashed color-mix(in srgb, var(--cf-primary) 42%, var(--cf-border-glass));
  border-radius: 18px;
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--cf-primary) 9%, transparent), transparent 54%),
    color-mix(in srgb, var(--cf-bg-glass) 78%, transparent);
  color: var(--cf-text-primary);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px;
  text-align: left;
  box-shadow:
    0 18px 44px color-mix(in srgb, var(--cf-text-primary) 8%, transparent),
    0 1px 0 color-mix(in srgb, #ffffff 46%, transparent) inset;
  transition: transform 0.26s var(--cf-motion-ease), border-color 0.22s ease, box-shadow 0.26s ease, background 0.22s ease;

  &.active,
  &:hover {
    transform: translate3d(0, -3px, 0);
    border-color: color-mix(in srgb, var(--cf-primary) 68%, transparent);
    box-shadow:
      0 24px 62px color-mix(in srgb, var(--cf-primary) 14%, transparent),
      0 10px 24px color-mix(in srgb, var(--cf-text-primary) 7%, transparent);
  }

  &.selected {
    border-style: solid;
    background:
      linear-gradient(135deg, color-mix(in srgb, var(--cf-secondary) 12%, transparent), transparent 52%),
      color-mix(in srgb, var(--cf-bg-glass) 86%, transparent);
  }
}

.upload-folder {
  width: 58px;
  height: 58px;
  border-radius: 16px;
  background: color-mix(in srgb, var(--cf-primary) 16%, var(--cf-bg-readable));
  color: var(--cf-primary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border: 1px solid color-mix(in srgb, var(--cf-primary) 22%, transparent);
  box-shadow: 0 16px 34px color-mix(in srgb, var(--cf-primary) 12%, transparent);
}

.upload-drop-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;

  strong {
    font-size: 15px;
    overflow-wrap: anywhere;
  }

  small {
    color: var(--cf-text-secondary);
    font-size: 12px;
    line-height: 1.45;
  }
}

.upload-form-grid {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
  margin-top: 16px;
}

.upload-field {
  display: flex;
  flex-direction: column;
  gap: 6px;

  &.wide {
    grid-column: 1 / -1;
  }

  span {
    color: var(--cf-text-secondary);
    font-size: 12px;
    font-weight: 800;
  }

  input,
  textarea {
    width: 100%;
    border: 1px solid var(--cf-border-glass);
    border-radius: 12px;
    outline: none;
    background: var(--cf-bg-glass);
    color: var(--cf-text-primary);
    padding: 10px 12px;
    font: inherit;
    transition: border-color 0.22s ease, box-shadow 0.22s ease, background 0.22s ease;

    &:focus {
      border-color: var(--cf-border-strong);
      background: var(--cf-bg-readable);
      box-shadow: 0 0 0 4px color-mix(in srgb, var(--cf-primary) 12%, transparent);
    }

    &::placeholder {
      color: var(--cf-text-muted);
    }
  }

  textarea {
    min-height: 78px;
    resize: vertical;
  }
}

.space-upload-tags {
  width: 100%;
  min-height: 42px;
  padding: 8px 10px;
  background: var(--cf-bg-glass);
  border: 1px solid var(--cf-border-glass);
  border-radius: 12px;
  transition: border-color 0.22s ease, box-shadow 0.22s ease, background 0.22s ease;

  &:focus-within {
    background: var(--cf-bg-readable);
    border-color: var(--cf-border-strong);
    box-shadow: 0 0 0 4px color-mix(in srgb, var(--cf-primary) 12%, transparent);
  }
}

.tag-hint {
  position: relative;
  z-index: 1;
  margin-top: 10px;
  color: var(--cf-text-secondary);
  font-size: 12px;
}

.challenge-modal {
  :deep(.n-card) {
    background: var(--cf-bg-glass);
    border: 1px solid var(--cf-border-glass);
    box-shadow: var(--cf-shadow-card);
  }
}

.challenge-form-panel {
  margin-top: 6px;
}

.upload-actions {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}

.upload-secondary,
.upload-primary {
  border-radius: 999px;
  cursor: pointer;
  padding: 10px 17px;
  font-weight: 900;
  transition: transform 0.22s var(--cf-motion-ease), box-shadow 0.22s ease, opacity 0.22s ease;

  &:hover:not(:disabled) {
    transform: translate3d(0, -1px, 0);
  }

  &:disabled {
    cursor: wait;
    opacity: 0.64;
  }
}

.upload-secondary {
  border: 1px solid var(--cf-border-glass);
  background: var(--cf-bg-glass);
  color: var(--cf-text-secondary);
}

.upload-primary {
  border: 1px solid color-mix(in srgb, var(--cf-primary) 56%, transparent);
  background: var(--cf-primary);
  color: var(--cf-text-inverse);
  box-shadow: 0 16px 36px color-mix(in srgb, var(--cf-primary) 24%, transparent);
}

:global(.space-modal.n-card) {
  width: 320px;
  max-width: calc(100vw - 36px);
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--cf-bg-base) 16%, transparent), transparent 54%),
    color-mix(in srgb, var(--cf-bg-base) 72%, transparent);
  border: 1px solid var(--cf-border-glass);
  border-radius: 16px;
  box-shadow: 0 24px 72px color-mix(in srgb, var(--cf-text-primary) 18%, transparent), 0 10px 28px
    color-mix(in srgb, var(--cf-text-primary) 8%, transparent);
  backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(136%);
  -webkit-backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(136%);
}

:global(.space-modal.tiny-modal.n-card) {
  width: 300px;
}

:global(.space-modal .n-card-header) {
  padding: 14px 14px 6px;
}

:global(.space-modal .n-card-header__main) {
  font-size: 15px;
  font-weight: 800;
}

:global(.space-modal .n-card__content) {
  padding: 8px 14px 14px;
}

:global(.compose-modal.n-card) {
  width: min(92vw, 640px);
  max-width: 640px;
  background: transparent;
  border: none;
  box-shadow: none;
}

:global(.compose-modal .n-card-header) {
  display: none;
}

:global(.compose-modal .n-card__content) {
  padding: 0;
}

.paper-stage {
  --paper-hand-font: 'AR PL UKai CN', 'KaiTi', 'Kaiti SC', 'STKaiti', 'LXGW WenKai', 'FZKai-Z03', 'Microsoft YaHei',
    cursive;
  --pen-cursor: url("data:image/svg+xml,%3Csvg width='32' height='32' viewBox='0 0 32 32' xmlns='http://www.w3.org/2000/svg'%3E%3Cg transform='rotate(-38 16 16)'%3E%3Crect x='13' y='4' width='6' height='18' rx='2' fill='%2307111f'/%3E%3Cpath d='M13 21h6l-3 7z' fill='%2300d8bf'/%3E%3Ccircle cx='16' cy='7' r='1.2' fill='white'/%3E%3C/g%3E%3C/svg%3E") 4 28,
    auto;
  position: relative;
  padding: 18px 28px 24px;
  cursor: var(--pen-cursor);
}

.paper-stage *,
.paper-stage input,
.paper-stage textarea {
  cursor: var(--pen-cursor) !important;
}

/* 按钮使用 pointer 光标，确保点击区域直观 */
.paper-stage button {
  cursor: pointer !important;
}

.paper-sheet {
  position: relative;
  overflow: hidden;
  min-height: 460px;
  max-height: none;
  padding: 34px 40px 28px;
  border-radius: 9px 18px 14px 9px;
  color: #182033;
  background:
    linear-gradient(90deg, rgba(255, 126, 126, 0.22) 0 1px, transparent 1px 100%) 42px 0 / 1px 100% no-repeat,
    repeating-linear-gradient(180deg, transparent 0 31px, rgba(55, 83, 120, 0.13) 32px),
    linear-gradient(135deg, rgba(255, 255, 255, 0.92), rgba(255, 253, 244, 0.74)),
    #fffaf0;
  border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow:
    0 26px 72px rgba(17, 24, 39, 0.22),
    0 8px 22px rgba(17, 24, 39, 0.12),
    12px 12px 0 rgba(255, 255, 255, 0.34) inset,
    -18px -20px 38px rgba(171, 145, 88, 0.08) inset;
  transform: rotate(-0.35deg);
  isolation: isolate;
  transform-origin: center;

  &::before {
    content: '';
    position: absolute;
    inset: 0;
    z-index: -1;
    background:
      radial-gradient(circle at 12% 12%, rgba(0, 216, 191, 0.08), transparent 26%),
      radial-gradient(circle at 86% 18%, rgba(123, 97, 255, 0.08), transparent 24%);
    pointer-events: none;
  }

  &::after {
    content: '';
    position: absolute;
    right: 0;
    bottom: 0;
    width: 96px;
    height: 96px;
    background: linear-gradient(135deg, transparent 48%, rgba(215, 196, 142, 0.36) 49%, rgba(255, 247, 219, 0.88) 72%);
    filter: drop-shadow(-8px -8px 18px rgba(90, 75, 48, 0.12));
    pointer-events: none;
  }
}

.paper-sheet.is-rippling {
  animation: paperPress 0.58s cubic-bezier(0.18, 0.82, 0.28, 1) both;
}

.paper-ripple {
  position: absolute;
  z-index: 0;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background:
    radial-gradient(circle, rgba(255, 255, 255, 0.5) 0 18%, rgba(255, 255, 255, 0.14) 28%, transparent 66%),
    radial-gradient(circle, rgba(97, 82, 54, 0.08) 0 16%, transparent 60%);
  box-shadow:
    0 0 0 1px rgba(104, 90, 62, 0.05),
    0 6px 14px rgba(72, 60, 40, 0.07) inset,
    0 -6px 14px rgba(255, 255, 255, 0.38) inset;
  mix-blend-mode: multiply;
  transform: translate(-50%, -50%);
  animation: paperRipple 0.56s cubic-bezier(0.16, 0.84, 0.28, 1) both;
  pointer-events: none;
}

.paper-header,
.paper-mode,
.paper-title-input,
.paper-content-input,
.paper-topics,
.paper-topic-list,
.paper-actions {
  position: relative;
  z-index: 1;
}

.paper-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 16px;

  h2 {
    margin: 4px 0 0;
    font-size: 29px;
    line-height: 1;
    font-family: var(--paper-hand-font);
    font-weight: 800;
  }
}

.handwritten-label {
  color: rgba(24, 32, 51, 0.62);
  font-family: var(--paper-hand-font);
  font-size: 13px;
}

.paper-close {
  position: relative;
  width: 42px;
  height: 42px;
  margin: -8px -8px 0 0;
  border: 1px solid rgba(24, 32, 51, 0.16);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.38);
  color: rgba(24, 32, 51, 0.7);
  font-size: 22px;
  line-height: 1;
  z-index: 4;
  touch-action: manipulation;

  &::before {
    content: '';
    position: absolute;
    inset: -8px;
    border-radius: 50%;
  }
}

.paper-mode {
  display: inline-flex;
  gap: 8px;
  padding: 5px;
  border: 1px solid rgba(24, 32, 51, 0.12);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.35);
  margin-bottom: 14px;

  button {
    border: none;
    border-radius: 999px;
    background: transparent;
    color: rgba(24, 32, 51, 0.68);
    cursor: pointer;
    padding: 7px 14px;
    font-weight: 800;

    &.active {
      background: rgba(0, 216, 191, 0.18);
      color: #08796f;
    }
  }
}

.paper-title-input,
.paper-content-input,
.paper-topics input {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  color: #182033;
  font-family: var(--paper-hand-font);
  letter-spacing: 0.02em;
}

.paper-title-input {
  display: block;
  font-size: 23px;
  font-weight: 800;
  margin-bottom: 10px;
  padding: 7px 0;
}

.paper-content-input {
  min-height: 178px;
  resize: none;
  font-size: 17px;
  line-height: 32px;
}

.paper-title-input::placeholder,
.paper-content-input::placeholder,
.paper-topics input::placeholder {
  color: rgba(24, 32, 51, 0.38);
}

.paper-topics {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px dashed rgba(24, 32, 51, 0.18);

  button {
    flex: 0 0 auto;
    border: 1px solid rgba(0, 216, 191, 0.38);
    border-radius: 999px;
    background: rgba(0, 216, 191, 0.13);
    color: #08796f;
    cursor: pointer;
    padding: 8px 14px;
    font-weight: 800;
  }
}

.paper-topic-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;

  button {
    border: 1px solid rgba(24, 32, 51, 0.14);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.42);
    color: rgba(24, 32, 51, 0.74);
    cursor: pointer;
    padding: 5px 10px;
    font-size: 12px;
  }
}

.paper-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}

.paper-cancel,
.paper-submit {
  border-radius: 999px;
  cursor: pointer;
  padding: 11px 18px;
  font-weight: 900;
}

.paper-cancel {
  border: 1px solid rgba(24, 32, 51, 0.16);
  background: rgba(255, 255, 255, 0.36);
  color: rgba(24, 32, 51, 0.72);
}

.paper-submit {
  border: 1px solid rgba(0, 216, 191, 0.5);
  background: #00d8bf;
  color: #041311;
  box-shadow: 0 14px 34px rgba(0, 216, 191, 0.22);

  &:disabled {
    cursor: wait;
    opacity: 0.7;
  }
}

.pen-illustration {
  position: absolute;
  z-index: 2;
  width: 22px;
  height: 150px;
  transform: rotate(34deg);
  filter: drop-shadow(0 18px 20px rgba(17, 24, 39, 0.22));
  pointer-events: none;
}

.resting-pen {
  right: 12px;
  top: 4px;
}

.writing-pen {
  z-index: 3;
  opacity: 0;
  transform: translate3d(0, 0, 0) rotate(34deg);
  transition: left 0.12s ease-out, top 0.12s ease-out, opacity 0.12s ease-out;
}

.writing-pen.is-writing {
  opacity: 1;
  animation: penWriting 0.26s steps(2, end) infinite;
}

.pen-cap,
.pen-body,
.pen-tip {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
}

.ink-stroke {
  position: absolute;
  z-index: 2;
  color: rgba(24, 32, 51, 0.78);
  font-family: var(--paper-hand-font);
  font-size: 22px;
  font-weight: 800;
  pointer-events: none;
  transform-origin: left bottom;
  animation: inkWrite 0.5s ease-out both;
  filter: blur(0.1px);
}

.pen-cap {
  top: 0;
  width: 15px;
  height: 23px;
  border-radius: 10px 10px 4px 4px;
  background: #101827;
}

.pen-body {
  top: 20px;
  width: 17px;
  height: 92px;
  border-radius: 10px;
  background: linear-gradient(90deg, #121827, #334155 44%, #101827);
}

.pen-tip {
  top: 110px;
  width: 0;
  height: 0;
  border-left: 9px solid transparent;
  border-right: 9px solid transparent;
  border-top: 28px solid #00d8bf;
}

@keyframes paperRipple {
  0% {
    opacity: 0;
    transform: translate(-50%, -50%) scale(0.25);
    filter: blur(0);
  }
  18% {
    opacity: 0.46;
  }
  100% {
    opacity: 0;
    transform: translate(-50%, -50%) scale(8);
    filter: blur(1.2px);
  }
}

@keyframes paperPress {
  0% {
    transform: rotate(-0.35deg) translate3d(0, 0, 0) scale(1);
    box-shadow:
      0 26px 72px rgba(17, 24, 39, 0.22),
      0 8px 22px rgba(17, 24, 39, 0.12),
      12px 12px 0 rgba(255, 255, 255, 0.34) inset,
      -18px -20px 38px rgba(171, 145, 88, 0.08) inset;
  }
  28% {
    transform: rotate(-0.35deg) translate3d(0, 2px, 0) scale(0.997);
    box-shadow:
      0 18px 54px rgba(17, 24, 39, 0.19),
      0 5px 16px rgba(17, 24, 39, 0.1),
      6px 7px 14px rgba(99, 77, 45, 0.07) inset,
      -10px -12px 26px rgba(255, 255, 255, 0.28) inset;
  }
  100% {
    transform: rotate(-0.35deg) translate3d(0, 0, 0) scale(1);
    box-shadow:
      0 26px 72px rgba(17, 24, 39, 0.22),
      0 8px 22px rgba(17, 24, 39, 0.12),
      12px 12px 0 rgba(255, 255, 255, 0.34) inset,
      -18px -20px 38px rgba(171, 145, 88, 0.08) inset;
  }
}

@keyframes penWriting {
  0% {
    transform: translate3d(0, 0, 0) rotate(31deg);
  }
  50% {
    transform: translate3d(3px, 4px, 0) rotate(37deg);
  }
  100% {
    transform: translate3d(1px, -1px, 0) rotate(33deg);
  }
}

@keyframes inkWrite {
  0% {
    opacity: 0;
    clip-path: inset(0 100% 0 0);
    transform: translate3d(-2px, 2px, 0) scale(0.96) rotate(-1deg);
  }
  45% {
    opacity: 0.92;
    clip-path: inset(0 35% 0 0);
  }
  100% {
    opacity: 0;
    clip-path: inset(0 0 0 0);
    transform: translate3d(0, 0, 0) scale(1) rotate(0deg);
  }
}

@keyframes profileCoverDrift {
  0% {
    transform: scale(1.08) translate3d(-1.4%, -1.2%, 0);
  }
  50% {
    transform: scale(1.12) translate3d(1.2%, 0.8%, 0);
  }
  100% {
    transform: scale(1.09) translate3d(-0.6%, 1.4%, 0);
  }
}

@keyframes profileCoverLight {
  0% {
    background-position: 0 0, 0 0, 100% 0;
  }
  100% {
    background-position: 0 0, 18% 12%, 78% 18%;
  }
}

@keyframes profileEnvironmentDrift {
  0% {
    background-position: 0 0, 50% 50%, 50% 50%;
  }
  100% {
    background-position: 0 -96px, 50% 42%, 50% 58%;
  }
}

@media (prefers-reduced-motion: no-preference) {
  .embedded-profile {
    animation: profileEnvironmentDrift 9s linear infinite;
  }
}

@media (prefers-reduced-motion: reduce) {
  .embedded-profile,
  .profile-cover-card > img,
  .profile-cover-card__shade,
  .member-profile-cover > img,
  .member-profile-cover__shade {
    animation: none;
  }
}

@media (max-width: 1080px) {
  .embedded-profile__container {
    grid-template-columns: 1fr;
  }

  .profile-feed,
  .profile-side {
    grid-column: 1;
    grid-row: auto;
  }
}

@media (max-width: 720px) {
  .embedded-profile__top,
  .embedded-profile__scroll {
    padding-left: 16px;
    padding-right: 16px;
  }

  .embedded-profile__top {
    gap: 10px;
  }

  .embedded-profile__container {
    grid-template-columns: 1fr;
    gap: 16px;
  }

  .profile-cover-card {
    min-height: 300px;
  }

  .profile-identity {
    inset: 0;
    padding: 22px;
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
    justify-content: flex-end;
  }

  .profile-avatar-large {
    width: 76px;
    height: 76px;
    font-size: 32px;
  }

  .profile-name-row h2 {
    font-size: 25px;
  }

  .profile-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .profile-feed,
  .profile-side {
    grid-column: 1;
    grid-row: auto;
  }

  .member-profile-metrics,
  .member-profile-body {
    grid-template-columns: 1fr;
  }

  .member-profile-cover {
    min-height: 260px;
  }

  .member-profile-identity {
    align-items: flex-start;
    flex-direction: column;
  }

  .profile-tabs {
    overflow-x: auto;

    button {
      flex: 0 0 auto;
    }
  }

  .profile-feed-card__actions {
    gap: 14px;
    flex-wrap: wrap;

    span:last-child {
      margin-left: 0;
    }
  }

  .paper-stage {
    padding: 12px 4px;
  }

  .paper-sheet {
    min-height: 470px;
    padding: 28px 22px 24px;
  }

  .paper-header h2 {
    font-size: 27px;
  }

  .pen-illustration {
    display: none;
  }
}
</style>
