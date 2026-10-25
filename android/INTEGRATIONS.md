# Other apps: the integration API

Link Workshop can share the printer with other apps, read only, in two ways:

- **On the home network: the local API.** The phone answers apps on the same Wi-Fi, such as a TV, a tablet dashboard
  or a script. It serves the status, a live stream of changes and print events, and the printer's camera (and any
  added cameras). A TV can simply open the phone's address in its browser. A TV app or script can use the HTTP API.
- **Through the internet: the relay.** The phone pushes the status, print events, a picture now and then and the
  finished timelapse to *your own* relay. Apps that cannot reach your home network read from there, such as a Reddit
  app showing the printer in a post. A ready relay for Cloudflare Workers is in
  [`integrations/relay-worker`](integrations/relay-worker).

```
 printer ──(LAN or Elegoo cloud)──▶ phone: Link Workshop ──▶ local API  ◀── TV browser / TV app / script (same Wi-Fi)
                                                       └──▶ relay push ──▶ your relay (HTTPS) ◀── Reddit app, any page
```

Both are off until turned on in **Settings › Automations & integrations › Other apps**.

## What is promised

- **Read only.** Neither path can send the printer anything. The local API answers `GET`, plus `POST` only for pairing
  and for tickets. The relay only receives what the phone pushes. Printer control stays in the app, under its own
  rules.
- **The phone is the one connection.** Apps never talk to the printer. However many watch, the printer sees this one
  phone and, for the camera, one stream. That matters on the Centauri Carbon 2, whose camera may serve only one stream.
  While the app's own camera view is open, its frames feed outside viewers too.
- **Nothing identifying leaves the phone:** no serial, IP address, access code, account or cloud id. The status
  carries the printer's name, its state and the print. The relay can also leave out the file name.
- **Paired devices only.** Every read needs a device token. Devices pair by asking from their own screen, and you
  approve them on the phone after matching a 4-digit code. You can also add a device by hand. The phone keeps only a
  SHA-256 hash of each token, and you can remove a device at any time.
