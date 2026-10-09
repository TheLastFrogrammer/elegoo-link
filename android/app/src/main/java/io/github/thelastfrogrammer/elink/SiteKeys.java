package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** The user's own model-site token and key, encrypted with a key kept in the Android Keystore; never logged. */
final class SiteKeys {
    static final String THINGIVERSE = "thingiverse", MYMINIFACTORY = "myminifactory";
    private static final String ALIAS = "link-workshop-sites-v1";
    private final SharedPreferences prefs;
    SiteKeys(Context context) { prefs = context.getSharedPreferences("model-sites", Context.MODE_PRIVATE); }

    boolean has(String site) { return prefs.contains("cipher:" + site); }
    /** The saved value, or "" when there is none or it can no longer be read. */
    String load(String site) {
        String stored = prefs.getString("cipher:" + site, ""), iv = prefs.getString("iv:" + site, "");
        if (stored.isEmpty() || iv.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            cipher.updateAAD(site.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.decode(stored, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception unreadable) { return ""; }
    }
    /** Saves (or, with an empty value, forgets) a token or key. */
    void save(String site, String value) throws Exception {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) { prefs.edit().remove("cipher:" + site).remove("iv:" + site).apply(); return; }
        if (v.length() > 512 || !v.matches("[\\x21-\\x7e]+")) throw new IllegalArgumentException("That does not look like a token or key.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(site.getBytes(StandardCharsets.UTF_8));
        prefs.edit().putString("cipher:" + site, Base64.encodeToString(cipher.doFinal(v.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP))
            .putString("iv:" + site, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).apply();
    }
    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException("No key");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
}
