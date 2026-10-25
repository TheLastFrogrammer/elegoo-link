package io.github.thelastfrogrammer.elink;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The local API other apps on the home network read (a TV app, a dashboard, a script): the printer's status, a stream of
 * its changes and print events, and its cameras, through this phone's one connection to the printer. Read only: there is
 * no way to send the printer anything through it. Off until turned on; answers only addresses on the local network (and
 * the home VPN's, when allowed); every read needs a paired device's token (or a ticket made with one). Independent of
 * Android: the service hands in what it shows. See INTEGRATIONS.md for the API.
 */
final class IntegrationServer implements AutoCloseable {
    static final int DEFAULT_PORT = 8765, API = 1;
    static final int MAX_CONNECTIONS = 32, MAX_PER_ADDRESS = 12, MAX_STREAMS = 6, MAX_EVENTS = 10, MAX_FPS = 10, DEFAULT_FPS = 5;
    static final long STALL_MS = 20_000, PING_MS = 15_000, FIRST_FRAME_MS = 8_000, REQUEST_MS = 15_000;

    interface Host {
        /** The status now (IntegrationStatus). */
        JSONObject status();
        /** The cameras: [{id, name, live, ...}]. */
        JSONArray cameras();
        /** A bundled page ("index.html"), or null. */
        byte[] asset(String name);
        String appVersion();
    }

    private final Host host;
    private final IntegrationAuth auth;
    private final CameraHub hub;
    private final int port;
    private final boolean allowVpn;
    private ServerSocket server;
    private Thread acceptor;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "integration-api"); t.setDaemon(true); return t; });
    private final AtomicInteger connections = new AtomicInteger(), streams = new AtomicInteger();
    private final Map<String, Integer> perAddress = new HashMap<>();
    private final List<Client> listeners = new ArrayList<>();
    private final List<Client> writers = new ArrayList<>();
    private final Map<String, Long> pairAsked = new HashMap<>();
    private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "integration-api-timer"); t.setDaemon(true); return t; });
    private volatile boolean closed;
    /** How long a client may take to send its whole request (shorter in tests). */
    volatile long requestMs = REQUEST_MS;
    /** Requests answered, for the settings screen. */
    final AtomicInteger served = new AtomicInteger();

    /** A long-lived reader (events or a camera stream): its socket, its device, and since when a write has been waiting (0 = none). */
    private static final class Client {
        final Socket socket; final OutputStream out; final String deviceId; volatile long writingSince;
        final LinkedBlockingDeque<String> queue = new LinkedBlockingDeque<>();
        /** The status last queued for this listener (times aside), so each one hears every change once. Under `listeners`. */
        String statusKey = "";
        Client(Socket socket, OutputStream out, String deviceId) { this.socket = socket; this.out = out; this.deviceId = deviceId; }
        void write(byte[] bytes) throws IOException { writingSince = System.currentTimeMillis(); try { out.write(bytes); out.flush(); } finally { writingSince = 0; } }
    }

    IntegrationServer(Host host, IntegrationAuth auth, CameraHub hub, int port, boolean allowVpn) { this.host = host; this.auth = auth; this.hub = hub; this.port = port; this.allowVpn = allowVpn; }

    synchronized void start() throws IOException {
        if (server != null) return;
        server = new ServerSocket(); server.setReuseAddress(true); server.bind(new java.net.InetSocketAddress(port), 50);
        acceptor = new Thread(this::accept, "integration-api-accept"); acceptor.setDaemon(true); acceptor.start();
        timer.scheduleWithFixedDelay(() -> {
            // Caught: one failure must not end the timer (a scheduled task that throws never runs again).
            try {
                long now = System.currentTimeMillis();
                List<Client> all; synchronized (writers) { all = new ArrayList<>(writers); }
                // Writes that stall (a client that stopped reading) are cut, so a stream never holds the camera for nobody;
                // so are the streams of a device removed on the phone, which must not keep watching.
                for (Client c : all) if (c.writingSince > 0 && now - c.writingSince > STALL_MS || !auth.known(c.deviceId)) close(c.socket);
                synchronized (listeners) { for (Client c : listeners) if (c.queue.isEmpty()) c.queue.offer(": ping\n\n"); }
            } catch (RuntimeException unexpected) { }
        }, 5, 5, TimeUnit.SECONDS);
    }
    int port() { ServerSocket s = server; return s == null ? port : s.getLocalPort(); }
    boolean running() { return server != null && !closed; }
    int listeners() { synchronized (listeners) { return listeners.size(); } }

    private void accept() {
        while (!closed) {
            Socket socket;
            try { socket = server.accept(); }
            catch (IOException failed) { if (closed) return; try { Thread.sleep(200); } catch (InterruptedException stop) { return; } continue; }   // never a busy loop
            String from = socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress();
            if (!allowed(socket.getInetAddress(), allowVpn) || !homeInterface(socket.getLocalAddress(), allowVpn) || connections.get() >= MAX_CONNECTIONS || !take(from)) { close(socket); continue; }
            connections.incrementAndGet();
            try { pool.execute(() -> { try { handle(socket); } finally { connections.decrementAndGet(); give(from); } }); }
            catch (RuntimeException full) { connections.decrementAndGet(); give(from); close(socket); }
        }
    }
    /** Counts a connection from an address; false when it already holds MAX_PER_ADDRESS (one device cannot take them all). */
    private boolean take(String from) {
        synchronized (perAddress) { int n = perAddress.getOrDefault(from, 0); if (n >= MAX_PER_ADDRESS) return false; perAddress.put(from, n + 1); return true; }
    }
    private void give(String from) {
        synchronized (perAddress) { int n = perAddress.getOrDefault(from, 0) - 1; if (n <= 0) perAddress.remove(from); else perAddress.put(from, n); }
    }

    /** Only the home network: loopback, private and link-local addresses (and the home VPN's 100.64.0.0/10, when allowed). */
    /**
     * Whether a connection arrived on the home network's own link: Wi-Fi, Ethernet, the phone's hotspot or loopback (and a
     * VPN's, when allowed). Mobile data is refused even when the carrier hands out private addresses (10/8, 100.64/10),
     * as are other links nobody chose; an interface that cannot be named is refused too.
     */
    static boolean homeInterface(InetAddress local, boolean allowVpn) {
        try {
            java.net.NetworkInterface link = local == null ? null : java.net.NetworkInterface.getByInetAddress(local);
            return link != null && homeLink(link.getName(), link.isLoopback(), allowVpn);
        } catch (java.net.SocketException unknown) { return false; }
    }
    /** By the interface's name, as Android and Linux name them (wlan0, eth0, ap0, swlan0; tun0, wg0, tailscale0). */
    static boolean homeLink(String name, boolean loopback, boolean allowVpn) {
        if (loopback) return true;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("eth") || n.startsWith("ap") || n.startsWith("en") || n.startsWith("wl")) return true;
        return allowVpn && (n.startsWith("tun") || n.startsWith("wg") || n.startsWith("tailscale") || n.startsWith("ppp"));
    }
    static boolean allowed(InetAddress a, boolean allowVpn) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address && (b[0] & 0xfe) == 0xfc) return true;   // unique local fc00::/7
        if (a instanceof Inet6Address && ((Inet6Address) a).isIPv4CompatibleAddress()) return false;
        return allowVpn && a instanceof Inet4Address && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }

    // ------------------------------------------------------------------ requests

    private static final class Request {
        String method = "", path = "", body = ""; final Map<String, String> query = new HashMap<>(), headers = new HashMap<>();
    }

    private void handle(Socket socket) {
        boolean keep = false;
        try {
            socket.setTcpNoDelay(true);
            // One deadline for the whole request, not only for each read: a client sending a byte every few seconds would
            // pass every per-read timeout and, with a few more like it, hold every connection.
            InputStream in = new BufferedInputStream(new Deadline(socket, System.currentTimeMillis() + requestMs)); OutputStream out = socket.getOutputStream();
            Request r = read(in);
            if (r == null) { send(out, 400, "Bad request"); return; }
            served.incrementAndGet();
            keep = route(r, socket, out);
        } catch (IOException | RuntimeException failed) {
            // A closed connection, a request too slow or a malformed one: nothing to tell.
        } finally { if (!keep) close(socket); }
    }

    /** The socket's input under one deadline: each read waits until then at most (and 10 s at most). */
    private static final class Deadline extends FilterInputStream {
        private final Socket socket; private final long until;
        Deadline(Socket socket, long until) throws IOException { super(socket.getInputStream()); this.socket = socket; this.until = until; }
        private void arm() throws IOException {
            long left = until - System.currentTimeMillis();
            if (left <= 0) throw new SocketTimeoutException("the request took too long");
            socket.setSoTimeout((int) Math.min(10_000, left));
        }
        @Override public int read() throws IOException { arm(); return super.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { arm(); return super.read(b, off, len); }
    }

    private static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(); int b, last4 = 0;
        while ((b = in.read()) != -1) {
            head.write(b); last4 = (last4 << 8) | b;
            if (last4 == 0x0d0a0d0a) break;
            if (head.size() > 16 * 1024) return null;
        }
        if (b == -1) return null;
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length != 3 || !first[2].startsWith("HTTP/1.")) return null;
        Request r = new Request(); r.method = first[0];
        String target = first[1]; int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) for (String pair : target.substring(q + 1).split("&")) {
            int eq = pair.indexOf('='); if (eq <= 0) continue;
            try { r.query.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8")); }
            catch (IllegalArgumentException badEscape) { return null; }   // "%zz": answered as a bad request, not a dropped connection
        }
        for (int i = 1; i < lines.length; i++) { int c = lines[i].indexOf(':'); if (c > 0) r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim()); }
        int length = 0; try { length = Integer.parseInt(r.headers.getOrDefault("content-length", "0")); } catch (NumberFormatException bad) { return null; }
        if (length < 0 || length > 4096) return null;
        byte[] body = new byte[length]; int got = 0;
        while (got < length) { int n = in.read(body, got, length - got); if (n < 0) return null; got += n; }
        r.body = new String(body, StandardCharsets.UTF_8);
        return r;
    }

    /** Answers a request; true when the connection stays open (events, a camera stream). */
    private boolean route(Request r, Socket socket, OutputStream out) throws IOException {
        long now = System.currentTimeMillis();
        String p = r.path;
        if (r.method.equals("OPTIONS")) {
            // Pairing grants other web pages nothing (see pair), so a page elsewhere cannot even send the request.
            if (p.startsWith("/api/v1/pair")) { write(out, 204, "No Content", null, null, null, false); return false; }
            write(out, 204, "No Content", null, null, extra("Access-Control-Allow-Methods", "GET, POST, OPTIONS", "Access-Control-Allow-Headers", "Authorization, Content-Type", "Access-Control-Max-Age", "600"));
            return false;
        }
        if (r.method.equals("GET") && (p.equals("/") || p.equals("/index.html"))) {
            byte[] page = host.asset("index.html");
            if (page == null) { send(out, 404, "No page"); return false; }
            write(out, 200, "OK", "text/html; charset=utf-8", page, extra("Content-Security-Policy", policy(page), "Cache-Control", "no-store"));
            return false;
        }
        if (r.method.equals("GET") && p.equals("/favicon.ico")) { write(out, 204, "No Content", null, null, null); return false; }
        if (!p.startsWith("/api/v1/")) { send(out, 404, "Not found"); return false; }
        String api = p.substring("/api/v1/".length());
        try {
            if (r.method.equals("GET") && api.equals("info")) { json(out, 200, info()); return false; }
            // Pairing answers without CORS: the phone's own page asks from its own address and apps are not web pages, while
            // a page elsewhere that someone on this Wi-Fi happens to open must not be able to ask, or read a token handed out.
            if (r.method.equals("POST") && api.equals("pair")) { pair(r, socket, out, now); return false; }
            if (r.method.equals("GET") && api.startsWith("pair/")) {
                JSONObject state = auth.poll(api.substring(5), now);
                if (state == null) send(out, 404, "No such pairing (it may have run out: they last five minutes)", false); else json(out, 200, state, false);
                return false;
            }
            // Everything else needs a paired device.
            String bearer = r.headers.getOrDefault("authorization", "");
            IntegrationAuth.Device device = bearer.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.check(bearer.substring(7).trim(), now) : null;
            if (device == null && r.method.equals("GET") && r.query.containsKey("ticket")) device = auth.redeem(r.query.get("ticket"), now);
            if (device == null) { write(out, 401, "Unauthorized", "application/json", error("Pair this device first (POST /api/v1/pair), then send its token as Authorization: Bearer …"), extra("WWW-Authenticate", "Bearer")); return false; }
            if (r.method.equals("POST") && api.equals("tickets")) {
                if (!bearer.regionMatches(true, 0, "Bearer ", 0, 7)) { send(out, 403, "A ticket is made with a token, not another ticket"); return false; }
                json(out, 201, new JSONObject().put("ticket", auth.ticket(device, now)).put("expires_in", IntegrationAuth.TICKET_MS / 1000)); return false;
            }
            if (!r.method.equals("GET")) { send(out, 405, "Read only: this API only answers GET (and POST for pairing and tickets)"); return false; }
            if (api.equals("status")) { json(out, 200, host.status()); return false; }
            if (api.equals("cameras")) { json(out, 200, new JSONObject().put("cameras", host.cameras())); return false; }
            if (api.equals("events")) return events(socket, out, device);
            if (api.startsWith("cameras/")) {
                String[] parts = api.split("/");
                if (parts.length == 3 && known(parts[1])) {
                    if (parts[2].equals("snapshot.jpg")) { snapshot(out, parts[1]); return false; }
                    if (parts[2].equals("stream.mjpeg")) return stream(socket, out, parts[1], fps(r.query.get("fps")), device);
                }
            }
            send(out, 404, "Not found");
            return false;
        } catch (JSONException impossible) { send(out, 500, "Could not answer"); return false; }
    }

    /**
     * The page's security policy: its own inline script allowed by its hash and nothing else, so even text that slipped
     * into the page as markup (an injected onerror=…) could not run.
     */
    static String policy(byte[] page) {
        StringBuilder hashes = new StringBuilder();
        Matcher m = Pattern.compile("<script>([\\s\\S]*?)</script>").matcher(new String(page, StandardCharsets.UTF_8));
        try {
            while (m.find()) {
                String script = m.group(1).replace("\r\n", "\n").replace('\r', '\n');   // line ends as the browser reads them
                hashes.append(" 'sha256-").append(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8)))).append('\'');
            }
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        return "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src" + (hashes.length() == 0 ? " 'none'" : hashes)
            + "; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    }

    private JSONObject info() throws JSONException {
        return new JSONObject().put("app", "Link Workshop").put("app_version", host.appVersion()).put("api", API)
            .put("schemas", new JSONArray().put(IntegrationStatus.SCHEMA).put("linkworkshop.event/1")).put("auth", "bearer").put("pairing", true).put("read_only", true)
            .put("endpoints", new JSONArray().put("GET /api/v1/status").put("GET /api/v1/events").put("GET /api/v1/cameras").put("GET /api/v1/cameras/{id}/snapshot.jpg")
                .put("GET /api/v1/cameras/{id}/stream.mjpeg?fps=5").put("POST /api/v1/tickets").put("POST /api/v1/pair").put("GET /api/v1/pair/{id}"));
    }

    private void pair(Request r, Socket socket, OutputStream out, long now) throws IOException, JSONException {
        // Only JSON: a web page elsewhere can send a plain form or text without asking first, but not JSON.
        if (!r.headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT).startsWith("application/json")) { send(out, 415, "Send {\"name\": \"…\"} as JSON (Content-Type: application/json)", false); return; }
        String from = socket.getInetAddress().getHostAddress();
        synchronized (pairAsked) {
            Long last = pairAsked.get(from);
            if (last != null && now - last < 2_000) { send(out, 429, "Too many pairing requests: wait a moment", false); return; }
            pairAsked.put(from, now); if (pairAsked.size() > 100) pairAsked.clear();
        }
        String name = "";
        try { name = r.body.isEmpty() ? "" : new JSONObject(r.body).optString("name"); } catch (JSONException notJson) { send(out, 400, "Send {\"name\": \"…\"}", false); return; }
        IntegrationAuth.Pairing pairing = auth.request(name, now);
        if (pairing == null) { send(out, 429, "Too many devices are waiting to be approved on the phone", false); return; }
        json(out, 202, new JSONObject().put("id", pairing.id).put("code", pairing.code).put("poll", "/api/v1/pair/" + pairing.id)
            .put("expires_in", IntegrationAuth.PAIRING_MS / 1000).put("message", "Approve this device on the phone: Link Workshop › Settings › Automations & integrations › Other apps. The phone shows the same code."), false);
    }

    private boolean known(String camera) {
        JSONArray all = host.cameras();
        for (int i = 0; i < all.length(); i++) { JSONObject c = all.optJSONObject(i); if (c != null && camera.equals(c.optString("id"))) return true; }
        return false;
    }
    static int fps(String asked) { try { return Math.max(1, Math.min(MAX_FPS, Integer.parseInt(asked))); } catch (RuntimeException none) { return DEFAULT_FPS; } }

    private void snapshot(OutputStream out, String camera) throws IOException, JSONException {
        CameraHub.Frame frame = hub.latest(camera);
        if (frame == null || System.currentTimeMillis() - frame.at > 10_000) {
            hub.acquire(camera);
            try { CameraHub.Frame fresh = hub.await(camera, frame == null ? 0 : frame.seq, FIRST_FRAME_MS); if (fresh != null) frame = fresh; }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            finally { hub.release(camera); }
        }
        if (frame == null) { json(out, 503, problem(camera)); return; }
        write(out, 200, "OK", "image/jpeg", frame.jpeg, extra("Cache-Control", "no-store", "X-Frame-Age-Ms", String.valueOf(Math.max(0, System.currentTimeMillis() - frame.at))));
    }
    private JSONObject problem(String camera) throws JSONException {
        String why = hub.problem(camera);
        return new JSONObject().put("error", "No picture from this camera yet" + (why.isEmpty() ? "" : " (" + why + ")") + ".");
    }

    private boolean stream(Socket socket, OutputStream out, String camera, int fps, IntegrationAuth.Device device) throws IOException, JSONException {
        if (streams.incrementAndGet() > MAX_STREAMS) { streams.decrementAndGet(); send(out, 503, "Too many camera streams open"); return false; }
        Client client = new Client(socket, out, device.id);
        synchronized (writers) { writers.add(client); }
        try {
            hub.acquire(camera);   // counts the viewer first, so the release below always matches
            CameraHub.Frame frame = hub.latest(camera);
            if (frame == null || System.currentTimeMillis() - frame.at > 10_000) frame = hub.await(camera, frame == null ? 0 : frame.seq, FIRST_FRAME_MS);
            if (frame == null) { json(out, 503, problem(camera)); return false; }
            socket.setSoTimeout(0);
            client.write(headers(200, "OK", "multipart/x-mixed-replace; boundary=lwframe", -1, extra("Cache-Control", "no-store", "Connection", "close"), true));
            long gap = 1000L / fps, sentAt = 0;
            while (!closed && !socket.isClosed()) {
                long wait = sentAt + gap - System.currentTimeMillis(); if (wait > 0) Thread.sleep(wait);
                ByteArrayOutputStream part = new ByteArrayOutputStream();
                part.write(("--lwframe\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.jpeg.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                part.write(frame.jpeg); part.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                client.write(part.toByteArray()); sentAt = System.currentTimeMillis();
                CameraHub.Frame next = hub.await(camera, frame.seq, 10_000);
                if (next != null) frame = next;   // none for 10 s: the last one again, so the client knows the stream is alive
            }
            return false;
        } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return false; }
        finally { synchronized (writers) { writers.remove(client); } hub.release(camera); streams.decrementAndGet(); }
    }

    // ------------------------------------------------------------------ events (Server-Sent Events)

    private boolean events(Socket socket, OutputStream out, IntegrationAuth.Device device) throws IOException {
        Client client = new Client(socket, out, device.id);
        boolean full;
        // Counted, given the status now and added in one step: never more than MAX_EVENTS, and no change missed in between.
        synchronized (listeners) {
            full = listeners.size() >= MAX_EVENTS;
            if (!full) {
                JSONObject status = host.status();
                client.queue.offer("retry: 3000\n\n"); client.queue.offer(sse("status", status.toString())); client.statusKey = comparable(status);
                listeners.add(client);
            }
        }
        if (full) { send(out, 503, "Too many event streams open"); return false; }
        synchronized (writers) { writers.add(client); }
        try {
            socket.setSoTimeout(0);
            client.write(headers(200, "OK", "text/event-stream; charset=utf-8", -1, extra("Cache-Control", "no-store", "Connection", "close"), true));
            while (!closed && !socket.isClosed()) {
                String message = client.queue.poll(PING_MS, TimeUnit.MILLISECONDS);
                client.write((message == null ? ": ping\n\n" : message).getBytes(StandardCharsets.UTF_8));
            }
        } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
        catch (IOException gone) { }
        finally { synchronized (listeners) { listeners.remove(client); } synchronized (writers) { writers.remove(client); } }
        return false;
    }
    /**
     * The status changed: sent to each event listener whose last status differs (times aside). Kept per listener: one that
     * joined while the status was elsewhere still hears it come back.
     */
    void statusChanged() {
        if (listeners() == 0) return;
        JSONObject status = host.status();
        String key = comparable(status), message = null;
        synchronized (listeners) {
            for (Client c : listeners) {
                if (key.equals(c.statusKey)) continue;
                if (message == null) message = sse("status", status.toString());
                c.statusKey = key; queue(c, message);
            }
        }
    }
    /** A print event (IntegrationStatus.event). */
    void event(JSONObject event) { String message = sse("event", event.toString()); synchronized (listeners) { for (Client c : listeners) queue(c, message); } }
    private static void queue(Client c, String message) {
        // A slow listener keeps only the newest status, never a growing backlog.
        if (c.queue.size() > 20) c.queue.removeIf(m -> m.startsWith("event: status"));
        c.queue.offer(message);
    }
    static String comparable(JSONObject status) {
        try {
            JSONObject copy = new JSONObject(status.toString()); copy.remove("updated_at");
            JSONObject c = copy.optJSONObject("connection"); if (c != null) { c.remove("age_s"); c.remove("status_at"); }
            JSONObject p = copy.optJSONObject("print"); if (p != null) { p.remove("elapsed_s"); p.remove("done_at"); }
            return copy.toString();
        } catch (JSONException impossible) { return status.toString(); }
    }
    static String sse(String event, String data) { return "event: " + event + "\ndata: " + data.replace("\n", "\ndata: ") + "\n\n"; }

    // ------------------------------------------------------------------ responses

    private static Map<String, String> extra(String... pairs) { Map<String, String> m = new LinkedHashMap<>(); for (int i = 0; i + 1 < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]); return m; }
    private static byte[] error(String words) { try { return new JSONObject().put("error", words).toString().getBytes(StandardCharsets.UTF_8); } catch (JSONException impossible) { return new byte[0]; } }
    private static void send(OutputStream out, int code, String words) throws IOException { send(out, code, words, true); }
    /** `cors` false: no Access-Control-Allow-Origin, so web pages elsewhere cannot read the answer (pairing). */
    private static void send(OutputStream out, int code, String words, boolean cors) throws IOException { write(out, code, reason(code), "application/json", error(words), null, cors); }
    private static void json(OutputStream out, int code, JSONObject body) throws IOException { json(out, code, body, true); }
    private static void json(OutputStream out, int code, JSONObject body, boolean cors) throws IOException { write(out, code, reason(code), "application/json", body.toString().getBytes(StandardCharsets.UTF_8), extra("Cache-Control", "no-store"), cors); }
    private static void write(OutputStream out, int code, String reason, String type, byte[] body, Map<String, String> extra) throws IOException { write(out, code, reason, type, body, extra, true); }
    private static void write(OutputStream out, int code, String reason, String type, byte[] body, Map<String, String> extra, boolean cors) throws IOException {
        out.write(headers(code, reason, type, body == null ? 0 : body.length, extra, cors));
        if (body != null) out.write(body);
        out.flush();
    }
    private static byte[] headers(int code, String reason, String type, int length, Map<String, String> extra, boolean cors) {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        if (length >= 0) h.append("Content-Length: ").append(length).append("\r\n");
        if (cors) h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        if (extra == null || !extra.containsKey("Connection")) h.append("Connection: close\r\n");
        return h.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
    }
    private static String reason(int code) {
        switch (code) { case 200: return "OK"; case 201: return "Created"; case 202: return "Accepted"; case 400: return "Bad Request"; case 401: return "Unauthorized"; case 403: return "Forbidden";
            case 404: return "Not Found"; case 405: return "Method Not Allowed"; case 415: return "Unsupported Media Type"; case 429: return "Too Many Requests";
            case 500: return "Internal Server Error"; case 503: return "Service Unavailable"; default: return "Error"; }
    }
    private static void close(Socket s) { try { s.close(); } catch (IOException ignored) { } }

    @Override public synchronized void close() {
        closed = true;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        List<Client> all; synchronized (writers) { all = new ArrayList<>(writers); }
        for (Client c : all) close(c.socket);
        timer.shutdownNow(); pool.shutdownNow();
    }
}
