# Broad Android replacement roadmap

The goal is a useful phone app with monitoring, control, files, camera and remote access. Milestones are ordered by verifiable protocol coverage rather than UI breadth. No milestone below is presented as already completed.

| Stage | Deliverable | Evidence needed |
| --- | --- | --- |
| 1 — LAN foundation | Connect, full/delta status, pause/stop, upload-only G-code flow | v0.1 code and automated checks; physical CC2/S24+ validation still pending |
| 2 — Reliable daily use | UDP discovery, remembered printer profiles, secure stored credentials, reconnect/backoff, clear network and printer errors | Discovery port/method already in source; verify Android broadcast behavior; store secrets with Android Keystore; re-register on every reconnect |
| 3 — Files and print setup | Browse files, metadata/previews, upload cancellation/recovery, start jobs with plate/check/tray settings, resume when verified | Inspect CC2 firmware/API paths for commented methods; active upstream 1020 start-print payload; never automatically replay start/delete/stop requests |
| 4 — Camera and CANVAS | Live camera, timelapse where supported, tray/material/color state and slot mapping | Establish actual stream transport, authentication and capability support; upstream active 2005/2004 are CANVAS starting points |
| 5 — Background awareness | Completion/error alerts, ongoing print notification and optional background monitoring | Android foreground-service/notification behavior, power management, reconnect and missed-event reconciliation |
| 6 — Remote access | Secure access away from home, then official-cloud account integration if feasible | LAN access over a user-managed VPN can come first; audit cloud auth and Android Agora RTM, credential availability and service requirements; no exposed plaintext printer ports |
| 7 — Expansion | Multi-printer dashboard, print history, filament tracking, presets, exports, other supported models | A shared app model and separate CC2/CC/Moonraker adapters, capability gating, fixture and hardware coverage per model |

The app is a printer management client. Phone-side slicing is a separate project with different CPU, memory and toolpath-validation requirements and is not part of the current replacement scope.

## Next hardware feedback

Confirm the exact printer model/firmware, LAN mode, successful connection or displayed error, and whether the monitor matches the printer screen. Do not send access codes. Before extending camera/files/resume, prefer firmware source and documented SDK behavior, then compare real traffic from an authorized official client if source is incomplete.

## v0.2.0 progress

Stage 2 now includes foreground service ownership, retained session across Activity changes, optional encrypted single-printer credentials, bounded reconnect, network binding and layered connection diagnosis. Stage 4 now includes read-only CANVAS trays and confirmed automatic-refill requests; it does not include camera or filament loading. Physical printer/phone verification remains pending. Discovery, additional profiles, complete print setup/start/resume, camera and separate completion alerts are still future work.

## v0.2.1 delivered

Selected-IP UDP identity discovery and optional manual serial remove the HTTP prerequisite for MQTT monitoring. A full discovery picker and an alternative upload transport remain pending. Verify on the CC2 V02.01.00.00 that refused HTTP does not block MQTT registration/status.
