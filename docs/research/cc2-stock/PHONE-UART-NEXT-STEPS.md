# Next owner-access steps with the phone

Current checkpoint: 17 October 2026. The owner has Termux/Python, limited convenient laptop access and no USB UART adapter. Current screenshots establish TCP 22/80 refusal and TCP 1883/8080 reachability, without a protocol handshake. Exact current firmware and network mode remain unrecorded. The [field record](access/evidence/OWNER-NETWORK-OBSERVATIONS.json) and prior app tests are the starting point; do not repeat PIN/port/camera experiments as a new access lead.

## 1. Prepare without opening or buying anything

- When convenient, record the **full version string** shown on the printer. "Most recent" does not identify the image for an ABI/configuration comparison.
- Retain the existing stock firmware and Matrix/cloud binding. Do not downgrade, install patched firmware, or change LAN/WAN mode to bootstrap a shell.
- Review the [CC2-specific board picture](https://docs.opencentauri.cc/hardware/CC2/mainboard/) and [access analysis](OWNER-ACCESS-ROUTES.md). UART is the strongest remaining lead, with physical mapping and actual shell privilege still unverified.
- Software preparation now includes the [native first-contact probe](../../../tools/research/NATIVE-FIRST-PROBE.md). It needs no printer-side Python but still needs a shell and a qualified way to copy one executable to a temporary location. Preparing it does not create access from the phone.

## 2. Qualify a minimal phone kit

| Item | Requirement and reason |
|---|---|
| USB-to-TTL UART adapter | Documented TX/RX logic levels matched to the actual header; the chipset name or VCC jumper alone does not establish I/O voltage |
| Phone USB connection | A direct USB-C serial adapter, or a USB-C host/OTG adapter for a USB-A serial adapter |
| Jumper leads | Individually insulated connections appropriate to the verified header; do not assume pin orientation from text |
| Voltage verification | Board/signal documentation or suitable measurement; a multimeter reading during UART activity can be an average rather than the logic-high voltage |
| Android serial terminal | A USB-host serial application supporting the actual adapter, such as the author's [Serial USB Terminal](https://github.com/kai-morich/SimpleUsbTerminal#motivation) |

An adapter with a 5-V supply pin is not necessarily a 5-V logic adapter. A common CP2102/FT232/CH340 name is not enough to select an exact board. Match both the adapter's transmitted voltage and the printer receiver's tolerance before enabling transmit. Avoid PC RS-232 voltage-level adapters. The phone gets USB-device access through Android's permission API; a Termux process should not be assumed to see `/dev/ttyUSB0`.

A plain USB cable is not a qualified mainboard shell path. The printer's toolhead USB-C connection is documented as carrying **24 V** and must not be connected to the phone or laptop.

A USB keyboard is not a qualified shortcut either. Although init includes a tty1 shell, the pinned kernel's embedded configuration disables `CONFIG_HID`, `CONFIG_USB_HID` and `CONFIG_FRAMEBUFFER_CONSOLE`. Gadget-side `CONFIG_USB_CONFIGFS_F_HID` being enabled does not make the printer a USB keyboard host. See the [configuration fingerprint and selected options](access/evidence/UART-DT-FOLLOWUP.json).

## 3. Make one receive-only boot observation

Before pin-by-pin wiring, obtain a clear picture showing the actual mainboard revision, the header and its surrounding orientation. The reference identifies EF-M5-V1.0.073 and a UART0/DSP 2x4 header; that does not establish the owner's revision or the physical mapping to Linux `ttyS2`.

A deeper [embedded-device-tree check](access/evidence/UART-DT-FOLLOWUP.json) reinforces that distinction. One stock U-Boot-embedded tree names `console=ttyS2`, enables hardware UART0 on PF2/PF4, and marks hardware UART2 disabled. Its PF/PFO supplies reference both 1.8-V and 3.3-V fixed regulators. This is not the live boot-patched device tree or an electrical measurement: neither the console naming discrepancy nor the actual header signal voltage is resolved. Do not pick a 3.3-V-only adapter from the UART label or change boot arguments to force the names to agree.

Power off/unplug before opening or attaching leads; keep clear of the PSU/mains area. After mapping/levels are verified, secure only printer GND to adapter GND and printer TX to adapter RX. Leave adapter VCC and printer RX disconnected. Use the printer's normal power for the normal boot after leads are secured.

Start capture in the Android terminal. **115200, 8 data bits, no parity, 1 stop bit, no flow control** is a firmware-supported initial candidate, not a measured configuration. Save normal boot output privately. Do not press keys during the bootloader countdown or change boot arguments/environment. Garbled/no output means stop and resolve the wiring, voltage or terminal mapping; it is not evidence of an authentication failure.

## 4. Confirm the Linux shell only after the prompt is observed

The pinned stock init configures `askfirst` to run `/bin/ash --login`; its actual executable waits for LF then executes that command without a password check in that function. This suggests a direct shell on a matching console, but does not establish the live header or UID.

Only after voltage/mapping are qualified and the Linux `Please press Enter to activate this console.` prompt is observed should printer RX be connected to adapter TX, following the same safe power-off attachment procedure. Wait through normal boot again and press Enter at the Linux activation prompt. Do not send keys to the bootloader.

First read-only checks:

```sh
id
uname -a
test -S /tmp/elegoo_uds && echo 'stock console socket present'
command -v debug_window
command -v sha256sum
```

Record the actual UID, prompt, terminal evidence and installed firmware. If `sha256sum` is available, record the fingerprint of `/opt/bin/elegoo_printer` for comparison with the inspected image. Do not read/share password files or account configuration to establish root. A LAN access code is not an established Linux password.

## 5. Run the prepared inventory experiment, then file/log work

After temporary executable transfer/startup is separately qualified, run the native probe once on the idle printer. Copy its private capture to the phone and inspect it offline using the packaged Python scripts. The [probe guide](../../../tools/research/NATIVE-FIRST-PROBE.md) specifies exact commands, limits and the distinction between a finished window and a verified response.

Record normal WAN status before and after the run in the [owner worksheet](OWNER-CAPTURE-WORKSHEET.md). A matching inventory reply is the next meaningful deliverable. Help/output subscriptions, consistent records/log export and recovery of one known cloud G-code file follow as separate experiments. Active-print file reading, an authenticated companion transport and persistence remain later qualification stages. Keep camera maintenance separate from mainboard shell access.

Sources: [CC2 board reference](https://docs.opencentauri.cc/hardware/CC2/mainboard/), [Android USB host API](https://developer.android.com/develop/connectivity/usb/host), [serial-terminal author/source](https://github.com/kai-morich/SimpleUsbTerminal), and the stock-image [startup/binary evidence](access/evidence/OWNER-ACCESS-BOOTSTRAP.json). No physical wiring or printer command was performed during this preparation.
