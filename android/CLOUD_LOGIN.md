# How ElegooSlicer signs in to the Elegoo cloud

Researched 2026-10-06 to scope a cloud route that works with the printer's LAN Only mode off. Sources: ElegooSlicer `2d507e39` (2026-09-08) and the live account sign-in page. Nothing here has been tried from Android yet.

## Sign-in flow

1. ElegooSlicer opens a web view on `https://www.nexprint.com/account/slicer-login?language=…&region=…&theme=…&deviceId=…` (China: `www.nexprint.cn`). It currently redirects to `https://account.elegoo.com/account/slicer-login`. Source: `src/slic3r/Utils/Elegoo/ElegooNetworkHelper.cpp` (`getLoginUrl`), URLs in `src/libslic3r/libslic3r_version.h.in`.
2. The slicer sets its user agent to `ElegooSlicer/<version> (<theme>) Mozilla/5.0 (<platform>)` (`getUserAgent`).
3. The user signs in on Elegoo's own page. The page then sends the tokens to the host app through a JavaScript bridge: it calls `window.wx.postMessage(JSON.stringify(message))`, retrying a few times if `window.wx` is not there yet. The message is an IPC request with `method: "report.userInfo"`. Other methods include `report.ready`, `report.loginFailed` and `report.notLogged`. Confirmed in the page bundle `account.elegoo.com/assets/index-*.js`.
4. `report.userInfo` params include `userId`, `accessToken`, `accessTokenExpireTime`, `refreshToken`, `refreshTokenExpireTime`, `openid`, `avatar` and the nickname/username. Source: `src/slic3r/GUI/Elegoo/UserLoginView.cpp` (`setupIPCHandlers`).
5. The slicer passes these to this repository's C++ SDK (`ElegooLink::connectToIot`, `refreshToken`, `getUserBoundPrinters`). Source: `src/slic3r/Utils/Elegoo/ElegooUserNetwork.cpp`.

## After sign-in (this repository's SDK)

- **Token refresh:** `/api/v1/account-center-server/account-auth/token/refresh`.
- **Bound printers:** `/api/v1/device-management-server/device/list`.
- **Cloud MQTT credentials:** `GET /api/v1/device-management-server/mqtt-link/mqtt-client?mqttClientId=elegooslicer_<win|mac|linux>_<userId>`. The response contains `host`, `mqttClientId`, `mqttUserName` and `mqttPassword`. Source: `src/cloud/services/http_service.cpp` (`getMqttCredential`).
- **Topics:** subscribe under `app/v1/<mqttClientId>/…`. Source: `src/cloud/services/mqtt_service.cpp`.
- **Live session and camera:** Agora RTM/RTC with server-issued tokens (`device/list/agora-token`). A second login with the same RTM user ID kicks the first one off (`RTM_LINK_STATE_CHANGE_REASON_SAME_UID_LOGIN`).

## What an Android version would need

- A `WebView` on the same sign-in page that provides a `wx` object with a `postMessage(String)` method and handles `report.userInfo` (done; see below). The user types their credentials only into Elegoo's own page; the app never sees the password.
- Tokens stored with the same Keystore protection as the access codes, refreshed before they expire, and cleared on logout.
- A client ID choice. The slicer only uses `win`, `mac` or `linux`. Reusing one of those could collide with a running ElegooSlicer session for the same account. Whether the server accepts `elegooslicer_android_<userId>` is untested.
- Mapping the cloud MQTT messages to the existing status model. The SDK has a separate cloud parser, `src/cloud/adapters/elegoo_fdm_cc2_message_adapter.cpp`, which needs comparing with the LAN one.

## Sign-in test in the app

Settings → **Elegoo account (experimental)** → **Sign in with Elegoo…** (`CloudLoginActivity`, protocol in `CloudLogin`):

- Opens `account.elegoo.com` (or `account.elegoo.com.cn`) in a `WebView`. The user agent adds `ElegooSlicer/1.5.3.5` because the page only uses its `window.wx` bridge for that client, plus `LinkWorkshop/<version>`.
- `window.wx` is provided with `WebViewCompat.addWebMessageListener`, limited to those two origins and the main frame. Replies go back with `HandleStudio(...)`, and only while the page is still on an allowed origin.
- Handles `report.ready` (replies with a random per-install device ID), `report.userInfo`, `report.loginFailed`, `report.notLogged` and `report.websiteOpen` (https links only, opened in the browser). Anything else gets a 404 reply.
- Tokens are stored encrypted with their own Android Keystore key (`CloudAccountStore`). The Settings card shows only the nickname, the last four digits of the user ID and the token expiry. **Sign out on this phone** deletes the tokens and the web view's cookies and storage; it does not sign out other apps.
- The Cloud printers screen uses them for read-only HTTPS requests (see below). Nothing uses cloud MQTT or Agora yet.

