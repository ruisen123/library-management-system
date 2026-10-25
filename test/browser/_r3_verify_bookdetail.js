// 2026-10-25：BookDetail 孤儿页补入口验证（临时脚本，不计入 19 套件）
const { chromium } = require('playwright');
const BASE = 'http://localhost:5173';
const results = [];
const pass = (n, c, d) => { results.push({ n, c: !!c }); console.log('[' + (c ? 'PASS' : 'FAIL') + '] ' + n + ' ' + (d || '')); };
async function loginRole(page, tid) {
  await page.evaluate(() => localStorage.clear()).catch(() => {});
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.click('[data-testid="' + tid + '"]');
  await page.waitForURL((u) => !u.pathname.endsWith('/login'), { timeout: 10000 });
  await page.waitForTimeout(700);
}
(async () => {
  const browser = await chromium.launch({ args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] });
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e)));
  await loginRole(page, 'quick-login-reader');
  await page.goto(BASE + '/book-search', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1800);
  await page.locator('.book-card').first().click();
  await page.waitForTimeout(1500);
  const link = page.locator('[data-testid="detail-open-full"]');
  const cnt = await link.count();
  const href = cnt ? await link.getAttribute('href') : '';
  pass('K1 详情弹窗出现「在新标签页打开完整详情」入口', cnt === 1, 'count=' + cnt + ' href=' + href);
  pass('K2 链接指向 book-detail/:id', /^\/book-detail\/\d+$/.test(href || ''), 'href=' + href);
  let popped = null, pageErr = 0;
  if (href) {
    const [np] = await Promise.all([ctx.waitForEvent('page', { timeout: 10000 }), link.click()]);
    popped = np;
    popped.on('pageerror', () => { pageErr++; });
    await popped.waitForLoadState('domcontentloaded');
    await popped.waitForTimeout(2000);
  }
  const url = popped ? popped.url() : '';
  const body = popped ? ((await popped.locator('body').textContent().catch(() => '')) || '') : '';
  pass('K3 新标签确实打开到 book-detail 路由（未被 404/兜底页吞掉）', url.indexOf('/book-detail/') >= 0, 'url=' + url);
  pass('K4 该页有真实内容（非空白页）', body.replace(/\s+/g, '').length > 40, 'bodyLen=' + body.replace(/\s+/g, '').length + ' head=' + body.replace(/\s+/g, ' ').slice(0, 60));
  pass('K5 新标签无 JS 运行时错误', pageErr === 0, 'pageErrors=' + pageErr);
  pass('K6 主页面全程无 JS 运行时错误', errs.length === 0, errs.slice(0, 2).join(' | ').slice(0, 120));
  await browser.close();
  const bad = results.filter((r) => !r.c);
  console.log('---');
  console.log('合计 ' + results.length + ' 项，通过 ' + (results.length - bad.length) + '，失败 ' + bad.length);
  process.exit(bad.length ? 1 : 0);
})();