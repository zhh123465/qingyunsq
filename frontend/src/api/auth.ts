import { request } from './request';

interface UserVO {
  id: number;
  studentNo: string;
  email: string;
  nickname: string;
  avatarUrl: string;
  bio: string;
  college: string;
  major: string;
  grade: string;
  role: string;
  status: number;
  lastLoginAt: string;
  createdAt: string;
}

interface LoginResponse {
  token: string;
  user: UserVO;
  tenantId: number;
  tenantCode: string;
}

/**
 * 敏感凭证（密码）变更成功后，主动清空前端会话并跳转登录页。
 *
 * 设计说明：
 * - 后端 T3.2 已经在 changePassword / resetPassword 成功后调用
 *   `StpUtil.logoutByLoginId(userId)` 注销该用户的所有 token。前端如果不
 *   配合主动登出，下一次任意请求都会被全局 axios 拦截器以 401 处理，会
 *   出现"成功 toast 后又被自动跳走"的尴尬体验。
 * - 这里采用动态 import（lazy import）而非顶层静态 import，目的是
 *   **打破循环依赖**：`@/stores/auth` 与 `@/router` 在初始化阶段都会间接
 *   引用 `@/api/request`（通过 axios 拦截器/路由守卫读取 token），如果
 *   `@/api/auth` 在模块加载阶段就 import 它们，会形成
 *   `auth.ts → stores/auth → api/request → ... → auth.ts` 的循环引用，
 *   在 Vite 编译期或运行时都可能拿到未初始化的 export（undefined）。
 * - 改为函数体内动态 import 后，依赖在"调用时"才解析，此时模块图已经
 *   完成首次构建，可以安全拿到 `useAuthStore` 与默认导出的 router 实例。
 */
async function notifyAfterCredentialChange(): Promise<void> {
  const { useAuthStore } = await import('@/stores/auth');
  const { default: router } = await import('@/router');
  // 清空 token / 用户信息 / 租户信息 + 断开 WebSocket
  useAuthStore().logout();
  // 跳转到登录页，等待用户用新密码重新登录
  router.push('/login');
}

export async function register(data: {
  email: string;
  password: string;
  emailCode: string;
  studentNo?: string;
  nickname: string;
}): Promise<UserVO> {
  const res = await request<UserVO>({ method: 'POST', url: '/auth/register', data });
  return res.data;
}

export async function login(data: { email: string; password: string }): Promise<LoginResponse> {
  const res = await request<LoginResponse>({ method: 'POST', url: '/auth/login', data });
  return res.data;
}

export async function loginWithEmailCode(data: { email: string; emailCode: string }): Promise<LoginResponse> {
  const res = await request<LoginResponse>({
    method: 'POST',
    url: '/auth/login',
    data: { ...data, loginType: 'CODE' },
  });
  return res.data;
}

export async function loginWithWechatCode(code: string): Promise<LoginResponse> {
  const res = await request<LoginResponse>({
    method: 'POST',
    url: '/auth/wechat-login',
    data: { code },
  });
  return res.data;
}

/** GitHub 登录第一步：取授权页 URL（后端未配置 client id/secret 时 enabled=false）。 */
export async function getGithubAuthorizeUrl(): Promise<{ enabled: boolean; url?: string }> {
  const res = await request<{ enabled: boolean; url?: string }>({
    method: 'GET',
    url: '/auth/github/authorize-url',
  });
  return res.data;
}

/** GitHub 登录第二步：用回调携带的 code/state 完成登录。 */
export async function loginWithGithubCode(code: string, state: string): Promise<LoginResponse> {
  const res = await request<LoginResponse>({
    method: 'POST',
    url: '/auth/github-login',
    data: { code, state },
  });
  return res.data;
}

export async function sendEmailCode(email: string, scene: 'REGISTER' | 'LOGIN' | 'RESET_PASSWORD'): Promise<{ message: string }> {
  const res = await request<{ message: string }>({
    method: 'POST',
    url: '/auth/email-code',
    data: { email, scene },
  });
  return res.data;
}

export async function checkEmailExists(email: string): Promise<boolean> {
  const res = await request<{ exists: boolean }>({
    method: 'POST',
    url: '/auth/email-exists',
    data: { email },
  });
  return res.data.exists;
}

export async function logout(): Promise<void> {
  await request({ method: 'POST', url: '/auth/logout' });
}

export async function getMe(): Promise<UserVO> {
  const res = await request<UserVO>({ method: 'GET', url: '/auth/me' });
  return res.data;
}

/**
 * 修改密码：调用后端接口成功后，主动登出并跳转登录页。
 * 与 bugfix.md 漏洞 5 / 任务 T3.3 配套：后端 T3.2 已在密码变更成功后
 * 调用 `StpUtil.logoutByLoginId(userId)` 失效全部活跃 token，本地必须
 * 同步清空会话状态，避免后续请求 401 体验割裂。
 */
export async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  await request({ method: 'PUT', url: '/auth/password', data: { oldPassword, newPassword } });
  // 仅在 await 不抛错（即接口返回成功）时才登出 + 跳转
  await notifyAfterCredentialChange();
}

export async function forgotPassword(email: string): Promise<{ message: string }> {
  const res = await request<{ message: string }>({ method: 'POST', url: '/auth/forgot-password', data: { email } });
  return res.data;
}

/**
 * 通过邮箱验证码重置密码：成功后同样登出 + 跳登录页（同 changePassword）。
 */
export async function resetPassword(email: string, emailCode: string, newPassword: string): Promise<void> {
  await request({ method: 'POST', url: '/auth/reset-password', data: { email, emailCode, newPassword } });
  // 仅在 await 不抛错（即接口返回成功）时才登出 + 跳转
  await notifyAfterCredentialChange();
}
