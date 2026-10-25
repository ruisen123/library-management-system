// 录制「甲+乙」预览视频：同一段菜单切换流程，上半段=基线（现状），下半段=甲+乙
// 甲（等效模拟）：把 v-loading 遮罩裁剪到"从表格顶端开始"，标题/工具栏/Tab 头不再被盖住
// 乙：遮罩不透明度降低 + 新内容 0.18s 淡入
// 为便于观察，接口延迟放大到 1500ms（真实只有 ~55ms），视频里右下角有标注
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const BASE = 'http://localhost:5173';
const ARGS = ['--no-proxy-server', '--host-resolver-rules=MAP localhost 127.0.0.1'];
const OUT = process.argv[2];
const VIDEODIR = path.join(process.env.TEMP || '.', 'libms_video');
const PAGES = ['用户管理', '公告管理', '操作日志'];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function clickMenu(page, title) {
  const items = page.locator('.el-menu-item');
  const n = await items.count();
  for (let i = 0; i < n; i++) {
    if ((await items.nth(i).innerText()).trim() === title) { await items.nth(i).click(); return true; }
  }
  throw new Error('menu not found: ' + title);
}

// 乙：CSS
const CSS_YI = `
  .el-loading-mask { background-color: rgba(245,247,250,.5) !important; }
  @keyframes libmsFadeIn { from { opacity: 0 } to { opacity: 1 } }
  .layout-main > div { animation: libmsFadeIn .18s ease; }
`;

// 甲：把遮罩裁剪到表格区（等效于把 v-loading 从页面根节点下移到 el-table）
const JIA_JS = () => {
  const fix = () => {
    const main = document.querySelector('.layout-main');
    if (!main || !main.firstElementChild) return;
    const root = main.firstElementChild;
    const mask = root.querySelector('.el-loading-mask');
    if (!mask) return;
    const table = root.querySelector('.el-table');
    if (!table) return;
    const rt = root.getBoundingClientRect();
    const tt = table.getBoundingClientRect();
    const top = Math.max(0, Math.round(tt.top - rt.top - 6));
    mask.style.top = top + 'px';
    mask.style.borderRadius = '0 0 8px 8px';
  };
  new MutationObserver(fix).observe(document.documentElement, { childList: true, subtree: true });
  setInterval(fix, 50);
  fix();
};

const BANNER_JS = (text) => {
  let b = document.getElementById('__pv_banner');
  if (!b) {
    b = document.createElement('div');
    b.id = '__pv_banner';
    b.style.cssText = 'position:fixed;bottom:26px;right:26px;z-index:999999;background:#1f3552;' +
      'color:#fff;padding:11px 18px;border-radius:8px;font:600 16px/1.4 "Microsoft YaHei",sans-serif;' +
      'box-shadow:0 4px 16px rgba(0,0,0,.28);letter-spacing:.5px';
    document.body.appendChild(b);
  }
  b.textContent = text;
};

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  fs.mkdirSync(VIDEODIR, { recursive: true });

  const browser = await chromium.launch({ args: ARGS });
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    recordVideo: { dir: VIDEODIR, size: { width: 1440, height: 900 } },
  });
  const page = await ctx.newPage();

  // 登录
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('button.quick-btn', { timeout: 20000 });
  await page.click('button.quick-btn:has-text("admin · 系统管理员")');
  await page.waitForURL((u) => !u.pathname.endsWith('/login'), { timeout: 20000 });
  await page.waitForSelector('.el-menu-item', { timeout: 20000 });
  await page.waitForTimeout(900);

  // 预热 chunk（不录制重点，只是为了后面切换不卡在编译上）
  for (const t of PAGES) { await clickMenu(page, t); await page.waitForTimeout(900); }
  await clickMenu(page, '数据总览');
  await page.waitForTimeout(1200);

  // ——— 第一段：基线（现状）———
  await page.evaluate(BANNER_JS, '基线（现状）· 接口延迟已放大到 1500ms 便于观察');
  await page.route('**/api/**', async (route) => { await sleep(1500); await route.continue(); });

  await clickMenu(page, '用户管理');
  await page.waitForTimeout(420);
  await page.screenshot({ path: path.join(OUT, '10_基线-遮罩盖住整张卡片.png') });
  await page.waitForTimeout(2200);

  for (const t of ['公告管理', '操作日志', '用户管理']) {
    await clickMenu(page, t); await page.waitForTimeout(2600);
  }
  await page.waitForTimeout(400);

  // ——— 第二段：甲 + 乙 ———
  await page.evaluate(BANNER_JS, '甲 + 乙 预览（遮罩只盖表格区 + 内容淡入）');
  await page.addStyleTag({ content: CSS_YI });
  await page.evaluate(JIA_JS);
  await page.waitForTimeout(600);

  await clickMenu(page, '公告管理');   // 先切走，保证下一跳是真实切换
  await page.waitForTimeout(2400);
  await clickMenu(page, '用户管理');
  await page.waitForTimeout(420);
  await page.screenshot({ path: path.join(OUT, '11_甲+乙-遮罩只盖表格区.png') });
  await page.waitForTimeout(2200);

  for (const t of ['公告管理', '操作日志', '用户管理']) {
    await clickMenu(page, t); await page.waitForTimeout(2600);
  }
  await page.waitForTimeout(300);
  await page.evaluate(() => { const b = document.getElementById('__pv_banner'); if (b) b.remove(); });
  await page.waitForTimeout(500);

  const video = page.video();
  await ctx.close();
  const src = await video.path();
  const dest = path.join(OUT, '甲+乙-对照预览.webm');
  fs.copyFileSync(src, dest);
  console.log('video saved:', dest, fs.statSync(dest).size, 'bytes');
  await browser.close();
  console.log('VIDEO_DONE');
})().catch((e) => { console.error('FATAL', e.message); process.exit(2); });
