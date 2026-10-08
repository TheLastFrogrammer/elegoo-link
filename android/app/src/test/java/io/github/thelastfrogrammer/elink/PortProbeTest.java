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
}
