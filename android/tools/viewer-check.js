// Loads the toolpath viewer (app/src/main/assets/viewer) in headless Chrome with a real toolpath written by ViewerDataTest
// (app/build/viewer-check/data) and checks that it loads without a script error, that lockstep follows timed positions, and that
// the live preview's view survives a save and restore. Run from android/ after the unit tests:
//   node tools/viewer-check.js            (CHROME=/path/to/chrome to choose the browser; needs the playwright package)
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const assets = path.resolve('app/src/main/assets'), data = path.resolve('app/build/viewer-check/data');
const fail = (why) => { console.error('viewer check failed: ' + why); process.exitCode = 1; };
if (!fs.existsSync(path.join(data, 'meta.json'))) { fail('no toolpath data; run the unit tests first (ViewerDataTest)'); process.exit(1); }
const server = http.createServer((q, r) => {
  const url = decodeURIComponent(q.url.split('?')[0]);
  const file = url.startsWith('/assets/') ? path.join(assets, url.slice(8)) : url.startsWith('/data/') ? path.join(data, url.slice(6)) : null;
  if (!file) { r.writeHead(404); r.end(); return; }
  fs.readFile(file, (e, b) => { if (e) { r.writeHead(404); r.end(); } else { r.writeHead(200); r.end(b); } });
}).listen(0, async () => {
  const options = { args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] };
  if (process.env.CHROME) options.executablePath = process.env.CHROME;
  const browser = await chromium.launch(options);
  try {
    const page = await browser.newPage({ viewport: { width: 500, height: 400 } });
    const errors = []; page.on('pageerror', (e) => errors.push(e.message));
    await page.addInitScript(() => { window.Android = { onReady() {}, onLoaded(n) { window.__loaded = n; }, onError(m) { window.__error = m; }, onCamera() {}, onCameraDelay() {}, onMark() {} }; });
    await page.goto(`http://localhost:${server.address().port}/assets/viewer/index.html`);
    await page.waitForFunction(() => window.viewer, null, { timeout: 10000 });
    await page.evaluate(() => viewer.load());
    await page.waitForFunction(() => window.__loaded || window.__error, null, { timeout: 20000 }).catch(() => {});
    const r = await page.evaluate(async () => {
      const out = { loaded: window.__loaded || 0, error: window.__error || '' };
      const wait = (ms) => new Promise((done) => setTimeout(done, ms));
      viewer.setLockstep({ on: true, delayMs: 500 });
      const t = Date.now(); viewer.update({ end: 100, at: t - 2000 }); viewer.update({ end: 400, at: t });
      await wait(800); out.lockEnd = viewer.probe().end;
      viewer.setLockstep({ on: false });
      const yaw0 = viewer.camera.yaw; viewer.orbit(200, 90); await wait(500); out.orbit = viewer.camera.yaw - yaw0;
      viewer.setViewState({ looking: false, camera: { yaw: 12, pitch: 34, distance: 222, target: [10, 20, 3] } });
      out.view = viewer.getViewState();
      return out;
    });
    // Edge mode and per-type opacity: the same view drawn solid, then as edges only, must differ, and see-through infill too.
    const pixels = () => page.evaluate(() => { const c = document.querySelector('canvas'), p = document.createElement('canvas'); p.width = c.width; p.height = c.height;
      const g = p.getContext('2d'); g.drawImage(c, 0, 0); return Array.from(g.getImageData(0, 0, c.width, c.height).data.filter((v, i) => i % 16 === 0)); });
    const draw = async (state) => { await page.evaluate((st) => { viewer.setViewState({ looking: false, camera: { yaw: -60, pitch: 30, distance: 160, target: [128, 128, 2] } }); viewer.update(st); }, state); await page.waitForTimeout(300); return pixels(); };
    const full = { start: 0, end: r.loaded, layerStart: 0, edgeMask: 0, typeAlpha: null };
    const solid = await draw(full);
    if (process.env.SHOTS) await page.screenshot({ path: process.env.SHOTS + '/viewer-solid.png' });
    const edges = await draw(Object.assign({}, full, { edgeMask: 0x3ffff }));
    if (process.env.SHOTS) await page.screenshot({ path: process.env.SHOTS + '/viewer-edges.png' });
    const faded = await draw(Object.assign({}, full, { typeAlpha: [1, 1, 1, 0.2, 0.2, 0.2, 0.2, 0.2, 0.2, 1, 1, 1, 1, 1, 1, 0.2, 1, 1] }));
    const differ = (a, b) => a.reduce((n, v, i) => n + (Math.abs(v - b[i]) > 24 ? 1 : 0), 0);
    if (!(differ(solid, edges) > 200)) fail('edges only did not change the drawing (' + differ(solid, edges) + ')');
    if (!(differ(solid, faded) > 50)) fail('see-through infill did not change the drawing (' + differ(solid, faded) + ')');
    await page.evaluate(() => viewer.update({ edgeMask: 0, typeAlpha: null }));
    if (errors.length) fail('script error: ' + errors.join(' | '));
    if (r.error) fail('the viewer reported: ' + r.error);
    if (!(r.loaded > 0)) fail('the toolpath did not load');
    if (r.lockEnd !== 400) fail('lockstep did not reach the latest position (' + r.lockEnd + ')');
    const c = r.view && r.view.camera;
    if (!c || c.yaw !== 12 || c.pitch !== 34 || c.distance !== 222 || c.target.join() !== '10,20,3') fail('the view did not survive a save and restore: ' + JSON.stringify(r.view));
    if (!(Math.abs(r.orbit - 90) < 0.01)) fail('the showcase orbit did not turn the view by 90 degrees: ' + r.orbit);
    if (!process.exitCode) console.log(`viewer check passed: ${r.loaded} segments loaded, lockstep, orbit, view state, edges only and see-through types work`);
  } catch (e) { fail(e.message); } finally { await browser.close(); server.close(); }
});
