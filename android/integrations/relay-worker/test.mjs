// node test.mjs — checks the relay against a fake KV: signatures (the same vector the app's tests use), stale and
// unsigned pushes refused, reads need the key, and the newest status, events and picture come back.
import assert from "node:assert/strict";
import worker, { sign } from "./worker.js";

const VECTOR = "9baea68011788923add0e7f273f0dd7cdb6c56805f107c4eb2fd6a52d5a2a076";
assert.equal(await sign("test-secret-0123456789abcdef", 1700000000, "POST", "/v1/status", new TextEncoder().encode('{"a":1}')), VECTOR);

class FakeKV {
  constructor() { this.data = new Map(); }
  async get(k) { const v = this.data.get(k); return v ? v.value : null; }
  async put(k, value, options = {}) { this.data.set(k, { value: typeof value === "string" ? value : value.slice().buffer, metadata: options.metadata }); }
  async getWithMetadata(k) { const v = this.data.get(k); return v ? { value: v.value, metadata: v.metadata } : { value: null, metadata: null }; }
}
const env = { LW: new FakeKV(), LW_SECRET: "test-secret-0123456789abcdef", READ_KEY: "reader-key" };
const base = "https://relay.example";

async function signed(path, body, type, ts = Math.floor(Date.now() / 1000), secret = env.LW_SECRET) {
  const bytes = typeof body === "string" ? new TextEncoder().encode(body) : body;
  return worker.fetch(new Request(base + path, { method: "POST", body: bytes, headers: {
    "Content-Type": type, "Content-Length": String(bytes.length), "X-LW-Timestamp": String(ts), "X-LW-Signature": "v1=" + await sign(secret, ts, "POST", path, bytes) } }), env);
}

assert.equal((await signed("/v1/status", '{"schema":"linkworkshop.status/1","state":{"phase":"printing"}}', "application/json")).status, 200);
assert.equal((await signed("/v1/status", "{}", "application/json", Math.floor(Date.now() / 1000) - 900)).status, 401, "stale");
assert.equal((await signed("/v1/status", "{}", "application/json", undefined, "wrong-secret-0123456789")).status, 401, "wrong secret");
assert.equal((await worker.fetch(new Request(base + "/v1/status", { method: "POST", body: "{}" }), env)).status, 401, "unsigned");
assert.equal((await signed("/v1/event", '{"type":"print_finished"}', "application/json")).status, 200);
const jpeg = new Uint8Array([0xff, 0xd8, 1, 2, 3, 0xff, 0xd9]);
assert.equal((await signed("/v1/snapshot", jpeg, "image/jpeg")).status, 200);

assert.equal((await worker.fetch(new Request(base + "/v1/status"), env)).status, 401, "reads need the key");
const status = await (await worker.fetch(new Request(base + "/v1/status", { headers: { Authorization: "Bearer reader-key" } }), env)).json();
assert.equal(status.state.phase, "printing"); assert.ok(status.relay_received_at);
const events = await (await worker.fetch(new Request(base + "/v1/events?key=reader-key"), env)).json();
assert.equal(events[0].type, "print_finished");
const picture = new Uint8Array(await (await worker.fetch(new Request(base + "/v1/snapshot.jpg?key=reader-key"), env)).arrayBuffer());
assert.deepEqual([...picture], [...jpeg]);
assert.equal((await worker.fetch(new Request(base + "/v1/status"), { ...env, PUBLIC_READ: "true" })).status, 200, "public when chosen");

// A push sent again later (replayed), or out of order, cannot put back an older status; an event already kept is kept once.
const now = Math.floor(Date.now() / 1000);
assert.equal((await signed("/v1/status", '{"state":{"phase":"complete"}}', "application/json", now)).status, 200);
assert.equal((await signed("/v1/status", '{"state":{"phase":"printing"}}', "application/json", now - 60)).status, 409, "older than the status kept");
assert.equal((await (await worker.fetch(new Request(base + "/v1/status?key=reader-key"), env)).json()).state.phase, "complete");
assert.equal((await signed("/v1/status", '{"state":{"phase":"idle"}}', "application/json", now)).status, 200, "the same second is fine");
const finished = '{"type":"print_finished","at":"2026-10-25T02:47:10Z"}';
assert.equal((await signed("/v1/event", finished, "application/json")).status, 200);
assert.equal((await (await signed("/v1/event", finished, "application/json")).json()).duplicate, true);
assert.equal((await (await worker.fetch(new Request(base + "/v1/events?key=reader-key"), env)).json()).filter((e) => e.at === "2026-10-25T02:47:10Z").length, 1);

// A signed body that is not a JSON object is refused, not a crash.
assert.equal((await signed("/v1/status", "not json", "application/json")).status, 400);
assert.equal((await signed("/v1/event", "[1,2]", "application/json")).status, 400);

// A body without a length is read only up to the limit: an endless one is cut off, never held whole.
let pulled = 0;
const endless = new ReadableStream({ pull(c) { pulled++; if (pulled > 640) c.close(); else c.enqueue(new Uint8Array(65536)); } });
const big = await worker.fetch(new Request(base + "/v1/snapshot", { method: "POST", body: endless, duplex: "half",
  headers: { "X-LW-Timestamp": String(now), "X-LW-Signature": "v1=00" } }), env);
assert.equal(big.status, 413);
assert.ok(pulled < 100, "stopped reading at the limit (read " + pulled + " pieces of 64 KiB)");
console.log("relay worker: all checks passed");
