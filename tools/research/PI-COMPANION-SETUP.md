# CC2 Pi companion starter

Read-only expansion: [READONLY-STATUS-AND-FILES.md](READONLY-STATUS-AND-FILES.md) adds owner-qualified fixed status captures, bounded G-code ranges and explicit USB v2 profiles. The default setup below still launches identity-only export; it does not automatically run the new readers or enable live telemetry.

This is an installable **first transport setup**, built from the verified stock 02.01.01.07 investigation. It is not yet physically qualified on a CC2. The Pi can boot as a CDC-ACM USB device, receive private firmware facts and completed native-console captures, retain them across reconnects, and show status over your existing SSH connection. The optional printer service starts the small read-only exporter after a separately recorded qualification.

**Initial owner-shell access is still required. Plugging in USB does not unlock a shell.** The package does not automatically drive UART, apply tweaks, modify the stock image, switch LAN/WAN modes, recover G-code/media, or repair the camera. Those need subsequent device qualification. Neither the Pi installer nor the exporter contacts the printer over its network.

## What is included

| Component | Runs on | Function |
|---|---|---|
| `cc2_pi_install.py` | Pi | Default read-only plan; explicit installation, boot-config backup and removal |
| `cc2-companion-gadget.service` | Pi | Loads a serial gadget without taking over another bound USB controller |
| `cc2_pi_companion.py` / receiver service | Pi | Receives, validates, deduplicates and archives fixed bundles; no network listener |
| `cc2-usb-export-armv7` | Printer | Static native exporter; no shell/exec, console socket, remote file paths or write API |
| `uart-transfer/` | Owner terminal | Checksummed paced-text staging of the exporter and bundle-preparation script; never executes them |
| `printer-prepare.sh` | Printer | Creates a fresh identity-only bundle in `/tmp` |
| `printer-start.sh`, `link-companion.init` | Printer, optional | Separately installed procd hook; fingerprint, helper hash and USB route gates |

The ARMv7 executable is for the **printer**. The Pi runs Python 3.10+ with no pip dependencies, including on the original ARMv6 Pi Zero W. The setup targets current Raspberry Pi OS with `/boot/firmware/config.txt`; use a 32-bit OS for an original Zero W. Zero 2 W is also accepted by the installer. Other boards/boot layouts deliberately require a reviewed adaptation.

## Connections and first Pi boot

Use the Pi Zero's **micro-USB data port**, connected by a data cable to the printer's ordinary front USB host port. The other Pi micro-USB connector is power only. The printer's toolhead USB-C connection is a different interface carrying 24 V; do not use it for a Pi or phone. Supply current and backfeeding have not been qualified: use a verified single-source/isolated power arrangement rather than tying independent 5-V supplies together.

The stock image enables host CDC-ACM, so a correctly enumerated Pi is expected to appear as `/dev/ttyACM*` on the printer. The Pi side is `/dev/ttyGS0`. Enumeration, current draw, disconnect behavior and normal printing with WAN telemetry still need measurement.

UART remains the initial owner-access/recovery candidate. On a Zero, the sole USB data port cannot also host a USB UART dongle while it is a gadget. Direct GPIO UART is possible **only after the printer signal voltage/pinout is confirmed**; Pi signals are 3.3 V, while the printer header's level remains unresolved. Do not connect UART power pins. This installer leaves Pi UART, Bluetooth and its serial login configuration untouched. It never sends bytes to a bootloader or tries to infer a shell prompt.

One initial SD-card preparation is still needed. Use Raspberry Pi Imager to set up the OS, your Wi-Fi, a hostname and your own SSH account/key. Once the Pi is reachable on Wi-Fi, routine commands can come from Termux on your phone:

```sh
ssh YOUR_PI_USER@YOUR_PI_HOST
```

Use your configured username/hostname. No default credentials are assumed or included.

## Install on the Pi

The GitHub Actions **Research capture tools** run produces `cc2-companion-starter`. Download/extract that artifact on the Pi, keep it private, and enter its directory. It includes the executable, source, manifest, checksums, scripts and this guide. Start by checking:

```sh
sha256sum --check SHA256SUMS
python3 cc2_pi_install.py
```

The checksum list detects incomplete/corrupted downloads; trust the repository/Actions source, not a checksum delivered by an unrelated party. The default command only prints a plan. After reviewing it, run:

```sh
sudo python3 cc2_pi_install.py install --apply
sudo reboot
```

Installation appends a marked `[all]` / `dwc2` peripheral block to the Pi boot configuration, saves its previous content, copies managed files, and enables two services for next boot. It does not start them immediately or change Wi-Fi, SSH or UART. Existing managed paths, an explicit dwc2 configuration, loaded `g_serial`, and an active ttyGS0 login service are refused for review. The runtime gadget guard also refuses an already bound controller. Included boot-config files and third-party gadget managers still need owner review; this installer cannot prove arbitrary existing configurations are compatible.

After reboot, reconnect from the phone:

```sh
ls -l /dev/ttyGS0
sudo systemctl status cc2-companion-gadget cc2-companion --no-pager
sudo python3 -B /opt/cc2-companion/cc2_pi_companion.py status
```

`waiting_or_incomplete` is expected until the printer exporter is launched. `ttyGS0` proves Pi-side gadget creation only. It does not prove enumeration on the printer or owner access.

The receiver runs as root to open the root-owned gadget TTY and private state, with an empty capability set, no external network, a read-only system view and device access restricted to ttyGS0. It has a 64-MiB memory limit and 25% CPU quota. These settings need checking on the actual Pi OS. Its journal contains transport outcomes, not raw captures or boot IDs.

## First printer launch: temporary, identity only

Do this with the printer idle, normal WAN monitoring available, and an independently established **normal Linux owner root shell**. Save a private terminal log. The initial physical voltage/console qualification remains necessary; the package does not replace it.

1. On that shell, run the existing read-only owner worksheet from the repository and record actual firmware hash, UID, mounts and USB enumeration. Confirm which new `/dev/ttyACM*` is the Pi by comparing unplugged/attached sysfs device information. Do not assume ttyACM0 is always the paired Pi.
2. Stage `uart-transfer/stage-usb-export.sh` and `stage-printer-prepare.sh` as paced text through the established terminal. Require **both actual** `CC2_STAGE_VERIFIED` output lines and the hashes in `uart-transfer/transfer.json`. Echoed command text is not successful verification. The target directories are new, private `/tmp/cc2-owner-*` directories. A qualified USB storage copy is another option; USB storage insertion does not autorun these files.
3. Read the exact `target` and `prepare_script` paths in `transfer.json`. First run `TARGET --version`, substituting the target path, and require `cc2-usb-export-v2` for this updated package (the original package reported v1).
4. Run the following, substituting **PREPARE_SCRIPT**, **TARGET**, and the observed **DEVICE**:

```sh
/bin/sh PREPARE_SCRIPT /tmp/cc2-bundle-first
TARGET --device DEVICE --bundle /tmp/cc2-bundle-first
```

The first command creates a fresh identity file; the exporter stays in the foreground until Ctrl-C/disconnect. Do not reuse a populated bundle directory. There is no persistent printer change in this step. The Pi service should collect within its next 60-second poll. In a separate phone SSH session, inspect:

```sh
sudo python3 -B /opt/cc2-companion/cc2_pi_companion.py status
```

Require `transport_status: bundle_complete`, inspect the reported executable hash and boot ID against your owner-console observation, then stop the exporter with Ctrl-C. `matches_research_fingerprint` compares a **reported executable hash** with the inspected image; it is not cryptographic device authentication or proof of the entire installed firmware. Unknown firmware is retained as observation only. All operations in this starter are read-only exports.

Exercise unplug/replug and both boot orders before installing the printer hook. Check that the main printer, WAN status and camera remain healthy. The physical test checklist below defines acceptance; synthetic tests do not substitute for it.

## Add one completed native inventory capture

The separate native first-contact probe still makes exactly one fixed `list_endpoints` request to `/tmp/elegoo_uds`. Obtain its own `cc2-native-first-probe-armv7` artifact and follow `NATIVE-FIRST-PROBE.md` / `SERIAL-TRANSFER.md`. Do not repeatedly run it from a timer or boot hook.

After that probe exits, create a **new** identity bundle using the preparation script, and copy only its finalized `request.bin`, `summary.json` and `console.rx` into the new directory. Use owner-verified source/destination paths, keep files 0600 and the bundle 0700, and do not modify them during export. Launch the USB exporter on this new bundle. It requires all three capture files together, or none.

The Pi uses the existing strict native-capture inspector. `console_response_verified` means the completed capture contained a valid matching inventory response; it says nothing about current printer state, G-code identity, firmware authentication or a live toolpath. Repeated exports of the same bundle are deduplicated and explicitly labeled `capture_is_live: false`.

## Optional printer boot hook, after qualification

This is a separately reviewable **overlay change**, not an automatic part of Pi installation. It has not been tested on the physical printer. First confirm that `/opt/usr` is the expected persistent user-data filesystem and that the root overlay is persistent rather than a temporary fallback. Keep UART or another independently qualified recovery route.

Create a new owner-only `/opt/usr/cc2-companion` directory. Copy the **checksummed** exporter there as `usb-export`, plus `companion/printer-prepare.sh` and `companion/printer-start.sh`. Make the directory and these files root-owned mode 0700. Record the exact paired device path in a root-owned mode-0600 `device` file and the result of `readlink -f /sys/class/tty/OBSERVED_TTY/device` in `usb-device-path`. These files are data, never sourced as code. The route gate identifies a USB port/interface, not an authenticated peer.

Only after the temporary USB test succeeds, create root-owned mode-0600 `QUALIFIED` containing one line:

```text
CC2_COMPANION_QUALIFIED_V1 8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074 VERIFIED_EXPORTER_SHA256
```