- **Home network only.** The local API refuses every address outside loopback, private (10/8, 172.16/12,
  192.168/16), link-local and IPv6 unique-local (fc00::/7) ranges. The home VPN's 100.64.0.0/10 is refused too,
  unless you allow it. It also answers only connections that arrive on Wi-Fi, Ethernet or the phone's own hotspot
  (and a VPN's link, when allowed), never mobile data, because carriers hand out private addresses too.
- **Only while the phone follows the printer.** The app's printer service runs the local API and the relay pushes.
  When the app is not connected (locally or through the cloud), the status says so (`"phase": "offline"`).
- **Stable formats.** `linkworkshop.status/1` and `linkworkshop.event/1` only grow: fields may be added, and none
  change meaning or disappear within version 1. Ignore fields you don't know.

## The local API

Base address: `http://<phone's Wi-Fi address>:8765` (the port can be changed). The Other apps screen shows it.

### Finding the phone

The phone announces itself with DNS-SD/mDNS as `_linkworkshop._tcp` (service name "Link Workshop"), with TXT
records `api=1` and `path=/api/v1`. On Android or Fire OS, use `NsdManager.discoverServices("_linkworkshop._tcp", …)`.
`GET /api/v1/info` needs no token and says what the phone offers:

```json
{"app": "Link Workshop", "app_version": "0.103.0", "api": 1, "read_only": true, "auth": "bearer", "pairing": true,
 "schemas": ["linkworkshop.status/1", "linkworkshop.event/1"], "endpoints": ["GET /api/v1/status", "…"]}
```

### Pairing

1. `POST /api/v1/pair` with `{"name": "Living room TV"}` (sent as `Content-Type: application/json`) returns **202**:
   `{"id": "…", "code": "4821", "poll": "/api/v1/pair/…", "expires_in": 300}`.
2. Show the code on the device's screen. The phone shows a notification. In Other apps the person sees the same code
   and taps **Approve** or **Refuse**.
3. Poll `GET <poll>` every 2 s. It returns `{"state": "pending"}`, then either
   `{"state": "approved", "token": "lw1_…"}` (handed out once; keep it) or `{"state": "denied"}`. It returns 404 once
   the request has run out (5 minutes).

At most 3 requests wait at once, and one address may ask every 2 s. The token never expires. Removing the device on
the phone ends it: its open event and camera streams stop within 5 seconds, and a request with it then gets 401: pair
again.

Pairing answers only the phone's own page and apps, not other web pages: its answers carry no CORS header, and it takes
only JSON. A page elsewhere that someone on your Wi-Fi opens therefore cannot ask to pair, nor read a token. A dashboard
page served from somewhere else can use a device added by hand on the phone.

### Authentication

Send `Authorization: Bearer lw1_…` on every request. Some clients cannot set a header: an `<img>` showing the stream,
or a browser's `EventSource`. For those, `POST /api/v1/tickets` (with the bearer token) returns
`{"ticket": "…", "expires_in": 60}`. Add `?ticket=…` to a `GET` for the next 60 seconds. A stream opened in that time
keeps going after the ticket expires. A ticket cannot make another ticket.

Every answer except pairing allows any origin (`Access-Control-Allow-Origin: *`; tokens are not cookies, so this cannot
be used against you). Errors are JSON: `{"error": "words"}`, with 400, 401, 403, 404, 405, 415, 429 or 503.

### Endpoints

| Request | Answer |
|---|---|
| `GET /` | A ready dashboard page for a TV or tablet browser: pairing, live status, the camera. |
| `GET /api/v1/info` | What the phone offers (no token needed). |
| `POST /api/v1/pair`, `GET /api/v1/pair/{id}` | Pairing, as above (no token needed). |
| `POST /api/v1/tickets` | A 60-second ticket. |
| `GET /api/v1/status` | The status now (`linkworkshop.status/1`). |
| `GET /api/v1/events` | Server-Sent Events: `status` (the full status, whenever it changes), `event` (print events), and a comment every 15 s. |
| `GET /api/v1/cameras` | `{"cameras": [{"id": "printer", "name": "Printer camera", "live": true, "source": "camera", "rotation": 0, "mirror": false}, …]}` |
| `GET /api/v1/cameras/{id}/snapshot.jpg` | The newest picture (JPEG). Header `X-Frame-Age-Ms` gives its age. 503 if no picture arrives within 8 s. |
| `GET /api/v1/cameras/{id}/stream.mjpeg?fps=5` | `multipart/x-mixed-replace; boundary=lwframe`, up to `fps` pictures a second (1–10, default 5). |

**Cameras.**
- `printer` is the printer's own camera.
- Locally, and in cloud mode with "the printer's own camera alongside the cloud" turned on, it is the live stream
  (`"live": true`).
- Otherwise, in cloud mode, it is the print's newest kept picture (`"live": false, "source": "pictures"`). Elegoo's
  cloud video is not passed on.
- Added cameras appear as `added-<id>`. Their `rotation` (degrees clockwise) and `mirror` say how to turn the
  picture: frames are passed on exactly as the camera sent them.
- The phone opens a camera only while someone watches, and closes it 5 s after the last viewer leaves.
- When the app's own camera view opens, the phone hands it the printer's camera and passes its frames on. It waits up to
  12 s for that view's first picture before opening a stream of its own again, so the camera never has two.
- If a frame stays the same for 10 s (the camera is stuck), the stream repeats the last frame.

**Limits:** 32 connections (12 from one address), 6 camera streams and 10 event streams at once. A request must
arrive whole within 15 s. A client that stops reading for 20 s is cut off.

### The status: `linkworkshop.status/1`

```json
{
  "schema": "linkworkshop.status/1",
  "updated_at": "2026-10-25T01:34:05Z",
  "printer": {"name": "Centauri Carbon 2", "model": "Centauri Carbon 2"},
  "connection": {"route": "local network", "fresh": true, "status_at": "2026-10-25T01:34:04Z", "age_s": 1},
  "state": {"phase": "printing", "words": "Printing", "printing": true, "machine_status": 2, "sub_status": 2075, "faults": []},
  "print": {"file": "Desk_mount_PLA", "progress": 42, "layer": 51, "total_layers": 120,
            "remaining_s": 4380, "done_at": "2026-10-25T02:47:05Z", "started_at": "2026-10-25T00:44:05Z", "elapsed_s": 3000},
  "temperatures": {"nozzle": {"now": 214.8, "target": 215.0}, "bed": {"now": 60.1, "target": 60.0}, "chamber": {"now": 31.4}},
  "fans": {"part": 70, "aux": 35, "chamber": 0},
  "light": true
}
```

- `connection.route` is `local network`, `home VPN`, `Elegoo cloud` or `not connected`. `fresh` is false when the
  phone has no recent status (none for a minute counts as none). In that case `state.phase` is `offline` and the other
  blocks are left out. The phone checks again every 15 s, so the event stream says so too.
- `state.phase` is one of `idle`, `preparing`, `printing`, `pausing`, `paused`, `resuming`, `stopping`, `stopped`,
  `complete`, `busy` (levelling, loading filament, other machine work) and `offline`.
  - `words` is the app's own wording.
  - `machine_status` and `sub_status` are the printer's numbers, for anyone who wants them.
  - `faults` lists the printer's fault codes.
- Fields the printer did not report are left out, never invented. Temperatures are °C, fans are %, times are UTC
  (ISO 8601) and durations are seconds.

### Print events: `linkworkshop.event/1`

Sent on the event stream as `event: event`, and to the relay:

```json
{"schema": "linkworkshop.event/1", "type": "print_finished", "words": "A print finishes", "at": "2026-10-25T02:47:10Z",
 "file": "Desk_mount_PLA", "layer": 120, "total_layers": 120, "progress": 100}
```

`type` is one of the app's print events:

| `type` | When |
|---|---|
| `print_started` | A print starts. |
| `first_layer` | The first layer is done. |
| `paused` / `resumed` | The print pauses or resumes. |
| `print_finished` | The print finishes. |
| `print_stopped` | The print is stopped. |
| `fault` | The printer reports a new fault. |
| `guardian_alarm` | The print guardian sees a problem. |
| `print_watch` | The print watch sees a stall or heater drift. |
| `contact_lost` | Contact with the printer is lost during a print. |
| `bed_levelled` | Bed levelling finishes. |
| `maintenance_finished` | A maintenance task finishes. |

Per-layer and value events are not sent here: follow `status` for those.

## Building a Fire TV (or Android TV) app

**No app at all.** Open the address the phone shows (`http://192.168.…:8765/`) in Silk, Fire TV's browser, and
choose **Pair this screen**. The page shows the code, you approve it on the phone, and it then shows the camera, the
progress, the times and the temperatures. It remembers the pairing.

**A native app.** [`integrations/examples/PrinterWatch.java`](integrations/examples/PrinterWatch.java) is a
copy-in client with no libraries. It does discovery, pairing (it hands you the code to show), the event stream and the
camera as decoded bitmaps. Show the bitmaps in an `ImageView` and the status in your layout. Things to know:

- The phone answers plain `http` on the home network. Allow cleartext traffic for it
  (`android:usesCleartextTraffic="true"`, or a network security config).
- Keep the screen on while watching (`FLAG_KEEP_SCREEN_ON`). Make every control reachable with the remote's D-pad.
- MJPEG is not a format ExoPlayer/Media3 plays. Decode the JPEGs yourself, as `PrinterWatch` does, or show the stream in
  a `WebView` `<img>` with a ticket.
- Reconnect calmly: the stream ends whenever the phone loses the printer or the app is closed. `PrinterWatch` retries
  every 5 s. After a refusal on the phone it stops asking until you start it again, and after an unanswered request it
  waits a minute. The page at `/` waits longer after each failure, up to 30 s.

**A script.** [`integrations/examples/watch.py`](integrations/examples/watch.py) pairs, prints each status change
and event, and can save a picture. It uses only Python's standard library.

## The relay

### Setting up the ready relay (Cloudflare Workers)

1. Install Wrangler (`npm install -g wrangler`) and run `wrangler login`.
2. In [`integrations/relay-worker`](integrations/relay-worker), create the store with
   `npx wrangler kv namespace create LW` and paste its id into `wrangler.toml`.
3. In the app: **Other apps › Relay › Secret… › Make a new secret**. Copy it, then run
   `npx wrangler secret put LW_SECRET` and paste it.
4. Choose a read key for your readers (any long random text) and run `npx wrangler secret put READ_KEY`. Or set
   `PUBLIC_READ = "true"` in `wrangler.toml` to let anyone read.
5. Deploy with `npx wrangler deploy`. Enter the address it prints (`https://printer-relay.<you>.workers.dev`) in
   **Relay address…**, turn **Push to my relay** on, and tap **Send a test now**.

`node test.mjs` in that folder checks the relay with a fake store (signatures, stale, unsigned, older and oversized pushes
refused, events kept once, reads needing the key).

**Costs and limits.** Cloudflare's free plan allows 1,000 KV writes a day. With the status at most every minute and a
picture every 5 minutes, an 8-hour print uses about 600. Choose "status at most every 5 min" for longer days, or use a
paid plan.

**Privacy.** Pictures and the timelapse of your printer leave your home when you turn them on. "Leave the file name
out" keeps the file name off the relay.

### What the phone pushes

| Push | When | Body |
|---|---|---|
| `POST /v1/status` | When it changes: at most every 15 s, 1 min or 5 min (your choice), and at least every 5 minutes. | `linkworkshop.status/1` |
| `POST /v1/event` | At once, on each print event. | `linkworkshop.event/1` |
| `POST /v1/snapshot` | Every 1, 2, 5 or 15 min while printing (your choice, off by default), and at start, finish and stop. | JPEG |
| `POST /v1/timelapse` | When a print's timelapse is made (if chosen; up to 24 MB). | MP4 |

Every push carries `X-LW-Timestamp` (Unix seconds) and `X-LW-Signature: v1=<hex>`, where the hex is
HMAC-SHA256(secret, `timestamp + "\n" + "POST" + "\n" + path + "\n" + hex(SHA-256(body))`), keyed with the secret's
UTF-8 text. `path` is the request's path as sent (`/v1/status`, or `/relay/v1/status` for an address ending in
`/relay`). A relay should refuse timestamps more than 5 minutes off and compare signatures in constant time. The ready
relay also refuses a status older than the one it keeps (409), so a push sent again cannot turn it back, keeps an event
it already has only once, and stops reading a body as soon as it passes the size limit.
To write your own relay, both the app's tests and `test.mjs` check the vector `9baea680…a076`:
secret `test-secret-0123456789abcdef`, timestamp `1700000000`, path `/v1/status`, body `{"a":1}`.

