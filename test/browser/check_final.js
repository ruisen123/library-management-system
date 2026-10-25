// 浏览器页面级全流程 E2E：登录→借阅→归还→统计→各角色页面遍历（历轮未覆盖层）
// 位置：test/browser/check_final.js（2026-09-12 从 %TEMP% 的 pwcheck 迁入，项目内为唯一维护版本）
// 依赖：playwright（运行器经 NODE_PATH 指向 pwcheck/node_modules；也可在本目录 npm i playwright）
//
// 2026-10-21 界面改造：选择器从「结构/文案」改为 data-testid 契约。
// 原因——原脚本依赖 .el-table / .toolbar button:has-text("查询") / .el-menu-item:has-text("我的借阅")，
// 界面一改（卡片墙、输入即搜、菜单改名）就整批失效。data-testid 是测试契约，UI 怎么改都要保留。
const { chromium } = require('playwright');
const BASE = 'http://localhost:5173';
const results = [];
const pass = (name, cond, detail) => {
  detail = detail || '';
  results.push({ name, pass: !!cond });
  console.log('[' + (cond ? 'PASS' : 'FAIL') + '] ' + name + ' ' + detail);
};

// 快捷登录按钮的 data-testid（与 Login.vue 一一对应）
const LOGIN_TID = {
  admin: 'quick-login-admin',
  librarian01: 'quick-login-librarian',
  reader001: 'quick-login-reader'
};
// 菜单项 data-testid = 'menu-' + 路由 path（见 Sidebar.vue）
const menuTid = (path) => '[data-testid="menu-' + path.replace(/^\//, '') + '"]';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  let errors = 0;
  page.on('pageerror', () => errors++);
  page.on('console', m => { if (m.type() === 'error') errors++; });

  const login = async (user) => {
    const tid = LOGIN_TID[user];
    if (!tid) throw new Error('未登记快捷登录 data-testid: ' + user);
    // 清空本地会话，避免已登录状态被路由守卫重定向拦截登录页
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => localStorage.clear());
    await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(500);
    await page.waitForSelector('[data-testid="' + tid + '"]', { timeout: 8000 });
    await page.click('[data-testid="' + tid + '"]');
    await page.waitForURL(u => !u.pathname.endsWith('/login'), { timeout: 8000 });
    return page.url();
  };

  // 1. reader 登录跳转
  const url1 = await login('reader001');
  pass('R1 读者登录自动跳转', url1.includes('/book-search'), url1);

  // 2. 图书检索页渲染（卡片墙或表格均可，用主内容区有内容判断）
  await page.waitForSelector('.el-main', { timeout: 8000 });
  const searchText = await page.locator('.el-main').textContent().catch(() => '');
  pass('R2 图书检索列表渲染', (searchText || '').trim().length > 10, 'len=' + (searchText || '').trim().length);

  // 3. 我的借阅四 Tab（预约页签为 2026-10-22 新增）
  await page.locator(menuTid('/my-borrow')).first().click().catch(() => {});
  await page.waitForTimeout(1200);
  const tabs = await page.locator('[data-testid="myborrow-tabs"] .el-tabs__item').allTextContents();
  const expectedTabs = ['在借', '预约', '历史', '罚金'];
  pass('R3 我的借阅四Tab', expectedTabs.every((x) => tabs.some((t) => t.includes(x))), tabs.join(','));
  // 切历史 tab，确认真的激活；再切罚金，确认内容面板真的可见
  const historyTab = page.locator('[data-testid="myborrow-tabs"] .el-tabs__item:has-text("历史")');
  await historyTab.click({ timeout: 5000 }).catch(() => {});
  await page.waitForTimeout(300);
  const historyActive = await historyTab
    .evaluate((el) => el.classList.contains('is-active'))
    .catch(() => false);
  const finesTab = page.locator('[data-testid="myborrow-tabs"] .el-tabs__item:has-text("罚金")');
  await finesTab.click({ timeout: 5000 }).catch(() => {});
  const finesPane = page.locator('[data-testid="myborrow-fines-tab"]');
  await finesPane.waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
  const finesVisible = await finesPane.isVisible().catch(() => false);
  pass('R4 Tab 切换无异常', errors === 0 && historyActive && finesVisible,
    `errors=${errors} history=${historyActive} fines=${finesVisible}`);

  // 4. admin 登录 + Dashboard
  await login('admin');
  await page.waitForURL(u => u.pathname.includes('/dashboard'), { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(1500);
  pass('R5 管理员跳转Dashboard', page.url().includes('/dashboard'), page.url());
  const hasChart = await page.locator('canvas').count();
  pass('R6 ECharts 图表渲染', hasChart >= 1, 'canvas=' + hasChart);

  // 5. 管理页遍历（admin 可见菜单，与历轮 check22 一致）
  const adminPages = [
    ['数据总览', '/dashboard'], ['用户管理', '/user-manage'], ['分类管理', '/category-manage'],
    ['出版社管理', '/publisher-manage'], ['借阅规则说明', '/rule-config'], ['公告管理', '/notice-manage'],
    ['操作日志', '/operation-log'], ['个人中心', '/profile']
  ];
  for (const [menu, path] of adminPages) {
    let clickErr = '';
    await page.locator(menuTid(path)).first().click({ timeout: 5000 })
      .catch((e) => { clickErr = String(e.message || e).slice(0, 40); });
    // 等"路由到位 + 内容真的渲染出来"这两个条件，替代原先的固定 waitForTimeout(900)：
    // 冷启动/首屏取数慢时 900ms 不够，是 2026-10-22 16:28 那轮 24/25 的头号嫌疑
    let rendered = true;
    await page.waitForFunction((p) => {
      const el = document.querySelector('.el-main');
      return !!(el && (el.textContent || '').trim().length > 5 && location.pathname.includes(p));
    }, path, { timeout: 5000 }).catch(() => { rendered = false; });
    pass('A-' + menu + ' 渲染', rendered && !clickErr,
      'url=' + page.url() + (clickErr ? ' clickErr=' + clickErr : ''));
  }

  // 6. librarian 借还流程（真实业务操作）—— SPA 内菜单导航（避免整页刷新被 catch-all 拦截）
  await login('librarian01');
  await page.locator(menuTid('/borrow-handle')).first().click().catch(() => {});
  // el-input 把 attrs 合并到内部 <input>（inheritAttrs:false + useAttrs），故 data-testid 在 input 元素本身
  await page.waitForSelector('input[data-testid="borrow-reader-input"]', { timeout: 10000 });
  await page.waitForTimeout(500);

  // 借书：输入读者 → 查询 → 点击行选中 → 输入书名 → 查询 → 点击行选中 → 确认借出
  // M-25 修复：读者用张三 R20260001（无罚金可成功借出；王五 R20260003 欠费会被"欠费禁借"正确拦截）
  await page.fill('input[data-testid="borrow-reader-input"]', 'R20260001');
  await page.locator('[data-testid="borrow-reader-search"]').click();
  await page.waitForTimeout(1000);
  await page.locator('[data-testid="borrow-reader-table"] .el-table__row').first().click();
  await page.waitForTimeout(500);
  const b1sel = await page
    .locator('[data-testid="borrow-reader-table"] .el-table__row.current-row:has-text("R20260001")')
    .count();
  pass('B1 读者已选中（张三高亮）', b1sel === 1, 'current-row=' + b1sel);

  // 2026-10-23：同一读者同一本书已禁止重复借阅。张三种子里已借《深入理解计算机系统》，
  // 旧用例靠"重复借成功"制造第二条记录，属于缺陷行为；改用张三未在借的《算法导论》。
  await page.fill('input[data-testid="borrow-book-input"]', '算法导论');
  await page.locator('[data-testid="borrow-book-search"]').click();
  await page.waitForTimeout(1000);
  const bookHits = await page
    .locator('[data-testid="borrow-book-table"] .el-table__row:has-text("算法导论")')
    .count();
  pass('B1b 图书查询命中', bookHits >= 1, 'hits=' + bookHits);
  await page
    .locator('[data-testid="borrow-book-table"] .el-table__row:has-text("算法导论")')
    .first()
    .click();
  await page.waitForTimeout(500);
  await page.locator('[data-testid="borrow-confirm"]').click();
  await page.waitForTimeout(800);
  // 确认弹窗（ElMessageBox）：校验内容含所选读者/图书（验证选中链路生效）
  const boxText = (await page.locator('.el-message-box').textContent().catch(() => '')) || '';
  pass(
    'B2a 借书确认弹窗含所选读者与图书',
    boxText.includes('张三') && boxText.includes('算法导论'),
    boxText.replace(/\s+/g, ' ').slice(0, 46)
  );
  // M-25：精确点"确定"（原 first() 实为"取消"，借书从未发生）
  await page.locator('.el-message-box__btns button:has-text("确定")').click();
  await page.waitForTimeout(2000);
  // 业务成功信号（M-18：原仅验 errors==0，静默失败无法发现）
  const okMsg = await page.locator('.el-message:has-text("借书成功")').count();
  pass('B2 借书成功提示（业务成功断言）', okMsg > 0 && errors === 0, 'okMsg=' + okMsg + ' errors=' + errors);

  // 还书：切"还书" tab → 查张三 → 点击读者行加载记录列表 → 归还刚借的记录
  await page.locator('[data-testid="borrow-tabs"] .el-tabs__item:has-text("还书")').first().click();
  await page.waitForTimeout(1500);
  await page.fill('input[data-testid="return-reader-input"]', 'R20260001');
  await page.locator('[data-testid="return-reader-search"]').click();
  await page.waitForTimeout(1000);
  await page.locator('[data-testid="return-reader-table"] .el-table__row:has-text("张三")').first().click();
  await page.waitForTimeout(1500);
  const bookRecRows = await page
    .locator('[data-testid="return-list-table"] .el-table__row:has-text("算法导论")')
    .count();
  pass('B3 还书-记录列表（含刚借记录）', bookRecRows >= 1, '算法导论记录行=' + bookRecRows);
  // 记录列表按 id 倒序，刚借的记录排第一，归还它（闭环自清理，不碰种子记录）
  await page
    .locator('[data-testid="return-list-table"] .el-table__row:has-text("算法导论")')
    .first()
    .locator('[data-testid="return-action"]')
    .first()
    .click();
  await page.waitForTimeout(800);
  await page.locator('.el-message-box__btns button:has-text("确定")').click();
  await page.waitForTimeout(2000);
  const okMsg2 = await page.locator('.el-message:has-text("还书成功")').count();
  const leftRows = await page
    .locator('[data-testid="return-list-table"] .el-table__row:has-text("算法导论")')
    .count();
  pass(
    'B4 还书成功提示（业务成功断言+闭环清理）',
    okMsg2 > 0 && leftRows === 0 && errors === 0,
    'msg=' + okMsg2 + ' 剩余记录=' + leftRows
  );

  // ===== C. 路由越权（第二十五轮：读者直入管理员/馆员 URL → 动态路由回角色首页）=====
  await page.evaluate(() => localStorage.clear());
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.click('[data-testid="quick-login-reader"]');
  await page.waitForURL(u => !u.pathname.endsWith('/login'), { timeout: 8000 });
  for (const p of ['/user-manage', '/book-manage', '/dashboard', '/operation-log']) {
    await page.goto(BASE + p, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(700);
    pass(
      'C1 读者直入 ' + p + ' 被回角色首页（路由越权防护）',
      page.url().includes('/book-search'),
      '最终URL=' + page.url()
    );
  }
  await page.goto(BASE + '/my-borrow', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(600);
  pass('C5 对照：读者正常访问 /my-borrow', page.url().includes('/my-borrow'), page.url());

  await browser.close();
  const total = results.length;
  const passed = results.filter(r => r.pass).length;
  console.log('\n===== 浏览器 E2E 汇总: ' + passed + '/' + total + ' =====');
  process.exit(passed === total ? 0 : 1);
})().catch(e => { console.error('FATAL', e.message); process.exit(2); });
