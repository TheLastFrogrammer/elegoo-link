# Link Workshop roadmap

State as of v0.12.0. "Done" means in the app and covered by automated tests where possible; almost nothing has
been checked on a real Centauri Carbon 2 yet, so every area also lists what still needs hardware. The field-test
checklist is [FIELD_TEST.md](FIELD_TEST.md); Settings → Share diagnostics records what the app saw.

## By area

| Area | Done | Still to do |
| --- | --- | --- |
| Connection | LAN (MQTT + HTTP) with discovery, saved printers, encrypted access codes, reconnect and connection diagnosis; home-VPN route; Elegoo cloud sign-in for monitoring, control and uploads (the printer fetches the file from Elegoo's storage) without LAN Only; read-only PIN probe | First successful LAN registration on the user's CC2; first cloud upload on hardware; Matrix coexistence proof (see [MATRIX_COEXISTENCE.md](MATRIX_COEXISTENCE.md)) |
| Monitor | Live camera on top while printing, job card with the file's preview, large percentage, layer and time left; temperatures with heating/cooling trend, faults, pause/resume/stop, light, printer settings (temperatures, fans, speed modes), maintenance (filament, CANVAS trays, homing, axis moves, leveling, vibration test, self-check, emergency stop), CANVAS colour strip with the active tray, alerts and background watching; switching between saved printers from the header | Hardware check of maintenance commands |
| Files | Printer files (internal/USB, including folders) with start, delete, download and download-and-save; upload (locally or through the cloud); offline G-code inspection with embedded previews and material evidence; print setup with plate, checks, timelapse and per-tool CANVAS tray mapping (suggested by material and colour, always overridable, refreshed while the dialog is open) | Why the Files tab can show blank on one phone (layout diagnostics added in 0.26.1) |
| Print history | History with durations; timelapse status per print; download and save of finished timelapse videos (LAN) | Check the timelapse download on hardware: the app follows Elegoo's own printer page, untested here |
| Camera | Local MJPEG stream with snapshots; cloud camera | Stream variations across firmware versions |
| Toolpath viewer | 3D G-code viewer with layers, moves, playback, features and travel; Live toolpath following the running print, over the camera picture with a line-up from taps, lens curve, plate margins, printhead mask and delay | Confirm the printer's layer numbering and nozzle position frame (logged in diagnostics) |
| Slicer | ElegooSlicer's own engine on the phone (identical G-code to the desktop on tested models); Elegoo presets; multi-filament with CANVAS trays, colours, flushing and prime tower; preview thumbnail; plate view (move, turn, scale evenly or per axis, fit to the bed, copy or several copies, remove, arrange; oversized models can be scaled down there); models added over several picks; full print settings editor with saved sets; 3MF projects with plate choice, all-plates slicing and project settings; lay on face; per-slot filament settings; settings for one model (per-object); calibration prints (temperature, flow, pressure advance tower, lines and pattern, max volumetric speed, retraction, input shaping frequency and damping); upload and print straight from the result; memory estimate before big slices; "Open with" from other apps | Speed and memory on a real phone; painting/supports/seam painting tools; modifier volumes (settings for part of a model); printer preset editing; whether the CC2 accepts SET_INPUT_SHAPER |
| Recordings | Graphs of each watched print (progress, layers, temperatures, fans) | — |

## Next

Plans, in order. Each round ends with tests, a build and a field check on the phone.

1. **Field check (closes the loop with the hardware).** An in-app "Field check" page that walks through FIELD_TEST.md on the phone and
   CC2, records pass/fail and notes, and gathers the matching diagnostics lines into one report to share (no codes, PINs, addresses
   or serials, as now). Open items it covers first: the Files tab shown blank, plate margins for the camera line-up, printhead mask
   and fan sizes, the camera delay, tray refresh through the cloud, maintenance commands, timelapse download, first LAN connection.
2. **Printing workflow.** Print setup shows the trays as the Monitor's colour strip and remembers the last tray choice per file
   (prefilled, still confirmed); Print again from history opens Print setup prefilled; a progress notification with the file's
   preview and time left (its buttons only open the app).
3. **Layout and slicer.** A drag handle to scale in the 3D view; support blockers and enforcers as boxes; modifier boxes (settings
   for part of a model); then support and seam painting. Measure slicing speed and memory on the phone.
4. **Live toolpath.** Line up from a printed test shape (tap its corners) to set the plate margins without guessing; keep a line-up
   per saved printer.
5. **Engineering.** Split MainActivity (over 2,000 lines) into one controller per tab, behind the existing tests; move the UI text
   into string resources (translations, and wording changes without touching code). Done: the slicer engine is pinned in
   android/engine/ (slicer/scripts/pin-engine.sh), so CI and release builds ship it; CI runs on every branch push and fails when
   the APK lacks the engine or the pin is older than the engine source; crashes leave a secret-free record in the diagnostics.
6. **Pinned: a helper on the printer's USB port.** Answered: stock cloud mode never opens port 80 (PROTOCOL.md). Next idea,
   no firmware change: a board posing as a USB drive (Pi 4 or Zero W via USB-C/OTG gadget mode with separate power, or an
   ESP32-S3 with microSD) that the app fills over Wi-Fi; prints are started through the cloud from "USB drive", so their
   G-code stays readable in cloud mode, and the board can see which file and part of it the printer reads. First a free
   test: a plain USB stick, then in cloud mode Files → USB drive and start a print from it. The printer, as USB host, uses
   only storage, so the port is not a way to query it.
7. Other printer models (the Centauri Carbon 1 uses a different protocol).

## Rules that stay

No command that changes the printer is ever repeated automatically. An acknowledgement is not treated as the
resulting state. Starting, deleting and temperature changes need a fresh status; tray mappings need fresh CANVAS
data with a loaded tray.
