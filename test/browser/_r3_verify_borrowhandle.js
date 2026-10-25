// 2026-10-24 R3-D3 验证：BorrowHandle「今日到期」截断提示（临时脚本，不计入 19 套件）
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
  const page = await browser.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e)));
  await loginRole(page, 'quick-login-librarian');
  await page.goto(BASE + '/borrow-handle', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  const markNormal = await page.locator('text=仅前 1000 条').count();
  pass('H1 种子态（在借中 4 条 < 1000）不显示截断提示，不打扰馆员', markNormal === 0, 'count=' + markNormal);
  const onLoan = await page.locator('.today-item:has-text("当前在借") .today-num').textContent().catch(() => '');
  // 同源比对：UI 显示值必须等于接口返回的 total（不硬编码种子值，避免断言脆弱）
  const lg = await page.request.post('http://localhost:8080/auth/login', { data: { username: 'librarian01', password: '123456' } });
  const tok = ((await lg.json()).data || {}).token;
  const rr = await page.request.get('http://localhost:8080/borrow-records?page=1&size=1&status=1', { headers: { token: tok } });
  const apiTotal = (((await rr.json()).data) || {}).total;
  pass('H2 当前在借与接口 total 同源一致', Number((onLoan || '').trim()) === apiTotal,
    'ui=' + (onLoan || '').trim() + ' api=' + apiTotal);
  // 拦截 status=1 的在借列表请求，伪造 total=5000（records 仍为真实 8 条）
  let intercepted = 0;
  await page.route('**/borrow-records**', async (route) => {
    const u = route.request().url();
    if (u.indexOf('status=1') >= 0 && u.indexOf('startDate') < 0) {
      const resp = await route.fetch();
      const j = await resp.json();
      if (j && j.data) { j.data.total = 5000; intercepted++; }
      return route.fulfill({ response: resp, body: JSON.stringify(j), headers: Object.assign({}, resp.headers(), { 'content-type': 'application/json' }) });
    }
    return route.continue();
  });
  await page.locator('.el-button:has-text("刷新")').first().click();
  await page.waitForTimeout(1800);
  const markTrunc = await page.locator('text=仅前 1000 条').count();
  const plusMark = await page.locator('.today-item:has-text("今日到期") .today-num span').count();
  pass('H3 伪造 total=5000 后出现「仅前 1000 条」提示', markTrunc >= 1 && intercepted >= 1, 'mark=' + markTrunc + ' intercepted=' + intercepted);
  pass('H4 数字旁出现 + 号（提示数字不完整）', plusMark >= 1, 'plus=' + plusMark);
  const ttl = await page.locator('.today-item:has-text("今日到期") [title]').getAttribute('title').catch(() => '');
  pass('H5 提示带可悬停说明', (ttl || '').indexOf('1000') >= 0, 'title=' + (ttl || '').slice(0, 60));
  pass('H6 全程无 JS 运行时错误', errs.length === 0, errs.slice(0, 2).join(' | ').slice(0, 140));
  await browser.close();
  const bad = results.filter((r) => !r.c);
  console.log('---');
  console.log('合计 ' + results.length + ' 项，通过 ' + (results.length - bad.length) + '，失败 ' + bad.length);
  process.exit(bad.length ? 1 : 0);
})();