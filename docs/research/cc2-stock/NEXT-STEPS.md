# Ordered next steps

**Current execution order:** [final connection and app plan](FINAL-CONNECTION-AND-APP-PLAN.md), 20 October 2026. The app is now v0.86.0/build 180 and the owner has confirmed SH-U09C5 hardware. Start with phone-only loopback, oriented board/voltage qualification and receive-only boot. In parallel, resolve the latest Android browser validation failure and connect companion archives to existing file/project provenance. The N01–N08 sections below retain the historical investigation breakdown; they are not a second current app roadmap.

Next implementation is prepared: [read-only status and file ranges](../../../tools/research/READONLY-STATUS-AND-FILES.md). After initial owner access, fresh endpoint discovery and identity observation, qualify the fixed objects capture, then snapshot, then optional short subscription. Independently qualify one known finalized idle G-code range and USB v2 export; assemble a complete candidate archive on Pi before attempting app/job association. No automatic recurring request or active-print reader is enabled by this pass.

Concrete placement map: [COMPANION-FEATURE-PLACEMENTS.md](COMPANION-FEATURE-PLACEMENTS.md). The shipping dispatch reaches VirtualSD file path/position/size and PrintStats lifecycle data. The bounded status and file-range tools are now implemented; next qualify them against the owner's installed firmware while idle. Subsequent software work is controlled refresh, opaque source selection and explicit app/job association. Embedded thumbnails can then be parsed from archived bytes on the Pi. The map also identifies media finalization, camera source/binary differences, configuration rename behavior and the boundary between an external helper and a compiled firmware fork.

Pi setup implementation: [PI-COMPANION-SETUP.md](../../../tools/research/PI-COMPANION-SETUP.md). Start hardware qualification with identity-only USB export, then one finalized fixed-inventory capture, before the new status/range profiles. The optional boot hook requires independent temporary-transport qualification, matching executable/helper fingerprints and an observed USB route. UART bootstrap/recovery, authenticated maintenance operations, finalized-media exports and automatic source capture remain separate steps.

Historical research baseline: dde7699 / v0.64.0; initial file-identity follow-on v0.65.0/build 156, based on 7239d9b. Current app review is v0.86.0/build 180. Device access is still not established. Keep the vendor printer executable intact and do not toggle mode to work around file access.

| Priority / ID | Work | Done when | Dependency |
|---|---|---|---|
| P0 / N01 | Offline capture inspector implemented; owner export pending | [Tool and instructions](../../../tools/research/README.md) inventory cloud_file_cache, thumbnails, print records and cloud-download/camera events; 16 synthetic tests pass | Owner-exported captures still needed for real-data validation |
| P0 / N02 | Implemented in app source; device acceptance pending | Content-addressed GcodeLibrary preserves same-name versions; printer-scoped candidates require explicit observed-job selection; source/hash shown; stale results cannot revive old scenes; eviction explained | [Implementation and acceptance](../../../android/GCODE_IDENTITY.md); [validation](../../../android/VALIDATION.md). Cloud references remain transport hints |
| P1 / N03 | Owner observation worksheet ready; physical serial capture pending | Exact board/header mapping, voltage, observed baud/boot output and configured terminal recorded in [worksheet](OWNER-CAPTURE-WORKSHEET.md) | Physical owner access and suitable UART hardware |
| P1 / N04 | Python reference and native first-contact recorder implemented; printer discovery pending | First validate one native inventory capture offline, then separately qualify advertised inventory/help and optional reports | [Native probe](../../../tools/research/NATIVE-FIRST-PROBE.md), [full reference](../../../tools/research/CONSOLE-CAPTURE.md); N03 or independently established owner shell and temporary executable transfer |
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

The next preparation adds a deliberately smaller native C recorder: one fixed `list_endpoints` request, a bounded private raw stream, and an offline Python inspector. Its static ARMv7 hard-float build targets the inspected firmware's ABI without relying on stock libc/Python. Host tests, actual ARM executable tests under QEMU, temporary file transfer, native execution on the owner device and physical console access are separate acceptance stages. Follow the [phone sequence](PHONE-UART-NEXT-STEPS.md); do not restart port/camera/PIN experiments already recorded in the repository.

N03/N04 now also have a [firmware-driven connection/sourcing guide](CONNECTION-SOURCING.md) and [checksummed UART text transfer](../../../tools/research/SERIAL-TRANSFER.md). The transfer uses stock applets, creates only a fresh temporary stage and requires manual native execution. CI prepares snippets from the actual qualified ARM binary. The separate port-number field in the stock kernel makes hardware-UART/Linux-TTY renumbering plausible, but neither the live device tree nor the physical header voltage is resolved. Next owner action remains adapter/phone qualification and one receive-only normal boot observation.

N06/N08 companion follow-up: [PI-COMPANION-HOOKS.md](PI-COMPANION-HOOKS.md) establishes stock `CONFIG_USB_ACM=y`, with USB network/HID/conventional USB-serial host support disabled. The Pi can be considered as a serial gadget after owner bootstrap, with a temporary helper first and an explicit procd/hotplug launcher only after qualification. The normal updater path sets `parts_clean=rootfs_data`; treat printer-side overlay hooks as update-disposable. A Pi-side UART supervisor and separately retained compatibility policy are the preferred recovery direction. The [owner observation worksheet](access/tools/collect-companion-facts.sh) is ready; no hook, USB helper, live SSH tunnel or persistence test has been performed.