Replace the final field with the artifact's verified exporter SHA-256. The launcher recomputes both executable hashes on every start; it refuses an unknown printer executable, changed helper, wrong device path or different sysfs route. Merely copying this guide's sample line cannot qualify a device.

Copy `companion/link-companion.init` to the new `/etc/init.d/link-companion`, chmod 0755, then explicitly run:

```sh
/etc/init.d/link-companion enable
/etc/init.d/link-companion start
```

The service prepares **identity only** on each start. It never automatically makes a console request, exports logs, changes networking, or repairs anything. It starts after the vendor S95 service, adds no boot wait, and uses at most five procd retries spaced 15 seconds apart. If the Pi arrives after retries are exhausted, restart this service manually. Index changes after replug fail the gate until the owner rechecks the paired route/device data; automatic discovery is intentionally not implemented.

Disable/remove the boot hook from the owner shell:

```sh
/etc/init.d/link-companion stop
/etc/init.d/link-companion disable
/bin/rm /etc/init.d/link-companion
```

Retained files under `/opt/usr/cc2-companion` do not execute by themselves. The stock updater explicitly schedules `rootfs_data` cleanup, so the overlay service/rc link can be erased by an official update. Do not preserve the old overlay, edit bootloader update flags, or blindly reinstall on an unknown image. The Pi keeps archives/policy outside the printer, but automated UART recovery is future work. New firmware may change or close the owner-console route.

## Data limits, privacy and removal

The wire protocol accepts only a fresh 128-bit nonce request, followed by these fixed files in order: `identity.json` (2 KiB), and optionally `request.bin` (128 B), `summary.json` (8 KiB), `console.rx` (4 MiB). Each file has exact length and CRC32 checks, hex data chunks of at most 256 bytes, a final nonce marker, and a 120-second transfer budget. The exporter has at least a one-second pause between transfers. Regular files, file ownership/modes, symlinks and changes during each read are checked. A finalized source bundle is still an owner requirement; CRC32 does not make a live file snapshot coherent.

This transport is **not encrypted or peer-authenticated**. It is restricted to a physical USB link and read-only exports. The random nonce avoids accepting stale responses; CRC32 detects corruption. USB IDs/routes are not credentials. Before adding shell execution, remote writes or maintenance actions, implement an authenticated pairing/authorization layer. The phone reaches the Pi through your SSH account; this starter adds no HTTP/MQTT/TCP endpoint.

Private state is `/var/lib/cc2-companion`, mode 0700, with files 0600. Status contains private identity metadata. Raw console output may contain sensitive information. Do not commit/share archives. At 16 distinct bundles or 64 MiB, new archives are refused instead of deleting owner evidence; repeated identical bundles remain readable. To clear space, stop the receiver, copy selected complete `bundle-*` directories to private storage, and deliberately remove those selected directories before restarting. No automatic eviction is supplied.

To remove Pi setup, use the original extracted package or the unchanged installed copy:

```sh
sudo python3 -B /opt/cc2-companion/cc2_pi_install.py uninstall --apply
sudo reboot
```

Removal verifies its installation journal, stops/disables services, removes unchanged managed files, removes its exact boot block while preserving later unrelated boot-config edits, and retains private archives and extra owner files. Changed managed files/configuration stop automatic removal for review. An interrupted install leaves a journal and backup; an interruption before the backup is written needs manual review of those named files.

## Qualification and next implementation

Automated checks cover the real native agent over pseudo-terminals, actual ARM executable under QEMU, all 256 byte values and the maximum capture size, fragmented frames, stale nonces, corrupt CRCs, missing completion, changed/oversized files, source symlinks, partial probe bundles, exclusive serial ownership, quotas and boot-config removal. They establish software behavior, not physical USB enumeration or persistence.

Record these on hardware before enabling routine use:

- Board/OS, power arrangement, exact printer firmware hash, shell UID and live mount state.
- Actual CDC-ACM descriptors/route and tty names with either boot order and unplug/replug.
- Identity-only transfer, then one completed fixed inventory capture inspected on the Pi.
- Printer/WAN/camera health and measured Pi/printer CPU, memory and USB transfer time.
- Printer boots/prints with the Pi absent; optional service stops and removes cleanly.
- A deliberate reboot with the qualified hook; later an official update observed with independent owner recovery available.

Next features are authenticated named operations, finalized log/media/G-code exports from discovered roots, and a separately qualified UART recovery supervisor. No arbitrary shell or write channel should be added to this wire format by extending filenames/commands casually.

Primary connection references: [Raspberry Pi OTG guide](https://pip-assets.raspberrypi.com/categories/685-app-notes-guides-whitepapers/documents/RP-009276-WP-1-Using%20OTG%20mode%20on%20Raspberry%20Pi%20SBCs), [official UART configuration](https://www.raspberrypi.com/documentation/computers/configuration.html#configure-uarts), [Linux gadget configuration](https://docs.kernel.org/usb/gadget_configfs.html). Firmware provenance and hook/update evidence are in `docs/research/cc2-stock/PI-COMPANION-HOOKS.md` in the repository.
