# Printer on TV

A Fire TV and Android TV app that watches your printer on the big screen: the camera full screen, with the print's
progress, times and temperatures over it. It reads everything through **Link Workshop on your phone** (its local
API, see [`../INTEGRATIONS.md`](../INTEGRATIONS.md)), so the printer still sees only the phone. It can watch, never
control: the only thing it ever sends is the pairing request.

Needs Fire OS 6 or later (Android 7.1), or Android TV 7.1 or later, on the same Wi-Fi as the phone.

## Install on a Fire TV

The app is not in the Amazon Appstore. Install it yourself (sideload):

1. On the Fire TV: **Settings › My Fire TV › Developer options**. If it is not there, open **Settings › My Fire TV ›
   About** and select the device name seven times. Turn on **ADB debugging**, and **Install unknown apps** for the app
   you will install with (older Fire OS calls it **Apps from Unknown Sources**). Note the TV's address under
   **About › Network**.
2. Install the APK (`tv-debug.apk`, or a release you built and signed) in one of two ways:
   - **From a computer with ADB:** `adb connect <TV address>`, accept the prompt on the TV, then
     `adb install -r tv-debug.apk`.
   - **With a downloader app on the TV** (such as "Downloader"): put the APK on a web server on your network (or a link
     you trust), open its address in the downloader and install.
3. The app shows in **Your Apps & Channels** as "Printer on TV". Move it to the front if you like.

On Android TV / Google TV the steps are the same: Developer options (select **Build** seven times), then ADB or a
file manager.

To build it: from `android/`, `./gradlew :tv:assembleDebug` (the APK is in `tv/build/outputs/apk/debug/`).
`:tv:assembleRelease` makes an unsigned release APK; sign it with your own key (`apksigner sign --ks …`). It is a
separate app from the phone app, with its own id (`io.github.thelastfrogrammer.elink.tv`).

## First pairing

1. On the phone, open Link Workshop and turn on **Settings › Automations & integrations › Other apps**.
2. Open Printer on TV. It looks for the phone on the network; choose it (or **Enter the address**: the Other apps
   screen shows it, like `192.168.1.20:8765`).
3. The TV shows a 4-digit code and how long it works (5 minutes). The phone shows a notification and the same code
   under Other apps. Check the codes match and tap **Approve**.

The TV keeps the phone's address and its token (in its private storage, never backed up). The next time it opens
straight on the camera. If the phone's address changes, it finds the phone again on the network. If you remove the TV
on the phone, it asks to pair again.

## What it shows

- **The camera**, full screen, turned and mirrored as the camera is set up on the phone. **Left** and **Right** switch
  between the printer's camera and any cameras added on the phone. In Elegoo cloud mode the printer's camera is the
  print's newest kept picture (the screen says so).
- **The status** over the lower part of the picture: the printer's name, how the phone reaches it, what it is doing,
  the file, a progress bar with the percentage, layer x of y, time left and "done about" in the TV's clock and format,
  nozzle, bed and chamber temperatures, part fan, and any fault codes. **OK** hides it (a thin progress line stays) and
  shows it again; the TV remembers which.
- **Print events** as a banner at the top for a few seconds: started, first layer done, paused, resumed, finished,
  stopped, a fault, the print guardian or print watch seeing a problem, contact lost, levelling and maintenance done.
- **Back** goes to the phone list (to change phones); Back again leaves the app.

The screen stays on while the camera is open and while a pairing code is shown.

## When things are away

- **The phone cannot be reached** (Link Workshop closed, phone asleep, Wi-Fi down): after a few seconds the TV says so
  calmly, keeps the last status (faded) and the last picture, and tries again: after 2 s, then 4, 8, 16, 32 and at most
  every minute. When the TV's network comes back it tries at once. A phone that is busy (too many screens) is given at
  least 10 s.
- **The phone has lost the printer**: the status says "The phone is not connected to the printer" or "Waiting for the
  printer" and how old the last status is; no old temperatures are shown as if current.
- **The camera sends nothing**: the picture says so and when the next try is.

## Limits

- Home network only, plain `http` (as the phone's API is): see INTEGRATIONS.md. The TV allows cleartext traffic for this
  reason (`res/xml/network_security_config.xml`).
- One phone at a time. Choosing another phone keeps the token; if that phone refuses it, the TV pairs with it, and
  pairing with the first one again later is needed when you switch back.
- No sound, no control, no history: the TV shows the present only.
- The TV shows what the phone reports, rounded: times are the printer's own estimates.
- Tested on the JVM (parsers, a fake phone over real sockets, the phone's own server code, and the screens rendered at
  1920×1080 with Robolectric), not yet on a physical Fire TV.

## For developers

Plain Android framework, Java, no libraries; views built in code like the phone app.

| File | What |
|---|---|
| `FindActivity`, `AddressActivity`, `PairActivity`, `WatchActivity` | The four screens. |
| `PhoneFinder` | DNS-SD discovery of `_linkworkshop._tcp` (one resolve at a time, for Fire OS 6). |
| `PhoneApi`, `PairingSession`, `PrinterLink` | The API calls, pairing, and the event and camera streams with their back-off (pure Java). |
| `SseParser`, `MjpegReader`, `StatusText`, `Backoff`, `PhoneAddress` | Pure parsers and wording, with JVM tests. |

Tests: `./gradlew :tv:testDebugUnitTest`. Add `-Dscreenshots=<dir>` to write each screen as a 1920×1080 PNG.
