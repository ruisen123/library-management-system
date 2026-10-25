// 浏览器推荐项 E2E：热门借阅条（5.2）+ 读者额度管理（5.3）
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const BASE = 'http://localhost:5173';
// 2026-10-24（D2-13）：回归截图改写 test/artifacts/ 运行产物目录，
// 不再覆写交付物目录 docs/界面截图/2026-10-23_推荐项/（一次脏跑就会替换论文配图）
const _d = new Date();
const _p2 = (n) => String(n).padStart(2, '0');
const STAMP = '' + _d.getFullYear() + _p2(_d.getMonth() + 1) + _p2(_d.getDate())
  + '_' + _p2(_d.getHours()) + _p2(_d.getMinutes()) + _p2(_d.getSeconds());
const SHOT_DIR = path.join(__dirname, '..', 'artifacts', 'screenshots', STAMP);
fs.mkdirSync(SHOT_DIR, { recursive: true });
const results = [];
const pass = (name, cond, detail) => {
  detail = detail || '';
  results.push({ name, pass: !!cond });
  console.log('[' + (cond ? 'PASS' : 'FAIL') + '] ' + name + ' ' + detail);
};
const LOGIN_TID = {
  admin: 'quick-login-admin',
  librarian01: 'quick-login-librarian',
  reader001: 'quick-login-reader'
};

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  let errors = 0;
  page.on('pageerror', () => errors++);
  page.on('console', (m) => {
    if (m.type() === 'error') errors++;
  });

  const login = async (user) => {
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => localStorage.clear());
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('[data-testid="' + LOGIN_TID[user] + '"]', { timeout: 8000 });
    await page.click('[data-testid="' + LOGIN_TID[user] + '"]');
    await page.waitForURL((u) => !u.pathname.endsWith('/login'), { timeout: 8000 });
    await page.waitForTimeout(1000);
  };

  // ===== [H] 热门借阅（读者端） =====
  await login('reader001');
  const hot = page.locator('[data-testid="hot-books"]');
  await hot.waitFor({ state: 'visible', timeout: 8000 }).catch(() => {});
  const hotCount = await page.locator('[data-testid="hot-book-card"]').count();
  pass('H1 热门借阅条显示（5 张卡）', hotCount === 5, 'count=' + hotCount);

  const firstHot = page.locator('[data-testid="hot-book-card"]').first();
  const firstTitle = ((await firstHot.locator('h4').textContent().catch(() => '')) || '').trim();
  const firstCount = ((await firstHot.locator('.hot-count').textContent().catch(() => '')) || '').trim();
  pass('H2 第一名有标题与累计借阅次数', firstTitle.length > 0 && firstCount.includes('借阅'), firstTitle + ' / ' + firstCount);

  await firstHot.click();
  await page.waitForTimeout(700);
  const dlgTitle = ((await page.locator('.dlg-book-title').textContent().catch(() => '')) || '').trim();
  pass('H3 点击热门卡打开对应详情', dlgTitle === firstTitle, dlgTitle + ' / ' + firstTitle);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(400);

  await page.locator('[data-testid="search-sort"]').click();
  await page.waitForTimeout(300);
  await page.locator('.el-select-dropdown__item:has-text("借阅热度")').click();
  await page.waitForTimeout(1200);
  const firstBook = ((await page.locator('[data-testid="book-card"]').first().locator('h3').textContent().catch(() => '')) || '').trim();
  pass('H4 借阅热度排序首条与热门第一名一致', firstBook === firstTitle, firstBook + ' / ' + firstTitle);
  pass('H5 选择热门排序后热门条自动隐藏', !(await hot.isVisible().catch(() => false)));

  await page.locator('[data-testid="search-sort"]').click();
  await page.waitForTimeout(300);
  await page.locator('.el-select-dropdown__item:has-text("最新上架")').click();
  await page.waitForTimeout(1200);
  pass('H6 切回最新上架后热门条恢复', await hot.isVisible().catch(() => false));
  await page.screenshot({ path: path.join(SHOT_DIR, '01_热门借阅条.png') });

  // ===== [Q] 读者额度（管理员改额度 → 馆员借还页看到新额度） =====
  await login('admin');
  await page.goto(BASE + '/user-manage', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  const zsRow = page.locator('tr:has-text("R20260001")').first();
  pass('Q1 用户列表默认额度显示 2/5', ((await zsRow.textContent()) || '').includes('2/5'),
    (((await zsRow.textContent()) || '').trim().slice(0, 40)));

  await zsRow.locator('button:has-text("编辑")').click();
  await page.waitForTimeout(600);
  const limitInput = page.locator('[data-testid="user-borrow-limit"] input');
  await limitInput.fill('8');
  await page.locator('.el-dialog:visible button:has-text("保存")').click();
  await page.waitForTimeout(1500);
  pass('Q2 保存额度 8 后列表显示 2/8', ((await zsRow.textContent()) || '').includes('2/8'),
    (((await zsRow.textContent()) || '').trim().slice(0, 40)));
  await page.screenshot({ path: path.join(SHOT_DIR, '02_读者额度编辑.png') });

  await login('librarian01');
  await page.goto(BASE + '/borrow-handle', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('input[data-testid="borrow-reader-input"]', { timeout: 10000 });
  await page.waitForTimeout(800);
  await page.fill('input[data-testid="borrow-reader-input"]', 'R20260001');
  await page.click('[data-testid="borrow-reader-search"]');
  await page.waitForTimeout(1200);
  await page.click('[data-testid="borrow-reader-table"] .el-table__row:has-text("R20260001")');
  await page.waitForTimeout(1800);
  const cardText = ((await page.locator('.reader-panel').textContent().catch(() => '')) || '').replace(/\s+/g, '');
  pass('Q3 借还页读者卡按读者级额度显示 2/8', cardText.includes('2/8'), cardText.slice(0, 60));
  await page.screenshot({ path: path.join(SHOT_DIR, '03_借还页读者额度.png') });

  pass('Q9 热门+额度段无页面/控制台错误', errors === 0, 'errors=' + errors);

  // ===== [N] 站内通知（顶栏铃铛 + 消息中心） =====
  const API = 'http://localhost:8080';
  const apiLogin = async (u) => {
    const r = await page.request.post(API + '/auth/login', { data: { username: u, password: '123456' } });
    const j = await r.json();
    return (j.data || {}).token;
  };
  const adminToken = await apiLogin('admin');
  const scanRes = await page.request.post(API + '/notifications/scan', { headers: { token: adminToken } });
  const scanJson = await scanRes.json();
  pass('N1 管理员扫描生成提醒通知', scanJson.code === 200 && (scanJson.data || {}).created >= 1,
    JSON.stringify(scanJson.data || {}).slice(0, 60));

  await login('reader001');
  await page.waitForTimeout(1500);
  const bell = page.locator('[data-testid="notification-bell"]');
  pass('N2 顶栏铃铛对读者可见', await bell.isVisible().catch(() => false));
  const badgeText = ((await page.locator('.notify-badge').textContent().catch(() => '')) || '').trim();
  pass('N3 铃铛显示未读红点', /\d/.test(badgeText), 'badge=' + badgeText);

  await bell.click();
  await page.waitForTimeout(1500);
  pass('N4 进入消息中心页', page.url().includes('/notifications'), page.url());
  const itemCount = await page.locator('[data-testid="notification-item"]').count();
  pass('N5 消息列表渲染（≥1 条）', itemCount >= 1, 'count=' + itemCount);
  await page.screenshot({ path: path.join(SHOT_DIR, '04_消息中心.png') });

  await page.click('[data-testid="notification-read-all"]');
  await page.waitForTimeout(1500);
  const badgeAfter = ((await page.locator('.notify-badge').textContent().catch(() => '')) || '').trim();
  pass('N6 全部已读后红点消失', !/\d/.test(badgeAfter), 'badge=' + badgeAfter);
  pass('N7 无未读时"全部已读"按钮置灰',
      await page.locator('[data-testid="notification-read-all"]').isDisabled().catch(() => false));

  await page.locator('[data-testid="notification-item"]').first().click();
  await page.waitForTimeout(1500);
  pass('N8 点击消息跳转对应页签', page.url().includes('/my-borrow'), page.url());

  await login('admin');
  pass('N9 管理员顶栏不显示读者铃铛',
    !(await page.locator('[data-testid="notification-bell"]').isVisible().catch(() => false)));
  pass('N10 通知段无新增页面/控制台错误', errors === 0, 'errors=' + errors);

  console.log('===== 推荐项浏览器（热门 + 额度 + 通知）: ' + results.filter((r) => r.pass).length + '/' + results.length + ' =====');
  console.log('[截图] 本轮运行截图（非交付物目录）：' + SHOT_DIR);
  await browser.close();
  process.exit(results.every((r) => r.pass) ? 0 : 1);
})().catch((e) => {
  console.error('EXC', e);
  process.exit(1);
});
