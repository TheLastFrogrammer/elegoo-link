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
    await page.addInitScript(() => { window.Android = { onReady() {}, onLoaded(n, ticket) { window.__loaded = n; window.__ticket = ticket; window.__loadCount = (window.__loadCount || 0) + 1; }, onError(m) { window.__error = m; }, onCamera() {}, onCameraDelay() {}, onMark() {} }; });
    await page.goto(`http://localhost:${server.address().port}/assets/viewer/index.html`);
    await page.waitForFunction(() => window.viewer, null, { timeout: 10000 });
    await page.evaluate(() => viewer.load(7));
    await page.waitForFunction(() => window.__loaded || window.__error, null, { timeout: 20000 }).catch(() => {});
    const r = await page.evaluate(async () => {
      const out = { loaded: window.__loaded || 0, ticket: window.__ticket, error: window.__error || '' };
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
    // The viewer draws on the next animation frame: wait for real frames (at most 3 s), not a fixed time, before reading.
    const frames = () => page.evaluate(() => new Promise((done) => { let n = 0; const tick = () => (++n >= 2 ? done() : requestAnimationFrame(tick)); requestAnimationFrame(tick); setTimeout(done, 3000); }));
    const draw = async (state) => { await page.evaluate((st) => { viewer.setViewState({ looking: false, camera: { yaw: -60, pitch: 30, distance: 160, target: [128, 128, 2] } }); viewer.update(st); }, state); await page.waitForTimeout(100); await frames(); return pixels(); };
    // When a drawing does not change, what the page had: segments loaded, whether anything is drawn, the 3D context, the browser.
    const why = async (shot) => {
      const gl = await page.evaluate(() => { const c = document.querySelector('canvas'), g = c && (c.getContext('webgl2') || c.getContext('webgl'));
        return g ? { lost: g.isContextLost(), size: c.width + 'x' + c.height } : { lost: 'no context' }; });
      const lit = shot.reduce((n, v) => n + (Math.abs(v - shot[0]) > 24 ? 1 : 0), 0);
      return ' [loaded ' + r.loaded + ', ' + lit + ' samples differ from the corner, context lost ' + gl.lost + (gl.size ? ', canvas ' + gl.size : '') + ', ' + browser.version() + (r.error ? ', load error ' + r.error : '') + ']';
    };
    const full = { start: 0, end: r.loaded, layerStart: 0, edgeMask: 0, typeAlpha: null };
    const solid = await draw(full);
    if (process.env.SHOTS) await page.screenshot({ path: process.env.SHOTS + '/viewer-solid.png' });
    const edges = await draw(Object.assign({}, full, { edgeMask: 0x3ffff }));
    if (process.env.SHOTS) await page.screenshot({ path: process.env.SHOTS + '/viewer-edges.png' });
    const faded = await draw(Object.assign({}, full, { typeAlpha: [1, 1, 1, 0.2, 0.2, 0.2, 0.2, 0.2, 0.2, 1, 1, 1, 1, 1, 1, 0.2, 1, 1] }));
    const differ = (a, b) => a.reduce((n, v, i) => n + (Math.abs(v - b[i]) > 24 ? 1 : 0), 0);
    if (!(differ(solid, edges) > 200)) fail('edges only did not change the drawing (' + differ(solid, edges) + ')' + await why(solid));
    if (!(differ(solid, faded) > 50)) fail('see-through infill did not change the drawing (' + differ(solid, faded) + ')' + await why(solid));
    await page.evaluate(() => viewer.update({ edgeMask: 0, typeAlpha: null }));
    // The bed mesh painted on the plate: an 11 × 11 grid from blue (front left) to orange (back right) must change the
    // plate's colour, and taking it away must bring the plain plate back.
    const bedView = { start: 0, end: 0, layerStart: 0 };
    const plain = await draw(bedView);
    await page.evaluate(() => {
      const xs = [], ys = [], colours = [];
      for (let k = 0; k < 11; k++) { xs.push(10 + k * 23.6); ys.push(10 + k * 23.6); }
      for (let j = 0; j < 11; j++) for (let i = 0; i < 11; i++) { const t = (i + j) / 20; colours.push(0.16 + 0.76 * t, 0.47 + 0.0 * t, 0.84 - 0.64 * t); }
      viewer.setBedMesh({ xs, ys, colours });
    });
    const painted = await draw(bedView);
    if (process.env.SHOTS) { await draw(full); await page.screenshot({ path: process.env.SHOTS + '/viewer-bed-mesh.png' }); }
    await page.evaluate(() => viewer.setBedMesh(null));
    const again = await draw(bedView);
    if (!(differ(plain, painted) > 200)) fail('the bed mesh did not colour the plate (' + differ(plain, painted) + ')' + await why(plain));
    if (differ(plain, again) > 5) fail('taking the bed mesh away did not bring the plain plate back (' + differ(plain, again) + ')');
    // A previous job's asynchronous data fetches must not repopulate the scene after it was cleared.
    const cleared = await page.evaluate(async () => {
      const original = window.fetch, release = [], before = window.__loadCount;
      window.fetch = (...args) => new Promise((resolve) => release.push(() => resolve(original(...args))));
      const pending = viewer.load(); viewer.clearToolpath();
      const immediate = viewer.probe(); release.forEach((done) => done()); await pending;
      window.fetch = original;
      const after = viewer.probe(), loads = window.__loadCount;
      await viewer.load(8);
      return { immediate, after, before, loads, reloaded: viewer.probe().count, ticket: window.__ticket };
    });
    if (cleared.immediate.count !== 0 || cleared.after.count !== 0 || cleared.after.end !== 0 || cleared.after.nozzle !== null || cleared.loads !== cleared.before) fail('a cleared job reappeared after its pending load: ' + JSON.stringify(cleared));
    if (cleared.reloaded !== r.loaded) fail('a new selected file could not load after clearing the previous job');
    if (r.ticket !== 7 || cleared.ticket !== 8) fail('WebView completion did not carry its app load ticket');
    if (errors.length) fail('script error: ' + errors.join(' | '));
    if (r.error) fail('the viewer reported: ' + r.error);
    if (!(r.loaded > 0)) fail('the toolpath did not load');
    if (r.lockEnd !== 400) fail('lockstep did not reach the latest position (' + r.lockEnd + ')');
    const c = r.view && r.view.camera;
    if (!c || c.yaw !== 12 || c.pitch !== 34 || c.distance !== 222 || c.target.join() !== '10,20,3') fail('the view did not survive a save and restore: ' + JSON.stringify(r.view));
    if (!(Math.abs(r.orbit - 90) < 0.01)) fail('the showcase orbit did not turn the view by 90 degrees: ' + r.orbit);
    if (!process.exitCode) console.log(`viewer check passed: ${r.loaded} segments loaded, lockstep, orbit, view state, edges, opacity, job clearing, stale-load cancellation and the bed mesh on the plate work`);
  } catch (e) { fail(e.message); } finally { await browser.close(); server.close(); }
});
