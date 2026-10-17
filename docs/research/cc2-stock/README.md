# CC2 stock firmware and file-access research

Goal: obtain exact G-code, console output and media while preserving official printer firmware and its update path. Full LAN/WAN command coexistence is a separate goal. No device installation or firmware modification was performed by this investigation.

Read in this order:

1. [Latest access findings](STOCK-ACCESS-FOLLOWUP.md): console, all 55 API methods, media paths, cloud-download cache and camera faults.
2. [Original firmware investigation](STOCK-FIRMWARE-INVESTIGATION.md): signature provenance, exclusive network strategies and the idle-only native download guard.
3. [Console quickstart](CONSOLE-QUICKSTART.md): requires an already established owner shell.
4. [Investigation log](INVESTIGATION-LOG.md): claim, status, evidence and remaining check.
5. [Next steps](NEXT-STEPS.md): dependency order, acceptance criteria and current blockers.
6. [Original implementation handoff](STOCK-FIRMWARE-HANDOFF.md): historical starting brief; the access follow-up and next-step tracker supersede its earlier console/cloud-cache unknowns.

The first follow-on tool is ready: [offline capture inspector](../../../tools/research/README.md). It reads consistent owner-exported records/logs; it does not collect them from a printer.

The second follow-on is implemented in Android v0.65.0 source: [retained-file identity and live-view selection](../../../android/GCODE_IDENTITY.md). This changes the app and requires device acceptance; it does not establish printer console access or bypass stock file serving.

Console preparation now includes an [owner UART/shell worksheet](OWNER-CAPTURE-WORKSHEET.md) and a [bounded console reference recorder](../../../tools/research/CONSOLE-CAPTURE.md). The recorder needs an established local access path and Python 3.10+; a Python runtime is not confirmed in the stock image. Protocol/fixture validation is separate from printer qualification.

The [owner-access route analysis](OWNER-ACCESS-ROUTES.md) now verifies the stock askfirst executable's direct shell-launch behavior and compares historical SSH, current UART, USB/ADB and recovery leads. Start with the installed firmware version and a bounded SSH-banner check before opening the printer.

The complete method list is also available as [CSV](CC2-API-METHODS.csv) and [JSON](baseline/evidence/API-METHOD-MAP.json). Numbered APIPrinter methods and local UDS methods are different namespaces.

## Evidence layout

- `baseline/evidence/`: package verification, component fingerprints, API registrations, bounded direct-call scan, ARM download-admission cases and analysis-tool versions.
- `access/evidence/follow-up/`: annotated printer-executable disassembly and call/string records.
- `access/evidence/camera-follow-up/`: camera-host executable records; its addresses belong to a different ELF.
- `baseline/tools/` and `access/tools/`: offline reproduction tools. Use the setup in [baseline/README.md](baseline/README.md); place access tools alongside the baseline tools when reproducing. Generated firmware, indexes, dependencies and captures are ignored by Git.

Reports were inspected against app commit dde76993f593f99b58264afaeb855cb718ddd2cc and stock package SHA-256 278a0606a072ae432e9bf2d4f0157acba4570d1c85dbc768c359fef53ac5f406. Binary addresses are meaningful only for the fingerprints in the reports.

The [shareable report](https://cc2-workshop-concepts.bagels.chatgpt.site/stock-access.html) is a presentation of these findings. This repository is the source for subsequent experiment tracking. Vendor binaries, decryption keys, private captures and camera dumps are excluded.
