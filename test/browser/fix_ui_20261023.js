// 2026-10-23 审查修复 UI 专项验证（不计入 19 套件 585 项基线）
// 覆盖：导出失败不再假成功 / 预约队列页签 / 馆员可借数扣待取 / 助手规则动态化
// 依赖：后端 8080 + 前端 5173 + playwright；运行前种子已重置
const { chromium } = require('playwright');
const BASE = 'http://localhost:5173';
const API = 'http://localhost:8080';
const results = [];
const pass = (name, cond, detail) => {
  detail = detail || '';
  results.push({ name, pass: !!cond });
  console.log('[' + (cond ? 'PASS' : 'FAIL') + '] ' + name + ' ' + detail);
};

async function apiLogin(username) {
  const r = await fetch(API + '/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: '123456' })
  });
  const j = await r.json();
  return j && j.data && j.data.token;
}

async function apiReserve(token, bookId) {
  const r = await fetch(API + '/reservations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', token },
    body: JSON.stringify({ bookId })
  });
  return await r.json();
}

async function loginRole(page, tid) {
  // 切换角色前清本地态：已登录时 /login 会被守卫弹回角色首页
  await page.evaluate(() => localStorage.clear()).catch(() => {});
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.click('[data-testid="' + tid + '"]');
  await page.waitForURL((u) => !u.pathname.endsWith('/login'), { timeout: 8000 });
  await page.waitForTimeout(700);
}

(async () => {
  // —— 准备数据：reader001 预约《算法导论》(book 2, 有货→待取) 与《Java虚拟机》(book 3, 无货→排队)
  const rt = await apiLogin('reader001');
  const r2 = await apiReserve(rt, 2);
  const r3 = await apiReserve(rt, 3);
  pass('P0 准备：两笔预约成功（待取 + 排队）',
    r2.code === 200 && r2.data && r2.data.status === 2 && r3.code === 200 && r3.data && r3.data.status === 1,
    JSON.stringify({ r2: r2.msg, r3: r3.msg }));

  const browser = await chromium.launch({ args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));

  // —— 1. 助手规则：不得再出现"累计罚金达到 5 元"这类硬编码
  await loginRole(page, 'quick-login-reader');
  await page.click('[data-testid="assistant-fab"]');
  await page.waitForTimeout(500);
  await page.locator('.quick-chip:has-text("借阅规则")').click();
  await page.waitForTimeout(1500);
  const botText = (await page.locator('.msg--bot .msg-bubble').last().textContent().catch(() => '')) || '';
  pass('U1 助手规则来自后端且不再硬编码 5 元阈值',
    botText.includes('新读者默认可借') && !botText.includes('累计罚金达到'),
    botText.replace(/\s+/g, ' ').slice(0, 90));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);

  // —— 2. 预约队列页签（馆员可见、无回收按钮；管理员有回收按钮）
  await loginRole(page, 'quick-login-librarian');
  await page.goto(BASE + '/borrow-record', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(800);
  await page.locator('.el-tabs__item:has-text("预约队列")').first().click();
  await page.waitForTimeout(1200);
  const queueRows = await page.locator('[data-testid="reservation-queue-table"] .el-table__row').count();
  const queueText = (await page.locator('[data-testid="reservation-queue-table"]').textContent().catch(() => '')) || '';
  pass('U2 馆员可见预约队列且有数据',
    queueRows >= 2 && queueText.includes('Java虚拟机') && queueText.includes('张三'),
    'rows=' + queueRows + ' text=' + queueText.replace(/\s+/g, ' ').slice(0, 80));
  pass('U3 馆员看不到"回收过期预约"（仅管理员）',
    (await page.locator('[data-testid="reservation-expire"]').count()) === 0);

  await loginRole(page, 'quick-login-admin');
  await page.goto(BASE + '/borrow-record', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(800);
  await page.locator('.el-tabs__item:has-text("预约队列")').first().click();
  await page.waitForTimeout(800);
  pass('U4 管理员可见"回收过期预约"按钮',
    (await page.locator('[data-testid="reservation-expire"]').count()) === 1);

  // —— 3. 馆员借还办理：可借数必须扣掉待取预约（book2 stock=3, 待取=1 → 显示 2）
  await loginRole(page, 'quick-login-librarian');
  await page.goto(BASE + '/borrow-handle', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(800);
  await page.fill('input[data-testid="borrow-reader-input"]', 'R20260001');
  await page.click('[data-testid="borrow-reader-search"]');
  await page.waitForTimeout(900);
  await page.locator('[data-testid="borrow-reader-table"] .el-table__row').first().click();
  await page.waitForTimeout(500);
  await page.fill('input[data-testid="borrow-book-input"]', '算法导论');
  await page.click('[data-testid="borrow-book-search"]');
  await page.waitForTimeout(900);
  const stockCell = (
    await page
      .locator('[data-testid="borrow-book-table"] .el-table__row:has-text("算法导论") td')
      .nth(3)
      .textContent()
      .catch(() => '')
  ) || '';
  pass('U5 馆员"可借"扣掉待取预约（3-1=2）', stockCell.trim() === '2', 'cell=' + stockCell.trim());

  // —— 4. 管理员导出失败：不得下载坏 JSON、不得提示"已开始下载"
  await loginRole(page, 'quick-login-admin');
  await page.goto(BASE + '/dashboard', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1000);
  const rangeInputs = page.locator('.report-actions .el-range-editor input');
  await rangeInputs.nth(0).fill('2020-01-01');
  await rangeInputs.nth(1).fill('2026-10-23');
  await page.keyboard.press('Enter');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);
  console.log('[debug] export range =',
    await rangeInputs.nth(0).inputValue().catch(() => '?'), '~',
    await rangeInputs.nth(1).inputValue().catch(() => '?'));
  const exportBtn = page.locator('[data-testid="stats-export"]');
  const downloadPromise = page.waitForEvent('download', { timeout: 6000 }).catch(() => null);
  await exportBtn.click({ force: true });
  // 先读错误提示（ElMessage 默认 3 秒消失），再等下载事件超时
  await page.waitForTimeout(1200);
  const messages = (await page.locator('.el-message').allTextContents()).join(' | ');
  const download = await downloadPromise;
  pass('U6 导出超区间：没有触发文件下载', download === null, download ? download.suggestedFilename() : 'no download');
  pass('U7 导出超区间：提示明确错误而非"已开始下载"',
    messages.includes('366') && !messages.includes('报表已开始下载'), messages.slice(0, 160));

  // —— 5. 全流程无页面错误
  pass('U8 全程无页面错误', errors.length === 0, errors.slice(0, 3).join(' | '));

  await browser.close();
  const failed = results.filter((r) => !r.pass).length;
  console.log('\n===== 修复 UI 专项: ' + (results.length - failed) + '/' + results.length + ' =====');
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(2);
});
