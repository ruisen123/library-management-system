// 量化「切换界面白屏/闪烁」——真实 SPA 路由切换（点击侧边菜单）
// 关键修正：加载遮罩生命周期常常 < 1 帧（<16ms），rAF 轮询会漏采；改用 MutationObserver 捕获亚帧级 DOM 变化。
// 指标：
//   swapAt  点击→旧页面被替换成新页面（"旧界面消失"时刻）
//   maskOn  点击→白色遮罩出现
//   dataAt  点击→遮罩消失=新数据渲染完成（"新界面闪出"时刻）
//   whiteMs = dataAt - maskOn   白色遮罩覆盖时长（= 用户感知的白屏时长）
//   bareMs  = maskOn - swapAt   新页面已出现但尚无遮罩的窗口（白底空卡闪现）
const { chromium } = require('playwright');
const fs = require('fs');
const BASE = 'http://localhost:5173';
const LAUNCH_ARGS = ['--no-proxy-server', '--host-resolver-rules=MAP localhost 127.0.0.1'];

const ROLES = [
  { user: 'admin', btn: 'admin · 系统管理员', pages: [
    ['数据总览', '/dashboard'], ['用户管理', '/user-manage'], ['分类管理', '/category-manage'],
    ['出版社管理', '/publisher-manage'], ['借阅规则说明', '/rule-config'], ['公告管理', '/notice-manage'],
    ['操作日志', '/operation-log'], ['个人中心', '/profile'] ] },
  { user: 'librarian01', btn: 'librarian01 · 图书管理员', pages: [
    ['图书档案管理', '/book-manage'], ['借还办理', '/borrow-handle'],
    ['借阅记录查询', '/borrow-record'], ['罚金管理', '/fine-manage'], ['个人中心', '/profile'] ] },
  { user: 'reader001', btn: 'reader001 · 读者（张三）', pages: [
    ['图书检索', '/book-search'], ['我的借阅', '/my-borrow'], ['个人中心', '/profile'] ] },
];

const PROBE = () => {
  const now = () => performance.now();
  window.__p = { armed: false, t0: 0, swapAt: null, maskOn: null, maskOff: null, bareMs: null, rootClass: '', maskCount: 0 };
  const isMask = (n) => n && n.nodeType === 1 && n.classList && n.classList.contains('el-loading-mask');
  const main = () => document.querySelector('.layout-main');
  // 用「子节点元素身份」判断换页：两个页面的 class 都是 .page-card，按 class 比对区分不出来
  const firstChild = () => { const m = main(); return m && m.children.length ? m.children[0] : null; };

  // 亚帧级：捕获遮罩的插入 / 移除
  const mo = new MutationObserver((recs) => {
    const p = window.__p;
    if (!p.armed) return;
    for (const r of recs) {
      for (const n of r.addedNodes) {
        if (isMask(n)) { p.maskCount++; if (p.maskOn == null) p.maskOn = now() - p.t0; }
        else if (n.nodeType === 1 && n.querySelector && n.querySelector('.el-loading-mask')) {
          p.maskCount++;
          if (p.maskOn == null) p.maskOn = now() - p.t0;
        }
      }
      for (const n of r.removedNodes) {
        if (isMask(n) && p.maskOn != null && p.maskOff == null) p.maskOff = now() - p.t0;
      }
    }
  });
  const startObserve = () => mo.observe(document.documentElement, { childList: true, subtree: true });
  if (document.documentElement) startObserve(); else document.addEventListener('DOMContentLoaded', startObserve);

  document.addEventListener('click', (e) => {
    const mi = e.target.closest && e.target.closest('.el-menu-item');
    if (!mi) return;
    const p = window.__p;
    p.armed = true; p.t0 = now();
    p.swapAt = null; p.maskOn = null; p.maskOff = null; p.maskCount = 0;
    p._child0 = firstChild();
  }, true);

  const loop = () => {
    const p = window.__p;
    if (p.armed) {
      const t = now() - p.t0;
      const c = firstChild();
      if (p.swapAt == null && c && c !== p._child0) { p.swapAt = t; p.rootClass = c.className; }
      if (t > 2000) p.armed = false;
    }
    requestAnimationFrame(loop);
  };
  requestAnimationFrame(loop);
};