## Reaching the printer through the cloud

In the SDK, a cloud printer is reached over three separate channels (`src/cloud/cloud_service.cpp`):

| Channel | Used for | Status in the app |
| --- | --- | --- |
| HTTPS to `matrix.elegoo.com` (`.cn` for China), `Authorization: Bearer <accessToken>` | Bound printers (`device/list`), online state (`device-register/online-status`), last reported status (`device/report-data/list?deviceCode=<SN>`), token refresh | **Read-only screen added:** Settings → Elegoo account → Cloud printers |
| Cloud MQTT, credentials from `mqtt-link/mqtt-client` | Live status pushes on `app/v1/<clientId>/device/data`, where `reportValue` uses the LAN status format | Not started; needs the client ID decision |
| Agora RTM, tokens from `device/list/agora-token?source=slicer` | Commands: start/pause/stop print, printer attributes (`m_rtmService->executeRequest`) | Not started; needs the Agora RTM Android SDK |

The HTTPS status is rebuilt from per-field reports (`reportLinkKey`, `reportValue`, `updateTime`) with the SDK's typing rules (`CloudApi.assemble`), so the same status presentation as the Monitor tab works on it. Without a token, `device/list` answers HTTP 200 with `{"code":401,"msg":"账号未登录"}`, so the client treats body code 401/403 like HTTP 401: refresh the token once, then retry.

The HTTPS reads are the same kind of requests Matrix and ElegooSlicer make, with no session of their own, so they should not disturb those apps. Live MQTT and RTM do create sessions, which is where the client ID and same-user kick-off questions matter.

## First phone result (2026-10-06)

With a sign-in whose access token was valid for six more days, `GET device/list` answered `code 401 账号未登录` ("account not logged in"), and `POST token/refresh` with `clientId "Slicer"` answered `code 400 无效的刷新令牌` ("invalid refresh token").

What the account page's code shows (`account.elegoo.com/assets/*.js`):

- The slicer sign-in flow logs in with `clientId` `Slicer` (`useSlicerFontFamily-*.js`: `Slicer`, `SatelLite` or `Nexprint`, chosen from the user agent or a `clientId` query parameter). It then hands its "biz" token to the host through `report.userInfo` (`finishSlicerIpcLogin-*.js`).
- The page's own token renewal uses `clientId` `account`, which is the plain web sign-in client. It also sends `User-Lang`, `X-Client-Request-Id`, and `elegoo-gray-v: gray` when the page runs on Elegoo's canary ("gray") servers.

Working theory: the app received a web-session (`account`) token rather than a `Slicer` one, for example because the page reused an existing web login. The Cloud printers details now describe the token (claim names and client-identifying claims, never the token itself), check it against `account-info/account`, and show which fields the page reported at sign-in.

## Second phone result (fresh sign-in)

