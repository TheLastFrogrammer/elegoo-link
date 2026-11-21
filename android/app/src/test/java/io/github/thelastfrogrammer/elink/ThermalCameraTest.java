package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

public class ThermalCameraTest {
    private FakeGigeCamera camera;
    @After public void done() { if (camera != null) camera.close(); ThermalCamera.controlPort = GigeVision.PORT; }

    private Genicam description(FakeGigeCamera c) throws Exception {
        GigeVision.Control control = new GigeVision.Control(InetAddress.getLoopbackAddress(), c.port());
        return ThermalCamera.description(control);
    }

    @Test public void theDescriptionIsReadFromTheCamera() throws Exception {
        camera = new FakeGigeCamera();
        Genicam g = description(camera);
        assertEquals(FakeGigeCamera.W, g.get("Width"));
        assertTrue(g.entries("IRFormat").containsKey("TemperatureLinear10mK"));
        assertTrue(g.choose("IRFormat", "TemperatureLinear10mK"));
        assertEquals("only the masked bits change", 2L, (long) camera.regs.get(FakeGigeCamera.IR));
        camera.regs.put(FakeGigeCamera.IR, 0xABCD0000L | 2);
        assertTrue(g.choose("IRFormat", "TemperatureLinear100mK"));
        assertEquals(0xABCD0001L, (long) camera.regs.get(FakeGigeCamera.IR));
        assertEquals("TemperatureLinear100mK", g.chosen("IRFormat"));
        assertTrue(g.choose("IRFrameRate", "Rate15Hz"));
        assertEquals("pAddress plus Address", 1L, (long) camera.regs.get(FakeGigeCamera.RATE));
        assertFalse(g.usable("Computed"));
        try { g.get("Computed"); fail(); } catch (java.io.IOException expected) { }
        assertFalse(g.choose("IRFormat", "NoSuchFormat"));
    }

    @Test public void configureAsksForTemperaturesAtTheLowestRate() throws Exception {
        camera = new FakeGigeCamera();
        ThermalCamera.Setup s = ThermalCamera.configure(description(camera));
        assertEquals(0x01100007L, (long) camera.regs.get(FakeGigeCamera.PIXEL));
        assertEquals(2L, (long) camera.regs.get(FakeGigeCamera.IR));
        assertEquals(2L, (long) camera.regs.get(FakeGigeCamera.RATE));
        assertEquals(0.01, s.scale, 0);
        assertEquals("80×64, 7.5 pictures a second, temperatures in 0.01 °C steps", s.words());
    }

    @Test public void slowestAndScale() throws Exception {
        assertEquals("Rate7_5Hz", ThermalCamera.slowest(Arrays.asList("Rate30Hz", "Rate7_5Hz", "Rate15Hz")));
        assertNull(ThermalCamera.slowest(Arrays.asList("Fast", "Slow")));
        assertEquals(0.1, ThermalCamera.scaleFor("TemperatureLinear100mK", null), 0);
        assertEquals(0.01, ThermalCamera.scaleFor("TemperatureLinear10mK", null), 0);
    }

