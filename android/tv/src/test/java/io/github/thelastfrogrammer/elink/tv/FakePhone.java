package io.github.thelastfrogrammer.elink.tv;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the phone's local API over real sockets, answering the way IntegrationServer does (status codes,
 * headers, JSON errors, SSE and multipart framing). Each test says how a request is answered.
 */
final class FakePhone implements AutoCloseable {
    interface Answer { void answer(Request r, OutputStream out) throws Exception; }
    static final class Request {
        final String method, path, auth, body;
        Request(String method, String path, String auth, String body) { this.method = method; this.path = path; this.auth = auth; this.body = body; }
    }

    private final ServerSocket server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    final List<Request> seen = new CopyOnWriteArrayList<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    FakePhone() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!closed) {
                try { Socket s = server.accept(); open.add(s); Thread h = new Thread(() -> handle(s)); h.setDaemon(true); h.start(); }
                catch (IOException stop) { return; }
            }
        });
        t.setDaemon(true); t.start();
    }
    String base() { return "http://127.0.0.1:" + server.getLocalPort(); }
    /** How "METHOD /path" (without the query) is answered. */
    FakePhone on(String route, Answer a) { answers.put(route, a); return this; }
    int count(String path) { int n = 0; for (Request r : seen) if (r.path.startsWith(path)) n++; return n; }
    /** Ends every open connection (the phone app closed). */
    void cut() { for (Socket s : open) try { s.close(); } catch (IOException ignored) { } open.clear(); }

    private void handle(Socket s) {
        try (Socket socket = s) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream head = new ByteArrayOutputStream(); int b, last4 = 0;
            while ((b = in.read()) != -1) { head.write(b); last4 = (last4 << 8) | b; if (last4 == 0x0d0a0d0a) break; }
            String[] lines = head.toString("ISO-8859-1").split("\r\n");
            String[] first = lines[0].split(" ");
            String auth = "", length = "0";
            for (String l : lines) {
                int c = l.indexOf(':'); if (c <= 0) continue;
                String k = l.substring(0, c).trim().toLowerCase(), v = l.substring(c + 1).trim();
                if (k.equals("authorization")) auth = v; if (k.equals("content-length")) length = v;
            }
            byte[] body = new byte[Integer.parseInt(length)]; int got = 0;
            while (got < body.length) { int n = in.read(body, got, body.length - got); if (n < 0) break; got += n; }
            String target = first[1], path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            Request r = new Request(first[0], path, auth, new String(body, StandardCharsets.UTF_8));
            seen.add(r);
            Answer a = answers.get(first[0] + " " + path);
            OutputStream out = socket.getOutputStream();
            if (a == null) json(out, 404, "{\"error\":\"Not found\"}"); else a.answer(r, out);
            out.flush();
        } catch (Exception gone) { /* the client went away */ }
        finally { open.remove(s); }
    }

    static void json(OutputStream out, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(b); out.flush();
    }
    static void streamHead(OutputStream out, String type) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)); out.flush();
    }
    static void sse(OutputStream out, String event, String data) throws IOException { out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }
    static void jpeg(OutputStream out, byte[] jpeg) throws IOException {
        out.write(("--lwframe\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(jpeg); out.write("\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
    }
    /** Whether the request carried this token. */
    static boolean token(Request r, String token) { return r.auth.equals("Bearer " + token); }

    @Override public void close() throws IOException { closed = true; server.close(); cut(); }
}
