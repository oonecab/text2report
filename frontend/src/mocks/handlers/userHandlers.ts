import { http } from 'msw';
import { httpError, ok, okPage } from './utils';
import type { SysUser } from '@/types/user';

let users: SysUser[] = [
  {
    id: 1,
    username: 'admin',
    displayName: '系统管理员',
    role: 'ADMIN',
    email: 'admin@example.com',
    phone: '',
    isActive: true,
    lastLoginAt: '2026-04-16T20:00:00',
  },
  {
    id: 2,
    username: 'manager',
    displayName: '业务经理',
    role: 'MANAGER',
    email: 'manager@example.com',
    phone: '',
    isActive: true,
    lastLoginAt: '2026-04-16T19:40:00',
  },
  {
    id: 3,
    username: 'user',
    displayName: '普通用户',
    role: 'USER',
    email: 'user@example.com',
    phone: '',
    isActive: true,
    lastLoginAt: '2026-04-16T18:30:00',
  },
  {
    id: 4,
    username: 'analyst',
    displayName: '数据分析员',
    role: 'USER',
    email: 'analyst@example.com',
    phone: '',
    isActive: false,
    lastLoginAt: '2026-04-15T15:20:00',
  },
];

let userIdCounter = 10;

export const userHandlers = [
  http.get('/api/v1/users', async ({ request }) => {
    const url = new URL(request.url);
    const role = url.searchParams.get('role');
    const active = url.searchParams.get('active') ?? url.searchParams.get('isActive');
    const keyword = (url.searchParams.get('keyword') ?? '').trim();
    const page = Number(url.searchParams.get('page') ?? 1);
    const size = Number(url.searchParams.get('size') ?? 20);

    let filtered = [...users];
    if (role) {
      filtered = filtered.filter((item) => item.role === role);
    }
    if (active != null && active !== '') {
      filtered = filtered.filter((item) => item.isActive === (active === 'true'));
    }
    if (keyword) {
      filtered = filtered.filter((item) =>
        item.username.includes(keyword) || String(item.displayName ?? '').includes(keyword));
    }

    return okPage(filtered, page, size);
  }),

  http.post('/api/v1/users', async ({ request }) => {
    const body = await request.json() as SysUser & { password?: string };
    const exists = users.some((item) => item.username === body.username);
    if (exists) {
      return httpError(400, 'USERNAME_EXISTS', '用户名已存在');
    }
    const newUser: SysUser = {
      id: userIdCounter++,
      username: body.username,
      displayName: body.displayName,
      role: body.role,
      email: body.email,
      phone: body.phone,
      isActive: true,
      lastLoginAt: undefined,
    };
    users = [newUser, ...users];
    return ok(newUser);
  }),

  http.put('/api/v1/users/:id', async ({ params, request }) => {
    const id = Number(params.id);
    const body = await request.json() as Partial<SysUser>;
    const target = users.find((item) => item.id === id);
    if (!target) {
      return httpError(404, 'USER_NOT_FOUND', '用户不存在');
    }
    Object.assign(target, body);
    return ok(target);
  }),

  http.put('/api/v1/users/:id/password', async () => ok(null)),

  http.put('/api/v1/users/:id/activate', async ({ params }) => {
    const target = users.find((item) => item.id === Number(params.id));
    if (!target) {
      return httpError(404, 'USER_NOT_FOUND', '用户不存在');
    }
    target.isActive = true;
    return ok(null);
  }),

  http.put('/api/v1/users/:id/deactivate', async ({ params }) => {
    const target = users.find((item) => item.id === Number(params.id));
    if (!target) {
      return httpError(404, 'USER_NOT_FOUND', '用户不存在');
    }
    target.isActive = false;
    return ok(null);
  }),
];
