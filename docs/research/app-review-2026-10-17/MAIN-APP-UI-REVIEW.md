# Link Workshop: main app UI review and design study

Reviewed 16 October 2026, America/Chicago.

## Recommendation

Keep the existing five destinations for the first redesign pass. Improve the hierarchy inside them, make printer availability easier to understand, and connect preparation and print results more directly.

The alternative task-led navigation is worth testing, especially because slicing on a phone is now a major capability. It is a larger information architecture change, with a real discoverability cost for printer controls and settings. The interactive study includes both options.

## Scope and evidence

Source snapshot: **ad7670c70cb4e8708b8a6a7977dbd093b0f07bba**, app **v0.63.0 / build 154**, branch **claude/dreamy-hypatia-4ru1wu**, repository **TheLastFrogrammer/elegoo-link**.

This snapshot includes the new project outline, file linking, import/export, and cover-page work. The review does not treat those existing capabilities as future features.

Inspected the current MainActivity, FeatureIndex, SliceActivity, ProjectsActivity, RecordingsActivity, manifest, and theme. Also inspected the repository's older monitor and slicer documentation screenshots. Those screenshots show an earlier navigation and layout; they are visual context, not proof of the current app's appearance.

This is a source-grounded design review, not an observed user study or an instrumented Android session. No app source changes, printer commands, or account operations were performed. The published study contains sample data and local interactions.

### Source references

