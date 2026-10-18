# Offline CC2 capture inspector

The [read-only status and file tools](READONLY-STATUS-AND-FILES.md) now add a boot-scoped, fixed native status recorder, strict snapshot/delta inspection, a bounded native G-code range recorder, verified range assembly and separate USB v2 profiles. Initial owner access and hardware qualification remain required; no recurring reader or Android integration is enabled automatically.

For the new Pi transport setup, see [PI-COMPANION-SETUP.md](PI-COMPANION-SETUP.md): a Pi installer, supervised private USB receiver, static read-only printer exporter and optional fingerprint-gated procd hook. Initial owner access and physical qualification remain required. This starter exports fixed facts/captures; automated UART recovery, maintenance writes and G-code/media access are subsequent work.

For a stock owner shell without Python, use the [native first-contact recorder](NATIVE-FIRST-PROBE.md) and optional [checksummed paced-text transfer](SERIAL-TRANSFER.md). Both are preparation tools; physical access and actual printer acceptance remain pending.

For an independently established stock console, see the separate [bounded console recorder](CONSOLE-CAPTURE.md). It does not create shell access; Python availability on stock firmware is not established. The offline inspector below remains suitable for already exported files on a workstation or Termux.

`cc2_capture_inspect.py` summarizes files the owner has already exported. It never connects to a printer or cloud account, executes G-code, enables services or changes printer firmware. Python 3.10+ with the standard library is sufficient; no pip dependencies are needed. It can run on a workstation or in Termux with Python available.

## Use

From the repository root, with a finalized database export and selected logs:

```sh
python tools/research/cc2_capture_inspect.py \
  --db /path/to/export/elegoo.db \
  --log /path/to/export/printer.log \
  --log /path/to/export/ai_camera.log \
  --output /path/to/new/summary.json
```

Any one of `--db` or `--log` is sufficient. Repeat `--log` for up to 16 files. Omit `--output` to print the default summary to stdout. `--output` requires a new filename and an existing parent directory; it creates mode 0600 and refuses overwrite.

Normal output includes URL scheme/host and a SHA-256 of the reference, but omits raw URL paths, signed query strings, userinfo and fragments. It keeps selected filenames/task metadata, so it is still an owner document rather than a public diagnostic bundle. It omits raw log lines, account/config payloads and thumbnail image contents.

For deliberate private recovery of the original references:

```sh
python tools/research/cc2_capture_inspect.py \
  --db /path/to/export/elegoo.db \
  --include-private-references \
  --output /path/to/new/private-references.json
```

That explicit option includes the original references in the local report and requires `--output`. Do not commit the resulting report. Both research capture directories are ignored by Git; exports may also be stored outside the checkout.

## What it reads

Known SQLite tables: `cloud_file_cache`, `file_thumbnail`, `file_details`, `task_record` and `exception_message`. It reports missing tables/columns instead of assuming every export has the inspected schema. It reads only known real tables, not user-defined views, and never runs user-supplied SQL. Thumbnail records report byte lengths, not their base64/image contents.

Recognized log events include `on_cloud_print params=`, explicit cloud-download source/destination, cloud cache hits and selected V4L2 watchdog events. Console-output messages and camera health events are observations, not proof that a file matches the running job. The tool never follows a recovered URL or promises that it is still valid.

Limits: 256 MiB database; last 8 MiB of each log; 64 KiB per log line; default 50 rows/events, configurable with `--limit 1..1000`; 8192 characters per reference; two-second execution budget per SQLite query. It retains the newest recognized log events within the selected tail and reports omissions. File and log-tail hashes identify the inspected inputs. Database values of unexpected types are omitted or summarized.

## Database export requirement

Use an independently finalized, consistent export. Do not point the tool at the printer's live database or assume that copying the main file alone captured an in-flight transaction. The stock executable uses `unix-none`, so a second client's normal SQLite locks cannot coordinate with its writer.

The inspector uses read-only immutable SQLite access and refuses a nonempty adjacent `-wal` or `-journal`; it does not create/modify database sidecars. This refusal is not a complete proof of snapshot consistency: a missing journal can also mean the export is incomplete. It does not perform a physical capture, reconstruct incomplete transactions or audit every database page. A sidecar refusal calls for a proper export; deleting the sidecar is not a repair.

## Validation

```sh
python -m unittest discover -s tools/research/tests -v
```

Sixteen inspector tests use actual temporary SQLite databases and log files. They cover reference redaction/private opt-in, unchanged inputs and no new database sidecars, known/missing schema, table-versus-view handling, bounded BLOB/text/log output, row/event limits, incomplete JSON, repeated camera transitions, tail fragments, journal refusals, symlinks, malformed references, no overwrite and mixed successful/failed inputs. The same discovery command also runs the separate console protocol and native-connector tests described in CONSOLE-CAPTURE.md.

These tests establish host parser behavior. No genuine owner export, CC2 console, printer transfer or camera repair was exercised. Exit status: 0 for inspected inputs, 1 when an input failed but a report was generated, 2 for argument/output errors.

See [the research tracker](../../docs/research/cc2-stock/NEXT-STEPS.md) for the device-side capture and exact-file identity work.
