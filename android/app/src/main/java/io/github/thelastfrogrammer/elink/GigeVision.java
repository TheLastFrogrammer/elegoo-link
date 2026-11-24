package io.github.thelastfrogrammer.elink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GigE Vision, as far as a thermal camera such as the FLIR A65 needs it: the control protocol (GVCP, UDP port 3956) to find
 * a camera, read and write its registers and memory, and the stream protocol (GVSP) to put its pictures back together.
 * Only the standard's bootstrap registers are used directly; everything camera-specific comes from the camera's own
 * GenICam description (Genicam). Independent of Android.
 */
final class GigeVision {
    static final int PORT = 3956;
    static final int DISCOVERY = 0x0002, READREG = 0x0080, WRITEREG = 0x0082, READMEM = 0x0084, WRITEMEM = 0x0086;
    /** Bootstrap registers (the standard's fixed addresses). */
    static final int FIRST_URL = 0x0200, HEARTBEAT_TIMEOUT = 0x0938, TICK_HIGH = 0x093C, TICK_LOW = 0x0940, CCP = 0x0A00,
        SCP0 = 0x0D00, SCPS0 = 0x0D04, SCPD0 = 0x0D08, SCDA0 = 0x0D18;
    /** Control privilege: this app controls the camera (others may still watch). */
    static final int CONTROL = 2;
    /** Stream packet overhead: IP 20, UDP 8 and GVSP 8 bytes. */
    static final int OVERHEAD = 36;
    static final int MONO8 = 0x01080001, MONO14 = 0x01100025, MONO16 = 0x01100007;
    /** A READMEM may ask for at most this many bytes (a multiple of 4 that fits any network). */
    static final int MEM_CHUNK = 512;

    // ------------------------------------------------------------------ packets (pure)

    /** A command: key 0x42, flags (ack wanted, and "answer by broadcast" for discovery), command, length, request id. */
    static byte[] command(int command, int id, byte[] payload, boolean broadcastAnswer) {
        ByteBuffer b = ByteBuffer.allocate(8 + payload.length);
        b.put((byte) 0x42).put((byte) (0x01 | (broadcastAnswer ? 0x10 : 0))).putShort((short) command).putShort((short) payload.length).putShort((short) id).put(payload);
        return b.array();
    }
    static final class Ack {
        final int status, answer, id; final byte[] payload;
        Ack(int status, int answer, int id, byte[] payload) { this.status = status; this.answer = answer; this.id = id; this.payload = payload; }
    }
    /** An acknowledgement, or null when the bytes are not one. */
    static Ack ack(byte[] data, int length) {
        if (length < 8) return null;
        ByteBuffer b = ByteBuffer.wrap(data, 0, length);
        int status = b.getShort() & 0xffff, answer = b.getShort() & 0xffff, size = b.getShort() & 0xffff, id = b.getShort() & 0xffff;
        if (size > length - 8) return null;
        byte[] payload = new byte[size]; b.get(payload);
        return new Ack(status, answer, id, payload);
    }
    /** A status the camera answered with, in words. */
    static String status(int code) {
        switch (code) {
            case 0x8001: return "the camera does not support that";
            case 0x8002: return "the camera did not accept a value";
            case 0x8003: return "the camera has no such register";
            case 0x8004: return "that setting is read-only";
            case 0x8005: return "the camera wanted an aligned address";
            case 0x8006: return "another program is controlling the camera";
            case 0x8007: return "the camera is busy";
            default: return String.format("the camera answered with error 0x%04X", code);
        }
    }

    /** What a camera says about itself when found (the discovery answer, laid out as its first bootstrap registers). */
    static final class Device {
        String maker = "", model = "", version = "", serial = "", name = ""; InetAddress address;
        /** "FLIR AX5 (A65)" style: maker and model as the camera says them. */
        String label() { String m = (maker + " " + model).trim(); return m.isEmpty() ? "GigE Vision camera" : m; }
    }
    static Device device(byte[] payload, InetAddress from) {
        if (payload.length < 0xF8) return null;
        Device d = new Device();
        d.maker = text(payload, 0x48, 32); d.model = text(payload, 0x68, 32); d.version = text(payload, 0x88, 32);
        d.serial = text(payload, 0xD8, 16); d.name = text(payload, 0xE8, 16);
        byte[] ip = {payload[0x24], payload[0x25], payload[0x26], payload[0x27]};
        try { InetAddress said = InetAddress.getByAddress(ip); d.address = said.isAnyLocalAddress() ? from : said; } catch (IOException impossible) { d.address = from; }
        return d;
    }
    static String text(byte[] b, int at, int size) {
        int end = at; while (end < Math.min(b.length, at + size) && b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.US_ASCII).trim();
    }

