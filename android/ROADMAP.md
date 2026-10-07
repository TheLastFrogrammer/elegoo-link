# Link Workshop roadmap

State as of v0.11.0. "Done" means in the app and covered by automated tests where possible; almost nothing has
been checked on a real Centauri Carbon 2 yet, so every area also lists what still needs hardware. The field-test
checklist is [FIELD_TEST.md](FIELD_TEST.md); Settings → Share diagnostics records what the app saw.

## By area

| Area | Done | Still to do |
| --- | --- | --- |
| Connection | LAN (MQTT + HTTP) with discovery, saved printers, encrypted access codes, reconnect and connection diagnosis; home-VPN route; Elegoo cloud sign-in for monitoring and control without LAN Only; read-only PIN probe | First successful LAN registration on the user's CC2; Matrix coexistence proof (see [MATRIX_COEXISTENCE.md](MATRIX_COEXISTENCE.md)) |
| Monitor | Progress ring, temperatures, faults, pause/resume/stop, light, printer settings (temperatures, fans, speed modes), maintenance (filament, CANVAS trays, homing, axis moves, leveling, vibration test, self-check, emergency stop), CANVAS trays with colours and the active tray, alerts and background watching | Hardware check of maintenance commands |
| Files | Printer files (internal/USB, including folders) with start, delete, download and download-and-save; upload; offline G-code inspection with embedded previews and material evidence; print setup with plate, checks, timelapse and per-tool CANVAS tray mapping (prefilled from the phone's slicer) | — |
| Print history | History with durations; timelapse status per print; download and save of finished timelapse videos (LAN) | Check the timelapse download on hardware: the app follows Elegoo's own printer page, untested here |
| Camera | Local MJPEG stream with snapshots; cloud camera | Stream variations across firmware versions |
| Toolpath viewer | 3D G-code viewer with layers, moves, playback, features and travel; Live toolpath following the running print | Confirm the printer's layer numbering and nozzle position frame (logged in diagnostics) |
| Slicer | ElegooSlicer's own engine on the phone (identical G-code to the desktop on tested models); Elegoo presets; multi-filament with CANVAS trays, colours, flushing and prime tower; preview thumbnail; plate view (move, turn, scale, copy, remove, arrange); full print settings editor with saved sets; 3MF projects with plate choice, all-plates slicing and project settings; lay on face; per-slot filament settings; calibration prints (temperature, flow, pressure advance, max volumetric speed, retraction); upload and print straight from the result; memory estimate before big slices; "Open with" from other apps | Speed and memory on a real phone; painting/supports/seam painting tools; per-object settings; printer preset editing |
| Recordings | Graphs of each watched print (progress, layers, temperatures, fans) | — |

## Next

1. Hardware round: work through FIELD_TEST.md on the phone and CC2 and fix what the diagnostics show.
2. Slicer: supports and seam painting on models, and modifiers (per-object settings) in the plate view.
3. Pressure advance line and pattern tests (G-code generated directly rather than a sliced model), input shaping tests.
4. Other printer models (the Centauri Carbon 1 uses a different protocol).

## Rules that stay

No command that changes the printer is ever repeated automatically. An acknowledgement is not treated as the
resulting state. Starting, deleting and temperature changes need a fresh status; tray mappings need fresh CANVAS
data with a loaded tray.
