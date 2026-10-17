# Claude handoff: make the main app easier to scan and navigate

## Target

Implement the first UI cleanup pass on the current app after reviewing newer branch changes. The design baseline was ad7670c70cb4e8708b8a6a7977dbd093b0f07bba, v0.63.0 / build 154.

Read MAIN-APP-UI-REVIEW.md and try both concepts in main-app.html. The default **Refined five tabs** concept is the recommended first pass. **Task workbench** is an alternative to evaluate, not a mandate to replace navigation immediately.

This package changes the design-study website only. It is not a native app patch.

## First pass: retain the five destinations

### 1. Print screen

- Retain Print / Printer / Files / History / Settings.
- Establish one main current-job surface with file, state, progress, layer, remaining estimate, and camera.
- Keep pause/resume and the current light action visually primary.
- Put picture interval, layer alerts, automations, and secondary camera options behind a labeled Print options route.
- Keep normal stop and emergency stop distinct. Do not hide urgent stop when an ordinary changing command is pending.
- Use a compact measured/target temperature summary and direct entry to the current recording/toolpath.
- Reduce duplicate camera/control entry points without removing their capabilities.
- Keep an idle state with Prepare a model and useful recent work; preserve offline preparation.

### 2. Printer context and feedback

- Make printer switching visibly interactive using the selected name and a chevron.
- Expose capability-specific availability: status, camera, controls, upload.
- Show stale/cached state explicitly, including independent camera and telemetry ages.
- Separate requested, acknowledged, and observed command state.
- Bind feedback to the printer/operation that produced it. A result from one selected device must not appear to confirm a different device's operation.
- Preserve the service's authorization, busy, freshness, and session rules; do not recreate inconsistent policy in UI-only logic.

### 3. Files and settings

- Give Projects, On phone, and On printer clear locations within Files.
- Keep model preparation and imported G-code available independently of printer connectivity.
- Make a file's origin visible and rename destructive actions by location.
- Promote Projects beyond the Send a file card.
- Split App preferences into Recording & alerts, Automations & integrations, Appearance, and Storage & backup.
- Keep diagnostic/experimental transport tools available through Help & diagnostics and feature search.
- Update FeatureIndex routes, aliases, fallback handling, accessibility labels, and native back behavior as destinations move.

### 4. Results

- Give recent attempts priority over statistics.
- From one attempt, open its result, recording, pictures, and available setup evidence.
- Preserve printer-history versus app-recording provenance and incomplete metadata.
- Move printer storage usage/cleanup to selected-printer or storage settings.
- Reuse the existing journal and recordings; avoid parallel stores or filename-only joins.

### 5. Project polish

- Make part rows open their details directly.
- Move reorder controls into an explicit mode/menu with an accessible alternative.
- Put archive/delete under Project options.
- Keep sharing accessible but secondary to planning and files.
- Preserve the new project file relationships and import/export protections.

## Bound the first pass

Do not require a UI-framework migration, broad protocol work, automatic print actions, or a full preparation rewrite to ship the first cleanup.

Persistent draft saving, one review flow across imported/printer/sliced/repeated files, immutable setup capture, and exact reprint provenance are a subsequent integration pass. The mockup shows their direction; it does not prove those data-layer features already exist.

## Source areas

Paths are relative to the app repository.

| Area | Files |
| --- | --- |
| Shell, hubs, live print, feedback | android/app/src/main/java/io/github/thelastfrogrammer/elink/MainActivity.java |
| Navigation/discovery | android/app/src/main/java/io/github/thelastfrogrammer/elink/FeatureIndex.java |
| Preparation | android/app/src/main/java/io/github/thelastfrogrammer/elink/SliceActivity.java |
| Project outline/files | android/app/src/main/java/io/github/thelastfrogrammer/elink/ProjectsActivity.java |
| Results and charts | android/app/src/main/java/io/github/thelastfrogrammer/elink/RecordingsActivity.java, PrintJournal.java |
| Backend availability/commands | PrinterService.java and the shared command-policy components in the current branch |
| Appearance | android/app/src/main/res/values/styles.xml and the current UI helper |

Inspect current code before splitting responsibilities. MainActivity is large; avoid simply moving the same duplicated state rules into another oversized activity.

## Acceptance criteria

- On a typical phone, the current job, camera entry, state, progress, and pause/resume are understandable before secondary tool rows.
- The person can identify whether shown data is fresh, cached, or unknown.
- Cloud monitoring availability is not presented as universal upload/control availability.
- Offline and busy states retain local preparation and explain the blocked physical step.
- An ordinary pending command does not hide emergency stop.
- A printer change invalidates stale command feedback and any physical confirmation tied to the old target.
- The file screen differentiates model input, G-code output, phone retention, and printer storage.
- Deletion text says what location or relationship will be removed.
- One print entry reaches its journal, charts, and photos when available.
- A printer-history-only entry honestly shows missing recordings/setup.
- Search finds every relocated capability and never executes a printer-changing action.
- Project outline, sharing, diagnostics, calibration, CANVAS, extra cameras, and automation remain discoverable.
- Large font, TalkBack, themes, rotation, process recreation, and native back hierarchy work.

## Verification

Use focused view-state tests for offline, stale, cloud read-only, local idle, printing, paused, and pending-command states. Include target switches and late asynchronous results.

Use screenshot/device checks at ordinary and large font sizes for the first viewport and long labels. Existing repository screenshots are older than this source snapshot and should not be used as current golden images.

Run checks appropriate to the changed native code and report actual results. Distinguish browser mockup verification from Android instrumentation and hardware checks.

No physical printer testing or external messages are authorized by this handoff.
