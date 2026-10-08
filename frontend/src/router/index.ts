import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/pages/Home.vue'),
    // 首页允许游客访问，去除 requiresAuth
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/Login.vue'),
    meta: { guest: true },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/pages/Register.vue'),
    meta: { guest: true },
  },
  {
    path: '/forgot-password',
    name: 'forgot-password',
    component: () => import('@/pages/ForgotPassword.vue'),
    meta: { guest: true },
  },
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    children: [
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/pages/Profile.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'users/:id',
        name: 'user-page',
        component: () => import('@/pages/UserPage.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'users/:id/follows',
        name: 'follows-list',
        component: () => import('@/pages/FollowsList.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'square',
        name: 'square',
        redirect: '/resources',
      },
      {
        path: 'posts/new',
        name: 'post-create',
        component: () => import('@/pages/PostCreate.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'posts/:id',
        name: 'post-detail',
        component: () => import('@/pages/PostDetail.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'spaces',
        name: 'spaces',
        redirect: '/learning',
      },
      {
        path: 'spaces/new',
        name: 'space-create',
        component: () => import('@/pages/SpaceCreate.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'checkin',
        name: 'checkin',
        component: () => import('@/pages/CheckinChallenges.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'checkin/new',
        name: 'checkin-create',
        component: () => import('@/pages/CheckinChallengeCreate.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'checkin/:id',
        name: 'checkin-detail',
        component: () => import('@/pages/CheckinChallengeDetail.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'resources',
        name: 'resources',
        component: () => import('@/pages/Resources.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'tools',
        name: 'tools',
        component: () => import('@/pages/FeaturePlaceholder.vue'),
        meta: { requiresAuth: true, title: '工具' },
      },
      {
        path: 'software',
        name: 'software',
        component: () => import('@/pages/FeaturePlaceholder.vue'),
        meta: { requiresAuth: true, title: '软件' },
      },
      {
        path: 'learning',
        name: 'learning',
        component: () => import('@/pages/Learning.vue'),
        meta: { requiresAuth: true, title: '学习' },
      },
      {
        path: 'learning/notes/:id',
        name: 'learning-note-detail',
        component: () => import('@/pages/NotePublicDetail.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'learning/tutorial/:id',
        name: 'tutorial-reader',
        component: () => import('@/pages/TutorialReader.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'resources/upload',
        name: 'resource-upload',
        component: () => import('@/pages/ResourceUpload.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'resources/:id',
        name: 'resource-detail',
        component: () => import('@/pages/ResourceDetail.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'search',
        name: 'search',
        component: () => import('@/pages/Search.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'ai',
        redirect: '/ai/chat',
      },
      {
        path: 'ai/chat',
        name: 'ai-chat',
        component: () => import('@/pages/ai/AiChat.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'ai/libraries',
        name: 'ai-libraries',
        component: () => import('@/pages/ai/KnowledgeLibraries.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'ai/libraries/:id',
        name: 'ai-library-detail',
        component: () => import('@/pages/ai/KnowledgeBaseDetail.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'ai/notes',
        name: 'ai-notes',
        component: () => import('@/pages/ai/KnowledgeNotes.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'notifications',
        name: 'notifications',
        component: () => import('@/pages/Notifications.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'messages',
        name: 'messages',
        component: () => import('@/pages/Messages.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'announcements',
        name: 'announcements',
        component: () => import('@/pages/Announcements.vue'),
        meta: { requiresAuth: true, title: '公告' },
      },
      {
        path: 'announcements/:id',
        name: 'announcement-detail',
        component: () => import('@/pages/AnnouncementDetail.vue'),
        meta: { requiresAuth: true },
      },
    ],
  },
  {
    path: '/admin',
    component: () => import('@/pages/admin/AdminLayout.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      {
        path: '',
        name: 'admin-dashboard',
        component: () => import('@/pages/admin/AdminDashboard.vue'),
      },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('@/pages/admin/AdminUsers.vue'),
      },
      {
        path: 'posts',
        name: 'admin-posts',
        component: () => import('@/pages/admin/AdminPosts.vue'),
      },
      {
        path: 'spaces',
        name: 'admin-spaces',
        component: () => import('@/pages/admin/AdminSpaces.vue'),
      },
      {
        path: 'audit-logs',
        name: 'admin-audit-logs',
        component: () => import('@/pages/admin/AdminAuditLog.vue'),
      },
      {
        path: 'resources',
        name: 'admin-resources',
        component: () => import('@/pages/admin/AdminResources.vue'),
      },
      {
        path: 'notes',
        name: 'admin-notes',
        component: () => import('@/pages/admin/AdminNotes.vue'),
      },
      {
        path: 'checkin',
        name: 'admin-checkin',
        component: () => import('@/pages/admin/AdminCheckin.vue'),
      },
      {
        path: 'comments',
        name: 'admin-comments',
        component: () => import('@/pages/admin/AdminComments.vue'),
      },
      {
        path: 'announcements',
        name: 'admin-announcements',
        component: () => import('@/pages/admin/AdminAnnouncements.vue'),
      },
      {
        path: 'reports',
        name: 'admin-reports',
        component: () => import('@/pages/admin/AdminReports.vue'),
      },
      {
        path: 'sensitive-words',
        name: 'admin-sensitive-words',
        component: () => import('@/pages/admin/AdminSensitiveWords.vue'),
      },
      {
        path: 'tenants',
        name: 'admin-tenants',
        component: () => import('@/pages/admin/AdminTenants.vue'),
      },
      {
        path: 'ai-config',
        name: 'admin-ai-config',
        component: () => import('@/pages/admin/AdminAiConfig.vue'),
      },
    ],
  },
  {
    path: '/spaces/:id',
    name: 'space-detail',
    component: () => import('@/pages/SpaceDetail.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/pages/NotFound.vue'),
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('token');
  const role = localStorage.getItem('role');

  const isGuest = role === 'GUEST';
  const allowedGuestPaths = [
    '/',
    '/resources',
    '/announcements',
    '/login',
    '/register',
    '/forgot-password',
  ];
  const isAllowedGuestPath =
    allowedGuestPaths.includes(to.path) ||
    (to.path.startsWith('/posts/') && to.path !== '/posts/new') ||
    to.path.startsWith('/resources/') ||
    to.path.startsWith('/announcements/');

  if (to.meta.requiresAuth && !token) {
    next('/login');
  } else if (isGuest && to.meta.requiresAuth && !isAllowedGuestPath) {
    next('/login');
  } else if (to.meta.guest && token && !isGuest) {
    next('/resources');
  } else if (to.path === '/' && token && !isGuest) {
    next('/resources');
  } else if (to.meta.requiresAdmin && role !== 'TENANT_ADMIN' && role !== 'SUPER_ADMIN') {
    next('/');
  } else {
    next();
  }
});

export default router;
