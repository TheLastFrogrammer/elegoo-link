package io.github.thelastfrogrammer.elink;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A GigE Vision thermal camera on the loopback, as the app sees an Ax5: registers, a zipped description, a stream. */
final class FakeGigeCamera implements AutoCloseable {
    static final long XML_AT = 0x10000000L, PIXEL = 0x30024, IR = 0x20000, RATE = 0x20004, WIDTH = 0x30000, HEIGHT = 0x30004, START = 0x40000, STOP = 0x40004, LOCK = 0x40008;
    static final int W = 80, H = 64;
    static final String XML = "<?xml version=\"1.0\"?>\n<RegisterDescription xmlns=\"http://www.genicam.org/GenApi/Version_1_0\" ModelName=\"AX5\">"
        + "<Enumeration Name=\"PixelFormat\"><EnumEntry Name=\"Mono8\"><Value>0x01080001</Value></EnumEntry><EnumEntry Name=\"Mono16\"><Value>0x01100007</Value></EnumEntry><pValue>PixelFormatReg</pValue></Enumeration>"
        + "<IntReg Name=\"PixelFormatReg\"><Address>0x30024</Address><Length>4</Length><pPort>Device</pPort><Endianess>BigEndian</Endianess></IntReg>"
        + "<Enumeration Name=\"IRFormat\"><EnumEntry Name=\"Radiometric\"><Value>0</Value></EnumEntry><EnumEntry Name=\"TemperatureLinear100mK\"><Value>1</Value></EnumEntry><EnumEntry Name=\"TemperatureLinear10mK\"><Value>2</Value></EnumEntry><pValue>IRFormatReg</pValue></Enumeration>"
        + "<MaskedIntReg Name=\"IRFormatReg\"><Address>0x20000</Address><Length>4</Length><pPort>Device</pPort><LSB>31</LSB><MSB>30</MSB><Endianess>BigEndian</Endianess></MaskedIntReg>"
        + "<Enumeration Name=\"IRFrameRate\"><EnumEntry Name=\"Rate30Hz\"><Value>0</Value></EnumEntry><EnumEntry Name=\"Rate15Hz\"><Value>1</Value></EnumEntry><EnumEntry Name=\"Rate7_5Hz\"><Value>2</Value></EnumEntry><pValue>RateReg</pValue></Enumeration>"
        + "<IntReg Name=\"RateReg\"><pAddress>RateBase</pAddress><Address>4</Address><Length>4</Length><pPort>Device</pPort><Endianess>BigEndian</Endianess></IntReg>"
        + "<Integer Name=\"RateBase\"><Value>0x20000</Value></Integer>"
        + "<Integer Name=\"Width\"><pValue>WidthReg</pValue></Integer><IntReg Name=\"WidthReg\"><Address>0x30000</Address><Length>4</Length><pPort>Device</pPort><Endianess>BigEndian</Endianess></IntReg>"
        + "<Integer Name=\"Height\"><pValue>HeightReg</pValue></Integer><IntReg Name=\"HeightReg\"><Address>0x30004</Address><Length>4</Length><pPort>Device</pPort><Endianess>BigEndian</Endianess></IntReg>"
        + "<Command Name=\"AcquisitionStart\"><pValue>StartReg</pValue><CommandValue>1</CommandValue></Command><IntReg Name=\"StartReg\"><Address>0x40000</Address><Length>4</Length><pPort>Device</pPort><Endianess>BigEndian</Endianess></IntReg>"
        + "<Command Name=\"AcquisitionStop\"><pValue>StopReg</pValue><CommandValue>1</CommandValue></Command><IntReg Name=\"StopReg\"><Address>0x40004</Address><Length>4</Length><pPort>Device</pPort><Endianess>BigEndian</Endianess></IntReg>"
        + "<Integer Name=\"TLParamsLocked\"><pValue>LockReg</pValue></Integer><IntReg Name=\"LockReg\"><Address>0x40008</Address><Length>4</Length><pPort>Device</pPort><Endianess>BigEndian</Endianess></IntReg>"
        + "<Integer Name=\"Computed\"><pValue>Formula</pValue></Integer><IntSwissKnife Name=\"Formula\"><Formula>1+1</Formula></IntSwissKnife>"
        + "</RegisterDescription>";

    final Map<Long, Long> regs = new ConcurrentHashMap<>();
    final byte[] zip;
    final DatagramSocket socket;
    volatile boolean streaming, closed, refuseControl;
    /** Drop every payload packet with this id (0: none), to show lost pictures. */
    volatile int dropPacket;
    volatile int pictures;
    final Thread thread;

