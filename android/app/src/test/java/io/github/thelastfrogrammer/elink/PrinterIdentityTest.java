package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Collections;

public class PrinterIdentityTest {
    private Cc2Discovery unavailable() { return new Cc2Discovery(() -> { throw new IOException("UDP blocked"); }, Collections.emptyList()); }
    @Test public void manualSerialDoesNotNeedHttpOrUdp() throws Exception {
        try (PrinterIdentity identity = new PrinterIdentity(" TEST-CC2 ", unavailable())) {
            assertEquals("TEST-CC2", identity.resolve(new PrinterHttp("192.168.1.84", "secret", url -> { fail("HTTP must not open"); return null; })));
        }
    }
    @Test public void missingBothIdentityTransportsGivesActionableErrorWithoutSecret() throws Exception {
        try (PrinterIdentity identity = new PrinterIdentity("", unavailable())) {
            try { identity.resolve(new PrinterHttp("192.168.1.84", "secret", url -> { throw new java.net.ConnectException("http://host/?X-Token=secret"); })); fail("Expected identity error"); }
            catch (PrinterErrors.IdentityUnavailable error) {
                assertFalse(PrinterErrors.retryable(error));
                assertTrue(PrinterErrors.describe(error, "Identity").contains("optional serial field"));
                assertFalse(PrinterErrors.describe(error, "Identity").contains("secret"));
            }
        }
    }
}
