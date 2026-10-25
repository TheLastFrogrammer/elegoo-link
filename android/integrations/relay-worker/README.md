# Link Workshop relay

A small Cloudflare Worker that keeps the newest printer status, print events, picture and timelapse that the Link
Workshop app pushes to it. It is for readers that cannot reach your home network, such as a Reddit app. It accepts
only pushes signed with your relay secret, and it cannot reach the printer.

To set it up, follow "Setting up the ready relay" in [`../../INTEGRATIONS.md`](../../INTEGRATIONS.md#the-relay):
create a KV namespace, set `LW_SECRET` and `READ_KEY`, then run `npx wrangler deploy`.

`node test.mjs` checks it against a fake store, using the same signature test vector as the app's tests.