After signing out (which clears the page's web login) and signing in again, the token is opaque (32 characters), and:

- `GET device/list` → code 0. **The token works for the printer API**, so the earlier token most likely came from a reused web login.
- `GET account-info/account` → code 401, and `POST device-register/online-status` → code 401, with the same token.
- `POST token/refresh` with `clientId "Slicer"` → code 400 "invalid refresh token".

So refusals are per endpoint. The app now renews only when `device/list` itself is refused, treats online status as optional, and reports a status failure per printer instead of aborting. Renewal is still unexplained: until it works, a sign-in lasts until its access token expires (about 6 days here).

## Third phone result: working (2026-10-06 10:47)

After another fresh sign-in, every read succeeded: `account-info/account`, `device/list`, `device-register/online-status` and `device/report-data/list` all returned code 0. The CC2 showed online, idle, with live temperatures and "last report 0 s ago".

Comparing the three runs: a sign-in stopped working right after the app's first renewal attempt (`clientId "Slicer"`, refused with 400). So a refused renewal appears to end the session. Elegoo's account page renews the same token with `clientId "account"`, without an `Authorization` header, and only after the access token has expired. The app now does the same and never renews just because a request was refused. Cloud printers has a **Test sign-in renewal…** button to try that renewal before the token expires.

## Cloud in the main app (v0.4.0)

- **Monitor without LAN Only.** When there is no local session and the user is signed in, `PrinterService` polls the cloud (every 15 s while the app is visible, every 30 s with the optional background watch), and the Monitor tab, notification and completion/fault alerts use that status. A local session always takes precedence.
- **Cloud commands** (`CloudControl`, `AgoraLink`): Pause, Resume, Stop and the chamber light. They follow the SDK's `RtmService`: `GET device/list/agora-token?source=slicer` gives `agoraToken.{userId, rtmUserId, rtmToken}`; log in to Agora RTM (`io.agora:agora-rtm`, app ID from the SDK) as `rtmUserId`; subscribe to `userId`; publish the printer's ordinary JSON request (`{id, method, params}`, built by `Cc2Codec`) to the user channel `<userId><serial>` with custom type `PlainText`; the reply comes back from publisher `<userId><serial>` with the same `id`. One command at a time, 10 s reply timeout, never retried. The session opens on the first command and closes after two idle minutes.
- **Shared identity.** `source=slicer` is ElegooSlicer's identity, so the app and an open ElegooSlicer can sign each other out of cloud control (`SAME_UID_LOGIN`). The app explains this before the first cloud command, never reconnects by itself, and Matrix is expected to be unaffected (untested).
- **APK size.** Agora's native library is about 10 MB for 64-bit ARM; the app ships that CPU type only (`abiFilters 'arm64-v8a'`).

## Full feature set and camera through the cloud (v0.4.1)

What ElegooSlicer's own printer page does (`resources/web/elegoolink/lan_service_web/index.html`, the same web app the slicer shows for each printer):

- **Any printer command over the cloud.** Its cloud transport wraps every printer request (`{id, method, params}`) and hands it to the slicer's `sendRtmMessage` bridge, which publishes it on Agora RTM exactly as `CloudControl` does. Its command list includes GetFileList 1044, DeleteFile 1047, GetCapacity 1048, history 1036/1037, TemperatureControl 1028, LightSwitch 1029, FanControl 1030, PrintSpeedControl 1031, StartPrint 1020, Feed/Retreat 1024/1025, home/move 1026/1027, AutoLeveling 1032, GetTimeLapseVideoList 1051, AI detection settings 1062/1063 and CANVAS 2003-2005. The app now sends the same requests it already sends locally (files, history, storage, start/delete, temperatures, fans, speed, light, CANVAS and auto-refill) through the cloud when there is no local session. Uploads and the local camera address stay local.
- **Camera over Agora RTC.** `startWatching()` gets `rtcToken` and `userId` (the numeric `rtcUserId` from `agora-token`), creates `AgoraRTC.createClient({mode: "live", codec: "vp8"})` with the same Agora app ID, joins the channel named after the printer's serial, sets the client role to host and plays the first remote video track. The local build has this switched off (`useRTC: "false" === "true"`) and asks the printer for its MJPEG address instead (GetLivingVideoUrl 1042).
- The app's **Cloud camera** (`CloudCameraActivity`, `assets/camera/index.html`) does the same with the Agora Web SDK 4.22.0 bundled from npm (MIT), served from `https://appassets.androidplatform.net` through `WebViewAssetLoader`. In headless Chromium the page loads, joins Agora's gateway and reports the rejection of a fake token (`CAN_NOT_GET_GATEWAY_SERVER`); joining with a real token is untested.
- Cloud actions (controls, files, settings, camera) wait for a one-time agreement because they share ElegooSlicer's cloud identity. Monitoring does not.

## Open questions to test first

1. ~~Does the sign-in page hand off tokens to the Android `WebView`?~~ Yes, confirmed on the user's phone.
2. Does the Cloud printers screen stay current during a print? (Idle status and temperatures confirmed.)
3. Does renewal with `clientId "account"` work? (Use Test sign-in renewal.)
4. Does the MQTT credential endpoint accept an `android` client ID?
5. Do cloud commands, file browsing and the cloud camera work, and does Matrix keep working while the app uses them?

This uses Elegoo's servers and the user's own account in a way Elegoo did not design for, so it can break when they change their service.
