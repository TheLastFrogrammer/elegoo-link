# Ordered next steps

Research baseline: dde7699 / v0.64.0. Follow-on app implementation: v0.65.0 / build 156, based on 7239d9b. Device access is not established. Keep the vendor printer executable intact and do not toggle mode to work around file access.

| Priority / ID | Work | Done when | Dependency |
|---|---|---|---|
| P0 / N01 | Offline capture inspector implemented; owner export pending | [Tool and instructions](../../../tools/research/README.md) inventory cloud_file_cache, thumbnails, print records and cloud-download/camera events; 16 synthetic tests pass | Owner-exported captures still needed for real-data validation |
| P0 / N02 | Implemented in app source; device acceptance pending | Content-addressed GcodeLibrary preserves same-name versions; printer-scoped candidates require explicit observed-job selection; source/hash shown; stale results cannot revive old scenes; eviction explained | [Implementation and acceptance](../../../android/GCODE_IDENTITY.md); [validation](../../../android/VALIDATION.md). Cloud references remain transport hints |
| P1 / N03 | Owner observation worksheet ready; physical serial capture pending | Exact board/header mapping, voltage, observed baud/boot output and configured terminal recorded in [worksheet](OWNER-CAPTURE-WORKSHEET.md) | Physical owner access and suitable UART hardware |
| P1 / N04 | Bounded console reference client implemented; printer discovery pending | Advertised inventory/help captured; optional output subscription recorded; selected object reads remain a later qualification | [Tool, limits and runtime prerequisite](../../../tools/research/CONSOLE-CAPTURE.md); N03 or independently established owner shell; Python is not confirmed in stock |
| P1 / N05 | Recover one known cloud-started file | Exported cache/log reference correlated with completed local file and content digest | Owner access; consistent idle export |
| P2 / N06 | Manually start a limited file-reader proof | One idle copy then one bounded active-print copy verified while WAN telemetry remains healthy | N04/N05; transport and containment qualification |
| P2 / N07 | Identify camera faults precisely | Installed host build/logs and camera hardware family recorded; mode exclusion distinguished from capture stall/module failure | Owner observations; no automatic repair |
| P3 / N08 | Qualify startup and updates | Reboot and exact official-update behavior demonstrated with an explicit recovery path | Successful manual reader; persistence design reviewed separately |

## N01: immediate software deliverable

Implemented in [cc2_capture_inspect.py](../../../tools/research/cc2_capture_inspect.py), with [tests](../../../tools/research/tests/test_cc2_capture_inspect.py). This is a host-side tool, not an app release or an on-printer helper. It:

- open only local regular files and never contact a printer/cloud endpoint;
- identify known table/column shapes without arbitrary SQL execution;
- report row counts, filename/task metadata, URL host and a stable URL digest by default;
- keep signed query strings and raw URL paths out of normal summaries;
- bound table rows and log/line bytes and report skipped/truncated data;
- requires a consistent owner export; the native database uses unix-none, so another connection cannot be assumed to coordinate with its writer;
- exercises actual parser behavior against synthetic databases/logs, including unknown schemas, malformed URLs, oversized lines, bounded rows/events and refusal to overwrite output.

This tool can establish what a captured export contains. It does not establish that a recovered URL still works, that a cloud reference is the exact running job, or that a USB/local-started job ever had a cloud object.

## N02: implementation boundaries

The existing GcodeLibrary was extended rather than duplicated. SHA-256 objects, source/printer aliases and separate explicit observed-job bindings replace filename-keyed replacement and automatic live lookup. All import/slice/upload/download/folder/manual-choice paths feed the archive. Earlier copies migrate as unscoped candidates. CloudFileMemory remains a per-printer/name object-location hint, not evidence of the running job's exact bytes.

The UI labels a confirmed choice **Selected by you**. It does not claim a firmware-verified match. An observed job scope expires conservatively on transitions, route changes or gaps; wholly unreported same-name restarts remain unknowable. Automated domain, actual-activity, production-session and browser regressions cover the repaired paths. Device acceptance remains required. This implementation changes the Android app; the earlier research-only checkpoint remains historical. No signed release/tag or firmware change is included.

## Next available work

N03/N04 require owner hardware access. In parallel, run the N02 device checklist above and provide a consistent idle export for N01/N05. Do not attempt the active-print reader (N06) until the console/file source and bounded transfer path have been established. Keep camera diagnosis (N07) tied to the installed camera host build and hardware variant.

The N04 reference client and Linux CI fixtures are now implemented. It sends only discovered inventories/help and an explicit bounded `gcode/subscribe_report` request, never G-code or object queries. This validates the companion transport design without changing firmware. It does not solve owner bootstrap, prove a stock Python runtime exists, recover files, qualify a native printer helper, or establish update/reboot persistence. Record those boundaries before promoting the reference to a device capability.
