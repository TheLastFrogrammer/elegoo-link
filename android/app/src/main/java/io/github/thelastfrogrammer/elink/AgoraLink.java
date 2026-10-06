package io.github.thelastfrogrammer.elink;

import io.agora.rtm.ErrorInfo;
import io.agora.rtm.LinkStateEvent;
import io.agora.rtm.MessageEvent;
import io.agora.rtm.PublishOptions;
import io.agora.rtm.ResultCallback;
import io.agora.rtm.RtmClient;
import io.agora.rtm.RtmConfig;
import io.agora.rtm.RtmConstants;
import io.agora.rtm.RtmEventListener;
import io.agora.rtm.SubscribeOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Agora RTM 2.x implementation of {@link CloudControl.Link}, mirroring the SDK's RtmClient options. */
final class AgoraLink implements CloudControl.Link {
    private static final long OPERATION_TIMEOUT_S = 10;
    private RtmClient client;
    private volatile boolean closed;

    @Override public void login(String appId, String rtmUserId, String token, Events events) throws IOException {
        RtmEventListener listener = new RtmEventListener() {
            @Override public void onMessageEvent(MessageEvent event) {
                Object data = event.getMessage() == null ? null : event.getMessage().getData();
                String text = data instanceof byte[] ? new String((byte[]) data, StandardCharsets.UTF_8) : data == null ? "" : data.toString();
                events.message(event.getPublisherId(), text);
            }
            @Override public void onLinkStateEvent(LinkStateEvent event) {
                if (closed) return;
                RtmConstants.RtmLinkStateChangeReason reason = event.getReasonCode();
                if (reason == RtmConstants.RtmLinkStateChangeReason.SAME_UID_LOGIN)
                    events.ended("Another Elegoo app (such as ElegooSlicer) signed in to cloud control with this account, so this app's cloud control session ended.");
                else if (reason == RtmConstants.RtmLinkStateChangeReason.KICKED_OUT_BY_SERVER || reason == RtmConstants.RtmLinkStateChangeReason.TOKEN_EXPIRED
                    || reason == RtmConstants.RtmLinkStateChangeReason.INVALID_TOKEN || event.getCurrentState() == RtmConstants.RtmLinkState.FAILED)
                    events.ended("Elegoo's cloud control service ended the session. Try again later.");
            }
        };
        try {
            client = RtmClient.create(new RtmConfig.Builder(appId, rtmUserId).eventListener(listener).build());
        } catch (Exception error) { throw new IOException("Cloud control could not start on this phone."); }
        await("sign in to cloud control", callback -> client.login(token, callback));
    }

    @Override public void subscribe(String channel) throws IOException {
        SubscribeOptions options = new SubscribeOptions(); options.setWithMessage(true); options.setWithPresence(true);
        await("subscribe to printer replies", callback -> client.subscribe(channel, options, callback));
    }

    @Override public void publish(String channel, String text) throws IOException {
        PublishOptions options = new PublishOptions();
        options.setChannelType(RtmConstants.RtmChannelType.USER); options.setCustomType("PlainText");
        await("send the command", callback -> client.publish(channel, text, options, callback));
    }

    @Override public void close() {
        closed = true;
        RtmClient current = client; client = null;
        if (current == null) return;
        try { current.logout(new ResultCallback<Void>() { public void onSuccess(Void v) { } public void onFailure(ErrorInfo error) { } }); } catch (Exception ignored) { }
        try { RtmClient.release(); } catch (Exception ignored) { }
    }

    private interface Operation { void run(ResultCallback<Void> callback); }
    private void await(String what, Operation operation) throws IOException {
        CountDownLatch done = new CountDownLatch(1);
        final String[] failure = {null};
        try {
            operation.run(new ResultCallback<Void>() {
                public void onSuccess(Void value) { done.countDown(); }
                public void onFailure(ErrorInfo error) {
                    failure[0] = error == null ? "unknown error" : String.valueOf(error.getErrorCode()) + (error.getErrorReason() == null ? "" : " " + error.getErrorReason());
                    done.countDown();
                }
            });
            if (!done.await(OPERATION_TIMEOUT_S, TimeUnit.SECONDS)) throw new IOException("Timed out trying to " + what + " through the cloud.");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Interrupted."); }
        catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("Could not " + what + " through the cloud."); }
        if (failure[0] != null) throw new IOException("Could not " + what + " through the cloud (" + StatusPresentation.clean(failure[0]) + ").");
    }
}
