package io.github.thelastfrogrammer.elink;

import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/** The audit's backup acceptance tests (F03, F15), plus a password backup that brings the automations back whole. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class AuditBackupRegressionTest {
    Context context;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        CredentialStore.testKey=new SecretKeySpec(new byte[32],"AES");
    }
    @After public void cleanup() { CredentialStore.testKey=null; }
    static void entry(ZipOutputStream zip,String name,String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    @Test public void backupMustNotExposeWebhookCredentialsInCleartextEntries() throws Exception {
        Automation.Rule rule=new Automation.Rule();rule.name="Private endpoint";rule.event=Automation.FINISHED;
        Automation.Action action=new Automation.Action();action.kind=Automation.WEBHOOK;
        action.url="https://discord.com/api/webhooks/123456/fake-private-token";rule.actions.add(action);
        Files.write(new File(context.getFilesDir(),"automations.json").toPath(),Automation.save(Collections.singletonList(rule)).getBytes(StandardCharsets.UTF_8));
        for(String password:new String[]{"","backup password"}) {
            File file=File.createTempFile("audit-backup-",".zip");
            try(OutputStream out=new FileOutputStream(file)){Backup.export(context,out,false,password.toCharArray());}
            try(ZipFile zip=new ZipFile(file)) {
                Enumeration<? extends ZipEntry> entries=zip.entries();
                while(entries.hasMoreElements()) {
                    ZipEntry e=entries.nextElement();if(e.getName().equals(Backup.SECRETS)||e.isDirectory())continue;
                    try(InputStream in=zip.getInputStream(e)){
                        assertFalse("URL credential leaked in "+e.getName()+", password protected="+!password.isEmpty(),
                            new String(in.readAllBytes(),StandardCharsets.UTF_8).contains("fake-private-token"));
                    }
                }
            } finally {file.delete();}
        }
    }
    @Test public void aPasswordBackupBringsTheWebhookBackWholeAndWithoutOneItComesBackOff() throws Exception {
        Automation.Rule rule=new Automation.Rule();rule.name="Private endpoint";rule.event=Automation.FINISHED;
        Automation.Action action=new Automation.Action();action.kind=Automation.WEBHOOK;
        action.url="https://discord.com/api/webhooks/123456/fake-private-token";rule.actions.add(action);
        File saved=new File(context.getFilesDir(),"automations.json");
        Files.write(saved.toPath(),Automation.save(Collections.singletonList(rule)).getBytes(StandardCharsets.UTF_8));
        File file=File.createTempFile("audit-backup-",".zip");
        try {
            try(OutputStream out=new FileOutputStream(file)){Backup.export(context,out,false,"pw".toCharArray());}
            saved.delete();
            try {Backup.restore(context,file,"wrong".toCharArray());fail("wrong password");}catch(IOException expected){assertTrue(expected.getMessage().contains("password"));}
            assertFalse("nothing restored with a wrong password",saved.exists());
            Backup.restore(context,file,"pw".toCharArray());
            Automation.Rule back=Automation.load(new String(Files.readAllBytes(saved.toPath()),StandardCharsets.UTF_8)).get(0);
            assertEquals(action.url,back.actions.get(0).url);assertFalse(back.actions.get(0).needsAddress);assertTrue(back.enabled);

            try(OutputStream out=new FileOutputStream(file)){Backup.export(context,out,false,new char[0]);}
            saved.delete();
            Backup.restore(context,file,new char[0]);
            back=Automation.load(new String(Files.readAllBytes(saved.toPath()),StandardCharsets.UTF_8)).get(0);
            assertEquals("https://discord.com/",back.actions.get(0).url);assertTrue(back.actions.get(0).needsAddress);assertFalse(back.enabled);
        } finally {file.delete();saved.delete();}
    }
    @Test public void aRejectedBackupMustLeaveTheExistingSettingsUntouched() throws Exception {
        context.getSharedPreferences("workshop-settings",Context.MODE_PRIVATE).edit().putInt("theme",1).commit();
        File file=File.createTempFile("audit-restore-",".zip");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))) {
            entry(zip,"manifest.json","{\"format\":\"linkWorkshopBackup\",\"version\":1}");
            entry(zip,"prefs/workshop-settings.json","{\"theme\":{\"t\":\"i\",\"v\":2}}");
            entry(zip,"recordings/../../outside.txt","rejected");
        }
        try {
            try {Backup.restore(context,file,new char[0]);fail("fixture should be rejected");}catch(IOException expected){ }
            assertEquals("failed restore partially changed existing settings",1,
                context.getSharedPreferences("workshop-settings",Context.MODE_PRIVATE).getInt("theme",-1));
        } finally {file.delete();}
    }
}
