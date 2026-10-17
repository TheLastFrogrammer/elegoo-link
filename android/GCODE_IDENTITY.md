# Retained G-code and live-print file selection

Implemented for v0.65.0 / build 156, against app baseline `7239d9b8dbe3f002d2f5585e9fcd159da0561708`.

## What changes for the person using the app

Live toolpath asks which G-code copy was used for the current print. **Kept copies…** lists copies with the reported filename from this printer, plus unscoped phone imports/slices. Each row shows its source, date, byte count and SHA-256 prefix. Different contents with the same name remain separate choices. **Choose file…** imports a phone document; **Download from printer** uses the existing download flow and makes the result available among kept copies.

Before using a copy, the viewer shows its digest and asks the user to confirm that it is the sliced file used to start this print. The scene says **Selected by you**, includes the copy's source and digest prefix, and allows changing the copy. Matching filename, file size, cloud object reference or firmware UUID does not silently select a file or prove which bytes the printer is executing.

The choice survives activity recreation while the same service-observed print remains continuous. An observed idle state, printer/route/file/task change, duration/progress reset, service restart or at least 45 seconds without qualifying telemetry requires another choice. The old scene is cleared when the scope changes. Delayed parsing, JavaScript fetches and WebView completion callbacks cannot revive an earlier scene. A lost service clears it immediately.

## Archive model

The existing private `gcode-library` now contains:

| Item | Purpose |
|---|---|
| `objects/<sha256>.gcode` | One object per exact byte sequence; repeated names cannot overwrite different bytes |
| `index-v2.json` entries | Original basename, digest, size, source, retained time and optional printer serial |
| `index-v2.json` bindings | Explicit user choice for a printer and service-observed job token |

Imports, completed phone slices, acknowledged uploads, completed downloads, user-folder copies and manual viewer choices feed this archive. Identical bytes under different names share an object. Upload completion reports the actual uploaded source file, so changing the selected workspace file during an upload cannot archive a different file as the completed upload.

Earlier flat-cache files migrate on the service's file worker as unscoped **Earlier phone copy** candidates. A legacy original is removed only after successful retention. Interrupted archive temporary files are swept at startup; failed/empty legacy inputs remain available. Metadata replacement uses a synced temporary file and rename. Service/viewer instances share an archive lock; copy, hash, lookup and parsing work stays off the viewer's UI thread.

The archive keeps at most 12 content objects within a 1.5-GiB budget; each object is capped at 512 MiB. Pruning removes older objects without substituting another same-name version. A binding to a missing/pruned object produces an explicit recovery message when the viewer resolves it. This is a bounded working archive, not a backup: use the existing Save phone copy action for files that must be retained independently.

`CloudFileMemory` remains a per-printer/name hint for locating cloud storage objects. It is not a live-job identity authority. Recovered bytes are retained and confirmed through the same flow; this change neither creates cloud copies of USB/local-only prints nor changes stock firmware's download policy.

## Evidence and remaining limits

Regressions exercise same-name versions, deduplication, printer isolation, explicit job bindings, migration and interrupted copies, byte/count eviction, same-size corruption, separate library instances and a stale failure after a newer selection. Actual activity tests exercise the missing-file gate, source label, new-print clearing, delayed worker results, stale WebView completion and service loss. The production local-session fixture checks that upload completion carries the original source file. The browser viewer check defers actual fetches, clears the job, releases the old requests and checks that they cannot restore the scene.

An observed job token is a conservative continuity boundary, not firmware-attested identity. A same-name restart whose every transition/reset is omitted by the firmware inside the continuity window cannot be proven different. The user still confirms the copy, and the UI does not claim printer verification. Artifact verification detects changed phone bytes before parsing; it cannot compare against unreported printer bytes.

No physical printer, Android handset or owner console was connected for this implementation. Real document providers, large-file phone performance, cloud/USB-started jobs and stock firmware transfer behavior still need device acceptance. No firmware patch, mode toggle, helper installation or camera repair is included.

## Device acceptance

1. Keep two different G-code files with the same name, then start one using the normal printer workflow. Confirm that Live toolpath asks for a copy and offers the distinct hashes.
2. Select the correct copy and check the source label. Rotate the activity during the same continuous job; the choice should remain.
3. Finish/cancel, then start the same name again. Switch printers or disconnect/reconnect. The previous scene must clear and require a new choice.
4. Leave telemetry absent for at least 45 seconds. Once status resumes, confirm that an earlier choice is not restored by filename.
5. Exercise a download and a completed phone slice, and inspect their source labels. Use the user-folder/manual file flow when stock firmware refuses to serve a running file.
6. Import enough files to exceed a retention budget and reopen a previously selected print's viewer. A missing copy must be explained; no different version should appear automatically.
