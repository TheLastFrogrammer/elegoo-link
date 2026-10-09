package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;

public class PrinterProbeTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    @After public void stop() { scheduler.shutdownNow(); }

    /** Plays a printer that answers some methods, refuses one and ignores one. */
    private static class Printer implements PrinterProbe.Transport {
        final List<JSONObject> sent = new CopyOnWriteArrayList<>();
        public String name() { return "test"; }
        public void send(JSONObject request, PrinterProbe.Answer answer) {
            sent.add(request);
            try {
                int method = request.getInt("method");
                if (method == 1004) return; // never answers
                if (method == 1062) { answer.reply(new JSONObject().put("error_code", 1001), null); return; }
                if (method == 1005) { answer.reply(null, "Printer rejected the request (code 1003)."); return; }
                JSONObject result = new JSONObject().put("error_code", 0);
                if (method == 1044) result.put("file_list", new JSONArray().put(new JSONObject().put("filename", "Secret part.gcode").put("size", 1234)));
                if (method == 1036) result.put("history_task_list", new JSONArray().put(new JSONObject().put("task_id", "task-77").put("time_lapse_video_url", "/video/77.mp4")));
                if (method == 1001) result.put("sn", "F014SECRETSERIAL").put("hostname", "Workshop printer").put("network", new JSONObject().put("ip", "192.168.1.84"));
                answer.reply(result, null);
            } catch (Exception error) { throw new RuntimeException(error); }
        }
    }

    private String run(Printer printer, List<PrinterProbe.Outcome> outcomes) throws Exception {
        BlockingQueue<String> report = new LinkedBlockingQueue<>();
        new PrinterProbe(printer, scheduler, new PrinterProbe.Listener() {
            public void progress(String text) { }
            public void finished(String text, List<PrinterProbe.Outcome> list) { outcomes.addAll(list); report.add(text); }
        }, 300, 0).start();
        String text = report.poll(10, TimeUnit.SECONDS); assertNotNull("Probe finished", text); return text;
    }

    @Test public void asksEachReadOnlyMethodOnceAndNothingElse() throws Exception {
        Printer printer = new Printer(); List<PrinterProbe.Outcome> outcomes = new ArrayList<>();
        run(printer, outcomes);
        Set<Integer> asked = new HashSet<>();
        for (JSONObject request : printer.sent) {
            int method = request.getInt("method");
            assertTrue("Only read-only methods: " + method, PrinterProbe.readOnly(method));
            assertFalse("Never a printer-changing method: " + method, Cc2Codec.changing(method));
            assertTrue("Asked once: " + method, asked.add(method));
        }
        assertEquals(PrinterProbe.READS.length, asked.size());
        assertEquals(PrinterProbe.READS.length, outcomes.size());
        for (int dangerous : new int[] {1007, 1020, 1021, 1022, 1023, 1026, 1027, 1028, 1032, 1033, 1034, 1035, 1038, 1039, 1043, 1047, 1054, 1055, 1057, 1058, 1063, 2001, 2002, 2004})
            assertFalse(dangerous + " is not read-only", PrinterProbe.readOnly(dangerous));
    }

    @Test public void followUpQuestionsUseEarlierAnswersButReportOnlyFieldNames() throws Exception {
        Printer printer = new Printer(); List<PrinterProbe.Outcome> outcomes = new ArrayList<>();
        String report = run(printer, outcomes);
        Map<Integer, JSONObject> byMethod = new HashMap<>();
        for (JSONObject request : printer.sent) byMethod.put(request.getInt("method"), request.getJSONObject("params"));
        assertEquals("task-77", byMethod.get(1037).getString("task_id"));
        assertEquals("Secret part.gcode", byMethod.get(1045).getString("file_name"));
        assertEquals("Secret part.gcode", byMethod.get(1046).getString("filename"));
        assertEquals("/video/77.mp4", byMethod.get(1051).getString("url"));
        assertEquals("local", byMethod.get(1044).getString("storage_media"));

        for (String secret : new String[] {"Secret", "task-77", "F014SECRETSERIAL", "Workshop printer", "192.168.1.84", "77.mp4", "1234"})
            assertFalse("No values in the report: " + secret, report.contains(secret));
        assertTrue(report, report.contains("1001 System info: answered"));
        assertTrue(report, report.contains("network{ip:string}"));
        assertTrue(report, report.contains("file_list[1 × {filename:string, size:number}]"));
        assertTrue(report, report.contains("1004 Fans: no reply"));
        assertTrue(report, report.contains("1062 AI detection settings: refused with error code 1001"));
        assertTrue(report, report.contains("1005 Print info: refused with error code 1003"));
    }

    @Test public void followUpsAreSkippedWithoutWhatTheyNeed() throws Exception {
        PrinterProbe.Context empty = new PrinterProbe.Context();
        assertNull(PrinterProbe.request(1037, empty));
        assertNull(PrinterProbe.request(1045, empty));
        assertNull(PrinterProbe.request(1046, empty));
        assertNull(PrinterProbe.request(1051, empty));
        assertEquals(1001, PrinterProbe.request(1001, empty).getInt("method"));
        try { PrinterProbe.request(1047, empty); fail("Delete is never a probe question"); } catch (IllegalArgumentException expected) { }

        Printer silent = new Printer() {
            @Override public void send(JSONObject request, PrinterProbe.Answer answer) { sent.add(request); answer.reply(new JSONObject(), null); }
        };
        List<PrinterProbe.Outcome> outcomes = new ArrayList<>();
        String report = run(silent, outcomes);
        assertTrue(report, report.contains("1037 One print's details: skipped: no print history entry to ask about"));
        assertTrue(report, report.contains("1046 File details: skipped: no file on the printer to ask about"));
        for (JSONObject request : silent.sent) assertNotEquals(1037, request.getInt("method"));
    }

    @Test public void sessionAndCloudAcceptProbeReadsOnly() {
        for (int method : PrinterProbe.READS) assertEquals(method + " through the cloud", method != Cc2Codec.CAMERA, CloudControl.allowed(method));
        assertFalse(CloudControl.allowed(1039));
        assertFalse(CloudControl.allowed(1043));
        assertFalse(CloudControl.allowed(1063));
    }

    @Test public void tallyCountsUnaskedMessages() {
        PrinterProbe.Tally tally = new PrinterProbe.Tally();
        assertEquals("none yet", tally.summary());
        tally.saw("status", 6000); tally.saw("status", 6000); tally.saw("cloud", 6006);
        assertEquals("6000 on status ×2, 6006 on cloud ×1", tally.summary());
    }

    @Test public void httpHeadKeepsOnlyStatusServerAndType() throws Exception {
        assertEquals("HTTP/1.1 404 Not Found · Server: lighttpd · Content-Type: text/html",
            PrinterHttp.describeHead("HTTP/1.1 404 Not Found\r\nServer: lighttpd\r\nSet-Cookie: id=secret\r\nContent-Type: text/html\r\n\r\n"));
        assertEquals("answered, but not with HTTP", PrinterHttp.describeHead("SSH-2.0-dropbear\r\n"));
        assertEquals("connection closed without an answer", PrinterHttp.describeHead(""));
        assertFalse(PrinterHttp.describeHead("HTTP/1.0 200 OK\r\nServer: box 192.168.1.84\r\n\r\n").contains("192.168"));

        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Thread serve = new Thread(() -> {
                try (java.net.Socket client = server.accept()) {
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream()));
                    String first = in.readLine(); while (in.readLine().length() > 0) { }
                    client.getOutputStream().write(("HTTP/1.1 200 OK\r\nServer: " + ("GET / HTTP/1.0".equals(first) ? "ok" : "bad") + "\r\n\r\nbody").getBytes());
                } catch (Exception ignored) { }
            });
            serve.start();
            assertEquals("HTTP/1.1 200 OK · Server: ok", PrinterHttp.httpHead(javax.net.SocketFactory.getDefault(), "127.0.0.1", server.getLocalPort()));
            serve.join(2000);
        }
    }
}
