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
