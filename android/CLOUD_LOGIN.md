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

- A `WebView` on the same sign-in page, with a `@JavascriptInterface` object registered as `wx` that has a `postMessage(String)` method. It handles `report.userInfo`. The user types their credentials only into Elegoo's own page; the app never sees the password.
- Tokens stored with the same Keystore protection as the access codes, refreshed before they expire, and cleared on logout.
- A client ID choice. The slicer only uses `win`, `mac` or `linux`. Reusing one of those could collide with a running ElegooSlicer session for the same account. Whether the server accepts `elegooslicer_android_<userId>` is untested.
- Mapping the cloud MQTT messages to the existing status model. The SDK has a separate cloud parser, `src/cloud/adapters/elegoo_fdm_cc2_message_adapter.cpp`, which needs comparing with the LAN one.

## Sign-in test in the app

Settings → **Elegoo account (experimental)** → **Sign in with Elegoo…** (`CloudLoginActivity`, protocol in `CloudLogin`):

- Opens `account.elegoo.com` (or `account.elegoo.com.cn`) in a `WebView`. The user agent adds `ElegooSlicer/1.5.3.5` because the page only uses its `window.wx` bridge for that client, plus `LinkWorkshop/<version>`.
- `window.wx` is provided with `WebViewCompat.addWebMessageListener`, limited to those two origins and the main frame. Replies go back with `HandleStudio(...)`, and only while the page is still on an allowed origin.
- Handles `report.ready` (replies with a random per-install device ID), `report.userInfo`, `report.loginFailed`, `report.notLogged` and `report.websiteOpen` (https links only, opened in the browser). Anything else gets a 404 reply.
- Tokens are stored encrypted with their own Android Keystore key (`CloudAccountStore`). The Settings card shows only the nickname, the last four digits of the user ID and the token expiry. **Sign out on this phone** deletes the tokens and the web view's cookies and storage; it does not sign out other apps.
- Nothing else uses the tokens yet: no refresh, no device list, no cloud MQTT.

## Open questions to test first

1. Does the sign-in page hand off tokens to the Android `WebView`? The page code requires `ElegooSlicer` in the user agent, which the app adds; untested on a phone.
2. Does the MQTT credential endpoint accept an `android` client ID?
3. Does a cloud MQTT session from the app keep Matrix and ElegooSlicer connected?

This uses Elegoo's servers and the user's own account in a way Elegoo did not design for, so it can break when they change their service.
