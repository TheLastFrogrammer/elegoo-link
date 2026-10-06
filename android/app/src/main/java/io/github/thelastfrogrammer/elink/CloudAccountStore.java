package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Elegoo account tokens, encrypted with their own Android Keystore key; separate from printer access codes. */
public final class CloudAccountStore {
    private static final String ALIAS = "link-workshop-cloud-v1";
    private static final byte[] AAD = "elegoo-account".getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences prefs;
    public CloudAccountStore(Context context) { prefs = context.getSharedPreferences("cloud-account", Context.MODE_PRIVATE); }

    /** Stable random ID sent to the sign-in page, like ElegooSlicer's machine-derived device ID. */
    public String deviceId() {
        String id = prefs.getString("deviceId", "");
        if (id.isEmpty()) { id = UUID.randomUUID().toString().replace("-", ""); prefs.edit().putString("deviceId", id).apply(); }
        return id;
    }
    public boolean china() { return prefs.getBoolean("china", false); }
    public void china(boolean china) { prefs.edit().putBoolean("china", china).apply(); }

    public CloudLogin.Account load() throws Exception {
        String stored = prefs.getString("cipher", ""), iv = prefs.getString("iv", "");
        if (stored.isEmpty() || iv.isEmpty()) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        cipher.updateAAD(AAD);
        return CloudLogin.Account.fromJson(new JSONObject(new String(cipher.doFinal(Base64.decode(stored, Base64.NO_WRAP)), StandardCharsets.UTF_8)));
    }
    public void save(CloudLogin.Account account) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(AAD);
        byte[] sealed = cipher.doFinal(account.toJson().toString().getBytes(StandardCharsets.UTF_8));
        if (!prefs.edit().putString("cipher", Base64.encodeToString(sealed, Base64.NO_WRAP)).putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit())
            throw new IllegalStateException("Could not save Elegoo account");
    }
    public void forget() { prefs.edit().remove("cipher").remove("iv").apply(); }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException("Saved Elegoo account key is unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }
}
