package io.github.thelastfrogrammer.elink;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/**
 * Plain wording for the network failures a hobbyist meets (Wi-Fi gone, no internet, slow route). The technical text, which can
 * hold host names or paths, goes to the diagnostics log only. Returns null for exceptions the app wrote its own message for.
 */
final class FriendlyErrors {
    private FriendlyErrors() { }

    /** A connection the printer or server actively refused is not covered here: callers have specific advice for that. */
    static boolean refused(Throwable error) { return error instanceof ConnectException && String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("refused"); }

    static String describe(Throwable error, String what) {
        for (int i = 0; i < 6 && error != null; i++, error = error.getCause()) {
            String text = classify(error, what);
            if (text != null) { Diagnostics.note(Diagnostics.FILES, what + ": " + error.getClass().getSimpleName() + ": " + StatusPresentation.clean(String.valueOf(error.getMessage()))); return text; }
        }
        return null;
    }

    private static String classify(Throwable error, String what) {
        if (error instanceof UnknownHostException) return what + " could not find the server. Check that the phone has an internet connection, then try again.";
        if (error instanceof SocketTimeoutException) return what + " timed out. Check the Wi-Fi signal and try again.";
        if (error instanceof SSLException) return what + " could not make a secure connection. Check the phone's date and time, and that the network does not block it.";
        if (refused(error)) return null;
        if (error instanceof ConnectException || error instanceof NoRouteToHostException) return what + " could not connect. Check that the phone is on the right Wi-Fi network and has a connection.";
        if (error instanceof SocketException || error instanceof EOFException) return what + " was interrupted. Check the Wi-Fi and try again.";
        return null;
    }

    /** The message to show for an IOException: plain wording for network failures, else the exception's own message (or the fallback). */
    static String message(IOException error, String what, String fallback) {
        String plain = describe(error, what);
        if (plain != null) return plain;
        return error.getMessage() == null || error.getMessage().isEmpty() ? fallback : error.getMessage();
    }
}
