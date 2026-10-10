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

1. Hardware round: work through FIELD_TEST.md on the phone and CC2 and fix what the diagnostics show (Files tab blank, plate margins).
2. Layout: size in millimetres (type a width, depth or height), scale with a lock, and a handle to drag-scale.
3. Slicer: supports and seam painting on models; modifier volumes (a box or cylinder with its own settings inside a model).
4. Find out why the printer refused a cloud-mode download on port 80 (Elegoo's own cloud page uses the same request); check with the phone's browser and diagnostics.
5. Other printer models (the Centauri Carbon 1 uses a different protocol).

## Rules that stay

No command that changes the printer is ever repeated automatically. An acknowledgement is not treated as the
resulting state. Starting, deleting and temperature changes need a fresh status; tray mappings need fresh CANVAS
data with a loaded tray.
