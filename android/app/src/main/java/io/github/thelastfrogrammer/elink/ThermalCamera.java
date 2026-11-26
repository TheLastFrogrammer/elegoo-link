package io.github.thelastfrogrammer.elink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A GigE Vision thermal camera (a FLIR A65 or another Ax5) as the app watches it: one connection per camera, shared by
 * every view that watches it (the Print tab, print pictures, the toolpath viewer, other apps), since a camera takes orders
 * from one program at a time. It takes control, asks for temperature-linear pictures at the lowest frame rate offered
 * (a Wi-Fi network carries 640 × 512 16-bit pictures at 30 Hz badly), sends them to the phone, keeps the connection
 * alive, and gives control back when the last view closes. Only settings the camera's own description names are written,
 * and only the picture format, frame rate and stream: nothing about the camera's calibration or network is changed.
 * Independent of Android.
 */
final class ThermalCamera {
    interface Listener { void picture(ThermalImage image); void failed(String why); }
    /** A view's hold on a camera; closing it lets the connection go once no view holds it. */
    interface Handle extends AutoCloseable { @Override void close(); }

    /** Stream packets of this size (bytes, with headers) fit a normal 1500-byte network without splitting. */
    static final int PACKET = 1400;
    /** No picture for this long ends the connection with a reason. */
    static final long SILENT_MS = 10_000;
    /** How long a connection stays after its last view closes (a print picture right after closing the view reuses it). */
    static final long LINGER_MS = 3_000;
    static final int HEARTBEAT_MS = 1_000;

    private static final Map<String, Session> SESSIONS = new HashMap<>();
    /** The control port (3956; a test camera elsewhere). */
    static volatile int controlPort = GigeVision.PORT;

    /** Starts watching the camera at `host` (an address or a name on the local network); pictures at most every `everyMs`. */
    static Handle watch(String host, long everyMs, Listener listener) {
        Session s; Viewer v = new Viewer(listener, everyMs);
        synchronized (SESSIONS) {
            s = SESSIONS.get(host);
            if (s == null || s.ended) { s = new Session(host); SESSIONS.put(host, s); s.start(); }
            s.viewers.add(v); s.idleSince = 0;
        }
        Session held = s;
        return () -> { held.viewers.remove(v); synchronized (SESSIONS) { if (held.viewers.isEmpty()) held.idleSince = System.currentTimeMillis(); } };
    }

    private static final class Viewer {
        final Listener listener; final long everyMs; long last;
        Viewer(Listener listener, long everyMs) { this.listener = listener; this.everyMs = everyMs; }
    }

    /** What the camera was set to, for the settings screen's Test: in words. */
    static final class Setup {
        String model = "", format = "", rate = "", temperatures = "";
        int width, height;
        double scale = Double.NaN;
        /** The bits of each pixel that are the reading: the Ax5 sends 14-bit values with the top two bits of 16 set. */
        int mask = 0xFFFF;
        String words() {
            List<String> parts = new ArrayList<>();
            if (!model.isEmpty()) parts.add(model);
            if (width > 0) parts.add(width + "×" + height);
            if (!rate.isEmpty()) parts.add(rate);
            parts.add(Double.isNaN(scale) ? "no temperatures (" + (format.isEmpty() ? "picture only" : format) + ")" : temperatures);
            return String.join(", ", parts);
        }
    }

