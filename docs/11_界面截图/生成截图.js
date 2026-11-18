// LibMS 界面截图生成脚本（2026-11-19 重写）
// 修复：①路径改为相对脚本定位（原写死 C:\Users\<你的用户名>\...）；②登录用 data-testid（原用按钮文本，生产构建无快捷登录时会失败）；
//       ③无头模式；④覆盖 17 页 + 空态/弹窗/折叠侧栏；⑤输出目录用 docs/11_界面截图/2026-11-19_视觉美化/
// 用法：NODE_PATH=test/browser/node_modules node "docs/11_界面截图/生成截图.js"
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const BASE = 'http://localhost:5173';
// 相对本脚本定位到项目根：docs/11_界面截图/ → 上两级
const ROOT = path.resolve(__dirname, '..', '..');
const OUT = path.join(ROOT, 'docs', '11_界面截图', '2026-11-19_视觉美化');
fs.mkdirSync(OUT, { recursive: true });

const LOGIN_TID = { admin: 'quick-login-admin', librarian01: 'quick-login-librarian', reader001: 'quick-login-reader' };
const GROUPS = [
  ['admin', [
    ['02_数据总览', '/dashboard'], ['03_用户管理_读者', '/user-manage'],
    ['04_图书档案管理', '/book-manage'], ['05_分类管理', '/category-manage'],
    ['06_出版社管理', '/publisher-manage'], ['07_公告管理', '/notice-manage'],
    ['08_罚金管理', '/fine-manage'], ['09_借还办理', '/borrow-handle'],
    ['10_借阅记录', '/borrow-record'], ['11_借阅规则', '/rule-config'],
    ['12_操作日志', '/operation-log'], ['13_个人中心', '/profile'],
  ]],
  ['reader001', [
    ['14_找书', '/book-search'], ['15_图书详情', '/book-detail/1'],
    ['16_我的书架', '/my-borrow'], ['17_消息中心', '/notifications'],
  ]],
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.launch({ headless: true });  // 无头，不占桌面
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1029 }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 100)); });

  const shot = async (name) => { await page.screenshot({ path: path.join(OUT, name + '.png') }); console.log(name + ' 完成'); };
  const login = async (role) => {
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => localStorage.clear());
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await sleep(600);
    await page.waitForSelector('[data-testid="' + LOGIN_TID[role] + '"]', { timeout: 10000 });
    await page.click('[data-testid="' + LOGIN_TID[role] + '"]');
    await page.waitForURL((u) => !u.pathname.endsWith('/login'), { timeout: 12000 });
    await sleep(1800);
  };

  // 01 登录页
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await sleep(1500);
  await shot('01_登录页');

  for (const [role, list] of GROUPS) {
    await login(role);
    for (const [name, url] of list) {
      await page.goto(BASE + url, { waitUntil: 'domcontentloaded' });
      await sleep(2000);
      await shot(name);
    }
  }

  // 补充：折叠侧栏 + 弹窗 + 空态
  await login('admin');
  await page.goto(BASE + '/dashboard', { waitUntil: 'domcontentloaded' });
  await sleep(1600);
  await page.locator('.layout-header .el-icon').first().click();
  await sleep(700);
  await shot('18_折叠侧栏');
  await page.locator('.layout-header .el-icon').first().click();
  await sleep(600);
  await page.goto(BASE + '/book-manage', { waitUntil: 'domcontentloaded' });
  await sleep(1800);
  await page.locator('button:has-text("新增图书")').first().click();
  await sleep(900);
  await shot('19_新增图书弹窗');
  await page.keyboard.press('Escape');
  await sleep(500);
  // 空态：无结果搜索
  await login('reader001');
  await page.goto(BASE + '/book-search', { waitUntil: 'domcontentloaded' });
  await sleep(1800);
  await page.locator('[data-testid="search-keyword"]').fill('不存在的书xyz987');
  await page.locator('[data-testid="search-submit"]').click();
  await sleep(1600);
  await shot('20_找书空态');

  console.log('页面/控制台错误数：' + errs.length + (errs.length ? ' :: ' + errs.slice(0, 3).join(' | ') : ''));
  console.log('输出目录：' + OUT);
  await browser.close();
  console.log('LIBMS_SHOTS_DONE');
})().catch((e) => { console.error('FATAL', e.message); process.exit(2); });