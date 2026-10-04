import { http, HttpResponse } from 'msw';
import { ok, httpError } from './utils';
import { TOKEN_USER_MAP } from '../helpers/authHelpers';

// Login credentials - maps username to token key in TOKEN_USER_MAP
const MOCK_CREDENTIALS: Record<string, {
  password: string;
  tokenKey: string;
}> = {
  admin: { password: 'admin123', tokenKey: 'mock-jwt-admin-token' },
  manager: { password: 'manager123', tokenKey: 'mock-jwt-manager-token' },
  user: { password: 'user123', tokenKey: 'mock-jwt-user-token' },
};

let currentUser: (typeof TOKEN_USER_MAP)[keyof typeof TOKEN_USER_MAP] | null = null;

const readStoredUser = () => {
  const username = globalThis.localStorage?.getItem('mock-auth-username');
  return Object.values(TOKEN_USER_MAP).find((user) => user.username === username) ?? null;
};

const storeUser = (username: string) => {
  globalThis.localStorage?.setItem('mock-auth-username', username);
};

const clearStoredUser = () => {
  globalThis.localStorage?.removeItem('mock-auth-username');
};

export const authHandlers = [
  http.get('/api/v1/auth/csrf', async () => {
    return ok({ token: 'mock-csrf-token' });
  }),

  http.post('/api/v1/auth/login', async ({ request }) => {
    const body = await request.json() as { username: string; password: string };
    const { username, password } = body;

    const cred = MOCK_CREDENTIALS[username];

    // User not found or password mismatch - don't distinguish to avoid info leak
    if (!cred || cred.password !== password) {
      return httpError(401, 'INVALID_CREDENTIALS', '用户名或密码错误');
    }

    const userInfo = TOKEN_USER_MAP[cred.tokenKey];
    currentUser = userInfo;
    storeUser(userInfo.username);
    return ok({
      token: cred.tokenKey,
      refreshToken: cred.tokenKey.replace('mock-jwt-', 'mock-refresh-'),
      expiresIn: 86400,
      user: userInfo,
    });
  }),

  http.post('/api/v1/auth/logout', async () => {
    currentUser = null;
    clearStoredUser();
    return HttpResponse.json({ code: 'SUCCESS', message: 'ok', data: null });
  }),

  http.get('/api/v1/auth/me', async () => {
    const user = currentUser ?? readStoredUser();
    if (!user) {
      return httpError(401, 'UNAUTHORIZED', 'Token 无效或过期');
    }
    currentUser = user;
    return ok(user);
  }),

  http.post('/api/v1/auth/refresh', async ({ request }) => {
    const body = await request.json() as { refreshToken: string };
    if (!body.refreshToken) {
      return httpError(401, 'UNAUTHORIZED', 'Refresh token is required');
    }

    // Find original token from refresh token
    const originalToken = Object.entries(TOKEN_USER_MAP).find(
      ([, user]) => body.refreshToken === `mock-refresh-${user.username}-token`
    )?.[0];

    if (!originalToken) {
      return httpError(401, 'UNAUTHORIZED', 'Invalid refresh token');
    }

    return ok({
      token: originalToken,
      refreshToken: body.refreshToken,
      expiresIn: 86400,
    });
  }),
];
