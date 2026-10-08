<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { NEmpty, NIcon, NModal, NSpin, useMessage } from 'naive-ui';
import {
  ArrowBackOutline,
  CalendarOutline,
  ChatboxOutline,
  CheckmarkCircleOutline,
  CopyOutline,
  DocumentTextOutline,
  EyeOffOutline,
  EyeOutline,
  HeartOutline,
  MedalOutline,
  SaveOutline,
  SettingsOutline,
  ShareSocialOutline,
  ThumbsUpOutline,
} from '@vicons/ionicons5';
import { changePassword } from '@/api/auth';
import { getUserAchievements } from '@/api/achievement';
import { getChallenges } from '@/api/checkin';
import { getFollowCounts, getUserFollowers, getUserFollowing } from '@/api/follows';
import { getPosts } from '@/api/posts';
import { getMyProfile, updateProfile, uploadProfileAsset } from '@/api/users';
import { getPasswordStrength, validateConfirmPassword, validateNickname, validatePassword } from '@/utils/authValidation';
import { copyTextToClipboard } from '@/utils/clipboard';
import type { AchievementVO } from '@/types/achievement';
import type { CheckinChallengeVO } from '@/types/checkin';
import type { PostVO } from '@/types/post';
import type { UserVO } from '@/types/user';
import auroraBg from '@/assets/images/aurora_bg.png';

const router = useRouter();
const message = useMessage();

const user = ref<UserVO | null>(null);
const posts = ref<PostVO[]>([]);
const achievements = ref<AchievementVO[]>([]);
const challenges = ref<CheckinChallengeVO[]>([]);
const followCounts = ref({ followers: 0, following: 0 });
const loading = ref(true);
const saving = ref(false);
const assetUploading = ref<'avatar' | 'cover' | null>(null);
const editing = ref(false);
const activeTab = ref('上传的资源');
const followsVisible = ref(false);
const followsLoading = ref(false);
const followsTab = ref<'followers' | 'following'>('following');
const followUsers = ref<UserVO[]>([]);
const likesVisible = ref(false);
const avatarInputRef = ref<HTMLInputElement | null>(null);
const coverInputRef = ref<HTMLInputElement | null>(null);

const form = ref({
  nickname: '',
  avatarUrl: '',
  profileCoverUrl: '',
  bio: '',
  college: '',
  major: '',
  grade: '',
});
const profileNicknameState = ref({ active: false, touched: false, error: '', shaking: false });
const passwordForm = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
});
const passwordSaving = ref(false);
const showOldPassword = ref(false);
const showNewPassword = ref(false);
const showConfirmPassword = ref(false);
const passwordState = ref({
  oldPassword: { active: false, touched: false, error: '', shaking: false },
  newPassword: { active: false, touched: false, error: '', shaking: false },
  confirmPassword: { active: false, touched: false, error: '', shaking: false },
});

const tabs = ['上传的资源', '软件', '学习记录', '整理成果'];
const avatarText = computed(() => user.value?.nickname?.charAt(0)?.toUpperCase() || 'U');
const profilePasswordStrength = computed(() => getPasswordStrength(passwordForm.value.newPassword));
const profileTitle = computed(() => {
  if (!user.value) return '资料待同步';
  return [user.value.college, user.value.major, user.value.grade].filter(Boolean).join(' · ') || '正在完善学习档案';
});
const likeCount = computed(() => posts.value.reduce((sum, post) => sum + post.likeCount, 0));
const likedPosts = computed(() =>
  [...posts.value]
    .filter((post) => post.likeCount > 0)
    .sort((a, b) => b.likeCount - a.likeCount || new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()),
);
const awardedAchievements = computed(() => achievements.value.filter((item) => item.awarded));
const profileChallenges = computed(() =>
  challenges.value
    .filter((item) => item.isMember || item.creatorId === user.value?.id)
    .sort((a, b) => (b.myConsecutiveDays || 0) - (a.myConsecutiveDays || 0) || new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()),
);
const checkinStreak = computed(() => Math.max(0, ...profileChallenges.value.map((item) => item.myConsecutiveDays || 0)));
const feedPosts = computed(() => (activeTab.value === '上传的资源' ? posts.value.slice(0, 6) : posts.value));
const profileLink = computed(() => {
  if (!user.value?.id) return '';
  return `${window.location.origin}/users/${user.value.id}`;
});

async function loadProfile() {
  loading.value = true;
  try {
    const profile = await getMyProfile();
    user.value = profile;
    syncForm(profile);
    const [counts, postList, achievementList, challengeList] = await Promise.all([
      getFollowCounts(profile.id).catch(() => ({ followers: 0, following: 0 })),
      getPosts({ scope: 'SQUARE', authorId: profile.id, sort: 'latest', limit: 20 }).catch(() => []),
      getUserAchievements(profile.id).catch(() => []),
      getChallenges({ limit: 50 }).catch(() => []),
    ]);
    followCounts.value = counts;
    posts.value = postList;
    achievements.value = achievementList;
    challenges.value = challengeList;
  } catch (error) {
    user.value = null;
    message.error(error instanceof Error ? error.message : '个人中心加载失败');
  } finally {
    loading.value = false;
  }
}