    @Test public void discoverySaysWhatTheCameraIs() throws Exception {
        camera = new FakeGigeCamera();
        // Discovery asks the standard port; here the fake answers its own: the packets are read by hand.
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            byte[] ask = GigeVision.command(GigeVision.DISCOVERY, 7, new byte[0], false);
            s.send(new java.net.DatagramPacket(ask, ask.length, InetAddress.getLoopbackAddress(), camera.port()));
            byte[] buffer = new byte[1024]; java.net.DatagramPacket in = new java.net.DatagramPacket(buffer, buffer.length); s.setSoTimeout(2000); s.receive(in);
            GigeVision.Ack a = GigeVision.ack(in.getData(), in.getLength());
            assertEquals(7, a.id); assertEquals(GigeVision.DISCOVERY + 1, a.answer);
            GigeVision.Device d = GigeVision.device(a.payload, in.getAddress());
            assertEquals("FLIR Systems AB FLIR AX5", d.label()); assertEquals("127.0.0.1", d.address.getHostAddress());
        }
    }

    @Test public void picturesArriveWithTemperaturesAndControlIsGivenBack() throws Exception {
        camera = new FakeGigeCamera(); ThermalCamera.controlPort = camera.port();
        CountDownLatch two = new CountDownLatch(2); AtomicReference<ThermalImage> got = new AtomicReference<>(); AtomicReference<String> why = new AtomicReference<>();
        ThermalCamera.Handle a = ThermalCamera.watch("127.0.0.1", 0, new ThermalCamera.Listener() {
            public void picture(ThermalImage image) { got.set(image); two.countDown(); }
            public void failed(String reason) { why.set(reason); two.countDown(); two.countDown(); }
        });
        // A second view shares the connection (a camera takes orders from one program).
        CountDownLatch second = new CountDownLatch(1);
        ThermalCamera.Handle b = ThermalCamera.watch("127.0.0.1", 0, new ThermalCamera.Listener() {
            public void picture(ThermalImage image) { second.countDown(); }
            public void failed(String reason) { }
        });
        assertTrue(two.await(10, TimeUnit.SECONDS)); assertNull(why.get());
        assertTrue(second.await(5, TimeUnit.SECONDS));
        ThermalImage image = got.get();
        assertEquals(80, image.width); assertEquals(64, image.height);
        ThermalImage.Stats s = image.stats();
        assertTrue(s.temperatures);
        assertEquals(220, image.celsius(s.hottest), 0.01); assertEquals(60, s.hotX); assertEquals(10, s.hotY);
        assertEquals(30, image.celsius(s.centre), 0.01);
        assertEquals("the phone's address", 0x7f000001L, (long) camera.regs.get((long) GigeVision.SCDA0));
        assertEquals(2L, (long) camera.regs.get((long) GigeVision.CCP));
        a.close(); b.close();
        long until = System.currentTimeMillis() + ThermalCamera.LINGER_MS + 4000;
        while (System.currentTimeMillis() < until && camera.regs.getOrDefault((long) GigeVision.CCP, 0L) != 0) Thread.sleep(100);
        assertEquals("control given back", 0L, (long) camera.regs.get((long) GigeVision.CCP));
        assertEquals(1L, (long) camera.regs.get(FakeGigeCamera.STOP));
        assertEquals(0L, (long) camera.regs.get((long) GigeVision.SCP0));
    }

    @Test public void anotherProgramInControlIsSaidPlainly() throws Exception {
        camera = new FakeGigeCamera(); ThermalCamera.controlPort = camera.port(); camera.refuseControl = true;
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> why = new AtomicReference<>();
        ThermalCamera.watch("127.0.0.1", 0, new ThermalCamera.Listener() {
            public void picture(ThermalImage image) { }
            public void failed(String reason) { why.set(reason); done.countDown(); }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(why.get(), why.get().contains("another program"));
        assertFalse("no address in the words", why.get().contains("127.0.0.1"));
    }

    @Test public void aLostPacketDropsThePicture() {
        GigeVision.Assembler a = new GigeVision.Assembler(8);
        ByteBuffer leader = ByteBuffer.allocate(44).putShort((short) 0).putShort((short) 5).putInt(1 << 24).putShort((short) 0).putShort((short) 1).putInt(0).putInt(0)
            .putInt(GigeVision.MONO16).putInt(4).putInt(2).putInt(0).putInt(0).putInt(0);
        assertNull(a.packet(leader.array(), 44));
        byte[] pixels = new byte[16]; for (int i = 0; i < 8; i++) { pixels[2 * i] = (byte) i; pixels[2 * i + 1] = 1; }
        ByteBuffer p1 = ByteBuffer.allocate(16).putShort((short) 0).putShort((short) 5).putInt(3 << 24 | 2).put(pixels, 8, 8);   // second packet first
        ByteBuffer p2 = ByteBuffer.allocate(16).putShort((short) 0).putShort((short) 5).putInt(3 << 24 | 1).put(pixels, 0, 8);
        ByteBuffer trailer = ByteBuffer.allocate(16).putShort((short) 0).putShort((short) 5).putInt(2 << 24 | 3);
        a.packet(p1.array(), 16);
        assertNull("one missing", a.packet(trailer.array(), 16));
        assertEquals(1, a.dropped);
        a.packet(leader.array(), 44); a.packet(p1.array(), 16); a.packet(p2.array(), 16);
        GigeVision.Image image = a.packet(trailer.array(), 16);
        assertNotNull(image); assertEquals(1, a.complete);
        assertEquals(256 + 7, image.values[7]);
    }

    @Test public void turnedAndColoured() {
        ThermalImage i = new ThermalImage(3, 2, new int[] {1, 2, 3, 4, 5, 6}, 0.01);
        assertArrayEquals(new int[] {4, 1, 5, 2, 6, 3}, i.turned(90, false).values);
        assertArrayEquals(new int[] {6, 5, 4, 3, 2, 1}, i.turned(180, false).values);
        assertArrayEquals(new int[] {3, 6, 2, 5, 1, 4}, i.turned(270, false).values);
        assertArrayEquals(new int[] {3, 2, 1, 6, 5, 4}, i.turned(0, true).values);
        assertEquals(0xff000000, ThermalImage.colour(0)); assertEquals(0xffffffff, ThermalImage.colour(1));
        ThermalImage.Stats s = i.stats();
        assertFalse("0.01 K steps of 1–6 are far below -60 °C", s.temperatures);
        ThermalImage raw = new ThermalImage(2, 1, new int[] {10, 200}, Double.NaN);
        assertFalse(raw.stats().temperatures); assertEquals("", raw.words(raw.stats(), 200));
    }
}
