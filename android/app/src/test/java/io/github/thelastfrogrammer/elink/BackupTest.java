package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackupTest {
    private Context context;
    @Before public void setUp() { context = RuntimeEnvironment.getApplication(); CredentialStore.testKey = new SecretKeySpec(new byte[32], "AES"); }
    @After public void tearDown() { CredentialStore.testKey = null; }

    private File backup(boolean recordings, String password) throws Exception {
        File file = File.createTempFile("backup", ".zip");
        try (FileOutputStream out = new FileOutputStream(file)) { Backup.export(context, out, recordings, password == null ? null : password.toCharArray()); }
        return file;
    }
    private void setUpPhone() throws Exception {
        context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE).edit().putInt("theme", 2).putBoolean("guardian", false).putLong("since", 5L)
            .putFloat("gain", 1.5f).putStringSet("set", Set.of("a", "b")).putString("monitorSource", "cam1").commit();
        context.getSharedPreferences("viewer-hints", Context.MODE_PRIVATE).edit().putString("cameraPoints@cam1", "[[0,1,2,3,4,5,6,0,0]]").commit();
        context.getSharedPreferences("cloud-account", Context.MODE_PRIVATE).edit().putString("token", "SECRET-TOKEN").commit();
        Files.write(new File(context.getFilesDir(), "automations.json").toPath(), "{\"rules\":[]}".getBytes(StandardCharsets.UTF_8));
        File rec = new File(context.getFilesDir(), "recordings/1-part-pictures/000060.jpg"); rec.getParentFile().mkdirs(); Files.write(rec.toPath(), new byte[] {1, 2, 3});
        CredentialStore store = new CredentialStore(context);
        store.save("192.168.1.20", "654321", true);
        store.saveSecret("camera-url:cam1", "http://10.0.0.9/stream?token=CAMTOKEN");
    }

    @Test public void aBackupComesBackWithItsSecretsOnlyWithThePassword() throws Exception {
        setUpPhone();
        File withSecrets = backup(true, "correct horse"), plain = backup(false, null);
        String plainText = new String(Files.readAllBytes(plain.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse("no secrets in a backup without a password", plainText.contains("CAMTOKEN") || plainText.contains("654321"));
        assertFalse("never the Elegoo sign-in", plainText.contains("SECRET-TOKEN") || new String(Files.readAllBytes(withSecrets.toPath()), StandardCharsets.ISO_8859_1).contains("SECRET-TOKEN"));
        Backup.Summary s = Backup.inspect(withSecrets);
        assertTrue(s.secrets); assertEquals(1, s.recordings); assertTrue(s.prefs >= 2);

        // A new phone: everything gone.
        for (String name : new String[] {"workshop-settings", "viewer-hints", "printer-profile"}) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        new File(context.getFilesDir(), "automations.json").delete(); new File(context.getFilesDir(), "recordings/1-part-pictures/000060.jpg").delete();

        try { Backup.restore(context, withSecrets, "wrong".toCharArray()); fail("a wrong password restores"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("password")); }
        assertEquals("nothing changes on a wrong password", 0, context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE).getInt("theme", 0));

        Backup.restore(context, withSecrets, "correct horse".toCharArray());
        SharedPreferences settings = context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE);
        assertEquals(2, settings.getInt("theme", 0)); assertFalse(settings.getBoolean("guardian", true)); assertEquals(5L, settings.getLong("since", 0));
        assertEquals(1.5f, settings.getFloat("gain", 0), 0); assertEquals(Set.of("a", "b"), settings.getStringSet("set", null)); assertEquals("cam1", settings.getString("monitorSource", ""));
        assertEquals("[[0,1,2,3,4,5,6,0,0]]", context.getSharedPreferences("viewer-hints", Context.MODE_PRIVATE).getString("cameraPoints@cam1", ""));
        assertTrue(new File(context.getFilesDir(), "automations.json").isFile());
        assertTrue(new File(context.getFilesDir(), "recordings/1-part-pictures/000060.jpg").isFile());
        CredentialStore store = new CredentialStore(context);
        assertEquals("654321", store.load("192.168.1.20")); assertEquals("http://10.0.0.9/stream?token=CAMTOKEN", store.load("camera-url:cam1"));
        assertEquals("the printer's address comes back too", "192.168.1.20", store.host());
    }

    @Test public void restoringWithoutThePasswordKeepsTheRestAndOtherThingsAreRefused() throws Exception {
        setUpPhone();
        File withSecrets = backup(false, "correct horse");
        new CredentialStore(context).forget("camera-url:cam1");
        Backup.Summary done = Backup.restore(context, withSecrets, new char[0]);
        assertFalse(done.secrets); assertFalse(new CredentialStore(context).remembers("camera-url:cam1"));

        File notOurs = File.createTempFile("zip", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(notOurs))) { zip.putNextEntry(new ZipEntry("hello.txt")); zip.write(1); zip.closeEntry(); }
        try { Backup.inspect(notOurs); fail("not a backup"); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("not a Link Workshop backup")); }

        File climbing = File.createTempFile("zip", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(climbing))) {
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write("{\"format\":\"linkWorkshopBackup\",\"version\":1}".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("recordings/../../shared_prefs/evil.xml")); zip.write(1); zip.closeEntry();
        }
        try { Backup.restore(context, climbing, null); fail("a file outside the folders"); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("outside")); }

        File newer = File.createTempFile("zip", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(newer))) { zip.putNextEntry(new ZipEntry("manifest.json")); zip.write("{\"format\":\"linkWorkshopBackup\",\"version\":9}".getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        try { Backup.inspect(newer); fail("newer format"); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("newer")); }
    }

    @Test public void sealedSecretsOpenOnlyWithTheirPassword() throws Exception {
        byte[] sealed = Backup.seal("{\"a\":\"b\"}".getBytes(StandardCharsets.UTF_8), "pw123456".toCharArray());
        assertEquals("{\"a\":\"b\"}", new String(Backup.open(sealed, "pw123456".toCharArray()), StandardCharsets.UTF_8));
        try { Backup.open(sealed, "nope".toCharArray()); fail(); } catch (javax.crypto.AEADBadTagException expected) { }
        sealed[sealed.length - 1] ^= 1;
        try { Backup.open(sealed, "pw123456".toCharArray()); fail("tampered"); } catch (javax.crypto.AEADBadTagException expected) { }
    }
}
