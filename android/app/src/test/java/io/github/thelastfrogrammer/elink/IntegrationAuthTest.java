package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** Paired devices, their tokens (only a hash kept), pairing with a code approved on the phone, and tickets. */
public class IntegrationAuthTest {
    private static final class Memory implements IntegrationAuth.Storage { String json = "[]"; public String load() { return json; } public void save(String j) { json = j; } }

    @Test public void tokensAreCheckedAndOnlyTheirHashIsKept() {
        Memory m = new Memory(); IntegrationAuth auth = new IntegrationAuth(m);
        String token = auth.add("Living room TV", 1000);
        assertTrue(token.startsWith("lw1_"));
        assertFalse("the token itself is never stored", m.json.contains(token.substring(4)));
        assertEquals("Living room TV", auth.check(token, 2000).name);
        assertNull(auth.check(token + "x", 2000)); assertNull(auth.check("lw1_guess", 2000)); assertNull(auth.check(null, 2000));
        // Kept across restarts.
        IntegrationAuth again = new IntegrationAuth(m);
        assertNotNull(again.check(token, 3000));
        again.revoke(again.devices().get(0).id);
        assertNull(again.check(token, 4000));
    }

    @Test public void aDevicePairsOnlyWhenThePersonApproves() throws Exception {
        IntegrationAuth auth = new IntegrationAuth(new Memory());
        IntegrationAuth.Pairing[] heard = new IntegrationAuth.Pairing[1]; auth.onRequest = p -> heard[0] = p;
        IntegrationAuth.Pairing p = auth.request("Fire TV\u0007", 0);
        assertSame(p, heard[0]); assertEquals("Fire TV", p.name); assertTrue(p.code.matches("\\d{4}"));
        JSONObject waiting = auth.poll(p.id, 1000);
        assertEquals("pending", waiting.getString("state")); assertEquals(p.code, waiting.getString("code")); assertFalse(waiting.has("token"));
        assertTrue(auth.approve(p.id, 2000));
        JSONObject approved = auth.poll(p.id, 3000);
        assertEquals("approved", approved.getString("state"));
        assertNotNull(auth.check(approved.getString("token"), 4000));
        assertNull("the token is handed out once", auth.poll(p.id, 5000));

        IntegrationAuth.Pairing refused = auth.request("Someone", 0); auth.deny(refused.id);
        assertEquals("denied", auth.poll(refused.id, 1000).getString("state"));
        IntegrationAuth.Pairing late = auth.request("Late", 0);
        assertNull("pairing requests run out", auth.poll(late.id, IntegrationAuth.PAIRING_MS + 1));
        assertFalse(auth.approve(late.id, IntegrationAuth.PAIRING_MS + 1));
        for (int i = 0; i < IntegrationAuth.MAX_PENDING; i++) assertNotNull(auth.request("Device " + i, 10_000_000));
        assertNull("too many waiting", auth.request("One more", 10_000_000));
    }

    @Test public void ticketsStandInForATokenBriefly() {
        IntegrationAuth auth = new IntegrationAuth(new Memory());
        IntegrationAuth.Device d = auth.check(auth.add("Browser", 0), 0);
        String ticket = auth.ticket(d, 1000);
        assertEquals("Browser", auth.redeem(ticket, 1000 + IntegrationAuth.TICKET_MS - 1).name);
        assertNull(auth.redeem(ticket, 1000 + IntegrationAuth.TICKET_MS + 1));
        String other = auth.ticket(d, 0); auth.revoke(d.id);
        assertNull("a removed device's tickets go with it", auth.redeem(other, 1));
    }
}
