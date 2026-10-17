# Getting owner access while retaining stock firmware

Investigated 17 October 2026. The strongest current route for the inspected stock CC2 02.01.01.07 image is its configured mainboard UART console. An existing SSH service is worth checking first because it needs no disassembly, but its availability depends on the installed version. No printer was contacted, firmware executed, credentials guessed or hardware wiring validated during this pass.

## New finding: the console action launches a shell directly

The stock `/etc/inittab` configures `askfirst` actions on `tts/0`, `ttyS2` and `tty1`. Each runs `/bin/ash --login`. `/bin/ash` is a symlink to BusyBox. Here `--login` requests login-shell startup behavior; the configured command is not a `login` or `getty` authentication program.

I also inspected the **actual stock `/sbin/askfirst` ARM executable**, not just an upstream implementation. Its main function prints the activation prompt, calls `getchar` until LF, then calls `execvp` with the supplied program/arguments. It performs no password authentication in that function. Together with the init configuration, this strongly supports a directly activated shell once the matching terminal is reached. Root privilege is expected from the init context but still needs confirmation with `id` on the actual printer.

This is stronger than the earlier generic "owner shell required" wording. The unresolved part is now the physical mapping and installed-build behavior, not a demonstrated need to crack a root password. [Binary call flow and configuration fingerprints](access/evidence/OWNER-ACCESS-BOOTSTRAP.json).

## Route comparison

| Route | Evidence | What to do next |
|---|---|---|
| Existing SSH, TCP 22 | First-hand community field notes report stock 01.03.02.51 exposed OpenSSH and later 02.00.02.00 removed it. The inspected 02.01.01.07 rootfs has OpenSSH client metadata but no named SSH server executable | Check the actual firmware version and read the SSH banner on the owner's LAN. An open port still needs a verified authentication method |
| Mainboard UART0 | CC2 hardware reference documents the UART0/DSP header; stock init plus actual askfirst code support direct shell activation | Identify exact board/header, verify signal levels, capture receive-only normal boot, then activate the Linux console if observed |
| Printer USB/ADB | Generic USB configuration code and an `adbd` dependency string exist, but no named adbd executable or corresponding init service was found | No qualified stock printer ADB route established |
| Camera USB/ADB | Camera maintenance project establishes a route to supported camera modules | Useful for camera work; it does not establish mainboard shell access |
| USB autorun or failsafe hooks | Debug-install line is commented and its script absent. A failsafe script refers to Dropbear, but its named binaries are absent from the rootfs inventory | Do not treat these remnants as a working bootstrap |
| FEL / RAM recovery | Board reference labels FEL; this is a possible lower-level investigation | Not the first route. CC1 procedures and arbitrary boot images are not qualified CC2 recovery instructions |