    FakeGigeCamera() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) { z.putNextEntry(new ZipEntry("FLIR_AX5.xml")); z.write(XML.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        zip = bytes.toByteArray();
        regs.put(WIDTH, (long) W); regs.put(HEIGHT, (long) H); regs.put(PIXEL, 0x01080001L); regs.put(IR, 0L); regs.put(RATE, 0L);
        socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        thread = new Thread(this::serve, "fake-gige"); thread.setDaemon(true); thread.start();
    }
    int port() { return socket.getLocalPort(); }

    private byte[] memory(long address, int count) {
        byte[] out = new byte[count];
        if (address >= 0x200 && address < 0x400) {
            byte[] url = ("Local:FLIR_AX5.zip;" + Long.toHexString(XML_AT) + ";" + Integer.toHexString(zip.length)).getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < count; i++) { int k = (int) (address - 0x200) + i; out[i] = k < url.length ? url[k] : 0; }
        } else if (address >= XML_AT) {
            for (int i = 0; i < count; i++) { long k = address - XML_AT + i; out[i] = k < zip.length ? zip[(int) k] : 0; }
        } else for (int i = 0; i < count; i += 4) { long v = regs.getOrDefault(address + i, 0L); for (int b = 0; b < 4 && i + b < count; b++) out[i + b] = (byte) (v >>> (24 - 8 * b)); }
        return out;
    }

    private void serve() {
        byte[] buffer = new byte[2048];
        while (!closed) {
            try {
                DatagramPacket in = new DatagramPacket(buffer, buffer.length); socket.receive(in);
                ByteBuffer b = ByteBuffer.wrap(in.getData(), 0, in.getLength());
                b.get(); b.get(); int cmd = b.getShort() & 0xffff, len = b.getShort() & 0xffff, id = b.getShort() & 0xffff;
                int status = 0; ByteBuffer reply = ByteBuffer.allocate(1024);
                if (cmd == GigeVision.DISCOVERY) {
                    byte[] p = new byte[0xF8];
                    put(p, 0x48, "FLIR Systems AB"); put(p, 0x68, "FLIR AX5"); put(p, 0xD8, "12345678");
                    p[0x24] = 127; p[0x27] = 1;
                    reply.put(p);
                } else if (cmd == GigeVision.READREG) {
                    for (int i = 0; i < len / 4; i++) reply.putInt((int) (long) regs.getOrDefault(b.getInt() & 0xffffffffL, 0L));
                } else if (cmd == GigeVision.WRITEREG) {
                    long a = b.getInt() & 0xffffffffL, v = b.getInt() & 0xffffffffL;
                    if (a == GigeVision.CCP && refuseControl && v != 0) status = 0x8006;
                    else { regs.put(a, v); if (a == START && v == 1) startStream(); if (a == STOP && v == 1) streaming = false; }
                    reply.putShort((short) 0).putShort((short) 1);
                } else if (cmd == GigeVision.READMEM) {
                    long a = b.getInt() & 0xffffffffL; b.getShort(); int n = b.getShort() & 0xffff;
                    reply.putInt((int) a).put(memory(a, n));
                } else if (cmd == GigeVision.WRITEMEM) {
                    long a = b.getInt() & 0xffffffffL; for (int i = 0; i + 4 <= len - 4; i += 4) regs.put(a + i, b.getInt() & 0xffffffffL);
                    reply.putShort((short) 0).putShort((short) (len - 4));
                } else status = 0x8001;
                int size = reply.position();
                ByteBuffer out = ByteBuffer.allocate(8 + size).putShort((short) status).putShort((short) (cmd + 1)).putShort((short) size).putShort((short) id);
                out.put(reply.array(), 0, size);
                socket.send(new DatagramPacket(out.array(), out.capacity(), in.getAddress(), in.getPort()));
            } catch (Exception stop) { if (closed) return; }
        }
    }
    private static void put(byte[] p, int at, String s) { byte[] b = s.getBytes(StandardCharsets.US_ASCII); System.arraycopy(b, 0, p, at, b.length); }

    /** Temperatures: 30 °C everywhere, 220 °C at (60, 10), as 10 mK or 100 mK steps by the IR format set. */
    static int value(int x, int y, long ir) {
        double c = x == 60 && y == 10 ? 220 : 30;
        return (int) Math.round((c + 273.15) / (ir == 1 ? 0.1 : 0.01));
    }
    private void startStream() {
        if (streaming) return;
        streaming = true;
        Thread t = new Thread(() -> {
            int block = 1;
            try (DatagramSocket out = new DatagramSocket()) {
                while (streaming && !closed) {
                    InetAddress to = InetAddress.getByAddress(ByteBuffer.allocate(4).putInt((int) (long) regs.getOrDefault((long) GigeVision.SCDA0, 0L)).array());
                    int port = (int) (long) regs.getOrDefault((long) GigeVision.SCP0, 0L), size = (int) (regs.getOrDefault((long) GigeVision.SCPS0, 576L) & 0xffff);
                    long pixel = regs.get(PIXEL), ir = regs.get(IR) & 3;
                    boolean wide = pixel == 0x01100007L;
                    ByteBuffer frame = ByteBuffer.allocate(W * H * (wide ? 2 : 1));
                    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) { int v = wide ? value(x, y, ir) : (x == 60 && y == 10 ? 255 : 40); if (wide) { frame.put((byte) v).put((byte) (v >> 8)); } else frame.put((byte) v); }
                    ByteBuffer leader = ByteBuffer.allocate(8 + 36).putShort((short) 0).putShort((short) block).putInt(1 << 24)
                        .putShort((short) 0).putShort((short) 1).putInt(0).putInt(0).putInt((int) pixel).putInt(W).putInt(H).putInt(0).putInt(0).putShort((short) 0).putShort((short) 0);
                    out.send(new DatagramPacket(leader.array(), leader.capacity(), to, port));
                    int data = size - GigeVision.OVERHEAD, id = 1;
                    for (int at = 0; at < frame.capacity(); at += data, id++) {
                        int n = Math.min(data, frame.capacity() - at);
                        ByteBuffer p = ByteBuffer.allocate(8 + n).putShort((short) 0).putShort((short) block).putInt(3 << 24 | id); p.put(frame.array(), at, n);
                        if (id != dropPacket) out.send(new DatagramPacket(p.array(), p.capacity(), to, port));
                    }
                    ByteBuffer trailer = ByteBuffer.allocate(16).putShort((short) 0).putShort((short) block).putInt(2 << 24 | id).putShort((short) 0).putShort((short) 1).putInt(H);
                    out.send(new DatagramPacket(trailer.array(), trailer.capacity(), to, port));
                    pictures++; block = block == 0xffff ? 1 : block + 1;
                    Thread.sleep(50);
                }
            } catch (Exception stop) { }
        }, "fake-gige-stream");
        t.setDaemon(true); t.start();
    }
    @Override public void close() { closed = true; streaming = false; socket.close(); }
}