If a push fails, the phone waits a minute, then sends the status again, changed or not. Events still go out at once.
The phone pushes only while it follows the printer.

### Reading from the relay

| Request | Answer |
|---|---|
| `GET /v1/status` | The last status, plus `relay_received_at`. |
| `GET /v1/events` | The last 50 events, newest first. |
| `GET /v1/snapshot.jpg` | The last picture. Header `X-LW-At` gives when it was pushed. |
| `GET /v1/timelapse.mp4` | The last timelapse. |

Send the read key as `Authorization: Bearer <READ_KEY>` (or `?key=`), unless the relay is public.

### A Reddit app (Devvit)

A Reddit app runs on Reddit's servers, so it cannot reach your home network. Read from the relay instead:

- Fetch `/v1/status` from the app's server side, which keeps the read key out of the post. Show the phase, progress,
  layer and time left, and refresh every 30–60 s while the post is in view. The relay's status is at most a minute old
  (or 15 s, if you choose).
- For the picture, use `/v1/snapshot.jpg`. Reddit apps can usually only show images that are uploaded to Reddit's own
  media store or come from approved sources. If your app cannot show the relay's address directly, upload the picture
  through Reddit's media API when it changes (the `X-LW-At` header says when).
- `/v1/events` tells you when a print finished. A scheduled job can then update the post, or make a new one with the
  final picture or the timelapse.
- Reddit only lets an app fetch from domains it approves. Add your relay's domain in the app's configuration and
  request approval as Reddit's developer docs describe. Devvit changes over time, so follow its current docs for the
  exact calls.

There is no live video through the relay: a feed entry gets a picture every few minutes and the finished timelapse.
Live video needs the local API, on the home network.

## What is not here (yet)

- **Control.** Pausing, the light and so on stay in the app, by design. If control is ever added, it will be a
  separate, opt-in API with its own approval, never part of this one.
- **Elegoo cloud live video.** It is passed on only as the print's kept pictures. The live picture needs the printer's
  own camera on the home network.
- **Encryption on the home network** (`https` with a self-signed certificate). Tokens and pictures travel as plain
  HTTP on your Wi-Fi, like the printer's own camera stream does.
