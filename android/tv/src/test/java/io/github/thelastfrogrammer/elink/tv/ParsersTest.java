package io.github.thelastfrogrammer.elink.tv;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** The event stream and camera stream parsers, the back-off and typed addresses. */
public class ParsersTest {
    private static List<String> events(String stream) throws IOException {
        SseParser p = new SseParser(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)));
        List<String> out = new ArrayList<>();
        try { while (true) p.next((e, d) -> out.add(e + "=" + d)); } catch (EOFException end) { return out; }
    }

    @Test public void eventsAsThePhoneSendsThem() throws Exception {
        // The phone's opening: a retry hint, the status, pings as comments, then an event.
        String s = "retry: 3000\n\nevent: status\ndata: {\"a\":1}\n\n: ping\n\n: ping\n\nevent: event\ndata: {\"type\":\"paused\"}\n\n";
        assertEquals(Arrays.asList("status={\"a\":1}", "event={\"type\":\"paused\"}"), events(s));
    }
    @Test public void eventsFollowTheStandard() throws Exception {
        assertEquals("data lines join with newlines", Arrays.asList("status=a\nb"), events("event: status\ndata: a\ndata: b\n\n"));
        assertEquals("CRLF and CR end lines too", Arrays.asList("status=x", "event=y"), events("event: status\r\ndata: x\r\n\r\nevent: event\rdata: y\r\r"));
        assertEquals("no space after the colon", Arrays.asList("status=x"), events("event:status\ndata:x\n\n"));
        assertEquals("no event name: message", Arrays.asList("message=x"), events("data: x\n\n"));
        assertEquals("the name does not carry over", Arrays.asList("status=1", "message=2"), events("event: status\ndata: 1\n\ndata: 2\n\n"));
        assertEquals("an event without data is not sent", Arrays.asList("status=z"), events("event: status\n\nevent: status\ndata: z\n\n"));
        assertEquals("unknown fields and ids are skipped", Arrays.asList("status=1"), events("id: 7\nfoo: bar\nevent: status\ndata: 1\n\n"));
        assertEquals("a message cut off at the end is not sent", new ArrayList<String>(), events("event: status\ndata: half"));
        SseParser p = new SseParser(new ByteArrayInputStream("retry: 3000\n\n".getBytes(StandardCharsets.UTF_8)));
        try { p.next((e, d) -> { }); fail(); } catch (EOFException end) { assertEquals(3000, p.retryMs()); }
    }
    @Test(expected = IOException.class) public void aRunawayLineStops() throws Exception {
        byte[] big = new byte[SseParser.MAX_LINE + 10]; Arrays.fill(big, (byte) 'a');
        new SseParser(new ByteArrayInputStream(big)).next((e, d) -> { });
    }

    private static final byte[] A = {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9}, B = {(byte) 0xff, (byte) 0xd8, 9, (byte) 0xff, (byte) 0xd9};

    private static byte[] part(byte[] jpeg, boolean length) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(("--lwframe\r\nContent-Type: image/jpeg\r\n" + (length ? "Content-Length: " + jpeg.length + "\r\n" : "") + "\r\n").getBytes(StandardCharsets.US_ASCII));
        o.write(jpeg); o.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        return o.toByteArray();
    }
    private static byte[] join(byte[]... parts) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] p : parts) o.write(p); return o.toByteArray(); }

    @Test public void cameraPicturesAsThePhoneSendsThem() throws Exception {
        MjpegReader r = new MjpegReader(new ByteArrayInputStream(join(part(A, true), part(B, true))));
        assertArrayEquals(A, r.next()); assertArrayEquals(B, r.next());
        try { r.next(); fail("the end of the stream"); } catch (EOFException end) { }
    }
    @Test public void aLengthIsTrustedOverMarkers() throws Exception {
        // A picture holding a thumbnail: its inner end marker must not cut the picture short.
        byte[] withThumb = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd8, 5, (byte) 0xff, (byte) 0xd9, 6, 7, (byte) 0xff, (byte) 0xd9};
        assertArrayEquals(withThumb, new MjpegReader(new ByteArrayInputStream(part(withThumb, true))).next());
    }
    @Test public void withoutALengthMarkersAreUsed() throws Exception {
        MjpegReader r = new MjpegReader(new ByteArrayInputStream(join(part(A, false), part(B, false), "--lwframe--\r\n".getBytes(StandardCharsets.US_ASCII))));
        assertArrayEquals(A, r.next()); assertArrayEquals(B, r.next());
        try { r.next(); fail("the closing boundary ends it"); } catch (EOFException end) { }
    }
    @Test public void brokenStreamsStopInsteadOfGrowing() throws Exception {
        try { new MjpegReader(new ByteArrayInputStream(join(part(A, true)).clone())).next(); } catch (IOException unexpected) { fail(); }
        byte[] cut = Arrays.copyOf(part(A, true), part(A, true).length - 4);
        try { new MjpegReader(new ByteArrayInputStream(cut)).next(); fail(); } catch (EOFException end) { }
        byte[] huge = ("--lwframe\r\nContent-Length: " + (MjpegReader.MAX_FRAME + 1) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        try { new MjpegReader(new ByteArrayInputStream(huge)).next(); fail(); } catch (IOException tooLarge) { assertFalse(tooLarge instanceof EOFException); }
        try { new MjpegReader(new ByteArrayInputStream("<html>not a camera</html>\n\n".getBytes(StandardCharsets.US_ASCII))).next(); fail(); } catch (IOException notCamera) { }
    }

    @Test public void backoffGrowsToItsCapWithJitterAndResets() {
        Backoff b = new Backoff(2_000, 60_000, new Random(1));
        long[] bases = {2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000};
        for (long base : bases) { long w = b.next(); assertTrue(base + " ~ " + w, w >= base * 0.8 - 1 && w <= base * 1.2 + 1); }
        b.reset(); long w = b.next(); assertTrue(w >= 1_600 && w <= 2_400);
        b.atLeast(10_000); w = b.next(); assertTrue("a busy phone gets at least 10 s: " + w, w >= 8_000 && w <= 12_000);
    }

    @Test public void typedAddresses() {
        assertEquals("http://192.168.1.20:8765", PhoneAddress.parse("192.168.1.20", "").base);
        assertEquals("http://192.168.1.20:8765", PhoneAddress.parse(" 192.168.1.20 ", "8765").base);
        assertEquals("http://192.168.1.20:9000", PhoneAddress.parse("192.168.1.20", "9000").base);
        assertEquals("the port typed with the address", "http://192.168.1.20:9000", PhoneAddress.parse("192.168.1.20:9000", "8765").base);
        assertEquals("the link the phone shows", "http://192.168.1.20:8765", PhoneAddress.parse("http://192.168.1.20:8765/", "").base);
        assertEquals("leading zeros are not octal", "http://192.168.1.2:8765", PhoneAddress.parse("192.168.001.002", "").base);
        assertEquals("http://[fd00::12]:8765", PhoneAddress.parse("[fd00::12]:8765", "").base);
        assertEquals("http://[fe80::1%25wlan0]:8765", PhoneAddress.parse("fe80::1%wlan0", "8765").base);
        assertEquals("http://phone.lan:8765", PhoneAddress.parse("Phone.lan", "").base);
        for (String bad : new String[] {"", "192.168.1", "192.168.1.300", "1.2.3.4.5", "a b", "http://", "[fd00::1"}) assertNotNull(bad, PhoneAddress.parse(bad, "").problem);
        assertNotNull(PhoneAddress.parse("192.168.1.20", "70000").problem);
        assertNotNull(PhoneAddress.parse("192.168.1.20", "port").problem);
        assertEquals("192.168.1.20:8765", PhoneAddress.shown("http://192.168.1.20:8765"));
        assertEquals("192.168.1.20", PhoneAddress.host("http://192.168.1.20:8765"));
        assertEquals("9000", PhoneAddress.port("http://192.168.1.20:9000"));
        assertEquals("fe80::1%wlan0", PhoneAddress.host("http://[fe80::1%25wlan0]:8765"));
        assertEquals("8765", PhoneAddress.port("http://[fe80::1%25wlan0]:8765"));
    }
    @Test public void discoveredAddresses() throws Exception {
        assertEquals("http://192.168.1.20:8765", PhoneAddress.base(java.net.InetAddress.getByName("192.168.1.20"), 8765));
        assertEquals("http://[fd00:0:0:0:0:0:0:12]:8765", PhoneAddress.base(java.net.InetAddress.getByName("fd00::12"), 8765));
    }
}
