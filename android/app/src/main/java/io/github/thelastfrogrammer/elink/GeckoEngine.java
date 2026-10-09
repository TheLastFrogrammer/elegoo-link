package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

/**
 * Firefox's engine (GeckoView) for the model-site browser: one runtime for the whole app (Gecko allows only one), set up
 * for secure pages only, Firefox's standard tracking protection and Safe Browsing, no about:config, no remote debugging,
 * and no website able to install add-ons. The one add-on it takes is uBlock Origin, installed only when the user asks,
 * from Mozilla's add-on site (Gecko checks Mozilla's signature).
 */
final class GeckoEngine {
    static final String AD_BLOCKER_ID = "uBlock0@raymondhill.net";
    static final String AD_BLOCKER_NAME = "uBlock Origin";
    static final String AD_BLOCKER_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi";
    private static final long UPDATE_EVERY_MS = 7L * 24 * 3600 * 1000;
    private static GeckoRuntime runtime;
    /** Set only while the user's own "Install the ad blocker" is running, so no other install is ever allowed. */
    private static volatile boolean installing;

    /** False under the unit-test runner (Robolectric), which cannot load Gecko's native engine. */
    static boolean available() { return !"robolectric".equals(Build.FINGERPRINT); }

    static synchronized GeckoRuntime runtime(Context context, boolean dark) {
        if (runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(false)
                .aboutConfigEnabled(false)
                .extensionsWebAPIEnabled(false)          // sites cannot install add-ons
                .loginAutofillEnabled(false)
                .consoleOutput(false)
                .allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
                .preferredColorScheme(dark ? GeckoRuntimeSettings.COLOR_SCHEME_DARK : GeckoRuntimeSettings.COLOR_SCHEME_LIGHT)
                .contentBlocking(new ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT).build())
                .build();
            runtime = GeckoRuntime.create(context.getApplicationContext(), settings);
            runtime.getWebExtensionController().setPromptDelegate(new WebExtensionController.PromptDelegate() {
                @Override public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(WebExtension extension, String[] permissions, String[] origins, String[] data) {
                    boolean ours = installing && AD_BLOCKER_ID.equals(extension.id);
                    return GeckoResult.fromValue(new WebExtension.PermissionPromptResponse(ours, false, false));
                }
                @Override public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onUpdatePrompt(WebExtension extension, String[] permissions, String[] origins, String[] data) {
                    return AD_BLOCKER_ID.equals(extension.id) ? GeckoResult.allow() : GeckoResult.deny();
                }
                @Override public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onOptionalPrompt(WebExtension extension, String[] permissions, String[] origins, String[] data) {
                    return GeckoResult.deny();
                }
            });
        } else {
            runtime.getSettings().setPreferredColorScheme(dark ? GeckoRuntimeSettings.COLOR_SCHEME_DARK : GeckoRuntimeSettings.COLOR_SCHEME_LIGHT);
        }
        return runtime;
    }

    /** The installed ad blocker, or null. */
    static GeckoResult<WebExtension> adBlocker(GeckoRuntime runtime) {
        return runtime.getWebExtensionController().list().map(list -> {
            if (list != null) for (WebExtension extension : list) if (AD_BLOCKER_ID.equals(extension.id)) return extension;
            return null;
        });
    }

    static GeckoResult<WebExtension> installAdBlocker(GeckoRuntime runtime) {
        installing = true;
        return runtime.getWebExtensionController().install(AD_BLOCKER_URL).map(extension -> {
            installing = false;
            if (extension == null || !AD_BLOCKER_ID.equals(extension.id)) {   // never keep anything else
                if (extension != null) runtime.getWebExtensionController().uninstall(extension);
                throw new IllegalStateException("Mozilla's add-on site sent a different add-on.");
            }
            return extension;
        }, failure -> { installing = false; return failure; });
    }

    static GeckoResult<WebExtension> setAdBlocker(GeckoRuntime runtime, WebExtension extension, boolean on) {
        WebExtensionController controller = runtime.getWebExtensionController();
        return on ? controller.enable(extension, WebExtensionController.EnableSource.USER) : controller.disable(extension, WebExtensionController.EnableSource.USER);
    }

    /** Checks Mozilla's add-on site for a newer ad blocker about once a week (only while the browser is open). */
    static void updateIfDue(Context context, GeckoRuntime runtime, WebExtension extension) {
        SharedPreferences prefs = context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("adBlockerChecked", 0) < UPDATE_EVERY_MS) return;
        prefs.edit().putLong("adBlockerChecked", now).apply();
        runtime.getWebExtensionController().update(extension);
    }

    /** Signs out of every site: their cookies, storage and caches (the ad blocker stays). */
    static GeckoResult<Void> signOutEverywhere(GeckoRuntime runtime) {
        return runtime.getStorageController().clearData(StorageController.ClearFlags.COOKIES | StorageController.ClearFlags.DOM_STORAGES
            | StorageController.ClearFlags.AUTH_SESSIONS | StorageController.ClearFlags.ALL_CACHES);
    }

    private GeckoEngine() { }
}
