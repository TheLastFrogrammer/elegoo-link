# Link Workshop roadmap

State as of v0.86.0. "Done" means in the app and covered by automated tests where possible. Little has been confirmed
on a real Centauri Carbon 2 yet, so the app now measures that itself: Settings › Field check gathers the manual checklist
([FIELD_TEST.md](FIELD_TEST.md)), the checks measured during prints, and the self-test print into one report to share
(no codes, PINs, addresses or serials).

## By area

| Area | Done | Still to confirm on the printer, or to do |
| --- | --- | --- |
| Connection | LAN (MQTT + HTTP) with discovery, saved printers, encrypted access codes, reconnect and diagnosis; home-VPN route; Elegoo cloud sign-in for monitoring, control and uploads; read-only PIN probe; following the printer's mode | First LAN registration on the user's CC2; first cloud upload; Matrix coexistence ([MATRIX_COEXISTENCE.md](MATRIX_COEXISTENCE.md)) |
| Print tab | Camera or live toolpath on top, job card, temperatures and trends, faults, pause/resume/stop, light, printer settings, maintenance, CANVAS strip, start steps page that follows the printer's own status, alerts, widget and live progress notification | Maintenance commands on hardware |
| Files | Printer files (internal/USB) with start, delete, download; upload locally or through the cloud; G-code inspection; print setup with per-tool tray mapping; refused starts explained | Files tab blank on one phone |
| History and recordings | History with durations and statistics; recordings with charts, trays, material, journal; camera pictures by time, by layers, with the head far from the camera (from the G-code), or parked (smooth timelapse); a second far set to compare; timelapse, far and side-by-side videos, made by themselves when a print ends, with the end held and flicker evened out | Videos made on the user's phone (MediaCodec) |
| Live toolpath | 3D G-code viewer; Live toolpath following the print over the camera picture, with line-up, lens curve, plate margins, printhead mask and delay | Camera line-up and delay on hardware (manual checks) |
| Slicer | ElegooSlicer's engine on the phone; presets; multi-filament with CANVAS trays; plate view; full settings editor; 3MF projects; per-object settings; support blockers and enforcers; calibration prints; smooth timelapse (park at a chosen corner or the one farthest from the camera, with a comparison set); the self-test print; upload and print from the result; the reason Slice is off, with a tap to fix it | Speed and memory on a real phone; colour, support and seam painting |
| Measured checks | Status rate; parking, lift and return; layer numbers; head position on the layer; the self-test's position probe (exact, shifted or mirrored); picture timing; heaters; filament use; slicer estimate; printer's time left; faults. Kept with each recording, in Diagnostics at the end, and in Field check | Their first answers, from the self-test print |
| Automations and links | Rules with notify, light (once, off by default) and webhooks; Home Assistant (read only); print guardian (notify only); projects; backup and restore | — |

## Next

In order. Each round ends with tests, a build, and the self-test print when there is hardware time.

1. **Answers from the printer.** Run the self-test print once; read Field check's measured section. Fix what it shows
   (position frame, status rate, parking), then turn its numbers into defaults (camera delay, printhead mask).
2. **Safer printing.** Failure detection from the pictures on the phone (spaghetti, nothing on the bed), notify only;
   a clearer "is it still printing" in cloud mode, where status comes about every 30 s.
3. **Timelapse.** Frame alignment (small camera shifts); a camera timing marker in the self-test (the head at a known
   place at a known moment) to measure the camera's delay without guessing.
4. **Slicer.** Colour painting for multi-colour prints, then support and seam painting; measure slicing on the phone.
5. **Engineering.** UI text into string resources (translations, wording without code changes); a first-run guide;
   a signed release build.
6. **Pinned: a helper on the printer's USB port** (a board posing as a USB drive, filled over Wi-Fi), so G-code stays
   readable in cloud mode. First a free test with a plain USB stick.
7. Other printer models (the Centauri Carbon 1 uses a different protocol).

## Rules that stay

No command that changes the printer is ever repeated automatically. An acknowledgement is not treated as the
resulting state. Starting, deleting and temperature changes need a fresh status; tray mappings need fresh CANVAS
data with a loaded tray. Measured checks only read what the printer reports. Slices carry standard G-code only.
