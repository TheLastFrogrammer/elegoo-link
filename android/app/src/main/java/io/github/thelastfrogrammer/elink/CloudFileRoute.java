package io.github.thelastfrogrammer.elink;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Getting a printer file's G-code through Elegoo's cloud storage instead of the printer's own port. Nothing is sent to the printer.
 * Only https; the link's host is whatever Elegoo's API returns and only that host is logged. Independent of Android.
 */
final class CloudFileRoute {
    private CloudFileRoute() { }

    static final String NO_COPY = "Elegoo's cloud has no copy of this file that the app can fetch. Files sent through the cloud from this app can be fetched; others need the local connection.";

    interface Records { JSONObject record(String serial, String filename) throws Exception; }
    interface Links { String signedLink(String objectName) throws IOException; }

    /** What to fetch: a storage object name (from our own upload or a record field) or a ready link on an Elegoo domain; null when unknown. */
    static final class Choice {
        final String objectName, url, source;
        Choice(String objectName, String url, String source) { this.objectName = objectName; this.url = url; this.source = source; }
    }

    /** Own upload memory first (no request), then what the cloud's record for the file clearly names. Null when neither knows. */
    static Choice choose(CloudFileMemory memory, String serial, String filename, Records records) {
        String known = memory.objectNameFor(serial, filename);
        if (!known.isEmpty()) return new Choice(known, "", "an upload from this app");
        try {
            CloudApi.Reference reference = CloudApi.gcodeReference(records.record(serial, filename));
            if (reference != null) return new Choice(reference.objectName, reference.url, "the cloud's record of the file");
        } catch (Exception error) { Diagnostics.note(Diagnostics.FILES, "cloud file record not available: " + StatusPresentation.clean(String.valueOf(error.getMessage()))); }
        return null;
    }

    /** Fetches the chosen object into `destination` through the same checks as the printer download (size cap, gzip, error pages). */
    static void download(Choice choice, Links links, File destination, PrinterHttp.Progress progress, PrinterHttp.ConnectionFactory connections, AtomicBoolean cancelled) throws Exception {
        String link = choice.url.isEmpty() ? links.signedLink(choice.objectName) : choice.url;
        if (!link.startsWith("https://")) throw new IOException("Cloud downloads must use https.");
        String host = CloudApi.hostOf(link);
        HttpURLConnection connection = connections.open(new URL(link));
        try {
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/octet-stream"); connection.setRequestProperty("Accept-Encoding", "identity");
            Diagnostics.note(Diagnostics.FILES, "downloading through the Elegoo cloud from " + host + " (found in " + choice.source + ")");
            PrinterHttp.receive(connection, destination, progress, GcodeInspector.MAX_BYTES, true, "cloud object on " + host, false, cancelled);
        } finally { connection.disconnect(); }
    }
}
