// Loads the plate view (app/src/main/assets/plate) in headless Chrome with a small T-shaped model (a column with a wide cap,
// whose underside overhangs) and checks the handles: dragging the X arrow moves along X only, the bed ring turns in 5°
// steps, an upright ring tilts the model (it then lies on another face), the yellow square scales; that the support
// shading marks the cap's underside but not the model's base; and that a support blocker can be added, dragged, sized, and
// rides with its model when the model is moved, copied or removed. Saves screenshots when SHOTS is set. Run from android/:
//   node tools/plate-check.js            (CHROME=/path/to/chrome to choose the browser; needs the playwright package)
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const assets = path.resolve('app/src/main/assets');
const fail = (why) => { console.error('plate check failed: ' + why); process.exitCode = 1; };

// Triangles of an axis-aligned box, outward-facing (counter-clockwise seen from outside).
function box(x0, y0, z0, x1, y1, z1) {
  const v = (x, y, z) => [x, y, z], t = [];
  const quad = (a, b, c, d) => { t.push([a, b, c], [a, c, d]); };
  quad(v(x0, y0, z0), v(x0, y1, z0), v(x1, y1, z0), v(x1, y0, z0));   // bottom (normal -Z)
  quad(v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, z1), v(x0, y1, z1));   // top
  quad(v(x0, y0, z0), v(x1, y0, z0), v(x1, y0, z1), v(x0, y0, z1));   // front (-Y)
  quad(v(x1, y1, z0), v(x0, y1, z0), v(x0, y1, z1), v(x1, y1, z1));   // back
  quad(v(x0, y1, z0), v(x0, y0, z0), v(x0, y0, z1), v(x0, y1, z1));   // left (-X)
  quad(v(x1, y0, z0), v(x1, y1, z0), v(x1, y1, z1), v(x1, y0, z1));   // right
  return t;
}
const triangles = [...box(-5, -5, 0, 5, 5, 30), ...box(-15, -15, 30, 15, 15, 36)];
const mesh = Buffer.alloc(8 + triangles.length * 37);
mesh.write('LKM1', 0, 'ascii'); mesh.writeUInt32LE(triangles.length, 4);
triangles.forEach((tri, i) => tri.forEach((corner, k) => corner.forEach((c, j) => mesh.writeFloatLE(c, 8 + i * 36 + (k * 3 + j) * 4))));
for (let i = 0; i < triangles.length; i++) mesh.writeUInt8(1, 8 + triangles.length * 36 + i);
const sceneJson = JSON.stringify({ bed: [[0, 0], [256, 0], [256, 256], [0, 256]], excluded: null, height: 256, colours: ['#F2754E'],
  objects: [{ file: 0, object: 0, name: 'T.stl' }], placements: [{ file: 0, object: 0, x: 128, y: 128, rotation: 0, scale: 1 }] });

