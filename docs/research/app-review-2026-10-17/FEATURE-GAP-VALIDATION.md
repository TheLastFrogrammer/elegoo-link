# Feature-gap review validation

## Source baseline

- Repository: TheLastFrogrammer/elegoo-link.
- Branch: claude/dreamy-hypatia-4ru1wu.
- Commit: dde76993f593f99b58264afaeb855cb718ddd2cc.
- Commit title: Projects: notebook overview, parts bench, prints and inspection (from the UI concepts).
- Commit timestamp: 2026-10-17T04:02:26Z.
- Android version: 0.64.0 / build155.
- Retrieved 109 current Java/build/README files through the connected GitHub source tools. Targeted inspection concentrated on Projects, archive/backup, slice/plate/settings handoffs, service/recorder, spool/library/history, guardian/cameras, automations and online model handoff. Retrieval count is not a claim of equal line-by-line review of every file.
- Verified the exact Git blob SHA for all 109 retrieved files before the final host run. The branch head was checked again before packaging and still matched the pinned revision.
- Compared the immediately preceding UI-study baseline ad7670c to this commit: one commit, six files, chiefly the new project bench/attempt/inspection implementation and tests.

## Executed domain reproductions

Passing here means the existing gap was reproduced. The tests do not expect corrected behavior.

| Proof | Observed behavior |
|---|---|
| 1 | Changing A to B retains A's accepted pieces and assembled flag without a revision-eligibility decision |
| 2 | Two attempts linking the same recording/part count twice after serialization |
| 3 | Attempt 501 is silently omitted on reload, reducing accepted count |
| 4 | A replacement spool receives the whole planned material debit |
| 5 | Two printer labels with the same filename and no UUID merge into one recording |
| 6 | Reusing an exact filename replaces bytes used by a historical library reference |
| 7 | Creating recording101 removes the oldest metadata without a project-reference check |
| 8 | Notes-off project export includes an attempt note/reference that the importer then discards |

Host command:

~~~text
python proofs/run-proofs.py APP_JAVA_SOURCE_DIR JSON_JAVA_SOURCE_DIR JAVA_EXECUTABLE
~~~

The runner compiles unmodified domain logic in Projects, ProjectArchive, SpoolStore, PrintRecorder, PrintJournal, GcodeLibrary, TrayPlan, FilamentMatch, SlicedMaterials, StatusPresentation and Diagnostics. Java17's compiler module was invoked directly because the runtime has no javac launcher on PATH. Compilation and all eight reproductions succeeded. Source hashes and the reproduction log accompany the suite.

Dependency: JSON-java tag20240303, commit f9b5587c87aaf02e4a5ed1991d48d6c05993624a, already available from the earlier audit. Host-only boundaries provide WorkshopUi.colourName and ProjectsActivity.size display output; copied Automation.cap and Cc2Codec.position helpers; ModelSites' model-type set; and unexercised URL/extension methods that throw if called. No tested identity, count, retention, archive serialization or spool-settlement behavior was substituted. The source manifest identifies the reviewed production files independently of those boundaries.

## Source-confirmed findings without a runtime reproduction

Backup exports projects/projects.json but not projects/assets. Project preparation hands the slicer model paths without project/part/revision/quantity allocation identity. Calibration instruction-to-settings adoption is manual. Automation Test executes actions and delayed callbacks are in memory. These were traced in the current source; they were not tested against Android services or a physical printer.

## Limits

No current full Gradle run, APK build, emulator, native slicing execution, account access, printer commands or physical tests. No Android process-death, permission, background-policy or clean-device restore validation. Host fixtures establish behavior under the specified inputs, not its prevalence on every firmware. The shareable report page uses plain static HTML/CSS; browser visual QA was unavailable in this environment. No application implementation changes were made.

## Deliverable checks

The static report has 16 sections and unique anchors. All 74 link occurrences were checked for valid internal anchors/local files or allowed external URL form; external website availability was not re-fetched for this check. The 12-entry shareable ZIP passed CRC checks. Both existing concept pages link to the new review. The main-app prototype's existing 134 logic/state checks still pass after the navigation addition. Browser pixels were not verified.
