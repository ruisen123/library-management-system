// 生成"修复后"全量界面截图（3 角色 15 个二级界面 + 登录页，清晰命名归档）
const { chromium } = require('playwright');
const fs = require('fs');
const BASE = 'http://localhost:5173';
const OUT = 'C:\\Users\\23754\\Desktop\\软件\\SpringBoot Vue library\\docs\\界面截图\\修复后（当前版本）';
fs.mkdirSync(OUT, { recursive: true });

const GROUPS = [
  ['admin', [
    ['02_数据总览', '/dashboard'],
    ['03_用户管理', '/user-manage'],
    ['04_分类管理', '/category-manage'],
    ['05_出版社管理', '/publisher-manage'],
    ['06_借阅规则说明', '/rule-config'],
    ['07_公告管理', '/notice-manage'],
    ['08_操作日志', '/operation-log'],
    ['09_个人中心', '/profile'],
  ]],
  ['librarian01', [
    ['10_图书档案管理', '/book-manage'],
    ['11_借还办理', '/borrow-handle'],
    ['12_借阅记录', '/borrow-record'],
    ['13_罚金管理', '/fine-manage'],
  ]],
  ['reader001', [
    ['14_图书检索', '/book-search'],
    ['15_图书详情', '/book-detail/1'],
    ['16_我的借阅', '/my-borrow'],
  ]],
];

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(e.message));

  // 01 登录页（未登录）
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1800);
  await page.screenshot({ path: `${OUT}\\01_登录页.png` });
  console.log('01_登录页 完成');

  for (const [user, list] of GROUPS) {
    // 切换账号（清 localStorage 后快捷登录）
    await page.evaluate(() => localStorage.clear()).catch(() => {});
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(700);
    await page.click(`button.quick-btn:has-text("${user}")`);
    await page.waitForURL((u) => !u.pathname.endsWith('/login'), { timeout: 12000 });
    await page.waitForTimeout(1200);

    for (const [name, url] of list) {
      await page.goto(BASE + url, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1500);
      await page.screenshot({ path: `${OUT}\\${name}.png` });
      console.log(`${name} 完成`);
    }
  }

  console.log('pageerror=' + errs.length);
  await browser.close();
  console.log('ARCHIVE_SHOTS_DONE');
})().catch((e) => { console.error('FATAL', e.message); process.exit(2); });