function syncForm(profile = user.value) {
  if (!profile) return;
  form.value = {
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

type ProfilePasswordField = keyof typeof passwordState.value;

function shakeState(state: { shaking: boolean }) {
  state.shaking = false;
  window.setTimeout(() => {
    state.shaking = true;
    window.setTimeout(() => {
      state.shaking = false;
    }, 520);
  }, 0);
}

function validateProfileNickname() {
  profileNicknameState.value.error = validateNickname(form.value.nickname);
}

function focusProfileNickname() {
  profileNicknameState.value.active = true;
  validateProfileNickname();
}

function blurProfileNickname() {
  const state = profileNicknameState.value;
  state.active = false;
  state.touched = true;
  validateProfileNickname();
  if (state.error) {
    shakeState(state);
  }
}

function validatePasswordField(field: ProfilePasswordField) {
  const validators: Record<ProfilePasswordField, () => string> = {
    oldPassword: () => (passwordForm.value.oldPassword ? '' : '请输入当前密码'),
    newPassword: () => validatePassword(passwordForm.value.newPassword),
    confirmPassword: () => validateConfirmPassword(passwordForm.value.confirmPassword, passwordForm.value.newPassword),
  };
  passwordState.value[field].error = validators[field]();
  if (field === 'newPassword' && passwordForm.value.confirmPassword) {
    passwordState.value.confirmPassword.error = validateConfirmPassword(passwordForm.value.confirmPassword, passwordForm.value.newPassword);
  }
}

function focusPasswordField(field: ProfilePasswordField) {
  passwordState.value[field].active = true;
  validatePasswordField(field);
}

function blurPasswordField(field: ProfilePasswordField) {
  const state = passwordState.value[field];
  state.active = false;
  state.touched = true;
  validatePasswordField(field);
  if (state.error) {
    shakeState(state);
  }
}

function validatePasswordForm() {
  (Object.keys(passwordState.value) as ProfilePasswordField[]).forEach((field) => {
    passwordState.value[field].touched = true;
    validatePasswordField(field);
  });
  return (Object.keys(passwordState.value) as ProfilePasswordField[]).every((field) => !passwordState.value[field].error);
}

function resetPasswordForm() {
  passwordForm.value = {
    oldPassword: '',
    newPassword: '',
    confirmPassword: '',
  };
  passwordState.value = {
    oldPassword: { active: false, touched: false, error: '', shaking: false },
    newPassword: { active: false, touched: false, error: '', shaking: false },
    confirmPassword: { active: false, touched: false, error: '', shaking: false },
  };
}

function cancelProfileEdit() {
  syncForm();
  resetPasswordForm();
  editing.value = false;
}

async function saveProfile() {
  if (!user.value || saving.value) return;
  profileNicknameState.value.touched = true;
  validateProfileNickname();
  if (profileNicknameState.value.error) {
    shakeState(profileNicknameState.value);
    message.warning('请按提示修正昵称');
    return;
  }
  saving.value = true;
  try {
    user.value = await updateProfile({
      nickname: form.value.nickname.trim(),
      avatarUrl: form.value.avatarUrl.trim() || undefined,
      profileCoverUrl: form.value.profileCoverUrl.trim() || undefined,
      bio: form.value.bio.trim() || undefined,
      college: form.value.college.trim() || undefined,
      major: form.value.major.trim() || undefined,
      grade: form.value.grade.trim() || undefined,
    });
    syncForm();
    editing.value = false;
    message.success('个人资料已保存');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function savePassword() {
  if (passwordSaving.value) return;
  if (!validatePasswordForm()) {
    message.warning('请按提示修正密码');
    return;
  }
  passwordSaving.value = true;
  try {
    await changePassword(passwordForm.value.oldPassword, passwordForm.value.newPassword);
    resetPasswordForm();
    message.success('密码已更新，请牢记新密码');
  } catch (error) {
    resetPasswordForm();
    message.error(error instanceof Error ? error.message : '密码更新失败');
  } finally {
    passwordSaving.value = false;
  }
}

function openAssetPicker(target: 'avatar' | 'cover') {
  if (assetUploading.value) return;
  if (target === 'avatar') {
    avatarInputRef.value?.click();
  } else {
    coverInputRef.value?.click();
  }
}

async function handleAssetChange(event: Event, target: 'avatar' | 'cover') {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || assetUploading.value) return;
  if (!file.type.startsWith('image/')) {
    message.warning('请选择图片文件');
    return;
  }
  if (file.size > 5 * 1024 * 1024) {
    message.warning('图片不能超过 5MB');
    return;
  }

  assetUploading.value = target;
  try {
    const asset = await uploadProfileAsset(file);
    if (target === 'avatar') {
      form.value.avatarUrl = asset.url;
    } else {
      form.value.profileCoverUrl = asset.url;
    }
    message.success(target === 'avatar' ? '头像已上传' : '封面已上传');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '图片上传失败');
  } finally {
    assetUploading.value = null;
  }
}

function tabCount(tab: string) {
  if (tab === '上传的资源') return posts.value.length;
  if (tab === '软件') return profileChallenges.value.length;
  if (tab === '整理成果') return achievements.value.length;
  return profileChallenges.value.length;
}

async function goFollows(tab: 'followers' | 'following') {
  if (!user.value) return;
  followsTab.value = tab;
  followsVisible.value = true;
  followsLoading.value = true;
  try {
    followUsers.value =
      tab === 'followers'
        ? await getUserFollowers(user.value.id, undefined, 30)
        : await getUserFollowing(user.value.id, undefined, 30);
  } catch {
    followUsers.value = [];
    message.error('清单加载失败');
  } finally {
    followsLoading.value = false;
  }
}

function openLikes() {
  likesVisible.value = true;
}

function goPost(postId: number) {
  router.push('/resources');
}

function goChallenge(challengeId: number) {
  router.push('/learning');
}

function goSoftware() {
  router.push('/software');
}

function goCreatePost() {
  router.push('/resources/upload');
}

function goCheckin() {
  router.push('/learning');
}

async function copyProfileLink() {
  if (!profileLink.value) return;
  if (await copyTextToClipboard(profileLink.value)) {
    message.success('个人中心链接已复制');
  } else {
    message.warning(`复制失败，请手动复制：${profileLink.value}`);
  }
}

function formatTime(value?: string) {
  if (!value) return '刚刚';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '刚刚';
  return date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function formatDate(value?: string | null) {
  if (!value) return '暂无记录';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '暂无记录';
  return date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' });
}

function formatCompactNumber(value?: number | null) {
  const count = value ?? 0;
  if (count >= 10000) return `${(count / 10000).toFixed(1)}万`;
  if (count >= 1000) return `${(count / 1000).toFixed(1)}k`;
  return String(count);
}

function postTitle(post: PostVO) {
  return post.title || '未命名资源';
}

function postPreview(content: string) {
  const normalized = content.replace(/\s+/g, ' ').trim();
  return normalized.length > 140 ? `${normalized.slice(0, 140)}...` : normalized;
}

onMounted(loadProfile);
</script>

<template>
  <div class="profile-layout">
    <header class="top-header">
      <button
        class="header-left"
        type="button"
        @click="router.back()"
      >
        <n-icon size="20">
          <ArrowBackOutline />
        </n-icon>
        <span>个人中心</span>
      </button>
      <div class="header-actions">
        <button
          title="复制个人中心链接"
          type="button"
          @click="copyProfileLink"
        >
          <n-icon size="20">
            <CopyOutline />
          </n-icon>
        </button>
        <button
          title="编辑资料"
          type="button"
          @click="editing = !editing"
        >
          <n-icon size="20">
            <SettingsOutline />
          </n-icon>
        </button>
      </div>
    </header>

    <div class="scroll-content">
      <div
        v-if="loading"
        class="profile-loading glass-card"
      >
        <n-spin size="large" />
        <span>正在加载个人中心</span>
      </div>

      <div
        v-else-if="user"
        class="profile-container"
      >
        <section class="cover-section glass-card">
          <button
            class="cover-edit-hit"
            type="button"
            title="修改知识库封面"
            @click="editing = true"
          >
            <img
              :src="user.profileCoverUrl || auroraBg"
              class="cover-img"
              alt="知识库封面"
            />
          </button>
          <div class="profile-main-info">
            <button
              class="avatar-wrapper"
              type="button"
              title="修改头像"
              @click="editing = true"
            >
              <div class="avatar">
                <img
                  v-if="user.avatarUrl"
                  :src="user.avatarUrl"
                  :alt="user.nickname"
                />
                <span v-else>{{ avatarText }}</span>
              </div>
            </button>
            <div class="user-details">
              <div class="name-row">
                <h2>{{ user.nickname }}</h2>
                <span class="level-tag">{{ user.role || 'USER' }}</span>
              </div>
              <p class="title">
                {{ profileTitle }}
              </p>
              <button
                class="bio-edit"
                type="button"
                title="修改个人简介"
                @click="editing = true"
              >
                {{ user.bio || '还没有写下个人简介。' }}
              </button>

              <div class="stats-row">
                <button
                  class="stat"
                  type="button"
                  @click="goFollows('following')"
                >
                  <span class="label">收藏资源</span>
                  <span class="val">{{ formatCompactNumber(followCounts.following) }}</span>
                </button>
                <button
                  class="stat"
                  type="button"
                  @click="goFollows('followers')"
                >
                  <span class="label">软件清单</span>
                  <span class="val">{{ formatCompactNumber(followCounts.followers) }}</span>
                </button>
                <button
                  class="stat"
                  type="button"
                  @click="openLikes"
                >
                  <span class="label">整理反馈</span>
                  <span class="val">{{ formatCompactNumber(likeCount) }}</span>
                </button>
                <button
                  class="outline-btn edit-btn"
                  type="button"
                  @click="editing = !editing"
                >
                  {{ editing ? '收起编辑' : '编辑资料' }}
                </button>
              </div>
            </div>
          </div>
        </section>
        <section
          v-if="editing"
          class="edit-panel glass-card"
        >
          <div class="edit-preview">
            <img
              :src="form.profileCoverUrl || auroraBg"
              class="cover-img"
              alt="知识库封面预览"
            />
            <button
              class="edit-cover-btn"
              type="button"
              :disabled="Boolean(assetUploading)"
              @click="openAssetPicker('cover')"
            >
              {{ assetUploading === 'cover' ? '上传中...' : '更换封面' }}
            </button>
            <button
              class="avatar preview-avatar"
              type="button"
              :disabled="Boolean(assetUploading)"
              @click="openAssetPicker('avatar')"
            >
              <img
                v-if="form.avatarUrl"
                :src="form.avatarUrl"
                alt="头像预览"
              />
              <span v-else>{{ avatarText }}</span>
              <small>{{ assetUploading === 'avatar' ? '上传中' : '更换头像' }}</small>
            </button>
          </div>
          <input
            ref="avatarInputRef"
            class="asset-input"
            type="file"
            accept="image/png,image/jpeg,image/gif,image/webp"
            @change="handleAssetChange($event, 'avatar')"
          />
          <input
            ref="coverInputRef"
            class="asset-input"
            type="file"
            accept="image/png,image/jpeg,image/gif,image/webp"
            @change="handleAssetChange($event, 'cover')"
          />
          <label
            class="validated-field"
            :class="{ invalid: profileNicknameState.touched && profileNicknameState.error, shake: profileNicknameState.shaking }"
          >
            <span>昵称</span>
            <input
              v-model="form.nickname"
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
          <div class="asset-field">
            <span>头像</span>
            <button
              type="button"
              :disabled="Boolean(assetUploading)"
              @click="openAssetPicker('avatar')"
            >
              {{ form.avatarUrl ? '重新选择头像' : '选择头像图片' }}
            </button>
          </div>
          <div class="asset-field wide">
            <span>知识库封面</span>
            <button
              type="button"
              :disabled="Boolean(assetUploading)"
              @click="openAssetPicker('cover')"
            >
              {{ form.profileCoverUrl ? '重新选择封面' : '选择封面图片' }}
            </button>
          </div>
          <label>
            <span>学院</span>
            <input v-model="form.college" />
          </label>
          <label>
            <span>专业</span>
            <input v-model="form.major" />
          </label>
          <label>
            <span>年级</span>
            <input v-model="form.grade" />
          </label>
          <label class="wide">
            <span>个人简介</span>
            <textarea
              v-model="form.bio"
              maxlength="500"
            />
          </label>
          <div class="password-panel wide">
            <div class="password-panel-head">
              <strong>账号安全</strong>
              <span>修改密码后，下一次登录请使用新密码。</span>
            </div>
            <div class="password-grid">
              <label
                class="validated-field"
                :class="{ invalid: passwordState.oldPassword.touched && passwordState.oldPassword.error, shake: passwordState.oldPassword.shaking }"
              >
                <span>当前密码</span>
                <div class="relative">
                  <input
                    v-model="passwordForm.oldPassword"
                    :type="showOldPassword ? 'text' : 'password'"
                    autocomplete="current-password"
                    class="pr-10"
                    @focus="focusPasswordField('oldPassword')"
                    @blur="blurPasswordField('oldPassword')"
                    @input="validatePasswordField('oldPassword')"
                  />
                  <button
                    type="button"
                    class="absolute right-3 top-1/2 -translate-y-1/2 text-outline-variant hover:text-on-surface transition-colors"
                    @click="showOldPassword = !showOldPassword"
                    tabindex="-1"
                  >
                    <EyeOffOutline v-if="showOldPassword" class="w-5 h-5" />
                    <EyeOutline v-else class="w-5 h-5" />
                  </button>
                </div>
                <small
                  v-if="passwordState.oldPassword.touched && passwordState.oldPassword.error"
                  class="field-hint error"
                >
                  {{ passwordState.oldPassword.error }}
                </small>
              </label>
              <label
                class="validated-field"
                :class="{ invalid: passwordState.newPassword.touched && passwordState.newPassword.error, shake: passwordState.newPassword.shaking }"
              >
                <span>新密码</span>
                <div class="relative">
                  <input
                    v-model="passwordForm.newPassword"
                    :type="showNewPassword ? 'text' : 'password'"
                    maxlength="32"
                    autocomplete="new-password"
                    placeholder="8-32 位新密码"
                    class="pr-10"
                    @focus="focusPasswordField('newPassword')"
                    @blur="blurPasswordField('newPassword')"
                    @input="validatePasswordField('newPassword')"
                  />
                  <button
                    type="button"
                    class="absolute right-3 top-1/2 -translate-y-1/2 text-outline-variant hover:text-on-surface transition-colors"
                    @click="showNewPassword = !showNewPassword"
                    tabindex="-1"
                  >
                    <EyeOffOutline v-if="showNewPassword" class="w-5 h-5" />
                    <EyeOutline v-else class="w-5 h-5" />
                  </button>
                </div>
                <div
                  v-if="passwordState.newPassword.active || passwordForm.newPassword"
                  class="password-strength"
                  :class="profilePasswordStrength.strength"
                >
                  <div class="strength-bar">
                    <span />
                  </div>
                  <small>密码强度：{{ profilePasswordStrength.label }}，{{ profilePasswordStrength.hint }}</small>
                </div>
                <small
                  v-if="passwordState.newPassword.touched && passwordState.newPassword.error"
                  class="field-hint error"
                >
                  {{ passwordState.newPassword.error }}
                </small>
              </label>
              <label
                class="validated-field"
                :class="{ invalid: passwordState.confirmPassword.touched && passwordState.confirmPassword.error, shake: passwordState.confirmPassword.shaking }"
              >
                <span>确认新密码</span>
                <div class="relative">
                  <input
                    v-model="passwordForm.confirmPassword"
                    :type="showConfirmPassword ? 'text' : 'password'"
                    maxlength="32"
                    autocomplete="new-password"
                    class="pr-10"
                    @focus="focusPasswordField('confirmPassword')"
                    @blur="blurPasswordField('confirmPassword')"
                    @input="validatePasswordField('confirmPassword')"
                  />
                  <button
                    type="button"
                    class="absolute right-3 top-1/2 -translate-y-1/2 text-outline-variant hover:text-on-surface transition-colors"
                    @click="showConfirmPassword = !showConfirmPassword"
                    tabindex="-1"
                  >
                    <EyeOffOutline v-if="showConfirmPassword" class="w-5 h-5" />
                    <EyeOutline v-else class="w-5 h-5" />
                  </button>
                </div>
                <small
                  v-if="passwordState.confirmPassword.touched && passwordState.confirmPassword.error"
                  class="field-hint error"
                >
                  {{ passwordState.confirmPassword.error }}
                </small>
              </label>
              <button
                class="outline-btn password-save-btn"
                type="button"
                :disabled="passwordSaving"
                @click="savePassword"
              >
                {{ passwordSaving ? '更新中...' : '更新密码' }}
              </button>
            </div>
          </div>
          <div class="edit-actions">
            <button
              class="outline-btn"
              type="button"
              @click="cancelProfileEdit"
            >
              取消
            </button>
            <button
              class="primary-btn"
              type="button"
              :disabled="saving"
              @click="saveProfile"
            >
              <n-icon><SaveOutline /></n-icon>
              {{ saving ? '保存中...' : '保存资料' }}
            </button>
          </div>
        </section>

        <div class="content-section">
          <main class="main-left">
            <nav class="tabs glass-card">
              <button
                v-for="tab in tabs"
                :key="tab"
                type="button"
                class="tab"
                :class="{ active: activeTab === tab }"
                @click="activeTab = tab"
              >
                <span>{{ tab }}</span>
                <small>{{ tabCount(tab) }}</small>
              </button>
            </nav>

            <div class="feed-list">
              <template v-if="activeTab === '上传的资源'">
                <article
                  v-for="post in feedPosts"
                  :key="post.id"
                  class="feed-item glass-card"
                  @click="goPost(post.id)"
                >
                  <div class="feed-header">
                    <div class="avatar-mini">
                      {{ avatarText }}
                    </div>
                    <div class="info">
                      <span class="name">{{ user.nickname }}</span>
                      <span class="time">{{ formatTime(post.createdAt) }}</span>
                    </div>
                    <n-icon class="more">
                      <DocumentTextOutline />
                    </n-icon>
                  </div>
                  <div class="feed-content">
                    <strong>{{ postTitle(post) }}</strong>
                    <p>{{ postPreview(post.content) }}</p>
                  </div>
                  <div class="feed-actions">
                    <span class="action"><n-icon><ThumbsUpOutline /></n-icon> {{ post.likeCount }}</span>
                    <span class="action"><n-icon><ChatboxOutline /></n-icon> {{ post.commentCount }}</span>
                    <span class="action"><n-icon><ShareSocialOutline /></n-icon> 查看详情</span>
                  </div>
                </article>
                <div
                  v-if="feedPosts.length === 0"
                  class="empty-card glass-card"
                >
                  <n-empty description="还没有上传资源" />
                  <button
                    class="primary-btn"
                    type="button"
                    @click="goCreatePost"
                  >
                    上传资源
                  </button>
                </div>
              </template>

              <template v-else-if="activeTab === '软件' || activeTab === '学习记录'">
                <article
                  v-for="challenge in profileChallenges"
                  :key="challenge.id"
                  class="feed-item checkin-item glass-card"
                  @click="goChallenge(challenge.id)"
                >
                  <div class="feed-header">
                    <div class="avatar-mini">
                      <n-icon><CheckmarkCircleOutline /></n-icon>
                    </div>
                    <div class="info">
                      <span class="name">{{ challenge.name }}</span>
                      <span class="time">{{ challenge.startDate }} ~ {{ challenge.endDate }}</span>
                    </div>
                  </div>
                  <div class="feed-content">
                    <p>{{ challenge.description || '这个条目还没有说明。' }}</p>
                  </div>
                  <div class="feed-actions">
                    <span class="action">累计 {{ challenge.myTotalDays || 0 }} 天</span>
                    <span class="action">连续 {{ challenge.myConsecutiveDays || 0 }} 天</span>
                    <span class="action">条目 {{ challenge.memberCount }}</span>
                  </div>
                </article>
                <div
                  v-if="profileChallenges.length === 0"
                  class="empty-card glass-card"
                >
                  <n-empty :description="activeTab === '软件' ? '还没有整理软件' : '还没有学习记录'" />
                  <button
                    class="primary-btn"
                    type="button"
                    @click="activeTab === '软件' ? goSoftware() : goCheckin()"
                  >
                    {{ activeTab === '软件' ? '查看软件' : '查看学习' }}
                  </button>
                </div>
              </template>

              <template v-else>
                <article
                  v-for="achievement in achievements"
                  :key="achievement.id"
                  class="feed-item achievement-row glass-card"
                  :class="{ locked: !achievement.awarded }"
                >
                  <div class="avatar-mini">
                    <n-icon><MedalOutline /></n-icon>
                  </div>
                  <div>
                    <strong>{{ achievement.name }}</strong>
                    <p>{{ achievement.description }}</p>
                  </div>
                  <span>{{ achievement.awarded ? '已解锁' : '待解锁' }}</span>
                </article>
                <div
                  v-if="achievements.length === 0"
                  class="empty-card glass-card"
                >
                  <n-empty description="暂无整理成果" />
                </div>
              </template>
            </div>
          </main>

          <aside class="sidebar-right">
            <section class="glass-card widget">
              <div class="widget-header">
                <h3>整理成果</h3>
                <button
                  type="button"
                  @click="activeTab = '整理成果'"
                >
                  {{ awardedAchievements.length }}/{{ achievements.length || 0 }}
                </button>
              </div>
              <div
                v-if="achievements.length"
                class="badges"
              >
                <button
                  v-for="achievement in achievements.slice(0, 4)"
                  :key="achievement.id"
                  type="button"
                  class="badge neon-glow"
                  :class="{ locked: !achievement.awarded }"
                  :title="achievement.name"
                  @click="activeTab = '整理成果'"
                >
                  {{ achievement.name.charAt(0) }}
                </button>
              </div>
              <p
              v-else
              class="widget-empty"
            >
                暂无整理成果
              </p>
            </section>

            <section class="glass-card widget">
              <div class="widget-header">
                <h3>近期学习</h3>
                <button
                  type="button"
                  @click="activeTab = '学习记录'"
                >
                  连续 {{ checkinStreak }} 天
                </button>
              </div>
              <div
                v-if="profileChallenges.length"
                class="checkin-list"
              >
                <button
                  v-for="challenge in profileChallenges.slice(0, 3)"
                  :key="challenge.id"
                  type="button"
                  @click="goChallenge(challenge.id)"
                >
                  <strong>{{ challenge.name }}</strong>
                  <span>累计 {{ challenge.myTotalDays || 0 }} 天 · 连续 {{ challenge.myConsecutiveDays || 0 }} 天</span>
                </button>
              </div>
              <p
              v-else
              class="widget-empty"
            >
                暂无学习记录
              </p>
            </section>

            <section class="glass-card widget">
              <div class="mini-metric">
                <n-icon><CalendarOutline /></n-icon>
                <div>
                  <span>加入时间</span>
                  <strong>{{ formatDate(user.createdAt) }}</strong>
                </div>
              </div>
            </section>
          </aside>
        </div>
      </div>

      <div
        v-else
        class="profile-loading glass-card"
      >
        <n-empty description="个人中心加载失败" />
      </div>
    </div>

    <NModal
      v-model:show="followsVisible"
      preset="card"
      :title="followsTab === 'followers' ? '软件清单' : '收藏资源'"
      class="profile-modal"
      transform-origin="center"
      :style="{ width: '360px' }"
    >
      <div class="follow-switch">
        <button
          type="button"
          :class="{ active: followsTab === 'following' }"
          @click="goFollows('following')"
        >
          收藏资源
        </button>
        <button
          type="button"
          :class="{ active: followsTab === 'followers' }"
          @click="goFollows('followers')"
        >
          软件清单
        </button>
      </div>
      <div class="follow-list">
        <article
          v-if="followsLoading"
          class="follow-empty"
        >
          列表加载中...
        </article>
        <article
          v-else-if="followUsers.length === 0"
          class="follow-empty"
        >
          {{ followsTab === 'followers' ? '暂无软件清单' : '暂无收藏资源' }}
        </article>
        <article
          v-for="followUser in followUsers"
          v-else
          :key="followUser.id"
          class="follow-user"
        >
          <img
            v-if="followUser.avatarUrl"
            :src="followUser.avatarUrl"
            :alt="followUser.nickname"
          />
          <span v-else>{{ followUser.nickname.charAt(0).toUpperCase() }}</span>
          <div>
            <strong>{{ followUser.nickname }}</strong>
            <small>{{ [followUser.college, followUser.major].filter(Boolean).join(' · ') || followUser.bio || '知识库条目' }}</small>
          </div>
        </article>
      </div>
    </NModal>

    <NModal
      v-model:show="likesVisible"
      preset="card"
      title="整理反馈"
      class="profile-modal"
      transform-origin="center"
      :style="{ width: '420px' }"
    >
      <div class="stat-summary">
        <span>累计反馈</span>
        <strong>{{ formatCompactNumber(likeCount) }}</strong>
        <p>统计当前个人中心资源条目的反馈数量。</p>
      </div>
      <div class="stat-list">
        <article
          v-if="likedPosts.length === 0"
          class="stat-empty"
        >
          <strong>暂无反馈记录</strong>
          <p>上传资源并获得反馈后，会在这里展示明细。</p>
        </article>
        <button
          v-for="post in likedPosts"
          v-else
          :key="post.id"
          class="stat-detail-item"
          type="button"
          @click="likesVisible = false; goPost(post.id)"
        >
          <span class="stat-item-copy">
            <strong>{{ postTitle(post) }}</strong>
            <small>{{ formatTime(post.createdAt) }} · {{ post.commentCount }} 条反馈</small>
          </span>
          <span class="stat-item-value">{{ formatCompactNumber(post.likeCount) }} 反馈</span>
        </button>
      </div>
    </NModal>

  </div>
</template>

<style scoped lang="scss">
.profile-layout {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 112px);
  background: var(--cf-page-bg);
  color: var(--cf-text-primary);
  overflow: visible;

  &::before,
  &::after {
    display: none;
  }
}

.top-header {
  position: relative;
  z-index: 1;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border: 1px solid var(--cf-card-border);
  border-radius: 20px;
  background: var(--cf-card-bg);
  box-shadow: var(--cf-card-shadow);
  backdrop-filter: blur(24px) saturate(150%);
  margin-bottom: 18px;
  z-index: 10;

  .header-left,
  .header-actions button {
    border: 1px solid var(--cf-border);
    background: var(--cf-bg-readable);
    color: var(--cf-text-primary);
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    transition: transform 0.22s ease, border-color 0.22s ease, box-shadow 0.22s ease;

    &:hover {
      transform: translateY(-1px);
      border-color: var(--cf-border-strong);
      box-shadow: var(--cf-shadow-soft);
    }
  }

  .header-left {
    gap: 10px;
    padding: 9px 13px;
    border-radius: 999px;
    font-weight: 800;
  }

  .header-actions {
    display: flex;
    gap: 10px;

    button {
      width: 40px;
      height: 40px;
      border-radius: 12px;
    }
  }
}

.scroll-content {
  position: relative;
  z-index: 1;
  flex: 0 0 auto;
  overflow: visible;
  padding: 8px 0 32px;
}

.profile-container {
  width: 100%;
  margin: 0 auto;
  padding-bottom: 60px;
}

.profile-loading {
  width: min(100%, 860px);
  min-height: 360px;
  margin: 40px auto;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--cf-text-secondary);
}

.glass-card {
  border: 1px solid var(--cf-card-border);
  border-radius: 20px;
  background: var(--cf-card-bg);
  box-shadow: var(--cf-card-shadow);
  backdrop-filter: blur(24px) saturate(150%);
  -webkit-backdrop-filter: blur(24px) saturate(150%);
}

.cover-section {
  position: relative;
  overflow: hidden;
  min-height: 320px;
  border-radius: 22px;

  .cover-edit-hit {
    position: absolute;
    inset: 0;
    padding: 0;
    border: 0;
    background: transparent;
    cursor: pointer;
    z-index: 0;
  }

  .cover-img {
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

  &::after {
    content: '';
    position: absolute;
    inset: 0;
    background:
      linear-gradient(180deg, transparent 18%, color-mix(in srgb, var(--cf-bg-base) 42%, transparent) 68%, var(--cf-bg-readable)),
      radial-gradient(circle at 16% 28%, color-mix(in srgb, var(--cf-primary) 20%, transparent), transparent 34%),
      radial-gradient(circle at 82% 22%, color-mix(in srgb, var(--cf-secondary) 18%, transparent), transparent 32%);
    background-size: 100% 100%, 128% 128%, 124% 124%;
    animation: profileCoverLight 20s ease-in-out infinite alternate;
  }

  .profile-main-info {
    position: absolute;
    z-index: 1;
    left: 36px;
    right: 36px;
    bottom: 30px;
    display: flex;
    align-items: flex-end;
    gap: 24px;
  }

  .avatar-wrapper {
    padding: 0;
    border: 0;
    background: transparent;
    cursor: pointer;
    flex: 0 0 auto;
  }

  .avatar {
    width: 128px;
    height: 128px;
    border-radius: 50%;
    overflow: hidden;
    border: 4px solid color-mix(in srgb, var(--cf-bg-readable) 92%, transparent);
    background: var(--cf-gradient-primary);
    box-shadow: 0 20px 48px color-mix(in srgb, var(--cf-text-primary) 22%, transparent);
    color: var(--cf-text-inverse);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 50px;
    font-weight: 900;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }
}

.user-details {
  min-width: 0;
  flex: 1;
}

.name-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 8px;

  h2 {
    margin: 0;
    font-size: 30px;
    line-height: 1.15;
  }
}

.level-tag {
  border: 1px solid color-mix(in srgb, var(--cf-warning) 48%, transparent);
  border-radius: 999px;
  background: color-mix(in srgb, var(--cf-warning) 13%, transparent);
  color: var(--cf-warning);
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 900;
}

.title,
.bio {
  margin: 0;
  color: var(--cf-text-secondary);
}

.bio,
.bio-edit {
  margin-top: 8px;
  line-height: 1.55;
}

.bio-edit {
  display: block;
  width: 100%;
  border: 1px dashed color-mix(in srgb, var(--cf-text-primary) 18%, transparent);
  border-radius: 14px;
  background: color-mix(in srgb, var(--cf-bg-glass) 72%, transparent);
  color: var(--cf-text-secondary);
  cursor: pointer;
  padding: 12px 14px;
  text-align: left;
  transition: color 0.22s ease, border-color 0.22s ease, background 0.22s ease;

  &:hover {
    color: var(--cf-text-primary);
    border-color: var(--cf-border-strong);
    background: var(--cf-bg-glass);
  }
}

.stats-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 18px;
}

