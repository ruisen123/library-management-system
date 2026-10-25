// 2026-10-25 R3 复查：助手热门推荐是否改用后端 sort=popular（临时脚本，不计入 19 套件）
const { chromium } = require('playwright');
const BASE = 'http://localhost:5173';
const API = 'http://localhost:8080';
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
  const lg = await page.request.post(API + '/auth/login', { data: { username: 'librarian01', password: '123456' } });
  const tok = ((await lg.json()).data || {}).token;
  const pb = await page.request.get(API + '/books?page=1&size=3&sort=popular', { headers: { token: tok } });
  const apiTop = (((await pb.json()).data || {}).records || []).map((b) => b.title);
  console.log('后端 popular 前 3:', JSON.stringify(apiTop));
  await loginRole(page, 'quick-login-librarian');
  await page.click('[data-testid="assistant-fab"]');
  await page.waitForTimeout(600);
  const chips = await page.locator('.quick-chip').allTextContents();
  console.log('快捷问题:', JSON.stringify(chips));
  let asked = false;
  const hot = chips.find((c) => /推荐|热门|借得多/.test(c));
  if (hot) { await page.locator('.quick-chip', { hasText: hot }).first().click(); asked = true; }
  else {
    const inp = page.locator('input[placeholder*="问我"]').first();
    await inp.fill('有什么热门书推荐');
    await page.keyboard.press('Enter');
    asked = true;
  }
  await page.waitForTimeout(2500);
  const bot = (await page.locator('.msg--bot .msg-bubble').last().textContent().catch(() => '')) || '';
  console.log('助手回答:', bot.replace(/\s+/g, ' ').slice(0, 200));
  pass('R1 助手能回答热门推荐', asked && bot.indexOf('《') >= 0, 'len=' + bot.length);
  const hit = apiTop.filter((t) => bot.indexOf(t) >= 0);
  pass('R2 回答内容与后端 sort=popular 前 3 一致（同源比对）', hit.length === Math.min(3, apiTop.length), 'hit=' + hit.length + '/' + apiTop.length + ' ' + JSON.stringify(hit));
  pass('R3 全程无 JS 运行时错误', errs.length === 0, errs.slice(0, 2).join(' | ').slice(0, 140));
  await browser.close();
  const bad = results.filter((r) => !r.c);
  console.log('---');
  console.log('合计 ' + results.length + ' 项，通过 ' + (results.length - bad.length) + '，失败 ' + bad.length);
  process.exit(bad.length ? 1 : 0);
})();