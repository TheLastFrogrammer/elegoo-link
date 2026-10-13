package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.ConnectException;
import java.net.ServerSocket;

public class PortProbeTest {
    @Test public void reportsOpenAndRefusedPorts() throws Exception {
        try (ServerSocket open = new ServerSocket(0)) {
            int closed; try (ServerSocket spare = new ServerSocket(0)) { closed = spare.getLocalPort(); }
            String result = PrinterHttp.probePorts(javax.net.SocketFactory.getDefault(), "127.0.0.1", new int[] {open.getLocalPort(), closed});
            assertTrue(result, result.startsWith(open.getLocalPort() + " open ("));
            assertTrue(result, result.contains(closed + " refused ("));
        }
    }

    @Test public void causesNameEachLayerWithoutTheAddress() {
        Exception error = new ConnectException("Failed to connect to /192.168.1.84:80");
        error.initCause(new ConnectException("failed to connect to /192.168.1.84 (port 80) after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)"));
        String text = PrinterHttp.causes(error);
        assertTrue(text, text.contains("ECONNREFUSED"));
        assertFalse(text, text.contains("192.168"));
    }

    @Test public void readsAGreetingWithoutSendingAnything() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            Thread greeter = new Thread(() -> { try (java.net.Socket client = server.accept()) {
                client.getOutputStream().write("220 (vsFTPd 3.0.3) at 192.168.1.84\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); client.getOutputStream().flush();
                client.setSoTimeout(500); assertEquals("nothing is sent to the service", -1, client.getInputStream().read());
            } catch (java.net.SocketTimeoutException fine) { } catch (Exception e) { throw new RuntimeException(e); } });
            greeter.start();
            assertEquals("\"220 (vsFTPd 3.0.3) at <address>\"", PrinterHttp.greeting(javax.net.SocketFactory.getDefault(), "127.0.0.1", server.getLocalPort()));
            greeter.join(3000);
        }
        try (java.net.ServerSocket quiet = new java.net.ServerSocket(0)) {
            Thread holder = new Thread(() -> { try (java.net.Socket client = quiet.accept()) { Thread.sleep(2500); } catch (Exception ignored) { } });
            holder.start();
            assertEquals("silent", PrinterHttp.greeting(javax.net.SocketFactory.getDefault(), "127.0.0.1", quiet.getLocalPort()));
            holder.join(4000);
        }
    }
}
