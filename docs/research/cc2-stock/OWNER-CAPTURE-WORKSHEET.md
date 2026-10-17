# Owner-console experiment worksheet

N03 (physical serial access) and N04 (console discovery) are still hardware experiments. This worksheet makes the observations reviewable without assuming a shell, a runtime or an update-surviving installation exists. Run the initial observation idle. No item below was completed on the owner's printer during development.

## 1. Establish what hardware and firmware are present

| Observation | Owner record |
|---|---|
| Printer model and displayed firmware version | |
| Mainboard marking/revision and header orientation photo | |
| Header reference and evidence tying it to this exact board | |
| Measured TX/RX logic levels and measurement method | |
| UART adapter model and supported logic levels | |
| Receive-only connections used; adapter power lead disconnected | |
| Observed baud, data bits, parity and stop bits | |
| Raw boot-capture filename, duration and SHA-256 | |
| Evidence mapping this header to the configured terminal | |

The pinned CC2 reference identifies a UART0/DSP header on EF-M5-V1.0.073, but the firmware has multiple terminal names. Its supply-pin label does not establish signal logic voltage. The existing [access findings](STOCK-ACCESS-FOLLOWUP.md#two-consoles-with-different-access-paths) explain the distinction and link the board reference. Do not choose left/right pin positions from a text list or assume that a camera's USB/ADB access reaches the printer computer. Transmit remains disconnected until the header, levels and terminal mapping are established.

Record observations from a normal boot; do not interrupt a running print to collect them. Keep raw boot output private and record anything missing or ambiguous rather than treating it as a successful shell bootstrap.

## 2. Record an independently established owner shell

| Observation | Owner record |
|---|---|
| How the owner shell was established | |
| Actual terminal/device and shell prompt | |
| Installed printer executable path, fingerprint and capture method | |
| `/tmp/elegoo_uds` exists and is a Unix socket | |
| `/opt/bin/debug_window` exists | |
| Existing Python version, if any; otherwise mark unavailable | |
| Printer idle and normal WAN telemetry before capture | |

Do not enable ADB, switch LAN/WAN modes, restart the printer process, install a runtime or change startup files to fill these boxes. If access is not established, stop at that unresolved prerequisite. A missing runtime is a result, not an instruction to install one.

## 3. Capture bounded console discovery

Use [CONSOLE-QUICKSTART.md](CONSOLE-QUICKSTART.md) for the bundled client. If a suitable Python environment already has access to the socket, the [bounded reference recorder](../../../tools/research/CONSOLE-CAPTURE.md) handles framing and evidence capture. It cannot create owner access or directly reach a printer socket from a phone/Pi.

For an unchanged printer without Python, the [native first-contact probe](../../../tools/research/NATIVE-FIRST-PROBE.md) is a prepared single-inventory alternative after executable transfer/startup qualification. It records a raw capture for offline inspection on the phone. It does not send the help/list/subscription requests below; mark those as not performed rather than inferring them from advertised endpoint names.

| Observation | Owner record |
|---|---|
| Client used and version/source fingerprint | |
| Start/end time and bounded capture filename | |
| `list_endpoints`: response or exact failure retained privately | |
| `gcode/help`: advertised and response received | |
| `objects/list`: advertised and response received | |
| Optional `gcode/subscribe_report`: advertised, acknowledged, report count | |
| Transcript SHA-256 and summary filename | |
| Normal WAN telemetry still healthy afterward | |
| Any printer/UI/log fault or interrupted response | |

Do not submit `gcode/script`, reset commands, internal network endpoints or object-query experiments during this discovery pass. A successful help/list response establishes only that read path. A finished subscription window without output is not a historical-log export or proof of working report delivery.

## 4. Prepare the separate file/log experiment

After the access path is established, retain a consistent idle records export and selected log tails for [the offline inspector](../../../tools/research/README.md). Keep raw configuration, signed URLs and camera identifiers private. Live `unix-none` SQLite files require independent consistency handling; deleting a sidecar does not finalize an export.

For one expendable known cloud-started job, record its task identity, original file hash, final local file hash and evidence of download finalization. Filename/size agreement remains a candidate match. A USB/local-started print may have no cloud object at all. Do not proceed to active-print file reads, a companion service, persistence or camera repair on the strength of console discovery alone.
