package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;
import org.junit.Test;

/** The cloud camera's messages in plain words, with the next step for each problem. */
public class CloudCameraExplainTest {
    @Test public void noVideoNamesTheCheckAndTheNextStep() {
        String text = CloudCameraActivity.explain("The printer is not sending video. It may be off, offline, or its camera may be disabled.");
        assertTrue(text, text.startsWith("No video from the printer."));
        assertTrue(text, text.endsWith("then tap Try again."));
        assertTrue(CloudCameraActivity.isProblem("The printer is not sending video. It may be off, offline, or its camera may be disabled."));
    }

    @Test public void connectionDetailsStayInBrackets() {
        String text = CloudCameraActivity.explain("Disconnected from the camera service (ICE_FAILED).");
        assertTrue(text, text.contains("[ICE_FAILED]"));
        assertTrue(CloudCameraActivity.isProblem("Disconnected from the camera service (ICE_FAILED)."));
    }

    @Test public void progressMessagesAreNotProblems() {
        assertFalse(CloudCameraActivity.isProblem("Connecting to the camera…"));
        assertFalse(CloudCameraActivity.isProblem("Connected. Waiting for the printer to send video…"));
        assertTrue(CloudCameraActivity.explain("Connecting to the camera…").startsWith("Connecting to the printer's camera"));
    }

    @Test public void missingCredentialsPointToSignIn() {
        String text = CloudCameraActivity.explain("Sign in with Elegoo in Settings first.");
        assertTrue(text, text.startsWith("Not signed in."));
        assertTrue(CloudCameraActivity.explain("Elegoo did not issue camera credentials for this account.").contains("linked to this Elegoo account"));
    }

    @Test public void unknownFailuresStillSayToTryAgain() {
        assertTrue(CloudCameraActivity.explain("Elegoo refused the sign-in (HTTP 403).").endsWith("Tap Try again, or sign in again in Settings if it keeps failing."));
    }
}
