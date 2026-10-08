package io.github.thelastfrogrammer.elink;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.SSLHandshakeException;

/** Pure-JVM checks for the smaller recovery fixes: plain network wording, finish time, size guesses and error codes. */
public class FailureBatchBTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void networkFailuresGetPlainWordsAndHideTheTechnicalText() {
        String host = FriendlyErrors.describe(new UnknownHostException("Unable to resolve host \"oss.example.com\""), "Sending the file");
        assertTrue(host, host.contains("could not find the server") && !host.contains("oss.example.com"));
        assertTrue(FriendlyErrors.describe(new SocketTimeoutException("read timed out"), "The download").contains("timed out"));
        assertTrue(FriendlyErrors.describe(new ConnectException("Network is unreachable"), "The download").contains("could not connect"));
        assertTrue(FriendlyErrors.describe(new SSLHandshakeException("chain validation failed"), "The request").contains("secure connection"));
        assertTrue(FriendlyErrors.describe(new java.net.SocketException("Software caused connection abort"), "The upload").contains("interrupted"));
        assertNull("a refused connection keeps its specific advice", FriendlyErrors.describe(new ConnectException("Connection refused"), "The download"));
        assertNull("our own messages are left alone", FriendlyErrors.describe(new IOException("File is empty"), "The download"));
        assertEquals("File is empty", FriendlyErrors.message(new IOException("File is empty"), "x", "fallback"));
    }

    @Test public void finishTimeShowsTheDayWhenItIsNotToday() {
        TimeZone zone = TimeZone.getTimeZone("UTC");
        long noon = 1_800_000_000_000L - 1_800_000_000_000L % 86_400_000L + 12 * 3_600_000L;
        assertEquals("14:00", StatusPresentation.doneAt(noon, 2 * 3600, Locale.ROOT, zone));
        String tomorrow = StatusPresentation.doneAt(noon, 14 * 3600, Locale.ENGLISH, zone);
        assertTrue(tomorrow, tomorrow.matches("[A-Z][a-z]{2} 02:00"));
    }

    @Test public void aBigModelIsWarnedAboutFromItsFileSize() {
        assertTrue("100 MB binary STL is about 2M triangles", SliceActivity.importEstimate(100_000_000L, 0).triangles > 1_900_000);
        assertTrue(SliceActivity.importEstimate(100_000_000L, 0).risky(512L * 1024 * 1024));
        assertFalse(SliceActivity.importEstimate(2_000_000L, 0).risky(1024L * 1024 * 1024));
    }

    @Test public void printerCodesNameFullStorageAndTheUploadFolder() {
        assertTrue(PrinterErrors.code(9002).contains("storage may be full"));
        assertTrue(PrinterErrors.code(1004).contains("Delete files on the printer"));
        assertTrue(PrinterErrors.code(9001).contains("open the file"));
        assertTrue(PrinterErrors.code(9007).contains("upload folder"));
    }
}
