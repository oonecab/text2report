import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

test('axios client uses cookie-based auth and CSRF settings', () => {
  const source = fs.readFileSync(new URL('./request.ts', import.meta.url), 'utf8');
  assert.equal(source.includes('withCredentials: true'), true);
  assert.equal(source.includes("xsrfCookieName: 'XSRF-TOKEN'"), true);
  assert.equal(source.includes("xsrfHeaderName: 'X-XSRF-TOKEN'"), true);
  assert.equal(source.includes("config.headers['X-XSRF-TOKEN'] = csrfTokenValue"), false);
});

test('auth store no longer keeps JWT state', () => {
  const source = fs.readFileSync(new URL('../store/useAuthStore.ts', import.meta.url), 'utf8');
  assert.equal(source.includes('token:'), false);
  assert.equal(source.includes('persist('), false);
  assert.equal(source.includes('setAuth: (user: UserInfo)'), true);
});

test('request layer no longer injects Authorization bearer tokens', () => {
  const source = fs.readFileSync(new URL('./request.ts', import.meta.url), 'utf8');
  assert.equal(source.includes("Authorization'] = `Bearer ${token}`"), false);
});

test('app bootstrap restores session through csrf + auth/me', () => {
  const source = fs.readFileSync(new URL('../app/App.tsx', import.meta.url), 'utf8');
  assert.equal(source.includes('getCsrfToken'), true);
  assert.equal(source.includes('getCurrentUser'), true);
});

test('app bootstrap deduplicates strict-mode session restore calls', () => {
  const source = fs.readFileSync(new URL('../app/App.tsx', import.meta.url), 'utf8');
  assert.equal(source.includes('let sessionBootstrapPromise'), true);
  assert.equal(source.includes('bootstrapSession'), true);
});

test('session bootstrap 401 is handled silently by request layer', () => {
  const source = fs.readFileSync(new URL('./request.ts', import.meta.url), 'utf8');
  assert.equal(source.includes('if (isSessionBootstrapRequest(requestPath))'), true);
  assert.equal(source.includes('return Promise.reject(apiError ?? error);'), true);
  assert.equal(source.includes('silent?: boolean'), true);
});

test('csrf bootstrap relies on cookie repository instead of manual token storage', () => {
  const source = fs.readFileSync(new URL('./authApi.ts', import.meta.url), 'utf8');
  assert.equal(source.includes('setCsrfToken('), false);
  assert.equal(source.includes("apiGet('/v1/auth/csrf'"), true);
});

test('login refreshes csrf token after authentication rotation', () => {
  const source = fs.readFileSync(new URL('../pages/LoginPage.tsx', import.meta.url), 'utf8');
  const matches = source.match(/getCsrfToken\(\)/g) ?? [];
  assert.equal(matches.length >= 2, true);
});

test('only app bootstrap uses silent csrf/session restore requests', () => {
  const appSource = fs.readFileSync(new URL('../app/App.tsx', import.meta.url), 'utf8');
  const loginSource = fs.readFileSync(new URL('../pages/LoginPage.tsx', import.meta.url), 'utf8');
  assert.equal(appSource.includes('getCsrfToken({ silent: true })'), true);
  assert.equal(appSource.includes('getCurrentUser({ silent: true })'), true);
  assert.equal(loginSource.includes('silent: true'), false);
});
