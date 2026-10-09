# Field test: slicing, Live toolpath and CANVAS tray plans

These parts work in tests and emulation but have not run on a real phone and printer yet. The app records what it
sees in a diagnostics log (no access codes, PINs, addresses or serial numbers). After testing, open
**Settings → Share diagnostics…** and send the report.

Start with an empty log: Settings → Share diagnostics… → **Clear log**.

## 1. Slicing on the phone

1. Files → **Slice a model…** → choose a model (a Benchy or a calibration cube is fine).
2. Slice it with the default presets. Note roughly how long it takes.
3. Slice something bigger or several models on one plate, to see time and memory on larger jobs.
4. Rotate the phone while it slices: progress should continue, and the result should appear.

The log records preset loading time, each slice's time, inputs and peak memory, and any failure.

## 2. Open with Link Workshop

1. In a file manager, a browser download or a chat app, tap an `.stl` or `.3mf` file, or share it, and choose
   **Link Workshop** (it may appear as "Slice a model").
2. The Slice screen opens with the model loaded. Slice it and tap **Send to Files tab for upload**: the Files tab
   should open with the G-code selected.

If the app is not offered for a file, note the app you opened it from and the file name.

## 3. Live toolpath

1. Print a file that the phone sliced, uploaded or downloaded (the viewer needs a phone copy).
2. Monitor → **Live toolpath** while it prints. Watch for a few layers, ideally including the first ones.
3. Note whether the highlighted move is where the nozzle actually is, and whether the shown layer matches.

The log records, once per layer, the printer's layer number and total, the file's Z heights for that layer
number and the one before it, the nozzle position the printer reports, and where the viewer put the nozzle.
That shows whether the printer counts layers from 1 (as assumed) and whether its position is in the G-code's
coordinates.

## 4. CANVAS tray plan

1. With the CANVAS loaded and the printer connected, open the Slice screen and tap **Fill from CANVAS trays**.
   Check that each slot got the right material and colour.
2. Slice with two colours, upload, then open the file's **Print setup** on the Files tab.
3. Check that the tool count and trays are prefilled, then start the print and see that each tool prints from the
   tray chosen for it.

The log records the stored plan, the trays the printer reported, how many were prefilled, and the mapping sent at
print start.

## 5. Plate view, settings and 3MF projects

1. Slice screen → **Edit plate…**: drag a model, turn it, scale it, copy it, then **Done** and slice. Check the
   printed layout matches the plate view.
2. **All settings…**: change a few settings (for example wall loops and infill pattern), slice, and check the
   G-code preview.
3. Open a 3MF project saved with several plates in ElegooSlicer: pick plate 2, slice, and compare with what the
   desktop slices for that plate.

## 6. Big slices

Slice something large (a big model, many copies or a fine layer height). If the app warns about memory, note the
estimate it shows. The log records the estimate next to the real peak memory of each slice, which is what the
estimate needs to be tuned on a phone; if Android closes the app mid-slice, the next start says so and logs it.

## 7. Timelapse videos

1. Start a print with **Record timelapse on printer** on, and let it finish.
2. Files → Storage & print history → **Refresh print history**. The print should say the timelapse video is ready
   (it can take a minute after the print while the printer makes it).
3. Tap **Download timelapse**, then **Save timelapse…**, and play the saved video.

If the download fails, the error and the history entry help: share the diagnostics and a screenshot of the history.

## 8. Downloads and calibration

1. Files → Printer files → tap a file → **Download and save to phone…**: the save picker should open when the download
   is in. If it fails, the diagnostics now include the printer's HTTP answer for each download.
2. Slice screen → **Calibration print…** → Temperature tower; slice, **Upload and print…**, and check that the
   temperature drops by 5 °C per 10 mm block. Enter the best temperature in slot 1's **Filament settings…** and slice
   something with it.

## 9. Upload through the Elegoo cloud

1. Disconnect the local connection, keep the printer in cloud mode and watch it through the cloud (Printer tab shows
   **Cloud**). Turn on cloud controls if asked.
2. Slice something small → **Upload and print…**. Files shows "Uploading … through the Elegoo cloud" up to 50 %
   while the phone stores the file, then "(printer fetching)" to 100 %. Print setup should open once the file is listed.
3. If it stops, Share diagnostics: the Printer file downloads section notes the cloud upload start and how it ended.

## 10. Per-model settings and the new calibrations

1. Load two models → **Edit plate…** → select one → **Model settings…** → set Wall loops 5 and infill 50 %. Slice and
   check in the toolpath viewer that only that model has the extra walls.
2. **Calibration print…** → Pressure advance pattern; print it and check that the numbers above the corners read
   from left to right as the dialog said.
3. **Calibration print…** → Input shaping frequency. Watch the printer's screen or console for an error about
   `SET_INPUT_SHAPER`: the app cannot tell whether the CC2 firmware accepts it. Report what happens.

## 11. Layout pass (v0.12.1)

1. Print setup: "Run printer / bed check" should start ticked. The test renderer draws it unticked; confirm it on the phone.
2. In cloud mode before agreeing to cloud control, the Printer tab should show only the explanation, "Turn on cloud control…" and Refresh status.
3. Slice a 3MF with all plates, then use the result's plate chooser: Preview and Save should follow the chosen plate.

## 12. Print again and history details (v0.13.0)

1. Files → Storage & print history → Refresh history. Tap an entry: check that the details match Elegoo's app
   (times, duration). Share diagnostics afterwards: it lists the field names the printer's history detail (1037)
   returned, so filament per tray, build plate and nozzle can be labelled properly.
2. Print again on a file still on the printer: Print setup should open with its usual checks; nothing starts without
   confirming. On a file you deleted, the button should say the file is no longer on the printer.
3. Open Live toolpath for a print started from the official app: it should show progress and the thumbnail, not an error.

## 13. Read-only printer probe (v0.13.1)

1. Settings → Link Workshop → **Probe printer (read-only)…** → Probe. Try it once connected locally if you can, and once
   watching through the cloud on the same Wi-Fi.
2. Check: it finishes within about a minute (local) or two (cloud); the printer does nothing visible; the list shows each
   method as answered, refused with an error code, skipped or no reply.
3. Share diagnostics… and send the **Printer probe** section. Especially wanted: the port line (is 80 refused while 9001 or
   others are open?), the HTTP line for port 80, and whether 1037, 1046 and 1051 answer.

## 14. The printer's camera in Live toolpath (v0.14.0)

1. Start a print and open Live toolpath. More… → **Show the printer's camera in the view**.
2. Check: a dark camera shape with a faint cone appears above the bed, with a screen in front of it playing the camera.
   Connected locally, the picture is the printer's own stream (port 8080); watching through the cloud, it is Elegoo's
   cloud video (needs cloud control turned on once, as for Monitor → Camera).
3. More… → **Camera position…**: pick the spot that matches where the camera really is on your CC2, then More… →
   **Look from the camera**: the picture fills the view and the planned toolpath is drawn over it. Tell us which spot
   lines up best, and whether the print in the picture sits where the toolpath is (field of view and position are
   estimates for now).
4. Leave the screen and come back: the camera should stop while away and restart on return.
5. (v0.14.1) More… → **Line up the camera by hand…**: the view looks from the camera with the yellow bed outline over the
   picture. Move the sliders until the outline sits on the bed; use Lens curve when the bed's edges bow outwards. Save,
   then Share diagnostics: the saved numbers are in the Live toolpath section, so the best values can become the default.