.stat {
  min-width: 92px;
  border: 1px solid var(--cf-border-glass);
  border-radius: 14px;
  background: var(--cf-bg-glass-soft);
  color: inherit;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 11px 13px;
  text-align: left;
  font: inherit;

  &button,
  &[type='button'] {
    cursor: pointer;
  }

  .label {
    color: var(--cf-text-secondary);
    font-size: 12px;
  }

  .val {
    font-size: 20px;
    font-weight: 900;
  }
}

.outline-btn,
.primary-btn {
  border: 1px solid var(--cf-border-glass);
  border-radius: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 40px;
  padding: 8px 16px;
  font-weight: 900;
  transition: transform 0.22s ease, border-color 0.22s ease, box-shadow 0.22s ease, opacity 0.22s ease;

  &:hover:not(:disabled) {
    transform: translateY(-1px);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-soft);
  }

  &:disabled {
    cursor: wait;
    opacity: 0.6;
  }
}

.outline-btn {
  background: var(--cf-bg-glass);
  color: var(--cf-text-primary);
}

.primary-btn {
  background: var(--cf-primary);
  color: var(--cf-text-inverse);
}

.edit-btn {
  margin-left: auto;
}

.edit-panel {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: 18px;
  padding: 20px;

  .edit-preview {
    position: relative;
    grid-column: 1 / -1;
    overflow: hidden;
    min-height: 180px;
    border: 1px solid var(--cf-border-glass);
    border-radius: 16px;
    background: var(--cf-bg-glass);

    > img {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      object-fit: cover;
      opacity: 0.9;
    }

    &::after {
      content: '';
      position: absolute;
      inset: 0;
      background:
        linear-gradient(180deg, transparent 20%, color-mix(in srgb, var(--cf-bg-base) 48%, transparent)),
        radial-gradient(circle at 18% 22%, color-mix(in srgb, var(--cf-primary) 16%, transparent), transparent 34%),
        radial-gradient(circle at 82% 24%, color-mix(in srgb, var(--cf-secondary) 14%, transparent), transparent 32%);
    }
  }

  .preview-avatar {
    position: absolute;
    left: 18px;
    bottom: 16px;
    z-index: 1;
    width: 82px;
    height: 82px;
    border: 3px solid color-mix(in srgb, var(--cf-bg-readable) 88%, transparent);
    box-shadow: 0 16px 36px color-mix(in srgb, var(--cf-text-primary) 16%, transparent);
    padding: 0;
    cursor: pointer;

    small {
      position: absolute;
      inset: auto 0 0;
      background: color-mix(in srgb, #000 54%, transparent);
      color: #fff;
      font-size: 11px;
      line-height: 24px;
      text-align: center;
    }
  }

  .edit-cover-btn,
  .asset-field button {
    border: 1px solid var(--cf-border-glass);
    border-radius: 12px;
    background: var(--cf-bg-glass);
    color: var(--cf-primary);
    cursor: pointer;
    font-weight: 900;
    transition: transform 0.22s ease, border-color 0.22s ease, box-shadow 0.22s ease, opacity 0.22s ease;

    &:hover:not(:disabled) {
      transform: translateY(-1px);
      border-color: var(--cf-border-strong);
      box-shadow: var(--cf-shadow-soft);
    }

    &:disabled {
      cursor: wait;
      opacity: 0.62;
    }
  }

  .edit-cover-btn {
    position: absolute;
    right: 18px;
    bottom: 18px;
    z-index: 1;
    padding: 9px 13px;
  }

  .asset-input {
    display: none;
  }

  .asset-field {
    display: flex;
    flex-direction: column;
    gap: 8px;

    &.wide {
      grid-column: 1 / -1;
    }

    button {
      min-height: 42px;
      padding: 9px 12px;
      text-align: left;
    }
  }

  label {
    display: flex;
    flex-direction: column;
    gap: 7px;

    &.wide {
      grid-column: 1 / -1;
    }
  }

  span {
    color: var(--cf-text-secondary);
    font-size: 13px;
    font-weight: 800;
  }

  input,
  textarea {
    width: 100%;
    border: 1px solid var(--cf-border-glass);
    border-radius: 12px;
    background: var(--cf-bg-readable);
    color: var(--cf-text-primary);
    outline: none;
    padding: 10px 12px;

    &:focus {
      border-color: var(--cf-border-strong);
      box-shadow: 0 0 0 4px color-mix(in srgb, var(--cf-primary) 10%, transparent);
    }
  }

  textarea {
    min-height: 96px;
    resize: vertical;
  }

  .validated-field.invalid {
    input {
      border-color: var(--cf-danger);
      box-shadow: 0 0 0 4px color-mix(in srgb, var(--cf-danger) 12%, transparent);
    }
  }

  .validated-field.shake {
    animation: field-shake 0.48s ease;
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

.password-panel {
  grid-column: 1 / -1;
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--cf-border-glass);
  border-radius: 16px;
  background: var(--cf-bg-glass-soft);
}

.password-panel-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;

  strong {
    font-size: 15px;
  }

  span {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.password-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  align-items: start;
}

.password-save-btn {
  grid-column: 1 / -1;
  justify-self: flex-end;
  min-height: 42px;
  align-self: end;
}

.password-strength {
  display: grid;
  gap: 6px;

  small {
    color: var(--cf-text-muted);
    font-size: 12px;
    line-height: 1.5;
  }

  &.weak {
    --strength-color: var(--cf-danger);
    --strength-width: 33%;
  }

  &.medium {
    --strength-color: #d97706;
    --strength-width: 66%;
  }

  &.strong {
    --strength-color: var(--cf-primary);
    --strength-width: 100%;
  }

  &.empty {
    --strength-color: var(--cf-border-strong);
    --strength-width: 0%;
  }
}

.strength-bar {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--cf-bg-glass-soft);

  span {
    display: block;
    width: var(--strength-width);
    height: 100%;
    border-radius: inherit;
    background: var(--strength-color);
    transition: width 0.2s ease, background-color 0.2s ease;
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

.follow-switch {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 14px;

  button {
    border: 1px solid var(--cf-border-glass);
    border-radius: 12px;
    background: var(--cf-bg-glass);
    color: var(--cf-text-secondary);
    cursor: pointer;
    padding: 10px 12px;
    font-weight: 900;

    &.active {
      border-color: color-mix(in srgb, var(--cf-primary) 36%, transparent);
      background: var(--cf-primary-soft);
      color: var(--cf-primary);
    }
  }
}

.follow-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.stat-summary {
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

.stat-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.stat-empty,
.stat-detail-item {
  border: 1px solid var(--cf-border-glass);
  border-radius: 12px;
  background: var(--cf-bg-glass);
}

.stat-empty {
  color: var(--cf-text-secondary);
  padding: 16px;
  text-align: center;

  strong {
    color: var(--cf-text-primary);
    display: block;
    margin-bottom: 6px;
  }

  p {
    margin: 0;
  }
}

.stat-detail-item {
  width: 100%;
  align-items: center;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 12px;
  text-align: left;
}

.stat-detail-item {
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

.follow-empty,
.follow-user {
  border: 1px solid var(--cf-border-glass);
  border-radius: 12px;
  background: var(--cf-bg-glass);
}

.follow-empty {
  padding: 16px;
  color: var(--cf-text-secondary);
  text-align: center;
}

.follow-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;

  img,
  > span {
    width: 34px;
    height: 34px;
    border-radius: 50%;
    flex: 0 0 auto;
  }

  > span {
    background: var(--cf-gradient-primary);
    color: var(--cf-text-inverse);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-weight: 900;
  }

  div {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  strong {
    color: var(--cf-text-primary);
  }

  small {
    color: var(--cf-text-muted);
  }
}

:global(.profile-modal.n-card) {
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--cf-bg-base) 16%, transparent), transparent 54%),
    color-mix(in srgb, var(--cf-bg-base) 72%, transparent);
  border: 1px solid var(--cf-border-glass);
  box-shadow: 0 24px 72px color-mix(in srgb, var(--cf-text-primary) 18%, transparent), 0 10px 28px
    color-mix(in srgb, var(--cf-text-primary) 8%, transparent);
  backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(136%);
  -webkit-backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(136%);
}

.edit-actions {
  grid-column: 1 / -1;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.content-section {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 24px;
  margin-top: 24px;
}

.tabs {
  display: flex;
  gap: 8px;
  padding: 8px;
  margin-bottom: 16px;

  .tab {
    border: none;
    border-radius: 12px;
    background: transparent;
    color: var(--cf-text-secondary);
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 7px;
    padding: 9px 13px;
    font-weight: 800;

    small {
      border-radius: 999px;
      background: color-mix(in srgb, var(--cf-text-primary) 8%, transparent);
      padding: 2px 6px;
      font-size: 11px;
    }

    &.active {
      background: var(--cf-primary);
      color: var(--cf-text-inverse);
      box-shadow: var(--cf-shadow-glow);
    }
  }
}

.feed-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.feed-item {
  padding: 20px;
  cursor: pointer;
  transition: transform 0.22s ease, border-color 0.22s ease, box-shadow 0.22s ease;

  &:hover {
    transform: translateY(-2px);
    border-color: var(--cf-border-strong);
    box-shadow: var(--cf-shadow-card-hover);
  }
}

.feed-header,
.feed-actions,
.achievement-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar-mini {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: var(--cf-gradient-primary);
  color: var(--cf-text-inverse);
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  font-weight: 900;
}

.info {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;

  .name {
    color: var(--cf-text-primary);
    font-weight: 900;
  }

  .time {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.more {
  margin-left: auto;
  color: var(--cf-text-secondary);
}

.feed-content {
  margin-top: 15px;

  strong {
    color: var(--cf-text-primary);
  }

  p {
    margin: 8px 0 0;
    color: var(--cf-text-secondary);
    line-height: 1.65;
  }
}

.feed-actions {
  flex-wrap: wrap;
  margin-top: 16px;
  color: var(--cf-text-secondary);

  .action {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
  }
}

.checkin-item .avatar-mini {
  border-radius: 14px;
}

.achievement-row {
  cursor: default;
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;

  &:hover {
    transform: none;
    box-shadow: var(--cf-shadow-card);
  }

  &.locked {
    opacity: 0.62;
  }

  p {
    margin: 4px 0 0;
    color: var(--cf-text-secondary);
    line-height: 1.45;
  }

  > span {
    color: var(--cf-primary);
    font-size: 12px;
    font-weight: 900;
  }
}

.empty-card {
  padding: 34px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.sidebar-right {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.widget {
  padding: 20px;
}

.widget-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 16px;

  h3 {
    margin: 0;
    font-size: 16px;
  }

  button {
    border: none;
    background: transparent;
    color: var(--cf-primary);
    cursor: pointer;
    font-weight: 900;
  }
}

.badges {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.badge {
  aspect-ratio: 1;
  border: 1px solid color-mix(in srgb, var(--cf-primary) 26%, transparent);
  border-radius: 12px;
  background: color-mix(in srgb, var(--cf-primary) 11%, var(--cf-bg-glass));
  color: var(--cf-primary);
  cursor: pointer;
  font-weight: 900;

  &.locked {
    opacity: 0.5;
    filter: grayscale(0.55);
  }
}

.checkin-list {
  display: flex;
  flex-direction: column;
  gap: 9px;

  button {
    border: 1px solid var(--cf-border-glass);
    border-radius: 12px;
    background: var(--cf-bg-glass-soft);
    color: var(--cf-text-primary);
    cursor: pointer;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 11px 12px;
    text-align: left;
  }

  span {
    color: var(--cf-text-muted);
    font-size: 12px;
  }
}

.widget-empty {
  margin: 0;
  border: 1px dashed var(--cf-border-glass);
  border-radius: 12px;
  color: var(--cf-text-muted);
  padding: 16px;
  text-align: center;
}

.mini-metric {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--cf-text-secondary);

  .n-icon {
    width: 42px;
    height: 42px;
    border-radius: 50%;
    background: color-mix(in srgb, var(--cf-primary) 14%, transparent);
    color: var(--cf-primary);
    display: flex;
    align-items: center;
    justify-content: center;
  }

  div {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  strong {
    color: var(--cf-text-primary);
  }
}

@keyframes profileCoverDrift {
  0% {
    transform: scale(1.08) translate3d(-1.2%, -1%, 0);
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
  .profile-layout {
    animation: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .profile-layout,
  .cover-section .cover-img,
  .cover-section::after {
    animation: none;
  }
}

@media (max-width: 900px) {
  .content-section {
    grid-template-columns: 1fr;
  }

  .cover-section .profile-main-info {
    left: 22px;
    right: 22px;
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 640px) {
  .scroll-content {
    padding: 18px;
  }

  .cover-section {
    min-height: 440px;
  }

  .edit-panel {
    grid-template-columns: 1fr;
  }

  .tabs {
    overflow-x: auto;
  }
}
</style>
