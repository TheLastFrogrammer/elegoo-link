package io.github.thelastfrogrammer.elink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Who may read the local API: paired devices, each with its own token. Only a hash of each token is kept (a backup or a
 * look at the app's files does not give a token away); the token itself is handed out once, when the device is added.
 * A device pairs by asking (it gets a 4-digit code to show) and the person approving it on the phone, where the same code
 * shows; or the person adds one on the phone and types or scans its token. Tickets are short-lived stand-ins for a token
 * where a client cannot send a header (an image's address, a browser's EventSource). Independent of Android.
 */
final class IntegrationAuth {
    static final int MAX_DEVICES = 20, MAX_PENDING = 3;
    static final long PAIRING_MS = 5 * 60_000L, TICKET_MS = 60_000L, SEEN_SAVE_MS = 60_000L;
    static final String PENDING = "pending", APPROVED = "approved", DENIED = "denied";

    interface Storage { String load(); void save(String json); }

    static final class Device {
        String id = "", name = "", hash = ""; long created, lastSeen;
        JSONObject json() throws JSONException { return new JSONObject().put("id", id).put("name", name).put("hash", hash).put("created", created).put("last_seen", lastSeen); }
        static Device of(JSONObject o) { Device d = new Device(); d.id = o.optString("id"); d.name = o.optString("name"); d.hash = o.optString("hash"); d.created = o.optLong("created"); d.lastSeen = o.optLong("last_seen"); return d; }
    }
    /** A device asking to pair: its secret poll id, the name it gave, the code both screens show, and how it went. */
    static final class Pairing {
        final String id, name, code; final long created; String state = PENDING; String token;
        Pairing(String id, String name, String code, long created) { this.id = id; this.name = name; this.code = code; this.created = created; }
    }
    private static final class Ticket { final String deviceId; final long until; Ticket(String deviceId, long until) { this.deviceId = deviceId; this.until = until; } }

    private final Storage storage;
    private final SecureRandom random = new SecureRandom();
    private final List<Device> devices = new ArrayList<>();
    private final Map<String, Pairing> pairings = new LinkedHashMap<>();
    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    private long savedSeen;
    /** Called when a device asks to pair (the phone shows it to the person). */
    volatile java.util.function.Consumer<Pairing> onRequest;

    IntegrationAuth(Storage storage) {
        this.storage = storage;
        String kept = storage.load();
        try { JSONArray a = new JSONArray(kept == null || kept.isEmpty() ? "[]" : kept); for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) devices.add(Device.of(a.optJSONObject(i))); }
        catch (JSONException unreadable) { devices.clear(); }
    }

    synchronized List<Device> devices() { List<Device> out = new ArrayList<>(); for (Device d : devices) { Device c = new Device(); c.id = d.id; c.name = d.name; c.created = d.created; c.lastSeen = d.lastSeen; out.add(c); } return out; }

    /** Adds a device and returns its token (shown once), or null when there are already MAX_DEVICES. */
    synchronized String add(String name, long now) {
        if (devices.size() >= MAX_DEVICES) return null;
        String token = "lw1_" + secret(24);
        Device d = new Device(); d.id = secret(9); d.name = clean(name); d.hash = hash(token); d.created = now; devices.add(d); save();
        return token;
    }
    synchronized void revoke(String id) { devices.removeIf(d -> d.id.equals(id)); tickets.values().removeIf(t -> t.deviceId.equals(id)); save(); }

    /** The device a token belongs to, or null. */
    synchronized Device check(String token, long now) {
        if (token == null || !token.startsWith("lw1_") || token.length() > 100) return null;
        byte[] wanted = hash(token).getBytes(StandardCharsets.US_ASCII);
        for (Device d : devices) if (MessageDigest.isEqual(wanted, d.hash.getBytes(StandardCharsets.US_ASCII))) { seen(d, now); return d; }
        return null;
    }
    private void seen(Device d, long now) { d.lastSeen = now; if (now - savedSeen > SEEN_SAVE_MS) { savedSeen = now; save(); } }

    /** A device asks to pair; null when too many are waiting already. */
    synchronized Pairing request(String name, long now) {
        expire(now);
        int waiting = 0; for (Pairing p : pairings.values()) if (PENDING.equals(p.state)) waiting++;
        if (waiting >= MAX_PENDING) return null;
        Pairing p = new Pairing(secret(18), clean(name), String.format(Locale.ROOT, "%04d", random.nextInt(10_000)), now);
        pairings.put(p.id, p);
        java.util.function.Consumer<Pairing> tell = onRequest; if (tell != null) tell.accept(p);
        return p;
    }
    /** What a pairing device sees when it asks: pending, approved with the token (given once, then the pairing is gone), denied; null unknown. */
    synchronized JSONObject poll(String id, long now) {
        expire(now);
        Pairing p = id == null ? null : pairings.get(id);
        if (p == null) return null;
        try {
            JSONObject out = new JSONObject().put("state", p.state);
            if (APPROVED.equals(p.state)) { out.put("token", p.token); pairings.remove(id); }
            else if (DENIED.equals(p.state)) pairings.remove(id);
            else out.put("code", p.code).put("expires_in", Math.max(0, (p.created + PAIRING_MS - now) / 1000));
            return out;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    synchronized List<Pairing> pending(long now) { expire(now); List<Pairing> out = new ArrayList<>(); for (Pairing p : pairings.values()) if (PENDING.equals(p.state)) out.add(p); return out; }
    /** The person approves on the phone: the device is added and its token waits for the device's next poll. */
    synchronized boolean approve(String id, long now) {
        expire(now);
        Pairing p = pairings.get(id); if (p == null || !PENDING.equals(p.state)) return false;
        String token = add(p.name, now); if (token == null) { p.state = DENIED; return false; }
        p.token = token; p.state = APPROVED; return true;
    }
    synchronized void deny(String id) { Pairing p = pairings.get(id); if (p != null) p.state = DENIED; }
    private void expire(long now) {
        for (Iterator<Pairing> it = pairings.values().iterator(); it.hasNext(); ) if (now - it.next().created > PAIRING_MS) it.remove();
        for (Iterator<Ticket> it = tickets.values().iterator(); it.hasNext(); ) if (now > it.next().until) it.remove();
    }

    /** A ticket for a device: good for any read for TICKET_MS (a stream it opens keeps going after). */
    synchronized String ticket(Device d, long now) { expire(now); if (tickets.size() > 200) tickets.clear(); String t = secret(18); tickets.put(t, new Ticket(d.id, now + TICKET_MS)); return t; }
    synchronized Device redeem(String ticket, long now) {
        expire(now);
        Ticket t = ticket == null ? null : tickets.get(ticket); if (t == null) return null;
        for (Device d : devices) if (d.id.equals(t.deviceId)) { seen(d, now); return d; }
        return null;
    }

    private void save() {
        JSONArray a = new JSONArray();
        try { for (Device d : devices) a.put(d.json()); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        storage.save(a.toString());
    }
    private String secret(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String hash(String token) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** A device's name as it may show on the phone: printable, at most 40 characters. */
    static String clean(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (n.length() > 40) n = n.substring(0, 40);
        return n.isEmpty() ? "A device" : n;
    }
}