    /**
     * Looks for cameras: asks `target` (one address, or 255.255.255.255 for every network the phone is on) and gathers the
     * answers for `waitMs`. Reads only; nothing on a camera changes.
     */
    static List<Device> discover(InetAddress target, int waitMs) throws IOException {
        Map<String, Device> found = new LinkedHashMap<>();
        List<InetAddress> targets = targets(target);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true); socket.setSoTimeout(100);
            // Answers may come by broadcast: a camera with a fixed address on another network can still say where it is.
            byte[] ask = command(DISCOVERY, 1, new byte[0], true);
            long end = System.currentTimeMillis() + waitMs, again = 0;
            byte[] buffer = new byte[1024];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= again) {
                    for (InetAddress to : targets) {
                        try { socket.send(new DatagramPacket(ask, ask.length, to, PORT)); }
                        catch (IOException unreachable) { if (to == target) throw unreachable; }   // another network is down: the rest still count
                    }
                    again = System.currentTimeMillis() + Math.max(200, waitMs / 3);
                }
                DatagramPacket in = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(in); } catch (SocketTimeoutException quiet) { continue; }
                Ack a = ack(in.getData(), in.getLength());
                if (a == null || a.answer != DISCOVERY + 1 || a.status != 0) continue;
                Device d = device(a.payload, in.getAddress());
                if (d != null) found.put(d.address.getHostAddress(), d);
            }
        }
        return new ArrayList<>(found.values());
    }

    /**
     * Where to ask. One address: just it. The limited broadcast (255.255.255.255) leaves by the phone's default network
     * only (its Wi-Fi), so it is also asked on every other network the phone has, by that network's own broadcast address:
     * a camera on a USB Ethernet adapter, shared from the phone (Ethernet tethering), answers there.
     */
    static List<InetAddress> targets(InetAddress target) {
        List<InetAddress> out = new ArrayList<>(); out.add(target);
        if (!isLimitedBroadcast(target)) return out;
        try {
            java.util.Enumeration<java.net.NetworkInterface> all = java.net.NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                java.net.NetworkInterface n = all.nextElement();
                try { if (!n.isUp() || n.isLoopback()) continue; } catch (java.net.SocketException gone) { continue; }
                for (java.net.InterfaceAddress a : n.getInterfaceAddresses()) {
                    InetAddress b = a.getBroadcast();
                    if (b != null && !out.contains(b)) out.add(b);
                }
            }
        } catch (java.net.SocketException none) { }
        return out;
    }
    /** The address is on one of the phone's own networks (same prefix as one of its IPv4 addresses), so it can be reached. */
    static boolean onPhonesNetwork(InetAddress a) {
        byte[] t = a.getAddress(); if (t.length != 4) return false;
        try {
            java.util.Enumeration<java.net.NetworkInterface> all = java.net.NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                java.net.NetworkInterface n = all.nextElement();
                for (java.net.InterfaceAddress ia : n.getInterfaceAddresses()) {
                    byte[] m = ia.getAddress().getAddress(); int bits = ia.getNetworkPrefixLength();
                    if (m.length != 4 || bits <= 0 || bits > 32 || ia.getAddress().isLoopbackAddress()) continue;
                    if (samePrefix(m, t, bits)) return true;
                }
            }
        } catch (java.net.SocketException none) { }
        return false;
    }
    static boolean samePrefix(byte[] a, byte[] b, int bits) {
        for (int i = 0; i < bits; i++) if (((a[i / 8] >> (7 - i % 8)) & 1) != ((b[i / 8] >> (7 - i % 8)) & 1)) return false;
        return true;
    }
    static boolean isLimitedBroadcast(InetAddress a) {
        byte[] b = a.getAddress(); if (b.length != 4) return false;
        for (byte x : b) if (x != (byte) 0xff) return false;
        return true;
    }

    // ------------------------------------------------------------------ control

    /** Register and memory access over one control socket. Each request is retried three times before it fails. */
    static final class Control implements AutoCloseable {
        private final DatagramSocket socket; private final InetAddress camera; private int nextId = 1;
        private final byte[] buffer = new byte[2048];
        Control(InetAddress camera) throws IOException { this(camera, PORT); }
        Control(InetAddress camera, int port) throws IOException {
            this.camera = camera; socket = new DatagramSocket(); socket.connect(camera, port); socket.setSoTimeout(400);
        }
        /** The phone's own address on the way to the camera (where the pictures are to be sent). */
        InetAddress localAddress() { return socket.getLocalAddress(); }

        synchronized Ack exchange(int command, byte[] payload) throws IOException {
            int id = nextId; nextId = nextId >= 0xFFFF ? 1 : nextId + 1;
            byte[] out = GigeVision.command(command, id, payload, false);
            for (int attempt = 0; attempt < 3; attempt++) {
                socket.send(new DatagramPacket(out, out.length));
                long until = System.currentTimeMillis() + 400;
                while (System.currentTimeMillis() < until) {
                    DatagramPacket in = new DatagramPacket(buffer, buffer.length);
                    try { socket.receive(in); } catch (SocketTimeoutException quiet) { break; }
                    Ack a = ack(in.getData(), in.getLength());
                    if (a == null || a.id != id) continue;   // a late answer to an earlier try
                    if (a.status == 0x0100) continue;        // "still working on it": wait for the real answer
                    if (a.status != 0) throw new IOException(status(a.status));
                    if (a.answer != command + 1) throw new IOException("the camera answered something else");
                    return a;
                }
            }
            throw new SocketTimeoutException("the camera did not answer");
        }
        long readReg(long address) throws IOException {
            Ack a = exchange(READREG, ByteBuffer.allocate(4).putInt((int) address).array());
            if (a.payload.length < 4) throw new IOException("short answer from the camera");
            return ByteBuffer.wrap(a.payload).getInt() & 0xffffffffL;
        }
        void writeReg(long address, long value) throws IOException {
            exchange(WRITEREG, ByteBuffer.allocate(8).putInt((int) address).putInt((int) value).array());
        }
        byte[] readMem(long address, int count) throws IOException {
            byte[] out = new byte[count]; int done = 0;
            while (done < count) {
                int ask = Math.min(MEM_CHUNK, (count - done + 3) & ~3);
                Ack a = exchange(READMEM, ByteBuffer.allocate(8).putInt((int) (address + done)).putShort((short) 0).putShort((short) ask).array());
                int got = Math.min(a.payload.length - 4, count - done);
                if (got <= 0) throw new IOException("short answer from the camera");
                System.arraycopy(a.payload, 4, out, done, got); done += got;
            }
            return out;
        }
        void writeMem(long address, byte[] data) throws IOException {
            if (data.length % 4 != 0) throw new IOException("unaligned write");
            exchange(WRITEMEM, ByteBuffer.allocate(4 + data.length).putInt((int) address).put(data).array());
        }
        @Override public void close() { socket.close(); }
    }

    // ------------------------------------------------------------------ stream (pure)

    /** One picture: its size, pixel format and pixel values (unsigned). */
    static final class Image {
        final int width, height, pixelFormat; final int[] values; final int block;
        Image(int width, int height, int pixelFormat, int[] values, int block) { this.width = width; this.height = height; this.pixelFormat = pixelFormat; this.values = values; this.block = block; }
    }
    static int bytesPerPixel(int pixelFormat) { return ((pixelFormat >> 16) & 0xff) > 8 ? 2 : 1; }

    /**
     * Puts pictures back together from stream packets: a leader (size and pixel format), numbered payload packets each
     * carrying `dataPerPacket` bytes (the last one fewer), then a trailer. A picture with a packet missing is dropped and
     * counted: over Wi-Fi a lost packet is not asked for again, the next picture follows soon enough.
     */
    static final class Assembler {
        private final int dataPerPacket;
        private int block = -1, width, height, format, size, received; private byte[] data; private java.util.BitSet got;
        int complete, dropped;
        Assembler(int dataPerPacket) { this.dataPerPacket = dataPerPacket; }

        /** Takes one packet; returns a finished picture when this packet completes one, else null. */
        Image packet(byte[] p, int length) {
            if (length < 8) return null;
            ByteBuffer b = ByteBuffer.wrap(p, 0, length);
            int status = b.getShort() & 0xffff, id = b.getShort() & 0xffff; int infos = b.getInt();
            int type = (infos >>> 24) & 0xff, packet = infos & 0xffffff;
            if ((type & 0x80) != 0 || status != 0 && status != 0x0100) return null;   // extended ids, or an error packet
            if (type == 1) {   // leader
                if (block >= 0 && block != id && data != null) dropped++;
                if (length < 8 + 24) { block = -1; return null; }
                b.getShort(); int payloadType = b.getShort() & 0xffff; b.getInt(); b.getInt();
                if (payloadType != 1) { block = -1; return null; }   // not an image
                format = b.getInt(); width = b.getInt(); height = b.getInt();
                if (width <= 0 || height <= 0 || width > 4096 || height > 4096) { block = -1; return null; }
                size = width * height * bytesPerPixel(format);
                block = id; data = new byte[size]; got = new java.util.BitSet(); received = 0;
                return null;
            }
            if (id != block || data == null) return null;
            if (type == 3) {   // payload
                if (packet < 1 || got.get(packet)) return null;
                int at = (packet - 1) * dataPerPacket, n = Math.min(length - 8, size - at);
                if (at >= size || n <= 0) return null;
                System.arraycopy(p, 8, data, at, n); got.set(packet); received += n;
                return null;
            }
            if (type == 2) {   // trailer
                Image out = null;
                if (received >= size) {
                    int[] values = new int[width * height];
                    if (bytesPerPixel(format) == 2) for (int i = 0; i < values.length; i++) values[i] = (data[2 * i] & 0xff) | (data[2 * i + 1] & 0xff) << 8;
                    else for (int i = 0; i < values.length; i++) values[i] = data[i] & 0xff;
                    out = new Image(width, height, format, values, block); complete++;
                } else dropped++;
                block = -1; data = null;
                return out;
            }
            return null;
        }
    }

    private GigeVision() { }
}
