# Link Workshop roadmap

State as of v0.104.0. "Done" means in the app and covered by automated tests where possible. Little has been confirmed
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
| Other apps | Read-only local API on the home network ([INTEGRATIONS.md](INTEGRATIONS.md)): status (`linkworkshop.status/1`), Server-Sent Events, print events, the printer's and added cameras as snapshots and MJPEG through one shared connection, pairing approved on the phone with a matching code, hashed tokens, tickets, DNS-SD discovery, a TV dashboard page; signed pushes to the person's own relay (ready Cloudflare Worker) for a Reddit app; Python and Android examples; a native Fire TV / Android TV app (android/tv: discovery, pairing, full-screen camera with status, D-pad); answers only on Wi-Fi, Ethernet or hotspot | A TV and the relay with the real printer; the TV app ([tv/README.md](tv/README.md)) on a real Fire TV; HTTPS on the home network |

## Where it is heading

The work has grown into three parts that feed each other:

- **Observe**: recordings of every print (status about every 10 s locally, 30 s through the cloud; camera pictures by
  time, layer or head position, with where the head was; warm-up phases; the kept G-code; the guardian's checks).
- **Understand**: charts with zoom and picture previews; per-layer analysis against the G-code (time, filament, line
  types, flow, acceleration); "Things to look at"; warm-up against earlier prints; compare two prints; the 3D head view;
  the G-code line at each picture; shareable reports; cost and maintenance.
- **Act**: the slicer on the phone with presets, calibration prints and the self-test; sending and starting prints.

What the suite lacks is the loop between them. A diagnosis today ends in words ("less fan, a little more heat"); the
person then changes settings elsewhere and has to remember to compare. The next rounds close that loop, in order:

1. **The tuning loop** (first version in v0.101.0). Findings name the settings that address them, starting from the
   values in the print's own G-code (less part fan and +5 °C for a long-cooled layer; a lower flow limit and +5 °C for a
   nozzle that could not hold its temperature; stalls and a cooling bed say no slicer setting fixes them). Any of fan,
   nozzle temperature, minimum layer time, top layers, flow ratio and flow limit can also be set by hand. "Slice again
   with these changes" opens the slicer with what the file was sliced from (kept with each slice, models included) and
   the changes on top of the presets, which stay as they are; nothing is sliced until Slice is tapped. The next
   recording of that file shows what changed, whether each finding came back, and a button to compare with the print
   before; the result is kept with the try. Left: the compare screen does not open by itself, and files sliced before
   v0.101.0 or elsewhere need the model chosen again.
2. **Memory per file and per spool** (first version in v0.102.0). "Every print of this file…" on a recording lists its
   prints newest first: rating, the spool and its drying, the settings changed since the print before (from each
   print's kept G-code), what the readings found and how a tuning try went. "What worked" names the best-rated finished
   print and its settings, and slices again with them when the newest print differs. Spools take a dried date (and a new
   full spool its opened date); the spools in the trays a print used are noted on its recording when it ends. When the
   journal names stringing, a rough surface or under-extrusion, the recording and the file page say how long it had been
   since the spool was dried. Left: prints recorded before v0.102.0 have no spool noted; the file page is not yet
   reachable from the Files tab.
3. **Answers from the printer.** Still the largest risk: the self-test print and Field check confirm the position frame,
   the status rate, parking, the camera delay and the toolpath overlay's line-up on real hardware.
4. **A calmer recording page** (done in v0.104.0). Six parts in story order (At a glance, What happened, Pictures and
   video, Charts, Layers and G-code, Tools) that open and close, remembered; the heavy parts are built only when opened.
   A file printed more than once links to all its prints from the list.
5. **The guardian learns from answers** (done in v0.104.0). Per camera and line-up, from "looks fine" / "real problem"
   answers: just enough more to keep the fine ones quiet, at most 3×, never enough to hide one called real; replayed on
   recorded prints and shown in the guardian's settings, with "Forget what it learned".
6. **Engineering.** UI text into string resources; a first-run guide; crash notes kept on the phone for Diagnostics.
   Done: signed releases that update over development builds (signing/lineage.bin), and an update check.
7. **Slicer.** Colour, support and seam painting; slicing speed on the phone.
8. Other printer models (the Centauri Carbon 1 uses a different protocol).
9. **Pinned: a helper on the printer's USB port** (a board posing as a USB drive, filled over Wi-Fi), so G-code stays
   readable in cloud mode. First a free test with a plain USB stick.

What the data cannot do, and so is not promised: the extruder position is what the printer was told to feed, not what
reached the nozzle; through the cloud, status comes about every 30 s; positions are the last reported before a picture.

## Rules that stay

No command that changes the printer is ever repeated automatically. An acknowledgement is not treated as the
resulting state. Starting, deleting and temperature changes need a fresh status; tray mappings need fresh CANVAS
data with a loaded tray. Measured checks only read what the printer reports. Slices carry standard G-code only.
