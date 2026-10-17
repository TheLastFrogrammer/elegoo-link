# Bounded stock-console capture

`cc2_console_capture.py` is a reference client for the stock CC2 Unix console. It implements framing and request correlation independently of the bundled CLI and saves evidence for the next owner-console experiment. It is not an access installer or a deployed printer companion.

## Prerequisites and first use

Use an already established owner shell, an idle printer, access to its existing Unix socket, and a writable existing parent for the new capture directory. **Python 3.10+ is required wherever this client runs.** No Python-named runtime appears in the pinned rootfs inventory. Do not assume this script runs on an unchanged stock printer, or install Python on its 128-MB mainboard as part of this experiment.

A phone, Pi or laptop cannot directly open the printer's `/tmp/elegoo_uds`. A Python-capable owner environment or an independently qualified forwarding arrangement is a separate prerequisite. This tool creates neither SSH access nor forwarding, and exposes no TCP service. The [bundled `debug_window` workflow](../../docs/research/cc2-stock/CONSOLE-QUICKSTART.md) remains the initial stock-shell option when a Python runtime is absent.

On a qualified environment with access to that local socket:

```sh
python3 cc2_console_capture.py \
  --socket /tmp/elegoo_uds \
  --output /path/to/existing-private-parent/new-discovery
```

The output directory must not exist. The default run sends only:

1. `list_endpoints` with `{}`.
2. `gcode/help` with `{}`, if advertised by the first response.
3. `objects/list` with `{}`, if advertised by the first response.

Optional observation of subsequent console reports:

```sh
python3 cc2_console_capture.py \
  --socket /tmp/elegoo_uds \
  --output /path/to/existing-private-parent/new-console-window \
  --listen-seconds 10
```

This adds `gcode/subscribe_report` with exactly `{"response_template":{}}`, only if advertised. It does not substitute `gcode/subscribe_output`, submit a G-code script or request a status-object subscription. Acknowledgement and a finished observation window are recorded separately. `output_report_frames` counts ID-0 report frames received after the subscription request; zero does not prove the subscription failed or that old logs are empty. This is not a historical log export.

## What it saves

| File | Contents |
|---|---|
| `summary.json` | Request outcomes, discovered endpoint/object/command names, frame counts, fixed failure codes and transcript digest; no help descriptions or console message values |
| `transcript.jsonl` | Private outgoing request attempts and original received frame payloads encoded as base64, with relative timestamps; incomplete trailing fragments are marked separately |

The summary is also printed to stdout. New directories use mode 0700 and files 0600. Existing directories, files and symlinks are never overwritten. Input socket symlinks and non-socket files are refused. The transcript digest identifies these captured bytes, not a printer, firmware version or running G-code file. The tool leaves firmware/file identity unverified.

**Keep the transcript private.** Base64 is encoding, not encryption. Console output can include signed links, filenames, credentials or configuration. The summary retains command, object and endpoint names, so review even that smaller file before sharing. Use a private external location or the ignored `tools/research/captures/` folder; create its parent separately. Never commit an owner capture.

## Limits and failure behavior

- Four fixed methods; no arbitrary method/parameter option, mechanical control, network-mode change, log-export trigger, firmware write, startup hook, retry or reconnect.
- One local Unix-stream connection. Complete sends use `sendall`; fragmented and combined `0x03` frames are buffered correctly. Reply IDs must be integer matches. Unrelated replies are counted and discarded, not reused for later requests. If a reply includes `method`, it must match too.
- 256 KiB per frame, 4 MiB received, 512 messages, 6 MiB transcript. Oversized trailing fragments preserve only a bounded prefix and the original pending-byte count.
- Five seconds per request by default (`--timeout` accepts 0.05–10); output observation 0–30 seconds; 90-second session ceiling after connection, plus at most the connection timeout.
- EOF, incomplete frames, malformed/duplicate-key/non-object JSON, ambiguous replies, remote errors or budget limits stop the run and retain a partial capture where writable. No automatic recovery actions are sent.

Exit codes: **0** completed the requested discovery/window; **1** incomplete capture with a summary; **2** argument or output failure. An output failure can leave a partial transcript without a summary. Ctrl-C attempts to finalize an incomplete capture. Completion does not establish that console output works, a cloud URL remains valid, or any file matches the executing job.

The pinned vendor source has a generic exception path that invokes printer shutdown. That source observation is another reason to keep discovery idle and avoid fuzzing parameters or broad object queries. This client deliberately omits `objects/query` and `objects/subscribe` until their selected fields and running-build behavior are qualified. The exact installed binary's behavior remains a device check.

## Validation and source provenance

Run from the repository root:

```sh
python -m unittest discover -s tools/research/tests -v
```

The protocol tests use a real loopback stream through a test-only connector adapter. Production has no TCP fallback. Separate native integration tests exercise the actual Unix connector, output subscription and socket-symlink refusal on Linux CI. Restricted workspaces that disallow Unix sockets explicitly skip those two integration cases; the workflow requires Unix-socket availability before running tests. Both Python 3.10 and 3.12 are covered in CI.

Verified checkpoint: [Linux CI run 39037683475](https://github.com/TheLastFrogrammer/elegoo-link/actions/runs/39037683475) passed **all 45 tests with zero skips** on each Python version, including both actual Unix-socket integration cases. The restricted local workspace passed 43 tests and explicitly skipped those two cases. [Machine-readable validation](../../docs/research/cc2-stock/CONSOLE-CAPTURE-VALIDATION.json) records both environments. This is synthetic-host validation; physical CC2 qualification is still pending.

Pinned vendor sources: [webhooks.cpp](https://github.com/elegooofficial/CentauriCarbon2/blob/5a2ea7fc03e707552701b1a69f463699cbd39230/elegoo/webhooks.cpp) for framing/envelopes, inventories and subscription; [whconsole.cpp](https://github.com/elegooofficial/CentauriCarbon2/blob/5a2ea7fc03e707552701b1a69f463699cbd39230/elegoo/whconsole.cpp) for the bundled CLI's fragmented-read and short-write limitations. [Evidence fingerprints](../../docs/research/cc2-stock/access/evidence/CONSOLE-CLIENT-PROTOCOL.json) record the inspected files and runtime-inventory limitation. No printer was contacted during implementation.
