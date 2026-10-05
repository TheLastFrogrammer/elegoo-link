# Android client progress

v0.3.0 provides a broader CC2 LAN client. Implemented means present in code and covered where feasible by automated checks; physical CC2/S24+ validation remains pending.

| Area | Implemented | Remaining |
| --- | --- | --- |
| Interface | Monitor/Files/Camera/Settings; System/Light/Dark | On-device layout, accessibility and landscape review |
| Connection | UDP picker/manual identity; per-IP encrypted profiles; foreground monitoring; bounded reconnect; layered diagnosis | Successful physical authorization/registration on the user's CC2; device lifecycle/Keystore migration validation |
| Files/printing | Internal/USB pagination, metadata, upload/cancel, delete, start with checks/plate/timelapse/tool mappings, pause/resume/stop | Thumbnails, downloaded files, automatic sliced-tool metadata, export/recovery transport when HTTP is unavailable |
| Printer settings | Light, bounded idle heater targets, fan channels, printing speed modes, automatic refill | Filament loading/unloading, calibration, homing/movement only after complete behavior verification |
| CANVAS | Materials/colors/active tray, fresh reported tray mapping | Editing filament profiles and persistent usage tracking |
| Camera | Local MJPEG, larger view, document-picker snapshots, independent lifecycle | Timelapse export, stream capability variations, background playback policy |
| Awareness | Live service notification, deduplicated completion/new fault alerts, history, storage usage | Reconciliation of missed completion while disconnected; device battery/notification behavior |
| Remote/model expansion | CC2 local Wi-Fi/Ethernet | User-managed secure remote routing and official cloud auth; other model adapters; simultaneous printer dashboard |

No automatic replay of any changing command or upload is planned. An acknowledgement and a fresh resulting printer state remain distinct. Printer start/delete/temperature require fresh appropriate state; explicit tray mappings require fresh CANVAS data and an existing connected tray with material. Axis movement remains blocked.

Phone-side slicing is a separate project and outside the present management-client scope.
