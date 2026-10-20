# Stock-firmware connection findings and hardware sourcing

20 October owner update: SH-U09C5 is now confirmed; follow the [current execution plan](FINAL-CONNECTION-AND-APP-PLAN.md) and phone/adapter loopback before printer wiring. The purchase comparisons/prices below are the dated 17 October research, not a new shopping requirement.

Checked 17 October 2026. Scope: the signature-verified stock **02.01.01.07** image already pinned in this repository. The owner's exact installed build, header revision and electrical measurements remain pending. No firmware was executed, altered or installed, and no printer was contacted for this pass.

## What the deeper firmware pass changes

| Finding | Evidence and implication |
|---|---|
| Stock already contains the text-transfer primitives | BusyBox 1.27.2's 121-name applet table and rootfs links include `printf`, `sha256sum`, `hexdump`, `wc`, `sed`, `mkdir` and `chmod`. Generate a temporary probe upload and a checksummed capture export instead of adding Python, SSH or a second USB-storage dependency. |
| Common transfer/terminal commands are absent | No `base64`, `uuencode`, `uudecode`, `stty` or `timeout` applets. Unrelated strings are not evidence of callable applets. The tool uses normal terminal settings and implements its own socket deadlines. |
| Hardware UART number and Linux terminal number can differ | Recovered kernel `sw_uart_probe` selects hardware state using `of_alias_get_id(...,"serial")`, then reads `uartN_port` into a separate port field. `sw_console_get_port` compares that field with the console index. This explains how UART0 could serve ttyS2, but the embedded `uart0_port` is empty and the live boot-patched value is unknown. |
| 115200 has a stronger basis | Actual `sw_console_setup` initializes baud 115200, 8 bits, parity `n` and flow `n` before parsing any supplied console options. It remains an initial setting until observed on the physical header. |
| A U-Boot console string needs context | The nearby `console=ttyS0,115200` string sits beside `bootcmd=run sunxi_sprite_test`, a factory/sprite environment. It does not establish the normal Linux console. Do not change boot arguments to reconcile labels. |
| PF has a separate optional regulator path | `sunxi_pmx_request` derives `vcc-pf` for bank 5 and conditionally obtains `vcc-pfo` through a descriptor flag, enabling both when applicable. A separate voltage-dependent bias function exists. Together with the embedded tree's 1.8-V and 3.3-V supplies, this reinforces that a supply name alone cannot establish header signal voltage or RX tolerance. |

The recovered kernel symbol table has 31,563 entries. Function fingerprints, addresses, literal references, stock tool paths and reproduction details are in [STOCK-CONNECTION-FOLLOWUP.json](access/evidence/STOCK-CONNECTION-FOLLOWUP.json). These are offline image findings, not a physical wiring map.

## Recommended buying path

**DFRobot FIT0416 is the best candidate for this job**: the manufacturer explicitly specifies selectable **TX/RX TTL levels**, as well as VCC output, at 1.8/3.3/5 V. That distinction matters; a board with a selectable power output can still have fixed 3.3-V signals. Select the level only after the printer header has been identified and measured. Leave its VCC output disconnected.

