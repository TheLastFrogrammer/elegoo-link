// Loads the cloud camera page (app/src/main/assets/camera) in headless Chrome with a stand-in for the video service and checks
// what it tells the app in three cases: the printer joins and sends video (then a picture for the recording is taken), the
// printer never joins (the 20 s wait ends with "not sending video" and the member count), and a second join with the same
// account (UID_BANNED). Nothing reaches the network. Run from android/:
//   node tools/camera-check.js            (CHROME=/path/to/chrome to choose the browser; needs the playwright package)
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const assets = path.resolve('app/src/main/assets');
const fail = (why) => { console.error('camera check failed: ' + why); process.exitCode = 1; };

// The stand-in for AgoraRTC: a client whose channel holds what window.__scenario says.
const FAKE = `
window.AgoraRTC = {
  setLogLevel() {}, disableLogUpload() {},
  createClient() {
    const handlers = {}; const emit = (name, ...args) => (handlers[name] || []).forEach((h) => h(...args));
    const client = {
      remoteUsers: [],
      on(name, h) { (handlers[name] = handlers[name] || []).push(h); },
      async join() {
        emit('connection-state-change', 'CONNECTING', 'DISCONNECTED');
        emit('connection-state-change', 'CONNECTED', 'CONNECTING');
        const s = window.__scenario;
        if (s === 'printer' || s === 'stops') setTimeout(() => {
          const printer = { uid: 7, hasVideo: false };
          client.remoteUsers.push(printer); emit('user-joined', printer);
          printer.hasVideo = true; emit('user-published', printer, 'video');
          // 'stops': the printer stops sending after a while (once), as a CC2 does after about 26 s.
          if (s === 'stops' && !window.__stoppedOnce) { window.__stoppedOnce = true; setTimeout(() => { printer.hasVideo = false; emit('user-unpublished', printer, 'video'); }, 3000); }
        }, 300);
        if (s === 'banned') setTimeout(() => emit('connection-state-change', 'DISCONNECTED', 'CONNECTED', 'UID_BANNED'), 300);
        return 1;
      },
      async setClientRole() {},
      async subscribe(user) {
        user.videoTrack = {
          play(box) { const c = document.createElement('canvas'); c.width = 64; c.height = 36; box.appendChild(c); },
          getCurrentFrameData() { const d = new ImageData(64, 36); d.data.fill(128); return d; },
        };
      },
      async leave() { window.__left = (window.__left || 0) + 1; },
    };
    return client;
  },
};`;

async function run(browser, port, scenario, untilMs, waitFor) {
  const page = await browser.newPage({ viewport: { width: 400, height: 300 } });
  const errors = []; page.on('pageerror', (e) => errors.push(e.message));
  await page.route('**/AgoraRTC_N-production.js', (route) => route.fulfill({ contentType: 'application/javascript', body: FAKE }));
  await page.addInitScript((s) => { window.__scenario = s; window.__msgs = []; window.Android = { postMessage(m) { window.__msgs.push(JSON.parse(m)); } }; }, scenario);
  await page.clock.install();
  await page.goto(`http://localhost:${port}/assets/camera/index.html`);
  await page.evaluate(() => start('app', 'SERIAL', 'token', '42'));
  await page.clock.runFor(untilMs);
  if (waitFor) { await page.evaluate(waitFor); await page.clock.runFor(1000); }
  const msgs = await page.evaluate(() => window.__msgs);
  await page.close();
  if (errors.length) fail(scenario + ': script error: ' + errors.join(' | '));
  return msgs;
}
const texts = (msgs, type) => msgs.filter((m) => m.type === type).map((m) => m.text || '');

const server = http.createServer((q, r) => {
  const url = decodeURIComponent(q.url.split('?')[0]);
  const file = url.startsWith('/assets/') ? path.join(assets, url.slice(8)) : null;
  if (!file) { r.writeHead(404); r.end(); return; }
  fs.readFile(file, (e, b) => { if (e) { r.writeHead(404); r.end(); } else { r.writeHead(200, { 'Content-Type': file.endsWith('.html') ? 'text/html' : 'application/octet-stream' }); r.end(b); } });
}).listen(0, async () => {
  const options = {}; if (process.env.CHROME) options.executablePath = process.env.CHROME;
  const browser = await chromium.launch(options);
  const port = server.address().port;
  try {
    // 1. The printer joins and sends video: the page says so, plays it, and gives a picture for the recording.
    const joined = await run(browser, port, 'printer', 2000, () => snapshot(true));
    const events = texts(joined, 'event');
    if (!joined.some((m) => m.type === 'ready')) fail('printer: no ready message');
    if (!events.some((t) => t.startsWith('joined (0 other members'))) fail('printer: no "joined" count: ' + events.join(' | '));
    if (!events.some((t) => t.startsWith('a member joined the channel (1 other member'))) fail('printer: no member joined: ' + events.join(' | '));
    if (!joined.some((m) => m.type === 'playing')) fail('printer: video never played');
    const picture = joined.find((m) => m.type === 'picture');
    if (!picture || !String(picture.data).startsWith('data:image/jpeg;base64,')) fail('printer: no JPEG picture for the recording');
    if (texts(joined, 'status').some((t) => t.startsWith('The printer is not sending'))) fail('printer: reported no video although it played');

    // 2. The printer never joins: after 20 s the page counts the channel and reports no video.
    const empty = await run(browser, port, 'empty', 21000);
    if (!texts(empty, 'event').some((t) => t === 'after 20 s: 0 other members in the channel, 0 sending video')) fail('empty: no 20 s count: ' + texts(empty, 'event').join(' | '));
    if (!texts(empty, 'status').some((t) => t.startsWith('The printer is not sending video'))) fail('empty: no "not sending video" status');
    if (empty.some((m) => m.type === 'playing')) fail('empty: claimed to play');
    const early = await run(browser, port, 'empty', 15000);
    if (texts(early, 'status').some((t) => t.startsWith('The printer is not sending'))) fail('empty: gave up before 20 s');

    // 2b. The printer stops sending: the page rejoins by itself and the video plays again, without reporting a problem.
    const stops = await run(browser, port, 'stops', 8000);
    if (!texts(stops, 'event').some((t) => t === 'the printer stopped sending video; joining again (1)')) fail('stops: no rejoin: ' + texts(stops, 'event').join(' | '));
    if (stops.filter((m) => m.type === 'playing').length < 2) fail('stops: video did not play again after the rejoin');
    if (texts(stops, 'status').some((t) => t.startsWith('The printer stopped'))) fail('stops: reported a stop instead of rejoining');
    // 3. A second join with the same account: the page reports the disconnect with its reason.
    const banned = await run(browser, port, 'banned', 1000);
    if (!texts(banned, 'status').some((t) => t.includes('UID_BANNED'))) fail('banned: reason not reported: ' + texts(banned, 'status').join(' | '));
    if (!texts(banned, 'event').some((t) => t.includes('-> DISCONNECTED (UID_BANNED)'))) fail('banned: no connection event');
  } catch (e) { fail(e.message); }
  finally { await browser.close(); server.close(); }
  if (!process.exitCode) console.log('camera check passed: printer joins and plays (picture kept), rejoins when the printer stops sending, empty channel reported after 20 s, double join reported');
});