const INJECT_FIX = () => {
  const s = document.createElement('style');
  s.textContent = `.el-loading-mask{background-color:rgba(245,247,250,.88)!important;}
@keyframes libmsFadeIn{from{opacity:0}to{opacity:1}}
.layout-main > div{animation:libmsFadeIn .18s ease;}`;
  (document.head || document.documentElement).appendChild(s);
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function clickMenu(page, title) {
  const items = page.locator('.el-menu-item');
  const n = await items.count();
  for (let i = 0; i < n; i++) {
    if ((await items.nth(i).innerText()).trim() === title) { await items.nth(i).click(); return true; }
  }
  return false;
}

async function login(page, role) {
  // 必须先清会话：已登录时访问 /login 会被守卫重定向回首页，找不到快捷登录按钮
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('button.quick-btn', { timeout: 20000 });
  await page.click(`button.quick-btn:has-text("${role.btn}")`);
  await page.waitForURL((u) => !u.pathname.endsWith('/login'), { timeout: 20000 });
  await page.waitForSelector('.el-menu-item', { timeout: 20000 });
  await page.waitForTimeout(800);
}

async function measure(page, title) {
  const ok = await clickMenu(page, title);
  if (!ok) return { error: 'menu-not-found' };
  await page.waitForTimeout(2100);
  const m = await page.evaluate(() => JSON.parse(JSON.stringify(window.__p)));
  const R = (x) => (x == null ? null : Math.round(x));
  const bare = (m.maskOn != null && m.swapAt != null) ? Math.round(m.maskOn - m.swapAt) : null;
  return {
    swapAt: R(m.swapAt), maskOn: R(m.maskOn), dataAt: R(m.maskOff),
    whiteMs: (m.maskOn != null && m.maskOff != null) ? Math.round(m.maskOff - m.maskOn) : null,
    bareMs: bare, masks: m.maskCount,
  };
}

async function runPass(page, role, label, { reload }) {
  const out = [];
  for (const [title, path] of role.pages) {
    if (reload) {
      await page.goto(BASE + role.pages[0][1], { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('.el-menu-item', { timeout: 20000 });
      await page.waitForTimeout(600);
    }
    const anchor = role.pages.find(([t]) => t !== title);
    await clickMenu(page, anchor[0]);
    await page.waitForTimeout(1000);
    out.push({ title, path, ...(await measure(page, title)) });
  }
  console.log(`\n===== ${label} =====`);
  console.log('页面'.padEnd(14) + '旧页消失'.padEnd(10) + '遮罩出现'.padEnd(10) + '数据就绪'.padEnd(10) + '白屏时长'.padEnd(10) + '空卡窗口');
  for (const r of out) {
    console.log(String(r.title).padEnd(12) + String(r.swapAt).padEnd(11) + String(r.maskOn).padEnd(11) +
      String(r.dataAt).padEnd(11) + String(r.whiteMs).padEnd(11) + String(r.bareMs));
  }
  return out;
}

const med = (a) => { const b = a.filter((x) => x != null).sort((x, y) => x - y); return b.length ? b[Math.floor(b.length / 2)] : null; };
const avg = (a) => { const b = a.filter((x) => x != null); return b.length ? Math.round(b.reduce((x, y) => x + y, 0) / b.length) : null; };

(async () => {
  const browser = await chromium.launch({ args: LAUNCH_ARGS });
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await ctx.newPage();
  await page.addInitScript(PROBE);

  const results = {};
  for (const role of ROLES) {
    await login(page, role);
    const warm = await runPass(page, role, `[基线] 热切换 ${role.user}`, { reload: false });
    const cold = await runPass(page, role, `[基线] 冷启动 ${role.user}`, { reload: true });
    await page.addInitScript(INJECT_FIX);
    await page.goto(BASE + role.pages[0][1], { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('.el-menu-item', { timeout: 20000 });
    await page.waitForTimeout(600);
    const warmFix = await runPass(page, role, `[预览①②] 热切换 ${role.user}`, { reload: false });
    results[role.user] = { warm, cold, warmFix };
  }

  const flat = (k) => ROLES.flatMap((r) => results[r.user][k]);
  console.log('\n\n########## 汇总（全部 16 页）##########');
  for (const [k, name] of [['warm', '基线·热切换'], ['cold', '基线·冷启动(整页刷新后首进)'], ['warmFix', '预览①②·热切换']]) {
    const rows = flat(k);
    console.log(`${name}: 白屏时长 中位${med(rows.map((r) => r.whiteMs))}ms 平均${avg(rows.map((r) => r.whiteMs))}ms | ` +
      `数据就绪 中位${med(rows.map((r) => r.dataAt))}ms | 遮罩捕获次数 平均${avg(rows.map((r) => r.masks))} | 空卡窗口 平均${avg(rows.map((r) => r.bareMs))}ms`);
  }
  fs.writeFileSync('probe_results.json', JSON.stringify(results, null, 2), 'utf8');
  await browser.close();
  console.log('\nPROBE_DONE');
})().catch((e) => { console.error('FATAL', e.message); process.exit(2); });