| Option | Verified source / price | Fit for this task |
|---|---|---|
| **DFRobot FIT0416** | [Manufacturer](https://www.dfrobot.com/product-1277.html), **US$10.90**. [DigiKey 1738-FIT0416-ND](https://www.digikey.com/en/products/detail/dfrobot/FIT0416/25558527) also showed $10.90 and 12 in stock at check time. [Mouser 426-FIT0416](https://www.mouser.com/en/ProductDetail/DFRobot/FIT0416?qs=kE1vTINknaUHsD6OWu9OQQ%3D%3D) is another listing; its availability was not independently rechecked. | Preferred inexpensive multi-voltage candidate. Verify the shipped USB connector and chipset/VID/PID; listing pictures and generic distributor attributes differ. Manufacturer links an FTDI driver, but the actual phone/adapter combination is not tested. |
| **Adafruit 954 console cable** | [Manufacturer](https://www.adafruit.com/product/954), **$9.95**, in stock at check time. | Convenient USB-A cable with individual leads, **3.3-V TX/RX only**. Its red lead supplies 5 V; do not connect it to the printer. Consider only after a 3.3-V header has been confirmed. |
| **Tigard** | [Hardware/source](https://github.com/tigard-tools/tigard), [Crowd Supply](https://www.crowdsupply.com/securinghw/tigard): **$49**, preorder estimate 5 November 2026, $8 US shipping shown. [1BitSquared](https://1bitsquared.com/collections/embedded-hardware/products/tigard) showed $49, sold out. | Reusable FT2232H hardware tool with onboard 1.8/3.3/5-V level selection. Higher cost and slower availability for this single task. Use its internally powered level-shifter mode at the measured level; leave target-power/reference leads unconnected in that mode. |

Prices are USD before shipping, tax and any applicable tariffs. Stock and delivery depend on destination and checkout. No order was placed.

Add a **USB-C host/OTG connection for the S24+**, an appropriate **USB data cable for the actual adapter connector**, and **individual insulated jumper leads** matched to the verified header. Do not assume the FIT0416 kit includes cables; the English maker shipping list guarantees only the module. Prefer an established component distributor and the exact part number over an unspecified marketplace "FT232/CP2102" board.

The [Waveshare CH343 board](https://www.waveshare.com/wiki/CH343_USB_UART_Board) is not an equivalent all-voltage kit: its onboard jumper provides 3.3/5 V, while 1.8/2.5 V requires external VIO. That adds a supply/reference requirement. A generic chip-name listing also leaves signal voltage and Android driver mode unresolved.

The Android [usb-serial-for-android driver](https://github.com/mik3y/usb-serial-for-android) supports FTDI FT232/FT2232 and Silicon Labs CP210x families; CH343 support is through generic CDC/ACM. This is a useful selection basis, not proof for a particular shipped board. First qualify the adapter on the phone, with no printer attached: Android USB permission, enumeration, selected baud and a private local TX/RX loopback. Do not assume Termux obtains a normal `/dev/ttyUSB0` device. The [terminal author's app/source](https://github.com/kai-morich/SimpleUsbTerminal) is a candidate for initial console observation; paced file sending and intact private log export must be checked separately for the chosen app/version.

## Connection and tool sequence

Follow [PHONE-UART-NEXT-STEPS.md](PHONE-UART-NEXT-STEPS.md) for the physical sequence. The short dependency order is: exact board/header and voltage → phone/adapter loopback → receive-only normal boot → observed Linux activation prompt → qualified transmit and `id` → temporary probe staging → one inventory capture → offline interpretation. Do not connect to the toolhead USB-C port, which the CC2 reference documents as carrying 24 V. This is a board UART connection, not a plain USB cable to the printer.

The new [serial transfer tool](../../../tools/research/SERIAL-TRANSFER.md) uses short octal `printf` lines, verifies the native binary's full SHA-256 before making it executable, and requires manual startup. The CI artifact contains ready-generated staging/export files tied to the actual ARM binary. The decoder reconstructs only the three fixed capture files, verifies their printer-side sizes/hashes and then invokes the strict inventory inspector. Ordinary prompts and CRLF are accepted; incomplete or corrupt exports are rejected.

Validated download: [passing run 39042718858](https://github.com/TheLastFrogrammer/elegoo-link/actions/runs/39042718858), [ARM probe and UART transfer bundle](https://github.com/TheLastFrogrammer/elegoo-link/actions/runs/39042718858/artifacts/12104951503). All 80 research tests passed on each host Python version and all 22 probe tests passed with the actual ARM executable under QEMU, with zero skips. The downloaded ZIP, all 14 packaged checksums, bundled source correspondence and regenerated staging/export snippets were independently verified. The binary remains 25,852 bytes; the upload is 135,556 ASCII bytes with a longest line of 667 bytes. [Validation record](SERIAL-TRANSFER-VALIDATION.json). The artifact expires 16 November 2026; the committed workflow can recreate a new bundle.

This keeps the first experiment small: an idle printer, one known inventory request and a temporary directory. It needs no firmware patch, startup installation or LAN/WAN-mode change. A separate USB drive remains an alternative if the chosen terminal cannot do paced text transfer; neither hardware path has yet been qualified on the printer.

For the eventual companion, keep **WAN as the normal monitoring source**, **UDS as a candidate local console source**, and **verified local file bytes as the toolpath source**. Cached cloud URLs are useful for a cloud-started job but do not create cloud objects for USB/local jobs. Recover and hash one known local file before developing a bulk transport. Avoid building an exposed `nc` shell, HTTP listener or patched stock network strategy merely because the firmware contains networking applets. Authentication, resource budgets, active-print reads and official-update behavior remain separate companion acceptance work in [NEXT-STEPS.md](NEXT-STEPS.md).
