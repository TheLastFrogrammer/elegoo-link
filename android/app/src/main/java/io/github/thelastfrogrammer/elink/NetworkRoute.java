package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.net.*;
import java.io.IOException;
import java.net.*;
import javax.net.SocketFactory;

/** Bind local printer traffic to Wi-Fi, including Wi-Fi without internet validated by Android. */
public final class NetworkRoute {
    public final Network network;
    private final ConnectivityManager manager;
    private NetworkRoute(Network network, ConnectivityManager manager) { this.network = network; this.manager = manager; }
    public static NetworkRoute local(Context context) throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))
                return new NetworkRoute(network, manager);
        }
        throw new IOException("No local Wi-Fi network is available. Connect this phone to the printer's Wi-Fi network, then try again.");
    }
    public SocketFactory sockets() { return network.getSocketFactory(); }
    public PrinterHttp.ConnectionFactory http() { return url -> (HttpURLConnection) network.openConnection(url); }
    public String details() {
        StringBuilder text = new StringBuilder("Local printer traffic: Wi-Fi / Ethernet\n");
        LinkProperties props = manager.getLinkProperties(network);
        if (props != null) for (LinkAddress address : props.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address) text.append("Phone IP: ").append(address.getAddress().getHostAddress()).append('/').append(address.getPrefixLength()).append('\n');
        }
        Network active = manager.getActiveNetwork(); NetworkCapabilities caps = active == null ? null : manager.getNetworkCapabilities(active);
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) text.append("VPN detected. Printer traffic uses the local network directly; VPN policy can still block it.\n");
        return text.toString();
    }
    public String check(String host, String code) {
        PrinterHttp http = new PrinterHttp(host, code, http());
        StringBuilder text = new StringBuilder(details()).append("Printer IP: ").append(host).append("\n\n");
        boolean web = probe(host, 80, text), mqtt = probe(host, 1883, text);
        if (web) {
            try { http.systemInfo(); text.append("HTTP system info: accepted; printer identity returned.\n"); }
            catch (Exception error) { text.append("HTTP system info: ").append(PrinterErrors.describe(error, "HTTP authentication")).append('\n'); }
        }
        if (!web && !mqtt) text.append("\nNeither printer port is reachable. An access code cannot fix this. Confirm the current printer IP, LAN Only, same Wi-Fi, and that the router does not isolate guest devices.");
        else if (!mqtt) text.append("\nHTTP is reachable but MQTT is not. Confirm LAN Only is enabled and local port 1883 is not blocked.");
        else if (!web) text.append("\nMQTT is reachable but HTTP is not. This app also needs the printer's HTTP system-information endpoint on port 80.");
        else text.append("\nBoth ports are reachable. TCP checks alone do not prove MQTT authentication or client registration; Connect tests those next.");
        http.cancel(); return text.toString();
    }
    private boolean probe(String host, int port, StringBuilder result) {
        try (Socket socket = sockets().createSocket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            result.append("TCP ").append(port).append(port == 80 ? " (HTTP)" : " (MQTT)").append(": reachable\n"); return true;
        } catch (Exception error) {
            result.append("TCP ").append(port).append(": ").append(error instanceof SocketTimeoutException ? "timed out" : error instanceof ConnectException ? "refused or unreachable" : "unreachable").append('\n'); return false;
        }
    }
}