SSH historical source: [pycentauri CC2 field notes](https://github.com/bjan/pycentauri/blob/main/docs/PROTOCOL.md#centauri-carbon-2-cc2-protocol-notes). Their SSH statement is historical and version-specific; other protocol claims are not adopted wholesale. Mainboard reference: [OpenCentauri CC2](https://docs.opencentauri.cc/hardware/CC2/mainboard/). Camera reference: [cc2_camera_fix](https://github.com/OpenCentauri/cc2_camera_fix).

## Owner observations: do not repeat completed network experiments

On 17 October the owner supplied Termux screenshots showing a refused TCP 22 connection, a refused TCP 80 connection, and successful TCP connections to ports 1883 and 8080. These were connection-only checks, with no MQTT authentication or camera request. Port numbers suggest service roles; a successful TCP connect alone does not establish the application protocol, a working camera, console access or coexistence. The owner reports the most recent firmware, but has not supplied its exact version string. The screenshots do not establish the current network mode or print state. See the [structured field record](access/evidence/OWNER-NETWORK-OBSERVATIONS.json).

The existing [app field results](../../../android/PROTOCOL.md#field-results-owners-cc2-2026-10-12-app-0454) already record a correct cloud-mode PIN accepted by the local broker, followed by zero messages in a 30-second listen-only window. They also record working internal and USB file downloads with the LAN access code after switching to LAN Only. This earlier evidence is not a fresh test of the owner's unspecified latest build, but it means another identical PIN or port experiment is not a new owner-access lead.

The same app research records a third-party report of repeated cloud-mode camera probes stalling port 8080 until a power cycle. Do not send further camera probes or infer streaming from the latest successful TCP connection. The latest owner screenshot does not show a camera fault.

The access code is relevant to supported LAN services after their listeners are available; it cannot authenticate a TCP connection that has already been refused. No network-only mainboard shell bootstrap has been established. The owner currently has no USB UART adapter and has limited laptop access. Mainboard UART therefore remains a physical prerequisite to investigate, while the phone can be the capture terminal after compatible hardware and signal levels are established. Firmware analysis and native companion preparation can continue offline; neither gives the current phone a connection to the printer's local Unix socket.

## First: a bounded network check from the owner's LAN

If Python is available in Termux or on a computer, replace the example address with the printer's current IP. This opens TCP 22 once and reads at most 256 bytes. It sends no application payload, credentials or commands.

```sh
python - <<'PY'
import socket
host = "192.168.1.50"  # Replace with your printer's address.
try:
    with socket.create_connection((host, 22), timeout=3) as connection:
        connection.settimeout(3)
        print(repr(connection.recv(256)))
except OSError as error:
    print(type(error).__name__, str(error))
PY
```

`SSH-2.0-...` identifies an SSH-speaking service. Refusal means no listener was reachable; timeout can also be routing or filtering, so it does not prove which services are installed. Do not scan other hosts or infer that a printer LAN access code is its Linux password. No verified current-stock SSH credential is supplied by this investigation.

## If SSH is unavailable: UART observation

Use the **CC2-specific** board picture and the actual revision marking. The reference calls the lower row of a 2×4 header UART0 and the upper row DSP. Its pin table labels +5V, TX, RX and GND, but that does not establish the signal voltage or left/right orientation in a user photo. Do not substitute a CC1 pinout.

For a verified, correctly oriented UART header and an adapter matched to its logic levels:

| Printer signal | Initial adapter connection |
|---|---|
| GND | GND |
| TX | RX |
| RX | Disconnected during initial capture |
| Supply / +5V | Disconnected |

Power off and unplug before opening or attaching leads; keep clear of the power-supply/mains area. The printer uses its own normal power after leads are secured. This is a TTL UART connection, not PC RS-232. The toolhead USB-C connection is documented as carrying 24 V and is not a computer debug port.

Start a receive-only normal-boot capture. Stock boot strings support **115200 8N1 as an initial candidate**, not a verified measurement for the owner's header. The bootloader mentions ttyS0 while Linux/init evidence includes ttyS2; their physical relationship is still unresolved. Readable boot text is the first result to establish.

Once boot has finished and the Linux `Please press Enter to activate this console.` prompt is actually observed, a transmit connection may be added only after its mapping/levels are verified. Enter at that prompt is the candidate activation step. Do not interrupt a bootloader countdown or change boot arguments/environment to obtain the initial shell.

Read-only first checks after a shell is established:

```sh
id
uname -a
test -S /tmp/elegoo_uds && echo "stock console socket present"
command -v debug_window
```

Only then proceed to [console discovery](CONSOLE-QUICKSTART.md). Getting a shell does not require enabling an SSH daemon, changing network mode, adding a startup hook, or replacing the printer executable. A later manually started helper can be tested separately; update survival remains unqualified.

## Computer versus S24+

A computer with a USB UART adapter is the most straightforward capture station. A USB-host-capable Android phone can also communicate through a supported USB serial adapter using an app such as [Simple USB Terminal](https://github.com/kai-morich/SimpleUsbTerminal). Android grants USB-device access through its [USB host permission API](https://developer.android.com/develop/connectivity/usb/host); do not assume a Termux process can open `/dev/ttyUSB0`. The adapter chipset and actual phone setup still need checking.

The information needed to select the next exact procedure is the installed firmware version, whether a PC or only the S24+ is available, and a clear board/header photo before any pin-by-pin wiring recommendation. Record results in the [owner worksheet](OWNER-CAPTURE-WORKSHEET.md).
