# Link Workshop — deeper workflow and feature-gap review

Reviewed 17 October 2026 · Android v0.64.0, build 155 · branch `claude/dreamy-hypatia-4ru1wu` · pinned commit `dde76993f593f99b58264afaeb855cb718ddd2cc`.

**The strongest next direction is continuity across the whole build: plan → prepare → print → inspect → improve.** Link Workshop already has an unusually broad set of tools. The opportunity is to make the relationships between them dependable, and give each workflow a clear finish. More top-level screens would add less value than carrying the right context between existing screens.

This review separates observed behavior from proposed product work. Eight host reproductions exercise the current domain code. Backup coverage and the missing handoff fields are confirmed by source inspection. The proposed interfaces and acceptance criteria are recommendations. No Android device, emulator, native slicer run, real account, or physical CC2 was exercised in this pass.

## What is already there

The new project notebook, grouped parts bench, design state, revision labels, accepted/rejected inspection, manual and recorded print links, and assembly check are implemented in this revision. So are multi-model and multi-plate slicing, copies and placement, support blockers/enforcers, per-object settings, saved override sets, calibration generators, material mapping, spool estimates, journal ratings, recordings, advisory guardian, automation testing/logs, backup/restore, and project sharing.

There is also recovery for an interrupted slice: the running selection is saved and can be restored. That is useful groundwork for reusable preparation recipes. A durable, named recipe associated with a completed job is a further step.

Several earlier audit concerns have corresponding changes in the current source: sealed/redacted automation backups, staged restore with rollback, accepted paths for retained slices, delayed-rule rechecks, event-bound recording attachments, and accumulated guardian colours. These are observations of the new implementation, not a claim that the entire previous audit was regression-tested here.

## Priority map

Priority reflects user impact and dependencies; it is not a delivery estimate.

| ID | Gap | Existing foundation | Smallest useful improvement | Order | Evidence |
|---|---|---|---|---|---|
| G01 | Share controls miss print-attempt notes | Project export and Notes checkbox | Explicit export schema that honors the selected content | Repair now | Reproduced 8 |
| G02 | Backup restores project records without stored models | Setup backup includes projects.json | Include managed assets and validate their references on restore | Repair now | Source confirmed |
| G03 | Printer identity stops at some subsystem boundaries | Saved printers and connection generations | Namespace recordings, tray locations and file references by stable printer ID | Repair now | Reproduced 5; source confirmed |
| G04 | Revision labels do not control accepted stock | Revision field and per-part inspection | Snapshot the built revision; ask whether older pieces remain usable | Next foundation | Reproduced 1 |
| G05 | Project → slice → job → inspection is a manual round trip | Prepare selected; Link recorded print | Carry part allocations into a job; offer one idempotent inspection entry | Next foundation | Source confirmed; reproduced 2 |
| G06 | A repeatable job lacks an immutable recipe/file reference | Retained G-code, saved settings and history | Keep the preparation recipe and exact output hash with the job | Next foundation | Reproduced 6; source confirmed |
| G07 | Retention ignores project evidence and can alter counts | Automatic recording/slice/cache limits | Pin referenced evidence; never silently discard counted attempts | Next foundation | Reproduced 3 and 7 |
| G08 | Material estimates lack a reconciliation ledger | Spools, shortfall warning and use estimates | Bind use to spool IDs and show estimated versus reconciled consumption | Follow foundation | Reproduced 4; source confirmed |
| G09 | Remaining quantities do not become a production plan | Counts, next steps, batch prepare and upload queue | Explicit quantities and compatible plates on a manual build board | Product expansion | Source confirmed |
| G10 | Calibration generates a test but does not capture its result | Towers/patterns and saved overrides | Record the observation, preview a setting change, save a scoped result | Product expansion | Source confirmed |
| G11 | Monitoring readiness is spread across settings and diagnostics | Freshness checks, guardian verdicts and picture status | One live explanation of what is currently being watched | Follow foundation | Source confirmed; design proposal |
| G12 | Automation testing lacks a consequence-free preview and durable pending view | Test, values, logs and one webhook retry | Render the planned action first; show pending/running/failed results | Product expansion | Source confirmed |