- [MainActivity: application shell, live print, controls, files, history, and settings](https://github.com/TheLastFrogrammer/elegoo-link/blob/ad7670c70cb4e8708b8a6a7977dbd093b0f07bba/android/app/src/main/java/io/github/thelastfrogrammer/elink/MainActivity.java)
- [FeatureIndex: current destinations and feature discovery](https://github.com/TheLastFrogrammer/elegoo-link/blob/ad7670c70cb4e8708b8a6a7977dbd093b0f07bba/android/app/src/main/java/io/github/thelastfrogrammer/elink/FeatureIndex.java)
- [SliceActivity: preparation, settings, native slicing, and handoff](https://github.com/TheLastFrogrammer/elegoo-link/blob/ad7670c70cb4e8708b8a6a7977dbd093b0f07bba/android/app/src/main/java/io/github/thelastfrogrammer/elink/SliceActivity.java)
- [ProjectsActivity: project planning, parts, links, and sharing](https://github.com/TheLastFrogrammer/elegoo-link/blob/ad7670c70cb4e8708b8a6a7977dbd093b0f07bba/android/app/src/main/java/io/github/thelastfrogrammer/elink/ProjectsActivity.java)
- [RecordingsActivity: recordings, journal, charts, and photos](https://github.com/TheLastFrogrammer/elegoo-link/blob/ad7670c70cb4e8708b8a6a7977dbd093b0f07bba/android/app/src/main/java/io/github/thelastfrogrammer/elink/RecordingsActivity.java)
- [Current theme](https://github.com/TheLastFrogrammer/elegoo-link/blob/ad7670c70cb4e8708b8a6a7977dbd093b0f07bba/android/app/src/main/res/values/styles.xml)

## What already works

Several foundations should be retained:

- Five task-related tabs: Print, Printer, Files, History, Settings.
- A feature search with everyday synonyms and explicit navigation targets.
- Sections with front-page summaries and an actual back hierarchy.
- State-dependent actions, several explicit confirmations, and connection recovery links.
- A current-print camera, snapshots, toolpath, recordings, and outcome journal.
- A native slicer with a persistent action bar, advanced controls, and existing collapsed groups.
- Project planning that works before a model exists, with reusable file relationships.
- Light and dark themes and explicit large-text/accessibility handling.

The recommendations refine this structure. They do not require removing advanced tools or replacing the Android UI framework.

## Highest-value opportunities

| Priority | Current evidence | User-facing friction | Proposed change |
| --- | --- | --- | --- |
| High | MainActivity adds several rows to the live-print hero: pause/resume/stop/light, toolpath/camera, pictures/automations, layer alerts, and recordings | Similar visual weight makes the next useful action harder to scan | Give current status, image, pause/resume, and light a clear hierarchy; put secondary job options behind a labeled entry |
| High | Camera is available in the monitor, a quick action, and a separate Camera section with local/cloud and checking tools | Watching the print and diagnosing transport are adjacent tasks with different complexity | One viewing surface with source and image age; diagnostics become a distinct secondary route |
| High | Current header title is clickable for printer switching; route is a separate summary chip | Switching printers has a weak visual affordance, and a route name does not explain all capabilities | Explicit printer selector with a chevron; connection detail lists status, camera, controls, and upload availability |
| High | Files builds the printer browser first, then a Send a file card with slicing, imported G-code, recent slices, and Projects | Preparing on the phone and organizing projects are visually secondary to printer storage | Give Projects, On phone, and On printer clear local destinations; place preparation at a predictable entry point |
| High | History builds statistics, a link to recordings, printer history, timelapses, and storage | A person must choose a collection before they can find the evidence for one print | Let one print entry open Result, Charts, Photos, and Setup; retain source and availability labels |
| Medium | App preferences includes appearance, recording, cloud pictures, notifications, guardian, backups, and automations | Related controls are split across a long mixed-purpose page | Separate Recording & alerts, Automations & integrations, Appearance, and Storage & backup |
| Medium | A selected phone file has a generic Remove action; printer file dialogs also contain deletion operations | The user must infer what location or relationship an action affects | Use explicit names: Remove phone copy, Delete from printer, Unlink from project |
| Medium | Upload is followed by refreshing the printer list and choosing Print setup; slicer output is handed into Files | The handoff exposes internal destinations rather than the user's goal | Converge on Review & start, with Transfer only retained as a separate deliberate action |
| Medium | Printer storage usage lives on History | Storage management is grouped with past print outcomes | Move usage/cleanup to the selected printer or Storage & backup, with a route back to files |
| Medium | Projects overview exposes archive and deletion; each part row has Open, up, and down controls | Administrative actions compete with the working outline | Put administration in project options; make the row open the part, and expose reorder mode when requested |
| Medium | Global feedback remains visible on every tab until dismissed or replaced | Old action feedback can be mistaken for current printer state or a new target's result | Bind command feedback to its printer/operation, separate requested from confirmed, and expire resolved informational messages |
| Lower | Large rounded cards, multiple full-width buttons, and repeated captions are common | Comfortable touch sizing can still create long, visually repetitive screens | Use fewer outer surfaces, quieter list rows, and compact summaries while preserving touch and large-text support |

These are design judgments inferred from the source structure. Actual frequency, first-screen fit, and task success should be checked with the app on a phone.

## Screen-by-screen direction

### Print: status first, secondary tools second

The current source already uses a linear progress bar and camera within the job area. Do not use the older screenshot's progress ring as evidence of today's implementation.

The proposed first screen contains:

1. Selected printer and route/freshness summary.
2. File identity, state, progress, layer, and one remaining-time estimate.
3. One camera surface with its own source and image age.
4. Pause/resume and the current light action.
5. A compact measured/target temperature strip.
6. Toolpath and this attempt's recording.

Pictures, layer alerts, automations, and detailed camera options remain reachable through Print options. A normal Stop print is clearly labeled and confirmed. Emergency stop remains distinct and accessible, including while an ordinary command awaits acknowledgment.

In the prototype, normal stopping is inside Print options. That adds a navigation tap for an infrequent action; urgent stop stays directly available in the job dock. Test whether users find this distinction quickly enough before adopting the placement.

In an idle state, the same destination should offer Prepare a model and useful work to continue. Disconnected state should preserve known data with clear age labels while leaving offline preparation available.

### Printer: controls scoped to the selected machine

Group live controls, filament, and maintenance. Preserve heater measurements separately from targets. Show why a control is unavailable instead of requiring users to deduce the rule from a disabled button.

Keep bed leveling, homing, vibration, and full self-check in maintenance, with existing confirmations and command policy. Detailed reported values, firmware transport experiments, logs, and local/cloud coexistence tests belong in diagnostics.

A UI redesign does not repair backend command priority, freshness, or session identity by itself. Those requirements from the earlier audit still apply.

### Files / Library: location is part of the identity

Projects, On phone, and On printer answer different questions. Make that scope explicit before the list. Printer storage includes internal/USB location; phone files distinguish editable models from already sliced output.

Opening a row opens its details. A visible preparation/review action should state the next step. Deletion and unlinking should name the affected location and preserve independent shared relationships.

Projects should be an ordinary organization route. Housing it only within a Send a file card makes its planning role harder to discover.

### Prepare: a continuous workspace

The source already has substantial model tools, presets, material slots, object settings, and a persistent slicing action bar. Keep that power.

The study tests three understandable stages:

- Model & plate: model source, copies, placement, orientation, and object tools.
- Print settings: effective process/material values, a short edited-value count, and access to full settings.
- Preview: output review, estimates, and an explicit transition to Review & start.

Live temperature controls and slicer material temperatures must remain separate. Changing a printer heater does not edit an existing sliced file.

A review screen should describe whether it will transfer and start, start a file already on the printer, or only transfer. Do not imply that every connected route supports uploading. Keep preparation available when the printer is busy or offline, and revalidate the target and artifact at dispatch.

Explicit reusable draft saving and a unified multi-entry review flow may require new state/data integration beyond visual rearrangement. Treat them as a second stage, not a cosmetic patch.

### History: one attempt, several kinds of evidence

Give recent print entries priority over aggregate statistics. Let an entry expose a result, charts, pictures, and the setup information actually retained.

Preserve provenance. A printer-history entry may have no app recording. An app recording may have incomplete model/settings metadata. Do not join records solely because their filenames match.

Use machine completion and user-rated quality as distinct facts. Existing journal outcomes are already a useful basis.

### Settings: divide by purpose

Recommended groups:

| Group | Contents |
| --- | --- |
| Connection & printers | Saved machines, account, local/VPN configuration, route details |
| Recording & alerts | Recorded data, image source/interval, cloud picture behavior, notifications, watch, guardian |
| Automations & integrations | Rules, templates, Home Assistant, Tasker/webhooks |
| Appearance & accessibility | Theme and native Android presentation/accessibility behavior |
| Storage & backup | Retained files, recording cleanup, printer usage, backup/restore |
| Help & diagnostics | Connection check, camera check, logs, field checks, reported values, licenses |

Keep consequence and cost explanations beside the relevant option, with longer detail on demand. For example, mobile-data use belongs with cloud pictures; guardian limitations belong with guardian configuration and alerts.

### Projects: reduce administration in the outline

The new v0.63 project implementation is already substantial. Preserve its file relationships, per-project planning, explicit external-link availability, and import/export validation.

Make the whole part row the primary open action. Move up/down controls into a reorder mode or part menu with an accessible alternative. Keep archive/delete in project options rather than the main work area. Put project sharing near the title/options so it is available without competing with every next action.

## Navigation concepts and tradeoffs

### Refined five tabs — recommended first

Print / Printer / Files / History / Settings.

Advantages: familiar structure, discoverable machine controls and settings, smaller migration surface, direct reuse of FeatureIndex and existing hub sections.

Tradeoff: preparation remains an action inside Files or Print rather than its own bottom destination. Make that action prominent and consistently named.

### Task workbench — validate before adopting

Now / Prepare / Library / History, with printer context and settings in the header.

Advantages: gives phone preparation a clear home, supports the project/part direction, and makes workflow transitions explicit.

Tradeoff: Printer and Settings lose bottom-bar positions. The selected-printer control must reliably expose machine controls, connection details, and switching. Test this without coaching the user.

Do not add a bottom tab for every major feature. Material inventory, calibration, and automation should have strong contextual routes and feature-search coverage even when they are not top-level destinations.

## Implementation order

1. Keep the five tabs. Reorganize the live-print action hierarchy and camera entry points.
2. Add an explicit printer selector/capability detail, plus scoped command feedback and stale-state behavior.
3. Restructure Files locations and Settings groups using existing screens and actions.
4. Consolidate print-record entry and provenance. Move storage management out of History.
5. Improve project row/action density.
6. Introduce persistent preparation drafts and the common review flow.
7. Compare task-led navigation against the refined structure after the content improvements work.

Avoid a framework rewrite as the price of UI cleanup. Separate state presentation and navigation ownership from the growing activity where useful; keep business policy shared with the service rather than recreating it in click handlers.

## Validation and task trials

Use real tasks with no coaching:

- Find the camera, pause the current print, and identify whether the pause is requested or confirmed.
- Find an emergency stop while an ordinary command is pending.
- Explain what can be done through the current cloud connection.
- Prepare a model while offline, then resume with a fresh selected target.
- Find a model on the phone versus similarly named G-code on printer storage.
- Find the charts for a specific print and identify whether its quality has been reviewed.
- Change picture frequency without opening unrelated automation settings.
- Add a planned project part without attaching a file.
- Switch printers and confirm that old status, command results, and physical confirmations do not follow the new target.

Record wrong first taps, backtracking, uncertainty, completion, and the user's explanation of the current state. Compare both navigation concepts using the same tasks. No quantitative improvement is claimed before those trials.

Test the final Android implementation with large font, TalkBack, dark/light themes, rotation, process recreation, native back gestures, and real connection transitions. Prototype web controls do not establish native accessibility or firmware behavior.

## Prototype limits

The study uses illustrative camera geometry, estimates, and recordings. It does not connect to a printer, run the slicer, send integrations, or load private user data. Primary navigation, preparation, simulated acknowledgments, preflight gating, inspection of records, and selected settings are interactive. Secondary advanced editors mainly demonstrate where the capability belongs.

Browser/device layout QA is unavailable in the execution environment. Executable logic and structural checks are reported separately from a physical Android/device review.
