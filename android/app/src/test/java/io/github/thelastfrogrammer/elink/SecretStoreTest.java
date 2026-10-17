package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Secrets that are not printer access codes (camera addresses, webhook tokens, the Home Assistant password) save and load. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SecretStoreTest {
    @After public void reset() { CredentialStore.testKey = null; }

    @Test public void otherSecretsSaveWithoutTouchingThePrinterProfile() throws Exception {
        CredentialStore.testKey = new SecretKeySpec(new byte[32], "AES");
        Context context = RuntimeEnvironment.getApplication();
        CredentialStore store = new CredentialStore(context);
        store.save("192.168.1.20", "123456", true);
        store.saveSecret("camera-url:abc", "http://cam.local:8080/?action=stream&token=xyz");
        store.saveSecret("homeassistant:ha.local", "pa ss\nword");
        assertEquals("http://cam.local:8080/?action=stream&token=xyz", store.load("camera-url:abc"));
        assertEquals("pa ss\nword", store.load("homeassistant:ha.local"));
        assertEquals("the printer's address is unchanged", "192.168.1.20", store.host());
        assertEquals("123456", store.load());
        assertTrue(store.remembered().contains("camera-url:abc")); assertTrue(store.remembered().contains("192.168.1.20"));
        store.forget("camera-url:abc");
        assertFalse(store.remembers("camera-url:abc"));

        // Through the camera store too: the address round-trips.
        ExtraCameras.Camera camera = new ExtraCameras.Camera();
        ExtraCameras.setSecrets(context, camera, "http://10.0.0.5/snap.jpg", "secret");
        assertEquals("http://10.0.0.5/snap.jpg", ExtraCameras.url(context, camera)); assertEquals("secret", ExtraCameras.password(context, camera));
    }
}