## G01 — Make project sharing honor its content controls

**Observed:** Export begins with the complete project serialization. Turning off Notes clears the project description and part notes, but leaves `attempts[].note`, recording references and attempt counts in `project.json`. The importer then creates a fresh project without those attempts. A recipient can still inspect the ZIP and read data that the imported UI does not show.

**Reproduction:** A project containing `PRIVATE_ATTEMPT_NOTE_123`, exported with notes disabled, still contains that exact marker and its recording filename. Importing that archive yields no attempts and zero accepted printed pieces.

**Recommendation:** Build a dedicated share representation with an explicit field allowlist. Offer clearly different purposes: “Share the plan” and, eventually, “Export my complete build record.” The plan should include the chosen files, design outline and permitted notes. A full record would deliberately carry outcomes and evidence. The UI should summarize exactly what will leave the phone.

**Done when:** Notes off removes every note-bearing field from the archive. Plan sharing excludes attempts, local recording paths and completion state by default. Tests inspect the raw archive as well as the imported project. Define purchased `have` and task `done` semantics too: the current importer preserves those while resetting printed acceptance and assembly.

Evidence: [ProjectArchive.export and import](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/ProjectArchive.java), [share controls](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/ProjectsActivity.java#L998-L1013).

## G02 — Make backup a credible phone-migration path

**Observed:** The backup allowlist includes `projects/projects.json`. It does not include `projects/assets/`, retained slices, the G-code library, or saved slice-selection bytes. Optional recordings are handled separately. On a clean phone, restoring the project JSON therefore cannot restore its managed model bytes through this backup path. Linked external documents also need a fresh availability/permission check.

Project sharing can carry managed files, but its importer creates a new project with new IDs and resets print attempts. It is not a substitute for preserving the user's complete project state.

**Recommendation:** Distinguish “App setup” from “Complete workshop backup.” Give a size preview for managed assets, selected prepared jobs and recordings. Deduplicate bytes by hash, maintain references in a manifest, and validate completeness before replacing live state. Reuse the staged-restore work already present. Show an explicit repair list for external links and missing optional evidence.

**Done when:** A clean-install restore opens a managed project model, retains its accepted counts and attempt identities, and can resolve any job marked available for repeat. External links are clearly identified for relinking. No claim of completeness depends on files that happen to remain on the original phone.

Evidence: [Backup.FILES, export and restore](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/Backup.java), [project import identity/reset behavior](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/ProjectArchive.java#L182-L204). This finding was source-traced; a clean Android restore was not run.

## G03 — Carry stable printer identity into every subsystem

**Observed:** PrintRecorder identifies the current/resumable job by reported UUID, falling back to filename. It does not include printer identity in that comparison. The service passes a printer display name, and changing connection data does not reset the recorder. Spools use a single `spools.json`, with tray locations such as `0:0` and no printer namespace. G-code lookup also uses a global filename.

**Reproduction:** Feed the actual recorder a printing status from Printer A, then Printer B with the same filename and no UUID. Both samples end up in one recording labeled Printer A. The fixture exercises a supported fallback path; it does not establish how often particular firmware omits UUIDs.

**Recommendation:** Introduce a persistent PrinterId based on verified device identity, an app JobId, and a separate connection generation. Route changes between local and cloud should keep the same physical printer identity. Selecting another printer must select another recording/inventory context. Where identity is unknown, keep the data provisional and avoid merging automatically.

**Done when:** Same filenames on two printers create distinct jobs. Reconnecting through another transport to the same verified printer can resume the same job. Tray bindings use printer + unit + tray. Display-name changes do not break history. Active job context is never inferred from the currently selected printer alone.

Evidence: [PrintRecorder.update/resumable](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/PrintRecorder.java#L168-L191), [SpoolStore slot and pending use](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/SpoolStore.java), [PrinterService.record](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/PrinterService.java#L75-L108).

## G04 — Give revisions real meaning

**Observed:** A part has a mutable revision label. An attempt stores part IDs and counts, with no built revision or asset hash. Accepted stock is the sum of every attempt for the part. Print rows display the part's current revision beside historical counts.

**Reproduction:** Accept two revision A pieces, mark the project assembled, then change the part to B. After serialization/reload the project still has two accepted pieces, all parts available, and assembled=true. The historical record has no built-revision field to distinguish A from B.

**Recommendation:** Preserve an immutable revision snapshot for each produced allocation. Changing the design should ask whether existing pieces remain compatible, require reinspection, or need replacement. Keep the historical judgment and add an explicit eligibility decision for the new build. Select the active model for a revision; the newest attachment should not silently determine the design to manufacture.

**Done when:** Historical rows retain their original revision. A change never silently relabels stock. The user can intentionally reuse compatible old pieces. Updating a design surfaces a review of assembly validity without erasing their prior assembly record.

Evidence: [Projects.Part, Attempt and accepted](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/Projects.java#L70-L156), [historical labels and prepare selection](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/ProjectsActivity.java).

## G05 — Close the project-to-print loop

**Observed:** Prepare selected sends an array of model paths to SliceActivity. It does not send project, part, revision, requested quantity or allocation IDs. Recorded-print linking is a separate manual picker, followed by entering the pieces per part. Reopening an inspection correctly sets counts rather than adding them, but adding the same recorded print as another attempt is still allowed.

**Reproduction:** Two attempts that reference the same recording and each accept one of the same part survive save/load and count as two accepted pieces. This proves a duplicate-link path; it does not imply every repeated link is invalid, since a job can contain multiple distinct allocations.

**Recommendation:** Carry a small build context through preparation and job observation. Preserve object → part/revision → intended piece count. On a matching completed or stopped job, offer an inspection draft with the allocations filled in. Firmware completion must leave pieces awaiting inspection. Enforce one allocation identity per physical set of pieces; revisiting, reconnecting or relinking must edit the existing record.

For prints started elsewhere, retain the manual linking flow and label the unknown preparation details. Mixed-project plates need explicit allocation of physical pieces so the same piece is not credited to two builds.

**Done when:** Preparing two brackets leads to an inspection draft for those brackets, even after a restart. Partial acceptance remains possible. Duplicate link attempts navigate to the existing allocation or require a deliberate split with a total-count check. Journal ratings and project counts link to the same job while preserving their different meanings.

Evidence: [ProjectsActivity.prepare](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/ProjectsActivity.java#L458-L472), [attempt picker/editor](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/ProjectsActivity.java#L608-L665), [recording schema](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/PrintRecorder.java#L24-L65).

## G06 — Preserve what actually produced a successful print

**Observed:** The G-code library replaces a file under its printer filename. Historical material analysis looks up the recording's filename in that mutable library. The recorder stores no output hash or full preparation recipe. Saved setting sets and interrupted-slice recovery exist, but successful slices clear the running selection file.

**Reproduction:** Store revision A as `bracket.gcode`, then store B under the same name. A historical library reference now reads B's bytes. The earlier name-sanitization collision was improved; exact-name reuse and cross-printer identity are separate issues that remain.

**Recommendation:** Keep an immutable recipe ID and G-code content hash with the job. Record model hashes, transforms/copies, support edits, engine/preset identity, explicit overrides and filament intent. Capture actual printer/tray/spool choices at start separately from the desired recipe. Offer “Repeat this exact prepared file” and “Edit a copy of this setup.” Explain availability and unknown provenance before either action.

**Done when:** Reusing a filename never changes historical analysis. If the exact artifact is gone, the UI says so. A retained recipe reopens the layout and settings without reconstruction. A changed slicer/preset produces a visible comparison rather than an implied identical repeat.

Evidence: [GcodeLibrary.put/find](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/GcodeLibrary.java#L19-L48), [historical material lookup](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/RecordingsActivity.java#L405-L417), [slice selection recovery](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/SliceActivity.java#L195-L221).

## G07 — Make retention respect project history

**Observed:** Project loading reads at most 500 attempts, while creating/serializing attempts does not enforce that cap. Print recordings prune to 100 without checking project references. Slices keep 20 outputs; the G-code library keeps 12 files within a 1.5 GiB budget. Project links survive missing recordings, but the evidence disappears.

**Reproductions:** A 501-attempt project with one accepted piece per attempt reloads with 500 attempts and 500 accepted pieces. Creating recording 101 removes the first recording metadata regardless of any project link.

**Recommendation:** Separate disposable previews from durable build evidence. Counted attempt records must remain durable. Pin project-referenced recordings/artifacts or offer a deliberate storage policy that preserves compact outcome/provenance records while removing large media. Show storage usage and the effect of cleanup before deletion. If a limit is necessary, enforce it before adding and offer archival; never silently truncate on load.

**Done when:** Save/reload never changes accepted quantities. Project-linked evidence is preserved or explicitly marked as intentionally removed. Storage cleanup cannot make an available exact-repeat action silently resolve to another file.

Evidence: [Project.fromJson attempt cap](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/Projects.java#L199-L203), [recording retention](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/PrintRecorder.java#L371-L374), [SliceStore](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/SliceStore.java).

## G08 — Add a material ledger users can reconcile

**Observed:** Consumption begins for app-started prints with known grams and explicit tray choices. Pending use records tray keys and planned grams, and settles against the spool occupying the tray at the end. It does not preserve spool identity for each portion of a print. External starts/default mappings can leave consumption unknown; partial use is estimated from progress.

**Reproduction:** A print begins on a spool with 500 g left. Replace it at 20% with another spool holding 800 g. Completing a planned 100 g print leaves the original at 500 g and charges the replacement the full 100 g, leaving 700 g.

**Recommendation:** Use entries bound to JobId + SpoolId, with source and confidence: planned, estimated, manually corrected or weighed. Record a spool change as a new allocation interval. For unobserved consumption, show “Needs reconciliation” and let the user enter a new remaining weight. Recompute the adjustment without charging the same job twice. A shared stock inventory can remain global while physical locations are printer-specific.

**Done when:** A mid-print spool change cannot move all usage onto the replacement silently. Unknown use is visible. Manual corrections have an explanation and can be revised. Use estimates do not present progress percentage as a precise mass measurement.

Evidence: [SpoolStore.started/observe/settle](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/SpoolStore.java#L132-L188), [start and use registration](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/MainActivity.java#L1846-L1854).

## G09 — Turn remaining quantities into a practical production plan

**Observed:** The overview computes remaining quantities. Prepare selected opens one newest managed model per selected part, without carrying remaining quantities. The upload queue transfers files; physical print scheduling, plate clearing and inspection remain separate.

**Recommendation:** Add a compact build board inside Projects: Ready to prepare → Prepared → Printing → Inspect → Accepted / Rework. Each card says what it will make and for which revision. A “Prepare remaining” review should default to shortages, allow a fit-test piece first, and group compatible material/nozzle/profile choices. Show remaining work as approximate plate count and estimated time/material once recipes exist.

Start with a manually advanced plan. Upload acknowledgement, observed printing, physical part removal and accepted inspection are separate events. Represent each one honestly. Persist interrupted work and give a clear next action.

**Done when:** A need for six brackets with two accepted proposes four, with an explicit quantity review before layout. Rejected pieces return to remaining work. Uploaded plates do not become “printed,” and completed plates do not become “accepted.”

Evidence: [next-step calculation](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/ProjectsActivity.java#L285-L307), [prepare handoff](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/ProjectsActivity.java#L458-L472), [service upload queue](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/PrinterService.java#L434-L502).

## G10 — Finish the calibration loop

The app already generates multiple useful calibration tests and explains how to read them. It also saves named setting overrides. The missing connection is a structured record of the observation and its adoption into a suitable setup.

**Recommendation:** Calibration history should capture test type/range, printer/nozzle, material/preset identity, photo, selected block/line/measurement and proposed setting delta. Offer “Save result and review changes,” show the computed value and unit, then save a named setup with its source test. Permit comparison with a prior test. Keep firmware-dependent tests visibly unverified until validated on that device.

**Done when:** The user can move from a measured result to a reviewed saved setting without transcribing a formula, and can later see why that setting was chosen. Applying to a different nozzle/material requires an explicit suitability review.

Evidence: [calibration catalog and reading instructions](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/SliceActivity.java#L1371-L1385), [saved override sets](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/SliceSettingsActivity.java#L518-L551). This is a workflow extension; generators and saved settings already exist.

## G11 — Explain monitoring coverage at a glance

There are already useful freshness checks, guardian verdicts, picture timing, cloud-background controls and explanatory diagnostics. They are distributed across the app. A user can reasonably need one answer: “What will this phone notice while I am away?”

**Recommendation:** A small monitoring health summary on Print should show status age, last saved picture, recording state, guardian eligibility/last check, notification availability and background-watch mode. Each row has an actual reason and a relevant repair action. Examples: “Status live; pictures paused while cloud video is closed”; “Recording temperatures; guardian waiting for exact G-code”; “Last checked 7 minutes ago.”

This is an observability feature, not a promise of uninterrupted background operation. Camera visits, guardian comparisons and status alerts have different requirements. Keep their states separate.

**Done when:** Every unavailable monitor has an explanation, timestamps refer to successful observations, and switching apps or losing contact updates coverage honestly. Verify on an Android device with permission denial, background restrictions, offline transitions and camera failures.

Evidence: [recording/camera prerequisites](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/PrinterService.java#L102-L110), [guardian prerequisites and verdicts](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/GuardianRun.java), [existing monitoring UI/settings](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/MainActivity.java).

## G12 — Give automations a preview and a visible lifecycle

Test, sample values, execution diagnostics and a one-time webhook retry are already implemented. Test runs the actions; it is not a pure preview. Delayed actions use in-memory Handler callbacks, and shutdown removes those callbacks. Pending delays are not represented as a durable job list in this implementation.

**Recommendation:** Add “Preview” alongside “Send test.” Preview renders the filled title/body, destination, attachment availability, target printer and conditions without performing actions. A run history groups the trigger, due time and per-action outcomes. For delays, choose and disclose a policy: persist appropriate pending actions with expiry/revalidation, or visibly mark them canceled when monitoring ends. Do not replay physical commands automatically after a restart. Retried external requests need an explicit idempotency policy where the receiver supports one.

**Done when:** Preview has no external effects. A pending action can be found and canceled. Restart behavior is understandable. Delivery uncertainty remains distinct from confirmed failure, and diagnostics already produced by the runner become useful in the workflow.

Evidence: [Test implementation](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/AutomationActivity.java#L324-L331), [delay/recheck and shutdown](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/AutomationRun.java).

## Two later opportunities worth preserving

**Model provenance:** Online search already displays designer/source/license information, but the result handed back to slicing is a path list. Carry a small source record with the downloaded asset: page URL, creator, license label as supplied, fetch date, original filename and content hash. Let the user correct it and include appropriate attribution in project sharing. Do not infer permission from downloading. [ModelSearchActivity](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/ModelSearchActivity.java#L171-L177), [path-only result](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/ModelSearchActivity.java#L235-L245).

**Maintenance history:** Leveling, vibration and self-check commands exist. A small printer-specific log could record nozzle/plate changes, cleaning and user-entered maintenance, with optional reminders based on observed print time. Treat recommendations as user-configured until manufacturer guidance is verified. This is a later product idea; an overdue-maintenance defect was not reproduced.

## Recommended implementation trajectory

1. **Restore trust in existing data.** Fix the share payload, complete backup coverage, and prevent cross-printer recording/inventory ambiguity. Add focused regressions for the reproduced paths.
2. **Join the build lifecycle.** Introduce printer/job/revision/artifact/allocation identities; connect project preparation to inspection; preserve recipes and evidence. Start with one plate and explicit quantities.
3. **Make the workshop easier to run.** Add the production board, material reconciliation and monitoring health using those records.
4. **Make each print improve the next.** Add calibration-result adoption, comparisons, model provenance and maintenance history.

Keep the existing five-tab direction while these workflows settle. Use contextual project/job links, compact summaries and focused editors. Reserve a new primary destination for a task that proves frequent in usage. I would defer a broad CAD toolset, a farm scheduler, automatic physical restart and extra AI controls until the shared job and provenance model is reliable.

The defining experience should be: “I know what this build needs, what this plate will make, what actually happened, and what to do next.”