    /**
     * Sets the camera up for the app through its description: control taken, acquisition stopped while changing, 16-bit
     * pictures, temperature-linear values when offered (10 mK steps preferred), the lowest frame rate. Returns what it set.
     */
    static Setup configure(Genicam g) throws IOException {
        Setup s = new Setup();
        if (g.usable("AcquisitionStop")) try { g.execute("AcquisitionStop"); } catch (IOException notRunning) { }
        if (g.usable("TLParamsLocked")) try { g.set("TLParamsLocked", 0); } catch (IOException fixed) { }
        if (g.usable("PixelFormat")) {
            for (String f : new String[] {"Mono16", "Mono14"}) if (g.entries("PixelFormat").containsKey(f)) { g.choose("PixelFormat", f); s.format = f; break; }
            if (s.format.isEmpty()) s.format = g.chosen("PixelFormat");
        }
        if (g.usable("IRFormat")) {
            Map<String, Long> formats = g.entries("IRFormat");
            String chosen = null;
            for (String want : new String[] {"TemperatureLinear10mK", "TemperatureLinear100mK"}) if (formats.containsKey(want)) { chosen = want; break; }
            if (chosen == null) for (String name : formats.keySet()) if (name.toLowerCase(Locale.ROOT).startsWith("temperaturelinear")) { chosen = name; break; }
            if (chosen != null && g.choose("IRFormat", chosen)) {
                s.scale = scaleFor(chosen, g);
                s.temperatures = "temperatures in " + (s.scale == 0.01 ? "0.01" : "0.1") + " °C steps";
            }
        }
        // The Ax5 (A65 and family) has no IRFormat: its temperature-linear output is a mode, with a resolution of 0.04 K a
        // step (High) or 0.4 K (Low). High is asked for: finer, and still up to 2600 K in 16 bits.
        if (Double.isNaN(s.scale) && g.usable("TemperatureLinearMode") && g.choose("TemperatureLinearMode", "On")) {
            String resolution = "";
            if (g.usable("TemperatureLinearResolution")) { g.choose("TemperatureLinearResolution", "High"); resolution = g.chosen("TemperatureLinearResolution"); }
            s.scale = ax5Scale(resolution); s.mask = AX5_MASK;
            s.temperatures = "temperatures in " + (s.scale == 0.04 ? "0.04" : "0.4") + " °C steps";
        }
        for (String rateFeature : new String[] {"IRFrameRate", "AcquisitionFrameRateEnum", "SensorFrameRate"}) if (g.usable(rateFeature)) {
            String slowest = slowest(g.entries(rateFeature).keySet());
            if (slowest != null && g.choose(rateFeature, slowest)) s.rate = rateWords(slowest);
            break;
        }
        if (g.usable("Width")) s.width = (int) g.get("Width");
        if (g.usable("Height")) s.height = (int) g.get("Height");
        return s;
    }
    /** Kelvin per step: from the format's name, else the resolution setting the Ax5 keeps beside it (High 0.01, Low 0.1). */
    static double scaleFor(String format, Genicam g) throws IOException {
        String f = format.toLowerCase(Locale.ROOT);
        if (f.contains("100mk")) return 0.1;
        if (f.contains("10mk")) return 0.01;
        if (g != null && g.usable("TemperatureLinearResolution")) return g.chosen("TemperatureLinearResolution").toLowerCase(Locale.ROOT).startsWith("high") ? 0.01 : 0.1;
        return 0.01;
    }
    /** The Ax5's 14-bit readings: the top two bits of its 16-bit pixels are set (0xC000) and are not part of the value. */
    static final int AX5_MASK = 0x3FFF;
    static int[] masked(int[] values, int mask) {
        if (mask == 0xFFFF) return values;
        for (int i = 0; i < values.length; i++) values[i] &= mask;
        return values;
    }
    /** Kelvin per step of an Ax5's temperature-linear mode: 0.04 at High resolution, 0.4 at Low. */
    static double ax5Scale(String resolution) { return resolution != null && resolution.toLowerCase(Locale.ROOT).startsWith("low") ? 0.4 : 0.04; }
    /** The entry with the lowest number in its name ("Rate7_5Hz" before "Rate30Hz"), else one called "Slow"; null when none. */
    static String slowest(Iterable<String> names) {
        String best = null; double lowest = Double.MAX_VALUE;
        for (String n : names) {
            if (best == null && n.equalsIgnoreCase("Slow")) best = n;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(?:[_.](\\d+))?").matcher(n);
            if (!m.find()) continue;
            double v = Double.parseDouble(m.group(1) + (m.group(2) != null ? "." + m.group(2) : ""));
            if (v > 0 && v < lowest) { lowest = v; best = n; }
        }
        return best;
    }
    static String rateWords(String entry) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(?:[_.](\\d+))?").matcher(entry);
        return m.find() ? m.group(1) + (m.group(2) != null ? "." + m.group(2) : "") + " pictures a second" : entry;
    }

    // ------------------------------------------------------------------ the connection

    private static final class Session {
        final String host; final List<Viewer> viewers = new CopyOnWriteArrayList<>();
        volatile boolean ended; volatile long idleSince;
        Session(String host) { this.host = host; }
        void start() { Thread t = new Thread(this::run, "thermal-camera"); t.setDaemon(true); t.start(); }

        private void fail(String why) { for (Viewer v : viewers) v.listener.failed(why); }
        private void run() {
            GigeVision.Control control = null; DatagramSocket stream = null; Genicam g = null; boolean controlling = false;
            try {
                InetAddress camera = InetAddress.getByName(host);
                control = new GigeVision.Control(camera, controlPort);
                GigeVision.Control c = control;
                try { c.writeReg(GigeVision.CCP, GigeVision.CONTROL); controlling = true; }
                catch (IOException refused) { throw new IOException(refused.getMessage().contains("another program") ? "another program (FLIR's own software, or another phone) is controlling the camera" : refused.getMessage()); }
                g = description(c);
                Setup setup = configure(g);
                stream = new DatagramSocket(); stream.setReceiveBufferSize(4 << 20); stream.setSoTimeout(200);
                InetAddress me = c.localAddress();
                if (!(me instanceof Inet4Address)) throw new IOException("the phone has no IPv4 address on the camera's network");
                c.writeReg(GigeVision.SCPS0, PACKET);
                int packet = (int) (c.readReg(GigeVision.SCPS0) & 0xffff); if (packet < 576 || packet > 9000) packet = PACKET;
                c.writeReg(GigeVision.SCDA0, ByteBuffer.wrap(me.getAddress()).getInt() & 0xffffffffL);
                c.writeReg(GigeVision.SCP0, stream.getLocalPort());
                if (g.usable("TLParamsLocked")) try { g.set("TLParamsLocked", 1); } catch (IOException fixed) { }
                g.execute("AcquisitionStart");
                GigeVision.Assembler assembler = new GigeVision.Assembler(packet - GigeVision.OVERHEAD);
                byte[] buffer = new byte[Math.max(packet, 1500) + 64];
                long lastPicture = System.currentTimeMillis(), lastBeat = 0;
                while (true) {
                    long now = System.currentTimeMillis();
                    synchronized (SESSIONS) { if (viewers.isEmpty() && idleSince > 0 && now - idleSince > LINGER_MS) { ended = true; SESSIONS.remove(host, this); break; } }
                    if (now - lastBeat > HEARTBEAT_MS) { c.readReg(GigeVision.CCP); lastBeat = now; }
                    DatagramPacket in = new DatagramPacket(buffer, buffer.length);
                    try { stream.receive(in); } catch (SocketTimeoutException quiet) {
                        if (now - lastPicture > SILENT_MS) throw new IOException(assembler.dropped > 0
                            ? "pictures arrive incomplete: the network between the camera and the phone loses too much (" + assembler.dropped + " pictures lost). A wired camera on the same router as the phone's Wi-Fi, a stronger Wi-Fi signal, or a 5 GHz network helps"
                            : "no pictures arrived. A firewall, or the camera and phone being on different networks, can stop them");
                        continue;
                    }
                    GigeVision.Image image = assembler.packet(in.getData(), in.getLength());
                    if (image == null) continue;
                    lastPicture = System.currentTimeMillis();
                    double scale = image.pixelFormat == GigeVision.MONO8 ? Double.NaN : setup.scale;
                    ThermalImage picture = new ThermalImage(image.width, image.height, masked(image.values, setup.mask), scale);
                    for (Viewer v : viewers) if (lastPicture - v.last >= v.everyMs) { v.last = lastPicture; v.listener.picture(picture); }
                }
            } catch (Exception failure) {
                String why = failure instanceof java.net.UnknownHostException ? "the camera's address was not found"
                    : failure instanceof SocketTimeoutException ? "the camera did not answer. Check its address, and that it is on and on the same network"
                    : failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                synchronized (SESSIONS) { ended = true; SESSIONS.remove(host, this); }
                fail(CameraHub.words(new IOException(why)).replaceFirst("^IOException: ", ""));
            } finally {
                synchronized (SESSIONS) { ended = true; SESSIONS.remove(host, this); }
                if (control != null) {
                    // Hands the camera back as it was found: acquisition stopped, the stream closed, control released.
                    if (g != null) { try { g.execute("AcquisitionStop"); } catch (Exception gone) { } if (g.usable("TLParamsLocked")) try { g.set("TLParamsLocked", 0); } catch (Exception gone) { } }
                    if (controlling) { try { control.writeReg(GigeVision.SCP0, 0); } catch (Exception gone) { } try { control.writeReg(GigeVision.CCP, 0); } catch (Exception gone) { } }
                    control.close();
                }
                if (stream != null) stream.close();
            }
        }
    }

    /** The camera's description, read from where its first bootstrap URL says (kept per camera model and version). */
    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();
    static Genicam description(GigeVision.Control c) throws IOException {
        Genicam.Port port = new Genicam.Port() {
            public byte[] read(long address, int length) throws IOException {
                return length == 4 ? ByteBuffer.allocate(4).putInt((int) c.readReg(address)).array() : c.readMem(address, length);
            }
            public void write(long address, byte[] data) throws IOException {
                if (data.length == 4) c.writeReg(address, ByteBuffer.wrap(data).getInt() & 0xffffffffL); else c.writeMem(address, data);
            }
        };
        String url = GigeVision.text(c.readMem(GigeVision.FIRST_URL, 512), 0, 512);
        long[] where = Genicam.localUrl(url);
        if (where == null) throw new IOException("the camera keeps its description somewhere the app cannot read (" + url.split(":")[0] + ")");
        if (where[1] <= 0 || where[1] > (8 << 20)) throw new IOException("the camera's description has an unlikely size");
        String xml;
        synchronized (DESCRIPTIONS) { xml = DESCRIPTIONS.get(url); }
        if (xml == null) {
            xml = Genicam.unpack(url, c.readMem(where[0], (int) where[1]));
            synchronized (DESCRIPTIONS) { DESCRIPTIONS.put(url, xml); }
        }
        return new Genicam(xml, port);
    }

    interface TestResult { void done(String model, ThermalImage picture, String problem); }
    /**
     * For the settings screen's Test: asks the camera at `host` what it is (reads only), then waits up to `waitMs` for one
     * picture. Calls back once, on a worker thread: the model ("" when it did not say), and the picture or why there is none.
     */
    static void test(String host, long waitMs, TestResult done) {
        Thread t = new Thread(() -> {
            String model = "";
            try { List<GigeVision.Device> found = GigeVision.discover(InetAddress.getByName(host), 1200); if (!found.isEmpty()) model = found.get(0).label(); }
            catch (IOException unreachable) { }
            Object lock = new Object(); ThermalImage[] got = {null}; String[] why = {null};
            Handle h = watch(host, 0, new Listener() {
                public void picture(ThermalImage image) { synchronized (lock) { if (got[0] == null) got[0] = image; lock.notifyAll(); } }
                public void failed(String reason) { synchronized (lock) { if (why[0] == null) why[0] = reason; lock.notifyAll(); } }
            });
            long end = System.currentTimeMillis() + waitMs;
            synchronized (lock) { while (got[0] == null && why[0] == null && System.currentTimeMillis() < end) try { lock.wait(Math.max(1, end - System.currentTimeMillis())); } catch (InterruptedException stop) { break; } }
            h.close();
            String problem = got[0] != null ? null : why[0] != null ? why[0] : model.isEmpty() ? "the camera did not answer" : "it answered, but no picture came within " + waitMs / 1000 + " s";
            done.done(model, got[0], problem);
        }, "thermal-camera-test");
        t.setDaemon(true); t.start();
    }

    private ThermalCamera() { }
}
