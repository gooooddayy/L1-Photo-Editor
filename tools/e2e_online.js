// 修复验证：T1 抠图→移除（proxy 竞态）+ T2 大图 3MB 限制导出（循环回归）
// 前置：python http.server 8123 在仓库目录运行
const { chromium } = require('playwright');
const LOG = [];
function log(s){ LOG.push(s); console.log(s); }

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
  page.on('pageerror', e => log('PAGEERROR: ' + e.message));
  await page.goto('http://127.0.0.1:8123/index.html', { waitUntil: 'load' });
  await page.evaluate(() => {
    window.toastLog = [];
    const _t = toast; window.toast = (m, k) => { window.toastLog.push(String(m)); return _t(m, k); };
  });
  await page.evaluate(async () => {
    const N = 5000;
    const c = document.createElement('canvas'); c.width = N; c.height = N;
    const x = c.getContext('2d');
    const g = x.createLinearGradient(0, 0, N, N);
    g.addColorStop(0, '#2244cc'); g.addColorStop(1, '#ffaa22');
    x.fillStyle = g; x.fillRect(0, 0, N, N);
    for (let i = 0; i < 80000; i++) {
      x.fillStyle = `rgb(${Math.random()*255|0},${Math.random()*255|0},${Math.random()*255|0})`;
      x.fillRect(Math.random()*N, Math.random()*N, 5, 5);
    }
    const blob = await new Promise(r => c.toBlob(r, 'image/jpeg', 0.95));
    openFile(new File([blob], 'big.jpg', { type: 'image/jpeg' }));
  });
  await page.evaluate(() => new Promise(res => { const t0 = Date.now(); (function p(){ (hasImg || Date.now()-t0 > 30000) ? res() : setTimeout(p, 100); })(); }));
  log('导入: ' + await page.evaluate(() => `${base.width}x${base.height}`));

  // T1: 抠图 → 移除（修复前必现 WebAssembly is not initialized yet.）
  log('--- T1a: 抠图 ---');
  await page.evaluate(() => runMatting());
  const r1 = await page.evaluate(() => new Promise(res => {
    const t0 = Date.now();
    (function p(){ (!aiBusy || Date.now()-t0 > 180000) ? res(window.toastLog.slice()) : setTimeout(p, 250); })();
  }));
  log('T1a: ' + JSON.stringify(r1));
  const ok1a = r1.some(t => /抠图完成/.test(t));

  log('--- T1b: 移除（修复点1）---');
  await page.evaluate(() => {
    maskStroke = { last: null };
    maskStrokeTo({ x: base.width/2, y: base.height/2 });
    maskStrokeTo({ x: base.width/2 + 80, y: base.height/2 + 20 });
    window.toastLog = [];
    runErase();
  });
  const r2 = await page.evaluate(() => new Promise(res => {
    const t0 = Date.now();
    (function p(){ (!aiBusy || Date.now()-t0 > 240000) ? res(window.toastLog.slice()) : setTimeout(p, 250); })();
  }));
  log('T1b: ' + JSON.stringify(r2));
  const ok1b = r2.some(t => /移除完成/.test(t)) && !r2.some(t => /移除失败/.test(t));

  // T2: 3MB 限制导出（修复点2：必须真正降质量/缩尺寸并成功）
  for (const fmt of ['jpg', 'png']) {
    await page.evaluate(() => openExport());
    await page.evaluate((f) => {
      $("#expFmt").value = f; $("#expLimit").value = "3";
      $("#expLimit").dispatchEvent(new Event('input'));
      window.toastLog = [];
    }, fmt);
    await page.evaluate(() => new Promise(res => { const t0 = Date.now(); (function p(){ ($("#expInfo").textContent.includes('限制内') || $("#expInfo").textContent.includes('超出') || Date.now()-t0>60000) ? res() : setTimeout(p, 200); })(); }));
    await page.evaluate(() => $("#btnExpDo").click());
    const r = await page.evaluate(() => new Promise(res => {
      const t0 = Date.now();
      (function p(){ (window.toastLog.length || Date.now()-t0 > 300000) ? res({ toasts: window.toastLog.slice(), ms: Date.now()-t0 }) : setTimeout(p, 250); })();
    }));
    const okT = r.toasts.some(t => /^已导出 /.test(t));
    log(`T2 ${fmt}: ${JSON.stringify(r)} => ${okT ? 'PASS' : 'FAIL'}`);
    if (fmt === 'jpg') var ok2jpg = okT; else var ok2png = okT;
  }

  console.log('=== SUMMARY ===');
  console.log(`T1a 抠图: ${ok1a ? 'PASS' : 'FAIL'}`);
  console.log(`T1b 抠图后移除: ${ok1b ? 'PASS' : 'FAIL'}`);
  console.log(`T2 JPG 3MB: ${ok2jpg ? 'PASS' : 'FAIL'}`);
  console.log(`T2 PNG 3MB: ${ok2png ? 'PASS' : 'FAIL'}`);
  const all = ok1a && ok1b && ok2jpg && ok2png;
  console.log(all ? '✓ ALL PASS' : '✗ HAS FAIL');
  await browser.close();
  require('fs').appendFileSync('C:/Users/Administrator/WorkBuddy/2026-10-17-13-44-41/_repro_log.txt', '\n=== 修复验证 ===\n' + LOG.join('\n'));
  process.exit(all ? 0 : 1);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
