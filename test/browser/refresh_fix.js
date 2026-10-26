// 第十七轮 P2（已登录刷新深层路由丢页）修复验证 —— 对照 docs/38 §4.1 与修复后的守卫行为
// 位置：test/browser/refresh_fix.js（2026-09-12 从 %TEMP% 的 pwcheck 迁入，项目内为唯一维护版本）
// 依赖：playwright（运行器经 NODE_PATH 指向 pwcheck/node_modules；也可在本目录 npm i playwright）
// 用法：node refresh_fix.js   （需 5173 前端 + 8080 后端已启动）
const { chromium } = require('playwright');
const BASE = 'http://localhost:5173';
// 2026-10-21 界面改造：快捷登录按钮改用 data-testid 契约（见 Login.vue）
const LOGIN_TID = {
  admin: 'quick-login-admin',
  librarian01: 'quick-login-librarian',
  reader001: 'quick-login-reader'
};
const results = [];
const pass = (name, cond, detail = '') => {
  results.push({ name, pass: !!cond });
  console.log(`[${cond ? 'PASS' : 'FAIL'}] ${name} ${detail}`);
};

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  let errors = 0;
  page.on('pageerror', () => errors++);
  page.on('console', (m) => { if (m.type() === 'error') errors++; });

  const login = async (user) => {
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => localStorage.clear());
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(400);
    const tid = LOGIN_TID[user];
    await page.waitForSelector('[data-testid="' + tid + '"]', { timeout: 8000 });
    await page.click('[data-testid="' + tid + '"]');
    await page.waitForURL((u) => !u.pathname.endsWith('/login'), { timeout: 8000 });
  };

  // 【核心场景】已登录整页直达深层路由（等价"刷新"）：page.goto 会重新加载整个文档
  await login('librarian01');
  await page.goto(BASE + '/borrow-handle', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  pass('C1 librarian 刷新 /borrow-handle 停留原页', page.url().includes('/borrow-handle'), page.url());

  await login('reader001');
  await page.goto(BASE + '/my-borrow', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  pass('C2 reader 刷新 /my-borrow 停留原页', page.url().includes('/my-borrow'), page.url());

  await login('admin');
  await page.goto(BASE + '/dashboard', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  pass('C3 admin 刷新 /dashboard 停留原页', page.url().includes('/dashboard'), page.url());

  // 【边界 1】未登录直达深层路由：仍应回登录页（原行为不变）
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => localStorage.clear());
  await page.goto(BASE + '/my-borrow', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1000);
  pass('C4 未登录直达 /my-borrow 仍回登录页', page.url().includes('/login'), page.url());

  // 【边界 2】越权：reader 直达 admin 页 → 警告 + 回角色首页（权限校验未被旁路）
  await login('reader001');
  await page.goto(BASE + '/user-manage', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  pass('C5 reader 直达 /user-manage 被拦回角色首页', page.url().includes('/book-search'), page.url());

  // 【边界 3】开放重定向：伪造 //evil.com → 不得跳外链
  await page.goto(BASE + '/login?redirect=//evil.com', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1000);
  pass('C6 redirect=//evil.com 回角色首页且不跳外链', page.url().includes('/book-search') && !page.url().includes('evil.com'), page.url());

  // 【边界 4】不可解析路径：/login?redirect=/nope-404 → 回角色首页而非空白
  await page.goto(BASE + '/login?redirect=/nope-404', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1000);
  // 该场景下用户已登录（reader），守卫应回角色首页 /book-search；接受 /login 会让
  // “守卫失效、停在登录页”也通过，属于空断言。
  const okUrl = page.url().includes('/book-search');
  pass('C7 redirect 指向不存在路径时回退角色首页', okUrl, page.url());

  // 【边界 5】登录表单流程未被影响（表单提交后仍按角色跳首页）
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => localStorage.clear());
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(400);
  await page.waitForSelector('[data-testid="quick-login-admin"]', { timeout: 8000 });
  await page.click('[data-testid="quick-login-admin"]');
  await page.waitForURL((u) => !u.pathname.endsWith('/login'), { timeout: 8000 });
  pass('C8 正常登录仍跳角色首页 /dashboard', page.url().includes('/dashboard'), page.url());

  await browser.close();
  const p = results.filter((r) => r.pass).length;
  console.log(`\n===== 路由刷新修复验证: ${p}/${results.length} =====  console/pageerror=${errors}`);
  process.exit(p === results.length && errors === 0 ? 0 : 1);  // M-21：console/pageerror 纳入退出码
})().catch((e) => { console.error('FATAL', e.message); process.exit(2); });
