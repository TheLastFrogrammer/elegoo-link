package io.github.thelastfrogrammer.elink;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import org.eclipse.paho.client.mqttv3.MqttException;

/** Never display raw transport exceptions: they can contain a credential-bearing URL. */
public final class PrinterErrors {
    private PrinterErrors() { }
    public static final class Rejected extends IOException {
        public final int code;
        public Rejected(int code) { super(code(code)); this.code = code; }
    }
    public static final class HttpStatus extends IOException {
        public final int status;
        public HttpStatus(int status) { this.status = status; }
    }
    public static final class MissingIdentity extends IOException { }
    public static String code(int code) {
        String reason;
        switch (code) {
            case 1000: reason = "Access code rejected. Check the code under Settings → LAN Only"; break;
            case 1001: reason = "This firmware does not support that request"; break;
            case 1002: reason = "Printer could not open the folder"; break;
            case 1003: reason = "Printer rejected the request parameters"; break;
            case 1004: case 9002: reason = "Printer could not write the file"; break;
            case 1009: reason = "Printer is busy"; break;
            case 1010: reason = "Printer is not currently printing"; break;
            case 1012: reason = "Print task was not found"; break;
            case 9000: reason = "Upload offset mismatch. Check for a partial file before uploading again"; break;
            case 9004: case 9008: case 9009: reason = "Upload checksum failed"; break;
            case -1: reason = "Printer response is missing an error code"; break;
            default: reason = "Printer rejected the request";
        }
        return reason + " (code " + code + ").";
    }
    public static String describe(Throwable error, String stage) {
        if (error instanceof Rejected) return error.getMessage();
        if (error instanceof HttpStatus) return stage + " replied with HTTP " + ((HttpStatus) error).status + ". Verify that this IP belongs to the CC2 and that its firmware exposes /system/info.";
        if (error instanceof MissingIdentity) return "HTTP connected, but the response did not contain the printer serial number. Check the IP and CC2 firmware/API compatibility.";
        if (error instanceof org.json.JSONException) return stage + " returned unexpected data. Verify that the IP belongs to the CC2, rather than another local device.";
        if (error instanceof MqttException) {
            int code = ((MqttException) error).getReasonCode();
            if (code == 4 || code == 5) return "MQTT access code rejected (code " + code + "). Check Settings → LAN Only → Access Code.";
            return "MQTT connection failed (code " + code + "). Run Check connection; verify LAN Only and MQTT port 1883.";
        }
        Throwable cause = error;
        for (int i = 0; i < 8 && cause != null; i++, cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException)
                return stage + " could not reach the printer. Check its current IP, LAN Only, and the phone's Wi-Fi. Guest-network isolation or a VPN can block access.";
            if (cause instanceof SocketTimeoutException)
                return stage + " timed out. Run Check connection to distinguish Wi-Fi reachability from printer authentication.";
        }
        if (error instanceof TimeoutException) return "Printer registration timed out. HTTP and MQTT connected, but the printer did not register this client.";
        return stage + " failed. Run Check connection and compare the IP with the printer's Network screen.";
    }
    public static boolean retryable(Throwable error) {
        if (error instanceof Rejected || error instanceof IllegalArgumentException || error instanceof IllegalStateException) return false;
        if (error instanceof MissingIdentity || error instanceof org.json.JSONException) return false;
        if (error instanceof HttpStatus) return ((HttpStatus) error).status >= 500;
        if (error instanceof MqttException) {
            int code = ((MqttException) error).getReasonCode();
            return code != 4 && code != 5;
        }
        return true;
    }
}