const server = http.createServer((q, r) => {
  const url = decodeURIComponent(q.url.split('?')[0]);
  if (url === '/data/scene.json') { r.writeHead(200, { 'Content-Type': 'application/json' }); r.end(sceneJson); return; }
  if (url === '/data/mesh/0') { r.writeHead(200); r.end(mesh); return; }
  const file = url.startsWith('/assets/') ? path.join(assets, url.slice(8)) : null;
  if (!file) { r.writeHead(404); r.end(); return; }
  fs.readFile(file, (e, b) => { if (e) { r.writeHead(404); r.end(); } else { r.writeHead(200); r.end(b); } });
}).listen(0, async () => {
  const options = { args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] };
  if (process.env.CHROME) options.executablePath = process.env.CHROME;
  const browser = await chromium.launch(options);
  const shots = process.env.SHOTS;
  try {
    const page = await browser.newPage({ viewport: { width: 480, height: 480 } });
    const errors = []; page.on('pageerror', (e) => errors.push(e.message));
    await page.addInitScript(() => { window.__changes = 0; window.Android = { onReady() {}, onLoaded(n) { window.__loaded = n; }, onError(m) { window.__error = m; }, onSelect() {}, onBoxSelect(i) { window.__box = i; }, onChanged(j) { window.__changes++; window.__last = JSON.parse(j); } }; });
    await page.goto(`http://localhost:${server.address().port}/assets/plate/index.html`);
    await page.waitForFunction(() => window.plate, null, { timeout: 10000 });
    await page.evaluate(() => plate.load());
    await page.waitForFunction(() => window.__loaded || window.__error, null, { timeout: 20000 });
    const placement = () => page.evaluate(() => JSON.parse(JSON.stringify(plate.scene.placements[0])));
    const drag = async (kind, dx, dy) => {
      const at = await page.evaluate((k) => plate.handlePoint(k), kind);
      if (!at) { fail('no ' + kind + ' handle on screen'); return; }
      await page.mouse.move(at[0], at[1]); await page.mouse.down();
      for (let i = 1; i <= 8; i++) await page.mouse.move(at[0] + dx * i / 8, at[1] + dy * i / 8);
      await page.mouse.up(); await page.waitForTimeout(100);
    };
    await page.evaluate(() => plate.select(0)); await page.waitForTimeout(200);
    if (shots) await page.screenshot({ path: path.join(shots, 'plate-gimbal.png') });

    let before = await placement(); await drag('x', 60, 0); let after = await placement();
    if (!(Math.abs(after.x - before.x) > 3) || after.y !== before.y) fail('the X arrow did not move along X only: ' + JSON.stringify([before, after]));

    before = after; await drag('z', 80, -40); after = await placement();
    if (after.rotation === before.rotation || Math.round(after.rotation) % 5 !== 0) fail('the bed ring did not turn in 5° steps: ' + after.rotation);

    before = after; await drag('tx', 0, -70); after = await placement();
    if (!after.down) fail('the upright ring did not tilt the model: ' + JSON.stringify(after));
    const size = await page.evaluate(() => plate.size());
    if (!(Math.abs(size[2] - 36) > 0.5)) fail('a tilted model should stand at another height than 36 mm: ' + size);

    await page.evaluate(() => plate.upright()); before = await placement(); await drag('s', 40, 30); after = await placement();
    if (!(after.scale !== 1 && after.scale > 0.02)) fail('the yellow square did not scale: ' + after.scale);
    if (!(await page.evaluate(() => window.__changes)) > 0) fail('no change was reported to the app');

    // Support shading: purple on the cap's underside seen from below the cap's level.
    const purple = async () => page.evaluate(() => {
      const c = document.getElementById('view'), probe = document.createElement('canvas'); probe.width = c.width; probe.height = c.height;
      const g = probe.getContext('2d'); g.drawImage(c, 0, 0); const d = g.getImageData(0, 0, c.width, c.height).data; let n = 0;
      for (let i = 0; i < d.length; i += 4) if (d[i + 2] > 120 && d[i + 2] > d[i + 1] + 50 && d[i] > d[i + 1] + 20) n++;
      return n;
    });
    await page.evaluate(() => { plate.setGimbal(false); plate.scene.placements[0].scale = 2; plate.camera.pitch = 3; plate.camera.distance = 300; plate.camera.target = [128, 128, 10]; plate.redraw(); });
    await page.waitForTimeout(200);
    const without = await purple();
    await page.evaluate(() => { plate.setOverhangs(30); }); await page.waitForTimeout(200);
    const shaded = await purple();
    if (shots) await page.screenshot({ path: path.join(shots, 'plate-supports.png') });
    if (!(shaded > 200 && shaded > without * 3 + 100)) fail('the overhang was not shaded: ' + without + ' → ' + shaded + ' purple pixels');
    await page.evaluate(() => { plate.scene.placements[0].scale = 1; plate.camera.pitch = 38; plate.camera.target = [128, 128, 0]; plate.camera.distance = 420; plate.setGimbal(true); plate.select(0); });
    await page.waitForTimeout(200);
    if (shots) await page.screenshot({ path: path.join(shots, 'plate-gimbal-supports.png') });

    // Support blockers and enforcers.
    await page.evaluate(() => { plate.setOverhangs(0); plate.upright(); plate.setScale(1); plate.select(0); });
    const added = await page.evaluate(() => plate.addSupport('blocker'));
    let boxes = await page.evaluate(() => JSON.parse(JSON.stringify(plate.scene.supports)));
    if (added !== 0 || boxes.length !== 1 || boxes[0].size[0] !== 15 || boxes[0].size[2] !== 37) fail('the blocker was not added on the model: ' + JSON.stringify(boxes));
    if ((await page.evaluate(() => window.__box)) !== 0) fail('the new box was not selected');
    await page.waitForTimeout(150);
    if (shots) await page.screenshot({ path: path.join(shots, 'plate-support-box.png') });
    const model = await placement(), at = await page.evaluate(() => plate.boxPoint(0));
    await page.mouse.move(at[0], at[1]); await page.mouse.down();
    for (let i = 1; i <= 8; i++) await page.mouse.move(at[0] + 5 * i, at[1]);
    await page.mouse.up(); await page.waitForTimeout(100);
    boxes = await page.evaluate(() => JSON.parse(JSON.stringify(plate.scene.supports)));
    const moved = await placement();
    if (Math.abs(boxes[0].center[0] - model.x) < 3 || moved.x !== model.x) fail('dragging the box did not move it alone: ' + JSON.stringify([boxes[0], model, moved]));
    if (!(await page.evaluate((x) => plate.setBox(0, [x, 128, 10], [8, 8, 20]), model.x))) fail('a box could not be sized');
    if (await page.evaluate(() => plate.setBox(0, [128, 128, 1], [8, 8, 20]))) fail('a box below the bed was accepted');
    await page.evaluate(() => { plate.select(0); plate.move(10, 0); });
    boxes = await page.evaluate(() => JSON.parse(JSON.stringify(plate.scene.supports)));
    if (boxes[0].center[0] !== model.x + 10) fail('the box did not ride with its model: ' + JSON.stringify(boxes[0]));
    await page.evaluate(() => { plate.select(0); plate.duplicate(); });
    if ((await page.evaluate(() => plate.scene.supports.length)) !== 2) fail('copying the model did not copy its box');
    await page.evaluate(() => plate.remove());
    if ((await page.evaluate(() => plate.scene.supports.length)) !== 1) fail('removing the copy did not remove its box');
    await page.evaluate(() => plate.setBox(0, [20, 20, 10], [8, 8, 20]));
    const report = await page.evaluate(() => window.__last);
    if (!report.supports || report.supports.length !== 1 || JSON.stringify(report.loose) !== '[0]') fail('a box away from the models was not reported: ' + JSON.stringify(report.loose));
    await page.evaluate(() => { const p = plate.scene.placements[0];
      plate.setBox(0, [p.x + 10, p.y, 15], [10, 34, 30]); plate.setBoxKind(0, 'enforcer'); plate.addSupport('blocker'); plate.setBox(1, [p.x - 10, p.y, 15], [10, 34, 30]); plate.selectBox(1); });
    await page.waitForTimeout(150);
    if (shots) await page.screenshot({ path: path.join(shots, 'plate-support-boxes.png') });
    if (errors.length) fail('script error: ' + errors.join(' | '));
    if (!process.exitCode) console.log('plate check passed: move, turn, tilt and scale handles work; overhangs shaded (' + shaded + ' px); support boxes add, drag, size and ride with their model');
  } catch (e) { fail(e.message); } finally { await browser.close(); server.close(); }
});
