package io.github.thelastfrogrammer.elink;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class FileAccessTest {
    @Test public void readsThePortProbe() {
        Map<Integer, String> ports = FileAccess.parse("80 refused (3 ms), 8080 open (12 ms), 1883 timeout (2001 ms)");
        assertEquals("refused", ports.get(80)); assertEquals("open", ports.get(8080)); assertEquals("timeout", ports.get(1883));
        assertTrue(FileAccess.parse(null).isEmpty());
    }

    @Test public void saysWhyAndWhatToTry() {
        FileAccess.Evidence e = new FileAccess.Evidence();
        e.wifi = false; assertTrue(FileAccess.verdict(e).startsWith("The phone is not on Wi-Fi"));
        e.wifi = true; assertTrue(FileAccess.verdict(e).startsWith("The printer did not answer on this Wi-Fi"));

        e.found = true; e.lanOnly = false; e.state = "Printing"; e.ports.putAll(FileAccess.parse("80 refused (3 ms), 8080 open (5 ms)"));
        String refused = FileAccess.verdict(e);
        assertTrue(refused, refused.contains("cloud mode") && refused.contains("refuses connections while it prints"));
        assertTrue(refused, refused.contains("camera port answers") && refused.contains("Download when it opens") && refused.contains("switch LAN Only on"));

        e.lanOnly = true; String printingLan = FileAccess.verdict(e);
        assertTrue(printingLan, printingLan.contains("LAN Only mode") && printingLan.contains("may open once the print ends"));
        e.lanOnly = false;
        e.state = "Idle"; e.ports.put(80, "open"); e.codeProtected = true;
        String open = FileAccess.verdict(e);
        assertTrue(open, open.startsWith("The printer's file server (port 80) is open in cloud mode: downloads should work now."));
        assertTrue(open, open.contains("access code that this phone has not saved"));
        e.savedCode = true; assertFalse(FileAccess.verdict(e).contains("not saved"));

        e.ports.put(80, "timeout"); assertTrue(FileAccess.verdict(e).contains("does not (timeout)"));
    }

    @Test public void reportHoldsNoAddressesOrCodes() {
        FileAccess.Evidence e = new FileAccess.Evidence(); e.found = true; e.lanOnly = true; e.codeProtected = false; e.state = "Idle";
        e.ports.putAll(FileAccess.parse("80 open (3 ms), 1883 open (4 ms)"));
        String report = FileAccess.report(e);
        assertEquals("file access: wifi true, found true, mode LAN Only, code off, saved code false, state Idle, ports 80 open, 1883 open", report);
        assertFalse(report.matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*"));
    }
}
