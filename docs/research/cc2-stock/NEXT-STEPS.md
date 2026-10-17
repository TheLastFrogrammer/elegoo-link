# Ordered next steps

App baseline: dde7699 / v0.64.0. Device access is not established. Keep the vendor printer executable intact and do not toggle mode to work around file access.

| Priority / ID | Work | Done when | Dependency |
|---|---|---|---|
| P0 / N01 | Offline capture inspector implemented; owner export pending | [Tool and instructions](../../../tools/research/README.md) inventory cloud_file_cache, thumbnails, print records and cloud-download/camera events; 16 synthetic tests pass | Owner-exported captures still needed for real-data validation |
| P0 / N02 | Strengthen exact-file identity in the app | Different bytes under the same name remain distinct; printers cannot cross-match; filename-only candidates cannot silently become a verified live toolpath; eviction is explicit | Extend existing GcodeLibrary/CloudFileMemory; do not introduce duplicate retention |
| P1 / N03 | Establish read-only CC2 serial capture | Exact board/header mapping, voltage, observed baud/boot output and configured terminal recorded | Physical owner access and suitable UART hardware |
| P1 / N04 | Run stock console discovery | list_endpoints and gcode/help captured; output subscription and selected object reads validated | N03 or another independently established owner shell |
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

GcodeLibrary already stores copies; put replaces the same filename and find matches a basename without printer identity. The next change is an artifact model with content digest/size plus a separate association record containing printer, job, original filename, source and confidence. Preserve multiple versions when filenames repeat. Treat external folders and heard cloud references as candidates until stronger evidence ties them to the job.

Start with a domain model and meaningful regressions for same-name/different-bytes, same-name/two-printers, unknown job, pruning, partial transfers and stale references. Wire all upload/slice/download/selection paths before claiming exact-file retention is repaired. Production UI must distinguish a retained candidate from verified job bytes. No new release or firmware modification is part of the research-only checkpoint.
