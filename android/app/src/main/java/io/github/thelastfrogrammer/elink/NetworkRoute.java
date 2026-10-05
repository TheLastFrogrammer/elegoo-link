package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.net.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
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
    public Cc2Discovery discovery() {
        List<InetAddress> broadcasts = new ArrayList<>();
        LinkProperties props = manager.getLinkProperties(network);
        if (props != null) for (LinkAddress address : props.getLinkAddresses()) {
            if (!(address.getAddress() instanceof Inet4Address) || address.getPrefixLength() >= 31) continue;
            byte[] bytes = address.getAddress().getAddress();
            int ip = 0; for (byte value : bytes) ip = (ip << 8) | (value & 255);
            int mask = address.getPrefixLength() == 0 ? 0 : -1 << (32 - address.getPrefixLength());
            int broadcast = ip | ~mask;
            try { broadcasts.add(InetAddress.getByAddress(new byte[] {(byte)(broadcast >>> 24), (byte)(broadcast >>> 16), (byte)(broadcast >>> 8), (byte)broadcast})); }
            catch (UnknownHostException ignored) { }
        }
        return new Cc2Discovery(() -> {
            DatagramSocket socket = new DatagramSocket();
            try { network.bindSocket(socket); return socket; }
            catch (IOException error) { socket.close(); throw error; }
        }, broadcasts);
    }
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
    public String check(String host, String code, String manualSerial) {
        PrinterHttp http = new PrinterHttp(host, code, http());
        StringBuilder text = new StringBuilder(details()).append("Printer IP: ").append(host).append("\n\n");
        boolean web = probe(host, 80, text), mqtt = probe(host, 1883, text);
        boolean identity = !manualSerial.isEmpty();
        Cc2Discovery.Info info = null;
        try (Cc2Discovery discovery = discovery()) {
            info = discovery.discoverInfo(host); identity = true;
            text.append("UDP 52700 discovery: printer identity received.\n").append(info.summary()).append('\n');
            if (!manualSerial.isEmpty() && !manualSerial.equals(info.serial)) text.append("Manual serial differs from discovery. Connect will use the discovered serial.\n");
        } catch (IOException error) {
            text.append("UDP 52700 discovery: no printer identity received; LAN mode and code protection unknown.\n");
            if (identity) text.append("MQTT identity: manual serial supplied; Connect verifies registration.\n");
        }
        if (web) {
            try {
                PrinterHttp effective = info != null && Boolean.FALSE.equals(info.codeProtected) ? new PrinterHttp(host, "", http()) : http;
                try { effective.systemInfo(); } finally { effective.cancel(); }
                identity = true; text.append("HTTP system info: accepted; printer identity returned.\n");
            }
            catch (Exception error) { text.append("HTTP system info: ").append(PrinterErrors.describe(error, "HTTP authentication")).append('\n'); }
        }
        if (info != null && Boolean.FALSE.equals(info.lanOnly)) text.append("\nThe printer reports cloud / WAN mode. Enable LAN Only for this app's supported authentication path. Cloud pairing uses a different credential path.");
        else if (!web && !mqtt) text.append("\nNeither TCP port is reachable. Confirm the current printer IP, LAN Only, same Wi-Fi, and that the router does not isolate guest devices.");
        else if (!mqtt) text.append("\nHTTP is reachable but MQTT is not. Confirm LAN Only is enabled and local port 1883 is not blocked.");
        else if (!web) text.append(identity ? "\nMQTT is reachable and identity is available. Connect can use MQTT without HTTP. HTTP file uploads remain unavailable." : "\nMQTT is reachable. Enter the exact Serial Number from Settings → Device in the optional serial field to connect without HTTP or UDP discovery. HTTP file uploads remain unavailable.");
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
