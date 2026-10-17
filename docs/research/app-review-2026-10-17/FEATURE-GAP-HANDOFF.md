# Claude handoff — close Link Workshop's build lifecycle

Baseline: `TheLastFrogrammer/elegoo-link`, branch `claude/dreamy-hypatia-4ru1wu`, commit `dde76993f593f99b58264afaeb855cb718ddd2cc`, Android v0.64.0/build155. Rebase these recommendations against newer work before changing code. This is a review/implementation brief; the native app has not been modified by this study.

## Product intent

Connect planning, preparation, printing, inspection and improvement through stable records. Preserve the new project notebook/parts bench. Preserve human acceptance of physical pieces as distinct from firmware completion. Existing features include project/part file links, revisions as labels, print attempts, calibration generators, saved overrides, spool estimates, automation tests/logs and interrupted-slice recovery.

Read FEATURE-GAP-REVIEW.md for evidence, source links and the rationale. The included host proofs assert current undesirable behavior; convert them to acceptance regressions with the expected post-fix behavior. Do not treat a passing reproduction as a repaired app.

## Change 1 — share only the selected content

Files: ProjectArchive.java, ProjectsActivity.java; focused archive tests.

- Construct an explicit share schema instead of exporting Project.toJson wholesale.
- “Plan” export excludes attempts, their notes/local recording references, accepted-elsewhere stock and assembly state. Decide and document whether purchased-have/task-done travel; current import preserves those while printed counts reset.
- Notes off strips all note-bearing content from every archive entry, including print-attempt fields if a future full-record export offers them.
- Keep external-link warnings and managed-file validation. Show a content summary before sharing.
- Acceptance: inspect raw ZIP entries with marker strings; import alone is insufficient because the current importer discards attempts that remain in the raw export.

## Change 2 — restore managed project files as well as records

Files: Backup.java, Projects.java, ProjectArchive.java, restore UI/tests.

- Keep setup-only backup if useful; offer explicit complete workshop coverage.
- Include managed project bytes in a manifest with hashes and reference validation. Expose optional media/prepared-job size.
- Reuse existing staging/rollback rather than restoring references before their assets are available.
- Preserve stable IDs and accepted counts in full restore. Sharing a plan remains a separate import operation with new identities.
- External content URIs require an availability/permission repair list after migration.
- Acceptance: restore onto an empty data directory/clean installation, open a model, resolve a retained exact-repeat job, and verify counts and notes. Existing files on the source phone must not mask missing backup entries.

## Change 3 — establish common identities

Suggested conceptual records (adapt to current style; these are domain boundaries, not a prescribed class hierarchy):

| Record | Minimum responsibility |
|---|---|
| PrinterId | Stable physical device identity; transport/session generation is separate |
| PartRevision | Part identity, immutable revision ID, chosen model/asset hashes; editable human label |
| PreparedJob | Recipe, input hashes, transforms/copies/supports, engine/preset identity, overrides, output hash |
| Job | App identity, printer, observed firmware UUID when available, preparation reference, state/provenance |
| Allocation | Job/object → project/part/revision → intended/made/accepted/rejected pieces, unique allocation identity |
| MaterialEntry | Job + stable spool ID + amount + estimated/reconciled source and reason |

Maintain compatibility with old records. Unknown printer/revision/file provenance stays explicitly unknown. Do not fabricate a reliable historical hash from today's same-named G-code. Display names and printer filenames are labels, not identities.

Acceptance cases:

- Printer A and B, same filename, absent/reused job UUID: distinct recordings. Same verified printer over local/cloud reconnect: correct continuation.
- Tray locations are printer + unit + tray. Inventory can share a shelf without merging physical positions.
- Same filename with new bytes: old material analysis/guardian evidence never silently binds to the replacement.
- Old records migrate without losing notes or accepted counts; unresolved provenance is visible.

## Change 4 — carry context from preparation to inspection

Files: Projects.java, ProjectsActivity.java, SliceActivity.java, MainActivity.java, PrinterService.java, PrintRecorder.java, RecordingsActivity.java.

- Prepare selected carries allocation/revision/asset identity and reviewed quantities alongside paths.
- Let users select the active model; attaching another file must not silently change the manufacturing target.
- Record intended material choices with the recipe, actual mapping/spool choices with the job.
- Associate the observed print only when evidence matches the prepared job. Handle external starts through an explicit manual association.
- Completion offers an inspection draft. It never automatically accepts pieces.
- Opening/relinking the same job allocation edits it. Legitimate multi-part/multi-project allocations remain possible with a total-piece check.
- A revision change asks whether old pieces are compatible, need reinspection, or need replacement. Historical labels remain unchanged. Assembly gets an explicit review state.
- Acceptance: two needed brackets → reviewed two-copy plate → observed job → one accepted/one rejected → one remaining; reopen/restart/relink produces the same counts. Change revision and verify old stock does not silently become new-revision stock.

## Change 5 — protect evidence and complete material accounting

- Remove silent 500-attempt truncation on project load. If limits remain, enforce them before entry and provide archival.
- Preserve compact outcome/allocation records indefinitely. Pin project-referenced recording/artifact evidence or require explicit removal with an accurate consequence summary.
- Do not debit the end-of-job tray occupant for an entire job. Bind material use to stable spool IDs and record changes during the print.
- Add reconciliation for external starts, default/unknown mappings and manual weighing. Keep uncertainty visible and adjustments idempotent.
- Acceptance: 501+ attempts survive save/reload without count changes; project evidence survives normal pruning policy; replacement spools receive only their attributable estimate and unknown intervals require review.

## Incremental feature follow-through

1. A manual production board with quantities and state transitions: Ready to prepare, Prepared, Printing, Inspect, Accepted/Rework. Begin with one plate; add compatible grouping later.
2. Named preparation recipes and exact-repeat availability, then editable copies and setup comparisons.
3. Calibration result capture: observation/photo → computed proposed value → reviewed scoped saved setting, linked to its test.
4. Monitoring health: status freshness, recording, picture age, guardian last check/prerequisites, notifications, background state; specific repair links.
5. Automation Preview separate from Send test; pending-action lifecycle and per-action outcomes. Choose restart/expiry/cancellation semantics before adding durable delay execution.

Later: model-source/creator/license metadata traveling with assets; printer maintenance history. Defer broad CAD/farm/autonomous-control expansion while common records settle.

## Verification boundaries

Eight host reproductions ran against eleven current production classes, with explicit compile-time helper/display boundaries. They cover domain behavior, not Android lifecycle or firmware. Backup coverage was source-inspected, not executed as a clean-device restore. No current full Gradle suite, APK build, emulator, hardware, cloud or screenshot QA was run by this pass. Run relevant existing tests, focused regressions and device checks for the changes actually made; see FEATURE-GAP-VALIDATION.md.
