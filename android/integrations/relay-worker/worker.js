// Link Workshop relay: a Cloudflare Worker that keeps the newest printer status, print events, picture and timelapse the
// phone pushes, for readers that cannot reach the home network (a Reddit app, a page anywhere).
//
// Writes come only from the phone: each push is signed with LW_SECRET (HMAC-SHA256 over
// "timestamp\nPOST\npath\nhex sha256(body)", sent as X-LW-Timestamp and X-LW-Signature: v1=<hex>), and pushes more than
// five minutes off the relay's clock are refused. Reads need READ_KEY (Authorization: Bearer <key>, or ?key=<key>),
// unless PUBLIC_READ is "true". Nothing here can reach the printer: the relay only keeps what the phone sends.
//
// Bindings (wrangler.toml): a KV namespace bound as LW; secrets LW_SECRET and READ_KEY (wrangler secret put ...);
// an optional plain variable PUBLIC_READ.

const MAX_SKEW_S = 300;
const KEEP_EVENTS = 50;
const LIMITS = { "/v1/status": 64 * 1024, "/v1/event": 16 * 1024, "/v1/snapshot": 4 * 1024 * 1024, "/v1/timelapse": 24 * 1024 * 1024 };
const TYPES = { "/v1/status": "application/json", "/v1/event": "application/json", "/v1/snapshot": "image/jpeg", "/v1/timelapse": "video/mp4" };

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors({ "Access-Control-Allow-Methods": "GET, OPTIONS", "Access-Control-Allow-Headers": "Authorization", "Access-Control-Max-Age": "600" }) });
    if (request.method === "POST") return push(request, env, url.pathname);
    if (request.method !== "GET") return json({ error: "Only GET and the phone's signed POST" }, 405);
    if (url.pathname === "/" || url.pathname === "/v1") return json({ relay: "Link Workshop", read: ["/v1/status", "/v1/events", "/v1/snapshot.jpg", "/v1/timelapse.mp4"] });
    if (!readable(request, env, url)) return json({ error: "Send the read key: Authorization: Bearer <READ_KEY> or ?key=<READ_KEY>" }, 401);
    switch (url.pathname) {
      case "/v1/status": { const s = await env.LW.get("status"); return s ? new Response(s, { headers: cors({ "Content-Type": "application/json", "Cache-Control": "no-store" }) }) : json({ error: "Nothing pushed yet" }, 404); }
      case "/v1/events": { const e = await env.LW.get("events"); return new Response(e || "[]", { headers: cors({ "Content-Type": "application/json", "Cache-Control": "no-store" }) }); }
      case "/v1/snapshot.jpg": return blob(env, "snapshot", "image/jpeg");
      case "/v1/timelapse.mp4": return blob(env, "timelapse", "video/mp4");
      default: return json({ error: "Not found" }, 404);
    }
  },
};

async function push(request, env, path) {
  if (!(path in LIMITS)) return json({ error: "Not found" }, 404);
  if (!env.LW_SECRET) return json({ error: "LW_SECRET is not set on the relay" }, 500);
  const ts = parseInt(request.headers.get("X-LW-Timestamp") || "", 10);
  const signature = (request.headers.get("X-LW-Signature") || "").replace(/^v1=/, "");
  if (!Number.isFinite(ts) || Math.abs(Date.now() / 1000 - ts) > MAX_SKEW_S) return json({ error: "Missing or stale timestamp" }, 401);
  const length = parseInt(request.headers.get("Content-Length") || "0", 10);
  if (length > LIMITS[path]) return json({ error: "Too large" }, 413);
  const body = new Uint8Array(await request.arrayBuffer());
  if (body.length > LIMITS[path]) return json({ error: "Too large" }, 413);
  const expected = await sign(env.LW_SECRET, ts, "POST", path, body);
  if (!same(expected, signature)) return json({ error: "Bad signature" }, 401);
  const at = new Date(ts * 1000).toISOString();
  if (path === "/v1/status") {
    const status = JSON.parse(new TextDecoder().decode(body));
    status.relay_received_at = at;
    await env.LW.put("status", JSON.stringify(status));
  } else if (path === "/v1/event") {
    const event = JSON.parse(new TextDecoder().decode(body));
    const events = JSON.parse((await env.LW.get("events")) || "[]");
    events.unshift(event);
    await env.LW.put("events", JSON.stringify(events.slice(0, KEEP_EVENTS)));
  } else {
    await env.LW.put(path === "/v1/snapshot" ? "snapshot" : "timelapse", body, { metadata: { at, type: TYPES[path] } });
  }
  return json({ ok: true, at });
}

/** Hex HMAC-SHA256 with the secret's UTF-8 bytes over "ts\nMETHOD\npath\nhex sha256(body)": the same as the phone. */
export async function sign(secret, ts, method, path, body) {
  const digest = hex(await crypto.subtle.digest("SHA-256", body));
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret.trim()), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return hex(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${ts}\n${method}\n${path}\n${digest}`)));
}
function hex(buffer) { return [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, "0")).join(""); }
function same(a, b) { if (a.length !== b.length) return false; let d = 0; for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i); return d === 0; }
function readable(request, env, url) {
  if (String(env.PUBLIC_READ || "").toLowerCase() === "true") return true;
  if (!env.READ_KEY) return false;
  const given = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "") || url.searchParams.get("key") || "";
  return same(given, env.READ_KEY);
}
async function blob(env, key, type) {
  const { value, metadata } = await env.LW.getWithMetadata(key, { type: "arrayBuffer" });
  if (!value) return json({ error: "Nothing pushed yet" }, 404);
  return new Response(value, { headers: cors({ "Content-Type": type, "Cache-Control": "no-store", "X-LW-At": (metadata && metadata.at) || "" }) });
}
function cors(headers) { return { "Access-Control-Allow-Origin": "*", "X-Content-Type-Options": "nosniff", ...headers }; }
function json(body, status = 200) { return new Response(JSON.stringify(body), { status, headers: cors({ "Content-Type": "application/json", "Cache-Control": "no-store" }) }); }
