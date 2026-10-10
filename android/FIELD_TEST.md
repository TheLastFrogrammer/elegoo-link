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
   picture. Move the sliders until the outline sits on the bed; raise Lens curve until the outline bends like the bed's edges. Save,
   then Share diagnostics: the saved numbers are in the Live toolpath section, so the best values can become the default.
6. (v0.14.5) With no print running: Camera tab → **Line up the camera in 3D…**. Line it up, then move the bed (or start a
   print) and check the outline stays on the bed as it moves down; the note above the sliders shows the bed's Z.
7. (v0.14.6) Height calibration: line up and Save with the bed near the top (Z ≈ 5), then move the bed down (Controls →
   Z, e.g. to Z 100 and Z 200), line up and Save at each. Share diagnostics: the "camera lined up by hand" lines list every
   saved height, which shows how the camera really follows the bed.
8. (v0.15.0) In the line-up, **Line up from taps…**: choose Back, tap 3–4 points along the far edge of the bed in the
   picture; then Left and Right (and Front if it shows), then **Fit**. The note gives the average miss as a share of the
   picture's height; under 1% is a good line-up. Save, and Share diagnostics (the fit is logged).
9. (v0.15.2) While marking, pinch to zoom into the picture and drag to pan (or Zoom in / Zoom out / Reset zoom); taps
   still mark, and the outline and dots should stay on the same spots of the picture at any zoom. Rotation lock (line-up
   panel; More… in the toolpath view) makes a drag pan instead of turning the view.
10. (v0.16.0) Joint fit: tap the edges and Save at bed Z ≈ 5, then again at Z ≈ 100 and Z ≈ 200 (Controls → Z). Each Fit
    uses all saved heights; the note lists the miss at each. Share diagnostics afterwards.
11. (v0.16.1) Line-up → "Move the bed": Home Z, then Lower 50 mm twice to reach about Z 100. The first tap warns (with
    "Don't ask again until I leave the line-up"); each tap sends exactly one move; the bed's Z above the sliders updates
    when the printer reports it. A move is refused while printing, unhomed, without fresh status or outside Z 0–250.

## 15. Getting to things faster (v0.18.1)

1. Monitor, idle: the top card offers Slice a model, Camera, Print again… (opens history on Files; it starts nothing) and
   Print recordings. While printing: Live toolpath, Camera and Print recordings.
2. Maintenance is folded away under **Show maintenance ▾**; Emergency stop stays visible. Find a feature → "level" (or
   "load filament") should open the section and point at the button.
3. Files tab → **Find models online…** opens the model search directly.
4. While printing, the ongoing notification has **Live toolpath** and **Camera** buttons; each only opens the app there.

## 16. Model sites in the app's own browser (v0.19.0)

1. Files → Find models online… → type a search → tap **Printables** (or MakerWorld, Cults3D…). The site opens inside the
   app with your search; the line at the top shows the site's address (🔒 = secure). The tabs switch site with the same search.
2. Sign in on a site as usual (email and password). Google/Apple sign-in may refuse to work inside an app: More… → **Open
   this page in the phone's browser** is the way round it.
3. Tap a model's download button: a bar shows the download, then "1 model ready". A ZIP is unpacked to its model files.
   Download a second model, then **Slice all 2**: both open in the slicer.
4. Tell us any download that does nothing or says "Not added" (and which site): Share diagnostics lists, under Model
   sites, how each download went (link or page data, file type), never the addresses.
5. More… → **Sign out of all sites** clears every site's sign-in in the app's browser.

## 17. Firefox engine and the ad blocker (v0.20.0)

1. The model-site browser now runs on Firefox's engine (GeckoView). Open a site as in section 16: pages, search, sign-in and
   the site's drop-down menus (sort order etc.) should all work.
2. More… → **Install the ad blocker (uBlock Origin)…** → Install. It downloads from Mozilla's add-on site; reload a site
   and ads should be gone. More… then shows "Ad blocker: on (tap to turn off)".
3. Downloads as in section 16; MakerWorld's download button should now work too (tell us if it does not).
4. Rotate the phone and leave/return to the app: the page should stay where it was.
5. The APK is much larger (about 70 MB) because Firefox's engine is inside it.

## 18. Choosing trays for a print (v0.21.0)

1. Slice a model on the phone with two filaments (e.g. PLA and PETG), then Upload and print. In Print setup each filament
   shows what it was sliced for (material and colour), and trays holding that material are suggested, closest colour first.
2. Each filament shows a verdict: ✓ matches, ! check (colour differs, a shared tray, fibre-filled), ✕ a different material.
   Choose a PETG file's filament from a PLA tray: the review says "Materials don't match" and offers Go back.
3. **Suggest trays** fills the trays again; **Clear trays** leaves every filament to the printer's own choice.
4. For a file on the printer that was not sliced on this phone: Share diagnostics after opening Print setup. If the printer
   sends filament details for the file ("color_map"), the Live tray plan section says so; tell us, so we can read them.

## 19. Model sites in Firefox (v0.22.0; replaces section 17)

The app no longer carries Firefox's engine (it is about 17 MB again). Instead:
1. Files → Find models online…: choose **In Firefox** (default when Firefox is installed) or **In this app**. Without
   Firefox, **Get Firefox…** opens its Play Store page.
2. In Firefox, the site opens with your search, your sign-ins and your add-ons (uBlock Origin). Download a model; when
   Firefox shows the download, tap **Open** and choose **Link Workshop** (Slice a model). The model opens in the slicer.
3. Download a ZIP (Thingiverse "Download all files", Printables): opening it in Link Workshop unpacks the model files
   inside and loads them all. Tell us if a site's download does not offer Link Workshop under Open.
4. **In this app** works as in section 16 (no ad blocker; downloads go straight to the slicer).

## 20. Smoother live toolpath and the printhead mask (v0.24.0)

1. Live toolpath during a print: between the printer's updates the line and the nozzle dot glide along the toolpath instead
   of jumping (about one update behind the printer). More… → Layers, transparency and nozzle dot… → untick "Smooth live
   movement" to compare.
2. Same dialog → tick "Printhead mask". From the camera, the toolpath behind the real printhead should be hidden by it; in
   the 3D view a faint box rides on the nozzle. Tell us if it hides too much or too little (its size is an estimate).
3. (v0.24.2) More… → Layers, transparency and nozzle dot… → **Printhead size…**: the mask shows tinted over the camera
   picture; size it to cover the real printhead (easiest between prints, with the head parked). **Defaults** resets it.
4. **Delay to match the camera**: if the real head in the picture trails the drawing, raise it until they move together.
