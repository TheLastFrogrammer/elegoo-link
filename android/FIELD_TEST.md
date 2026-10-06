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
