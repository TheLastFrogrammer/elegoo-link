# Main app UI study: validation

Source baseline: ad7670c70cb4e8708b8a6a7977dbd093b0f07bba, app v0.63.0 / build 154.

## Executed

- JavaScript syntax validation for the main study script.
- 134 executable interaction/state checks using the actual JavaScript handlers in a minimal DOM harness.
- Two layouts and six scenario states: local printing, local paused, local ready, cloud monitoring only, disconnected, and late status.
- 29 captured markup states; 489 controls checked for explicit button types and accessible names or wrapping field labels.
- Local stylesheet/script existence and study navigation structure.
- Standalone export with embedded application CSS/JavaScript.
- Shareable ZIP content and CRC integrity.

Representative checks cover:

- A pause remains requested until acknowledgment is simulated.
- Emergency stop remains available while an ordinary command is pending, and the older response cannot overwrite the newer result.
- Cloud monitoring does not imply upload or control capability.
- Offline preparation retains its model/settings when the printer becomes ready.
- Preflight blocks a busy/offline target and requires confirmation again after target changes.
- Printer switching discards pending results from the previous target.
- Heater acknowledgment applies the captured requested values, not subsequently edited input.
- History differentiates rated quality, printer completion, and missing recordings.
- Removing a retained G-code artifact blocks a later repeat rather than silently selecting another file.
- Planned parts can be added without models.
- Layouts preserve independent local demo state.

## Not executed

- Browser rendering, screenshot comparison, and pixel/layout measurement.
- Physical Android device or emulator testing.
- TalkBack or native accessibility verification.
- Native slicer execution or printer/firmware/account operations.

The DOM harness checks the prototype's behavior and generated markup. It does not establish browser layout quality, native Android behavior, timing characteristics, or printer safety.
