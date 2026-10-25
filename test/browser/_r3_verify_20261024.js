// 2026-10-24 R3 缺口补齐验证：管理员端封面能力 + 补扫通知按钮（临时脚本，不计入 19 套件）
const { chromium } = require('playwright');
const BASE = 'http://localhost:5173';
const results = [];
const pass = (name, cond, detail) => {
  results.push({ name, pass: !!cond });
  console.log('[' + (cond ? 'PASS' : 'FAIL') + '] ' + name + ' ' + (detail || ''));
};
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
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  const consoleErrs = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrs.push(m.text()); });
  // 1) 馆员端：图书档案管理
  await loginRole(page, 'quick-login-librarian');
  await page.goto(BASE + '/book-manage', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  const hasCoverCol = await page.locator('.el-table__header th:has-text("封面")').count();
  pass('B1 列表出现封面列', hasCoverCol >= 1, 'th count=' + hasCoverCol);
  const coverCells = await page.locator('.el-table__body .book-cover').count();
  pass('B2 列表渲染出封面缩略图', coverCells >= 1, 'book-cover count=' + coverCells);
  await page.locator('.el-button:has-text("新增图书")').first().click();
  await page.waitForTimeout(800);
  const dlg = page.locator('.el-dialog:visible').first();
  const hasCoverItem = await dlg.locator('.el-form-item:has-text("封面地址")').count();
  pass('B3 新增弹窗有封面地址字段', hasCoverItem >= 1, 'count=' + hasCoverItem);
  const coverItem = dlg.locator('.el-form-item:has-text("封面地址")');
  const input = coverItem.locator('input').first();
  await input.fill('/covers/9787111544937.jpg');
  await page.waitForTimeout(1000);
  const b4src = (await coverItem.locator('.book-cover__img').getAttribute('src').catch(() => '')) || '';
  pass('B4 填真实图片地址 → 预览出真图', b4src.includes('/covers/9787111544937.jpg'), 'src=' + b4src);
  await input.fill('/upload/not-exist-xyz.jpg');
  await page.waitForTimeout(1500);
  const fb = await coverItem.locator('.book-cover__fallback').count();
  pass('B5 填坏地址 → 降级为生成封面（不显示坏图）', fb >= 1, 'fallback=' + fb);
  const warn = await coverItem.locator('span:has-text("该地址加载不出来")').count();
  pass('B6 坏地址出现「已回退」提示（管理员不再误以为生效）', warn >= 1, 'warn=' + warn);
  await input.fill('/covers/9787111407010.jpg');
  await page.waitForTimeout(1500);
  const warn2 = await coverItem.locator('span:has-text("该地址加载不出来")').count();
  const img2 = (await coverItem.locator('.book-cover__img').getAttribute('src').catch(() => '')) || '';
  pass('B7 改回可用地址 → 提示消失并重新出图（watch 修正生效）',
    warn2 === 0 && img2.includes('9787111407010'), 'warn=' + warn2 + ' src=' + img2);
  await page.locator('.el-dialog:visible .el-button:has-text("取消")').first().click().catch(() => {});
  await page.waitForTimeout(400);
  // 2) 管理员端：数据总览
  await loginRole(page, 'quick-login-admin');
  await page.goto(BASE + '/dashboard', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
  const scanBtn = await page.locator('.el-button:has-text("补扫通知")').count();
  pass('A1 数据总览出现补扫通知按钮', scanBtn >= 1, 'count=' + scanBtn);
  let clicked = false;
  if (scanBtn >= 1) {
    await page.locator('.el-button:has-text("补扫通知")').first().click();
    clicked = true;
    await page.waitForTimeout(2500);
  }
  pass('A2 点击补扫通知无异常', clicked && errors.length === 0, 'pageerrors=' + errors.length);
  pass('A3 全程无 JS 运行时错误', errors.length === 0, errors.slice(0, 2).join(' | ').slice(0, 160));
  const realConsole = consoleErrs.filter((t) => !/favicon|ResizeObserver|Download the Vue Devtools/i.test(t));
  pass('A4 全程无控制台错误（已滤 frameware 噪声）', realConsole.length === 0, realConsole.slice(0, 2).join(' | ').slice(0, 160));
  await browser.close();
  const bad = results.filter((r) => !r.pass);
  console.log('---');
  console.log('合计 ' + results.length + ' 项，通过 ' + (results.length - bad.length) + '，失败 ' + bad.length);
  process.exit(bad.length ? 1 : 0);
})();