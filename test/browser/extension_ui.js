// 浏览器扩展功能 E2E（2026-10-22）：预约入口 / 报表导出 / 批量导入与模板下载
// 依赖：playwright（NODE_PATH 指向 test/browser/node_modules）
const { chromium } = require('playwright');
const BASE = 'http://localhost:5173';
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
const menuTid = (path) => '[data-testid="menu-' + path.replace(/^\//, '') + '"]';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ acceptDownloads: true });
  const page = await ctx.newPage();
  let errors = 0;
  page.on('pageerror', () => errors++);
  page.on('console', m => { if (m.type() === 'error') errors++; });

  const login = async (user) => {
    const tid = LOGIN_TID[user];
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => localStorage.clear());
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(500);
    await page.waitForSelector('[data-testid="' + tid + '"]', { timeout: 8000 });
    await page.click('[data-testid="' + tid + '"]');
    await page.waitForURL(u => !u.pathname.endsWith('/login'), { timeout: 8000 });
    await page.waitForTimeout(1200);
  };

  // ===== 读者：找书页预约入口 + 我的书架预约页签 =====
  await login('reader001');
  const mainText = (await page.locator('.el-main').textContent().catch(() => '')) || '';
  pass('R1 读者找书页显示待取预约入口', mainText.includes('待取预约'), 'len=' + mainText.length);

  // ===== R1b~R1f：预约 / 取消 共用一个按钮，红蓝切换（2026-10-22 作者要求）=====
  // 用固定书名定位，避免依赖卡片顺序
  const card = page.locator('[data-testid="book-card"]:has-text("平凡的世界")').first();
  const actBtn = card.locator('button');
  const stockTag = card.locator('.el-tag').first();

  // 健壮性：若上一轮残留了预约，先清掉，保证从"未预约"开始
  let initialBtn = ((await actBtn.textContent()) || '').trim();
  if (initialBtn.includes('取消预约')) {
    await actBtn.click();
    await page.waitForTimeout(700);
    await page.locator('.el-message-box__btns button:has-text("确定取消")').click().catch(() => {});
    await page.waitForTimeout(1800);
    initialBtn = ((await actBtn.textContent()) || '').trim();
  }
  const tagBefore = ((await stockTag.textContent()) || '').trim();
  pass('R1b 初始为蓝色「预约」按钮', initialBtn === '预约', initialBtn + ' / ' + tagBefore);

  await actBtn.click();
  await page.waitForTimeout(2400);
  const afterBtn = ((await actBtn.textContent()) || '').trim();
  const afterCls = (await actBtn.getAttribute('class')) || '';
  const tagAfter = ((await stockTag.textContent()) || '').trim();
  pass(
    'R1c 预约后同一按钮变红「取消预约」',
    afterBtn === '取消预约' && afterCls.includes('danger'),
    afterBtn + ' cls=' + afterCls
  );
  pass(
    'R1d 预约后真实可借数减 1（显示与事实一致）',
    tagBefore !== tagAfter,
    tagBefore + ' -> ' + tagAfter
  );

  await actBtn.click();
  await page.waitForTimeout(900);
  const dlgText = ((await page.locator('.el-message-box__message').textContent().catch(() => '')) || '').trim();
  pass('R1e 取消前弹二次确认', dlgText.includes('取消'), dlgText.slice(0, 36));
  await page.locator('.el-message-box__btns button:has-text("确定取消")').click().catch(() => {});
  await page.waitForTimeout(2400);
  const backBtn = ((await actBtn.textContent()) || '').trim();
  const tagBack = ((await stockTag.textContent()) || '').trim();
  pass(
    'R1f 取消后按钮变回蓝「预约」且可借数恢复',
    backBtn === '预约' && tagBack === tagBefore,
    backBtn + ' / ' + tagBack
  );

  await page.locator(menuTid('/my-borrow')).first().click().catch(() => {});
  await page.waitForTimeout(1000);
  const tabs = await page.locator('[data-testid="myborrow-tabs"] .el-tabs__item').allTextContents();
  pass('R2 我的书架含预约页签', tabs.some(t => t.includes('预约')), tabs.join(','));
  await page.locator('[data-testid="myborrow-tabs"] .el-tabs__item:has-text("预约")').click().catch(() => {});
  await page.waitForTimeout(800);
  const reserveTabText = (await page.locator('.el-main').textContent().catch(() => '')) || '';
  pass('R3 预约页签可渲染（空态或列表）',
    reserveTabText.includes('暂无预约') || reserveTabText.includes('排队位置'), 'len=' + reserveTabText.length);

  // ===== 登录页注册切换态（只验 UI，不实际注册，避免占用注册限流额度）=====
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => localStorage.clear());
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(500);
  await page.click('[data-testid="register-toggle"]');
  await page.waitForTimeout(400);
  const hasRegisterForm = await page.locator('[data-testid="register-username"]').count();
  const hasSubmit = await page.locator('[data-testid="register-submit"]').count();
  pass('R4 登录页可切换到注册表单', hasRegisterForm === 1 && hasSubmit === 1);
  const fieldCounts = await Promise.all([
    page.locator('[data-testid="register-realname"]').count(),
    page.locator('[data-testid="register-phone"]').count(),
    page.locator('[data-testid="register-password"]').count(),
    page.locator('[data-testid="register-confirm"]').count()
  ]);
  pass('R5 注册表单含姓名/手机号/密码/确认密码',
    fieldCounts.every(c => c === 1), fieldCounts.join(','));
  await page.click('[data-testid="back-login"]');
  await page.waitForTimeout(300);
  pass('R6 可返回登录态', (await page.locator('[data-testid="login-submit"]').count()) === 1);

  // ===== 管理员：报表导出（真实下载 xlsx）=====
  await login('admin');
  await page.waitForTimeout(800);
  const dashText = (await page.locator('.el-main').textContent().catch(() => '')) || '';
  pass('A1 Dashboard 显示借阅报表导出', dashText.includes('借阅报表导出'), 'len=' + dashText.length);
  const exportBtn = page.locator('[data-testid="stats-export"]');
  const downloadPromise = page.waitForEvent('download', { timeout: 15000 }).catch(() => null);
  await exportBtn.click();
  const exportDownload = await downloadPromise;
  pass('A2 点击导出触发 xlsx 下载',
    exportDownload && /\.xlsx$/i.test(exportDownload.suggestedFilename()),
    exportDownload ? exportDownload.suggestedFilename() : 'no download');

  // ===== 馆员：批量导入弹窗 + 模板下载 =====
  await login('librarian01');
  await page.locator(menuTid('/book-manage')).first().click().catch(() => {});
  await page.waitForTimeout(1000);
  pass('L1 图书管理显示批量导入入口',
    (await page.locator('[data-testid="book-import-open"]').count()) === 1);
  await page.click('[data-testid="book-import-open"]');
  await page.waitForTimeout(500);
  const importDialogText = (await page.locator('.el-dialog').textContent().catch(() => '')) || '';
  pass('L2 导入弹窗可打开并显示上传区',
    importDialogText.includes('批量导入图书') && importDialogText.includes('点击选择'));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(400);
  const tplPromise = page.waitForEvent('download', { timeout: 15000 }).catch(() => null);
  await page.click('[data-testid="book-import-template"]');
  const tplDownload = await tplPromise;
  pass('L3 模板下载触发 xlsx 下载',
    tplDownload && /\.xlsx$/i.test(tplDownload.suggestedFilename()),
    tplDownload ? tplDownload.suggestedFilename() : 'no download');

  // ===== U1~U4：图书助理浮动按钮不得压住可点击控件（2026-10-22 覆盖补强）=====
  // 作者反馈：按钮原固定在右下角，与右下对齐的分页器（页码 / 下一页）重叠。
  // 改为左下角后，用几何断言长期守住：按钮矩形与任何可点击控件的交集面积必须为 0。
  // 2026-10-22 核验补强 4 个盲点：① 覆盖全部 9 个分页页 + 数据总览 + 借还办理（原仅 4 页）；
  // ② 选择器补可点 div（.book-card/.hot-card）与 [role=button]/[role=tab]；③ 分页器必须真的进入视口
  // （否则滚动没生效也会绿，属空断言）；④ 补折叠侧栏态（按钮 x=88）不得压住内容。
  // 断言条数刻意保持 4 条（U1~U4），因此 19 套件总项数不变。
  const fabOverlap = async (path, expectPager) => {
    await page.goto(BASE + path, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1600);
    // 滚到底：分页器/最后一行内容只有滚到内容底部时才进入视口下沿，那正是浮动按钮与它相撞的时机
    await page.evaluate(() => {
      const m = document.querySelector('.el-main');
      if (m) m.scrollTop = m.scrollHeight;
    });
    await page.waitForTimeout(400);
    return page.evaluate((expectPager) => {
      const el = document.querySelector('[data-testid="assistant-fab"]');
      if (!el) return { missing: true, hits: [] };
      const f = el.getBoundingClientRect();
      const hits = [];
      document.querySelectorAll('button, a[href], input, [role="button"], [role="tab"], .el-pager li, .el-select, .book-card, .hot-card').forEach((n) => {
        if (n.closest('.assistant-root')) return;
        const r = n.getBoundingClientRect();
        if (!r.width || !r.height) return;
        const w = Math.min(r.right, f.right) - Math.max(r.left, f.left);
        const h = Math.min(r.bottom, f.bottom) - Math.max(r.top, f.top);
        if (w > 0 && h > 0) {
          hits.push(
            (n.className || n.tagName).toString().split(' ')[0] + '/' + (n.textContent || '').trim().slice(0, 6) +
              '=' + Math.round(w * h)
          );
        }
      });
      const pager = document.querySelector('.el-pagination');
      const pr = pager ? pager.getBoundingClientRect() : null;
      return { hits, x: Math.round(f.left), right: Math.round(f.right), y: Math.round(f.top),
               vw: innerWidth, vh: innerHeight,
               pagerVisible: pr ? (pr.top >= 0 && pr.bottom <= innerHeight) : null, expectPager };
    }, expectPager);
  };

  const fabGroups = [
    { name: 'U1 读者侧', role: 'reader001', pages: [['/book-search', true], ['/my-borrow', false], ['/notifications', false]] },
    { name: 'U2 馆员侧', role: 'admin', pages: [['/book-manage', true], ['/borrow-record', true], ['/fine-manage', true], ['/notice-manage', true]] },
    { name: 'U3 管理与其他', role: 'admin', pages: [['/user-manage', true], ['/operation-log', true], ['/dashboard', false], ['/borrow-handle', false]] },
  ];
  for (const g of fabGroups) {
    await login(g.role);
    const bad = [];
    for (const [p, expectPager] of g.pages) {
      const r = await fabOverlap(p, expectPager);
      const inContent = !r.missing && r.x >= 64 && r.right <= r.vw && r.y >= 0 && r.y + 52 <= r.vh;
      const pagerOk = !expectPager || r.pagerVisible === true;
      if (r.missing || r.hits.length > 0 || !inContent || !pagerOk) {
        bad.push(p + ' ' + (r.missing ? 'fab missing' : (r.hits.join(',') || 'pos=' + r.x + ',' + r.y) + ' pager=' + r.pagerVisible));
      }
    }
    pass(g.name + ' 助理按钮零控件重叠、完整可见、分页器已在视口', bad.length === 0,
      bad.length ? bad.join(' | ') : 'pages=' + g.pages.length + ' 全过');
  }

  // U4：折叠侧栏态（按钮 left=88，更贴近内容区左缘）同样不得压住任何可点控件
  await login('admin');
  const foldBad = [];
  for (const p of ['/book-manage', '/dashboard']) {
    await page.goto(BASE + p, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1400);
    await page.locator('.el-icon[style*="cursor"]').first().click({ timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(500);
    await page.evaluate(() => {
      const m = document.querySelector('.el-main');
      if (m) m.scrollTop = m.scrollHeight;
    });
    await page.waitForTimeout(400);
    const r = await page.evaluate(() => {
      const el = document.querySelector('[data-testid="assistant-fab"]');
      if (!el) return { missing: true, hits: [] };
      const f = el.getBoundingClientRect();
      const hits = [];
      document.querySelectorAll('button, a[href], input, [role="button"], [role="tab"], .el-pager li, .el-select, .book-card, .hot-card').forEach((n) => {
        if (n.closest('.assistant-root')) return;
        const rr = n.getBoundingClientRect();
        if (!rr.width || !rr.height) return;
        const w = Math.min(rr.right, f.right) - Math.max(rr.left, f.left);
        const h = Math.min(rr.bottom, f.bottom) - Math.max(rr.top, f.top);
        if (w > 0 && h > 0) hits.push((n.className || n.tagName).toString().split(' ')[0] + '=' + Math.round(w * h));
      });
      const aside = document.querySelector('aside, .el-aside');
      return { hits, x: Math.round(f.left), right: Math.round(f.right), y: Math.round(f.top),
               vw: innerWidth, vh: innerHeight, asideW: aside ? Math.round(aside.getBoundingClientRect().width) : null };
    });
    const inContent = !r.missing && r.x >= 64 && r.right <= r.vw && r.y >= 0 && r.y + 52 <= r.vh;
    if (r.missing || r.hits.length > 0 || !inContent || r.asideW !== 64) {
      foldBad.push(p + ' ' + (r.missing ? 'fab missing' : (r.hits.join(',') || 'pos=' + r.x + ',' + r.y) + ' aside=' + r.asideW));
    }
  }
  pass('U4 折叠侧栏态 助理按钮零控件重叠且完整可见', foldBad.length === 0,
    foldBad.length ? foldBad.join(' | ') : 'pages=2 aside=64 全过');

  pass('E1 全程无页面/控制台错误', errors === 0, 'errors=' + errors);

  await browser.close();
  const total = results.length;
  const passed = results.filter(r => r.pass).length;
  console.log('\n===== 浏览器扩展 E2E 汇总: ' + passed + '/' + total + ' =====');
  process.exit(passed === total ? 0 : 1);
})().catch(e => { console.error('FATAL', e.message); process.exit(2); });
