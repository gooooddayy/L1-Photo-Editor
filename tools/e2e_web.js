// 断网 E2E v2：等待统一用页内轮询（跨上下文 waitForFunction 在此环境不稳定），下载用 playwright 事件
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const HTML = 'file:///C:/Users/Administrator/WorkBuddy/2026-10-17-13-44-41/%E5%9B%BE%E7%89%87%E7%BC%96%E8%BE%91%E5%99%A8.html';
const OUT = path.join(__dirname, '_e2e_out');
fs.mkdirSync(OUT, { recursive: true });

function pngDims(buf) { return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) }; }

(async () => {
  const fails = [];
  const pageErrors = [];
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, acceptDownloads: true });
  await ctx.route('**/*', route => {
    if (route.request().url().startsWith('http')) return route.abort(); // 断网
    route.continue();
  });
  const page = await ctx.newPage();
  page.on('pageerror', e => pageErrors.push(e.message));

  // 页内轮询等待（已验证可靠的通道）
  const waitInPage = (expr, ms = 30000) => page.evaluate(async ([expr, ms]) => {
    const f = new Function('return (' + expr + ')');
    const t0 = Date.now();
    while (Date.now() - t0 < ms) { if (f()) return true; await new Promise(r => setTimeout(r, 100)); }
    return f();
  }, [expr, ms]);
  const must = (ok, label) => { if (!ok) fails.push(label); else console.log('PASS', label); };

  await page.goto(HTML);
  await page.waitForSelector('#dropzone', { timeout: 15000 });
  const imp = (w, h, name) => page.evaluate(async ([w, h, name]) => {
    const c = document.createElement('canvas'); c.width = w; c.height = h;
    const g = c.getContext('2d');
    const gr = g.createLinearGradient(0, 0, w, h);
    gr.addColorStop(0, '#c9a227'); gr.addColorStop(1, '#3d2f1a');
    g.fillStyle = gr; g.fillRect(0, 0, w, h);
    for (let i = 0; i < 2000; i++) { g.fillStyle = `rgba(0,0,0,${Math.random() * .3})`; g.fillRect(Math.random() * w, Math.random() * h, 2, 2); }
    const b = await new Promise(res => c.toBlob(res, 'image/png'));
    openFile(new File([b], name, { type: 'image/png' }));
    const t0 = Date.now();
    while (Date.now() - t0 < 30000 && !(hasImg === true && base.width === w && base.height === h)) await new Promise(r => setTimeout(r, 100));
    return hasImg === true && base.width === w && base.height === h;
  }, [w, h, name]);

  // ── 测试1：25MP 大图导入 → 24MP 钳位 ──
  const t1ok = await page.evaluate(async () => {
    const c = document.createElement('canvas'); c.width = 5000; c.height = 5000;
    const g = c.getContext('2d');
    const gr = g.createLinearGradient(0, 0, 5000, 5000); gr.addColorStop(0, '#4a7c59'); gr.addColorStop(1, '#0e1f14');
    g.fillStyle = gr; g.fillRect(0, 0, 5000, 5000);
    const b = await new Promise(res => c.toBlob(res, 'image/png'));
    openFile(new File([b], 'big.png', { type: 'image/png' }));
    const t0 = Date.now();
    while (Date.now() - t0 < 30000 && !hasImg) await new Promise(r => setTimeout(r, 100));
    return [hasImg, base.width, base.height];
  });
  must(t1ok[0] === true && t1ok[1] === 4899 && t1ok[2] === 4899, `测试1 大图钳位 5000x5000 → ${t1ok[1]}x${t1ok[2]}`);

  // ── 测试2：常规图导入 + 估算出现 ──
  await imp(800, 600, 'norm.png');
  must(await waitInPage('window.document.querySelector("#expInfo") !== null', 5000), '测试2a 导出面板可达');
  await page.click('#btnExport');
  must(await waitInPage('/预计约/.test(document.querySelector("#expInfo").textContent)', 30000), '测试2 估算出现');

  // ── 测试3：格式表无 JPEG ──
  const fmts = await page.evaluate(() => [...document.querySelector('#expFmt').options].map(o => o.value));
  must(!fmts.includes('jpeg'), '测试3 格式表无JPEG: ' + fmts.join(','));

  // ── 测试4：导出（缓存复用）→ 下载正确 ──
  const dl1P = page.waitForEvent('download', { timeout: 30000 });
  await page.click('#btnExpDo');
  const dl1 = await dl1P;
  const f1 = path.join(OUT, dl1.suggestedFilename());
  await dl1.saveAs(f1);
  const b1 = fs.readFileSync(f1);
  const d1 = pngDims(b1);
  must(dl1.suggestedFilename() === 'norm_edit_800x600.png' && d1.w === 800 && d1.h === 600,
    `测试4 导出 ${dl1.suggestedFilename()} ${d1.w}x${d1.h} ${b1.length}B`);

  // ── 测试5：同参数二次导出 → 字节一致 + 快（上次导出成功后弹窗已关，需重开面板） ──
  await page.click('#btnExport');
  must(await waitInPage('/预计约/.test(document.querySelector("#expInfo").textContent)', 30000), '测试5a 重开面板即出估算（缓存仍有效）');
  const t0 = Date.now();
  const dl2P = page.waitForEvent('download', { timeout: 30000 });
  await page.click('#btnExpDo');
  const dl2 = await dl2P;
  const dt = Date.now() - t0;
  const f2 = path.join(OUT, dl2.suggestedFilename());
  await dl2.saveAs(f2);
  const b2 = fs.readFileSync(f2);
  must(b1.equals(b2) && dt < 3000, `测试5 二次导出字节一致（缓存复用，${dt}ms）`);

  // ── 测试6：撤销无「已撤销」toast ──
  await page.evaluate(() => { bctx.fillStyle = '#ff0000'; bctx.fillRect(10, 10, 50, 50); pushHistory(); });
  await page.keyboard.press('Control+z');
  await page.waitForTimeout(400);
  const toastTxt = await page.evaluate(() => document.querySelector('#toast').textContent);
  must(!/已撤销/.test(toastTxt), '测试6 撤销无成功 toast，当前="' + toastTxt + '"');

  // ── 测试7：编辑后缓存失效 → 导出新内容 ──
  await page.evaluate(() => { bctx.fillStyle = '#0000ff'; bctx.fillRect(100, 100, 80, 80); pushHistory(); });
  await page.click('#btnExport');
  must(await waitInPage('/预计约/.test(document.querySelector("#expInfo").textContent)', 30000), '测试7a 编辑后再估算');
  const dl3P = page.waitForEvent('download', { timeout: 30000 });
  await page.click('#btnExpDo');
  const dl3 = await dl3P;
  const f3 = path.join(OUT, dl3.suggestedFilename());
  await dl3.saveAs(f3);
  const b3 = fs.readFileSync(f3);
  must(!b3.equals(b1), `测试7 编辑后缓存失效，新内容 ${b3.length}B`);

  if (pageErrors.length) fails.push('页面 JS 错误: ' + pageErrors.join(' | '));
  await browser.close();
  console.log(fails.length ? '\n✗ FAILED:/n' + fails.join('\n') : '\n✓ ALL PASS');
  process.exit(fails.length ? 1 : 0);
})().catch(e => { console.error('E2E 异常:', e.message); process.exit(2); });
