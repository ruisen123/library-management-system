// 2026-10-25：编辑图书弹窗改造 + 封面上传真实验证（临时脚本，不计入 19 套件）
const { chromium } = require('playwright');
const path = require('path');
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
  await page.goto(BASE + '/book-manage', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1800);
  await page.locator('.el-table__body .el-button:has-text("编辑")').first().click();
  await page.waitForTimeout(1200);
  const dlg = page.locator('.el-dialog:visible').first();
  const box = await dlg.boundingBox();
  pass('E1 弹窗已加宽（约 920px）', box && box.width > 880, 'width=' + (box ? Math.round(box.width) : 'n/a'));
  const xxl = await dlg.locator('.book-cover--xxl').count();
  const coverBox = xxl ? await dlg.locator('.book-cover--xxl').first().boundingBox() : null;
  pass('E2 封面预览用放大档（xxl）', xxl >= 1 && coverBox && coverBox.width >= 200, 'count=' + xxl + ' w=' + (coverBox ? Math.round(coverBox.width) : 'n/a'));
  const btn = await dlg.locator('[data-testid="cover-upload-btn"]').count();
  pass('E3 出现「从电脑选择图片」按钮', btn >= 1, 'count=' + btn);
  const before = await dlg.locator('.cover-url input').inputValue().catch(() => '');
  const fileInput = dlg.locator('input[type="file"]').first();
  await fileInput.setInputFiles(path.resolve('../../frontend/public/covers/9787111544937.jpg'));
  let after = '', naturalW = 0, okMsg = false;
  for (let i = 0; i < 12; i++) {
    await page.waitForTimeout(700);
    after = await dlg.locator('.cover-url input').inputValue().catch(() => '');
    if (after && after.indexOf('/upload/') === 0) break;
  }
  okMsg = (await page.locator('.el-message:has-text("封面已上传")').count()) > 0;
  pass('E4 选图后真实上传，cover 字段被写回 /upload/ 地址', after.indexOf('/upload/cover-') === 0, 'before=' + JSON.stringify(before) + ' after=' + JSON.stringify(after));
  pass('E5 上传成功提示出现', okMsg, 'toast=' + okMsg);
  if (after.indexOf('/upload/') === 0) {
    await page.waitForTimeout(1200);
    naturalW = await page.evaluate((src) => {
      const im = Array.from(document.querySelectorAll('.book-cover__img')).find((x) => x.getAttribute('src') === src);
      return im ? im.naturalWidth : 0;
    }, after);
  }
  pass('E6 上传后的图片真的可访问（naturalWidth>0）', naturalW > 0, 'naturalWidth=' + naturalW);
  pass('E7 全程无 JS 运行时错误', errs.length === 0, errs.slice(0, 2).join(' | ').slice(0, 140));
  await browser.close();
  const bad = results.filter((r) => !r.c);
  console.log('---');
  console.log('合计 ' + results.length + ' 项，通过 ' + (results.length - bad.length) + '，失败 ' + bad.length);
  process.exit(bad.length ? 1 : 0);
})();