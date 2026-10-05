# CC2 source audit and port contract

Baseline: `elegooofficial/elegoo-link` commit `46c7b814e055cf9675d58482d79f43d0bd2280da`, inspected 2026-10-05. Claims here refer to that code, not untested firmware guarantees.

| Area | Source | Android behavior |
| --- | --- | --- |
| System information | `src/lan/adapters/elegoo_fdm_cc2/elegoo_fdm_cc2_protocol.cpp` | HTTP port 80 `/system/info`, `X-Token` header/query; serial from `system_info.sn` |
| MQTT connection | Same file | TCP port 1883, user `elegoo`, access code/default `123456`, client identity `1_PC_<four digits>` |
| Registration | Same file | Subscribe to `elegoo/<sn>/<client>_req/register_response`; publish `{client_id,request_id}` to `elegoo/<sn>/api_register`; require matching client and `error: ok`, 3-second timeout |
| Request and status topics | Same file | Requests at `elegoo/<sn>/<client>/api_request`; replies at corresponding `/api_response`; events at `elegoo/<sn>/api_status` |
| Application heartbeat | Same file | `{"type":"PING"}` every 10 seconds on request topic; MQTT keepalive also enabled. Android additionally polls full status every 15 seconds and disables controls after 20 seconds without valid status |
| Request envelope | `src/lan/adapters/elegoo_fdm_cc2/elegoo_fdm_cc2_message_adapter.cpp` | Numeric `id`, numeric `method`, object `params`; require request-ID/method correlation and `result.error_code == 0` |
| Implemented method table | Same file, `COMMAND_MAPPING_TABLE` | Attributes 1001, status 1002, pause 1021, stop 1022; status deltas 6000 |
| Delta cache | Same file, `handlePrinterStatus` and `mergeStatusUpdateJson` | Require full snapshot before deltas, recursively merge objects, replace arrays/scalars, replace `exception_code` atomically. Five consecutive sequence gaps invalidate the baseline and request refresh; zero sequence permits restart |
| Status display | Same file | `machine_status.status/sub_status/progress`, `print_status.filename/current_layer/total_layer/remaining_time_sec`, `extruder` and `heater_bed` temperature/target, `ztemperature_sensor.temperature` |
| File upload | `src/lan/adapters/elegoo_fdm_cc2/elegoo_fdm_cc2_http_transfer.cpp` | PUT `/upload`, chunks no larger than 1 MiB, `Content-Range`, `X-File-Name`, whole-file `X-File-MD5`, `X-Token`; each chunk must acknowledge `error_code: 0` |

## Explicit gaps

The CC2 command mapping table comments out resume (1023), homing (1026), movement (1027), temperature (1028), light (1029), fan (1030), print speed (1031), task history (1036–1038), camera (1042), file list (1044), file detail (1046), file delete (1047), disk information (1048), and filament loading/unloading (1024/1025). These numbers are clues for subsequent firmware inspection, **not permission to expose working controls**. Their request parameters, error semantics and firmware support need verification.

Start-print (1020), CANVAS status (2005), auto refill (2004) and printer downloads (1057/1058) are active upstream. Start-print includes bed checks, plate type, timelapse configuration and tray mappings; the first app deliberately leaves starting a job to the printer screen until these options can be represented and tested properly.

Discovery is also implemented upstream: CC2 UDP port 52700 and method 7000, with LAN/cloud and token-status fields. v0.2.1 implements identity lookup for the manually selected IP, with Android-bound UDP unicast and subnet broadcast. A full discovery picker remains pending.

Cloud code uses HTTP/MQTT plus Agora RTM and a generated private config. `thirdparty/agora/` contains desktop platform dependencies; the repository has no Android cloud build or APK app. Camera capability flags alone do not specify a working stream URL or playback format. The CC2 discovery source explicitly says it has no specific built-in web interface, so a WebView shortcut cannot be assumed to replace this app.

## v0.2.0 additions

The baseline command mapping remains the same. This release additionally implements CANVAS query 2005 and confirmed auto-refill 2004 (`params.auto_refill`: boolean), parses 1001 attributes, and displays nested `exception.exception_code` without guessing fault meanings. Unknown tray states remain numeric; camera/start/resume are still absent.

MQTT registration timeout is now 8 seconds. An Android connectedDevice foreground service owns each session independently of Activity lifetime. Local HTTP and MQTT socket factories use a Wi-Fi/Ethernet Network instead of relying on the default cellular route. A new session and fresh full status are required after each reconnect; no printer-changing commands or uploads are automatically replayed. Retry policy: five delays 1/2/4/8/16 seconds, reset on registration success, stop on known authentication/rejected-registration errors.

Connection diagnosis performs TCP-only probes on 80/1883 and an authenticated `/system/info` read. A reachable TCP broker does not establish MQTT credentials or registration. Raw exception URLs and access-code-bearing payloads are not displayed. Credential storage is optional Android Keystore AES-GCM with IP-bound AAD; backups exclude preferences. The local code does not require an official cloud account.

## v0.2.1 identity correction

The v0.2.0 phone diagnostic shows 192.168.1.69/24 reaching printer 192.168.1.84 on MQTT 1883 while HTTP 80 is refused/unreachable. The old app always read `/system/info` before opening MQTT; the upstream adapter requires that HTTP read only when a serial was not already supplied.

Identity resolution now uses the optional exact manual serial first, otherwise read-only UDP method 7000 on port 52700. UDP goes to the selected IP and the local IPv4 subnet broadcast, retransmits once per second, and times out after four seconds. Only replies from the selected IP with id 0, a result object and a bounded topic-safe serial are accepted; echoed requests, malformed data and replies from other IPs are ignored. Disconnect closes the discovery socket. Discovery does not transmit the access code.

If UDP fails, authenticated HTTP identity remains a compatibility fallback. If both identity paths fail, the app requests a manual serial and stops retries. The serial is never guessed from an account ID or hardcoded from a screenshot. MQTT still authenticates and registers normally before status or controls become ready. File uploads retain their existing HTTP dependency and are never automatically replayed. No cloud authentication or broader control methods are added.

## v0.2.2 authentication-state correction

The user's v0.2.1 manual-serial attempt reaches MQTT but receives CONNACK code 5. MQTT 3.1.1 defines 4 as bad username/password and 5 as not authorized; the broker does not provide the failed policy rule. Code 5 must not be presented as proof that the user mistyped the password.

The inspected upstream discovery adapter selects accessCode authentication only for token_status 1; token_status 0 selects the default password. A reported lan_status 0 is cloud mode and selects a separate pinCode path upstream. The Android client now retains these fields (boolean or numeric 0/1); invalid/absent values remain unknown. It selects 123456 only when code protection is explicitly disabled, otherwise retains the entered code. A known cloud/WAN mode is rejected locally with an explanation before sending LAN credentials. MQTT 3.1.1 is requested explicitly. Uploads use the same selected credential as the MQTT session.

Discovery metadata is queried even with a manual serial; if a valid response from the selected IP provides a different serial, it takes priority and the mismatch is reported. If UDP is blocked, the manual serial still permits connection without HTTP. Connection checks report mode/protection and serial mismatch without printing the serial or access code. MQTT authorization failures include the known mode/protection summary and are terminal; the app does not guess passwords or rotate client formats to bypass an authorization refusal.
