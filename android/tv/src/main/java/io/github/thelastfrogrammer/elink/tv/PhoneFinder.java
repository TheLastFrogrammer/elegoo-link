package io.github.thelastfrogrammer.elink.tv;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Finds phones running Link Workshop on the network: they announce themselves with DNS-SD as _linkworkshop._tcp.
 * Each one found is resolved to its address, one at a time (older Android, Fire OS 6 among them, resolves only one at
 * once). Listener calls come on the main thread.
 */
final class PhoneFinder {
    static final String TYPE = "_linkworkshop._tcp";

    interface Listener {
        /** A phone, by its announced name (the key), at this base address. Called again if its address changes. */
        void found(String name, String base);
        void lost(String name);
        /** The TV could not look (the network service is not available). */
        void failed();
    }

    private final NsdManager nsd;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> waiting = new ArrayDeque<>();
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving, running;

    PhoneFinder(Context c, Listener listener) { this.nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE); this.listener = listener; }

    void start() {
        if (running || nsd == null) { if (nsd == null) listener.failed(); return; }
        running = true;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo info) { main.post(() -> { if (running) { waiting.add(info); resolveNext(); } }); }
            @Override public void onServiceLost(NsdServiceInfo info) { main.post(() -> { if (running) listener.lost(info.getServiceName()); }); }
            @Override public void onStartDiscoveryFailed(String type, int error) { main.post(() -> { if (running) { running = false; listener.failed(); } }); }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
        };
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (RuntimeException refused) { running = false; listener.failed(); }
    }

    void stop() {
        if (!running) return;
        running = false; waiting.clear();
        try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException notStarted) { /* nothing to stop */ }
    }

    @SuppressWarnings("deprecation")   // resolveService: the one way that works back to Android 7.1
    private void resolveNext() {
        if (resolving || waiting.isEmpty() || !running) return;
        NsdServiceInfo next = waiting.poll();
        resolving = true;
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onServiceResolved(NsdServiceInfo info) {
                    main.post(() -> {
                        resolving = false;
                        InetAddress address = address(info);
                        if (running && address != null && info.getPort() > 0) listener.found(info.getServiceName(), PhoneAddress.base(address, info.getPort()));
                        resolveNext();
                    });
                }
                @Override public void onResolveFailed(NsdServiceInfo info, int error) {
                    main.post(() -> {
                        resolving = false;
                        // Busy with another resolve (it happens on older Android): try this one again shortly.
                        if (error == NsdManager.FAILURE_ALREADY_ACTIVE && running) main.postDelayed(() -> { if (running) { waiting.add(next); resolveNext(); } }, 500);
                        else resolveNext();
                    });
                }
            });
        } catch (RuntimeException refused) { resolving = false; main.post(this::resolveNext); }
    }

    /** The phone's address, IPv4 first (an IPv6 link-local address needs its network named, which some TVs get wrong). */
    @SuppressWarnings("deprecation")
    private static InetAddress address(NsdServiceInfo info) {
        if (Build.VERSION.SDK_INT >= 34) {
            List<InetAddress> all = info.getHostAddresses();
            for (InetAddress a : all) if (a instanceof Inet4Address) return a;
            return all.isEmpty() ? null : all.get(0);
        }
        return info.getHost();
    }
}
