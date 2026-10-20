# OpenCentauri FEL/UART guide review

Checked 20 October 2026. The owner has now confirmed **DSD TECH SH-U09C5** and supplied its photo; logic-level configuration, phone loopback and printer wiring remain unverified. Current execution order is in [FINAL-CONNECTION-AND-APP-PLAN.md](FINAL-CONNECTION-AND-APP-PLAN.md).

The shared guide is [FEL & UART Setup](https://docs.opencentauri.cc/hardware/CC1/fel-uart-setup/), filed under **CC1**. Its 3.3-V recommendation and J5 bench wiring must not be treated as CC2-specific validation. It describes a removed-board 24-V bench supply, crossed TX/RX, common ground and no UART VCC connection. A bench supply/FEL cable is not required merely to attempt the existing normal-boot UART route on an installed printer.

The separate [CC2 board reference](https://docs.opencentauri.cc/hardware/CC2/mainboard/) identifies EF-M5-V1.0.073 and a two-row header: DSP above UART0, with each row tabulated as +5V/TX/RX/GND. This establishes neither the owner's viewing orientation nor TX/RX logic voltage. It also documents a separate FEL header and 24-V toolhead connection. Do not attach a phone/computer to that toolhead connection.

| Route | Role in this project |
|---|---|
| Normal UART boot | First hardware trial: confirm adapter/header/levels, receive-only capture, then an observed Linux console prompt. Existing stock-image askfirst findings still need live confirmation. |
| FEL | Separate recovery/development candidate, unavailable for ordinary printing while the system is in FEL. |
| RAM-loaded recovery bootloader | Potential temporary access/backup route if qualified for CC2; not an update-surviving hook. |
| eMMC restore/flash | Persistent storage changes; unnecessary for the initial console trial. |

The [FEL software guide](https://docs.opencentauri.cc/software/FEL-mode/) separates identification, UART initialization, DDR initialization and bootloader load/execute from eMMC restoration. This supports investigating RAM-only recovery without assuming that community firmware must be installed. It does **not** validate the example resistor location, DDR preset, bootloader binary, entry address or subsequent behavior for the owner's CC2. The companion restore document uses a different DDR preset; identify the actual tool/image pairing before executing either sequence. Do not run restore scripts to obtain an initial shell.

Next owner inputs: phone-only loopback result; exact printer firmware; actual board/header photo before pin-by-pin wiring. Adapter identity is now known; phone compatibility and normal receive-only boot remain hardware experiments. No printer was connected, booted into FEL, flashed or modified during this review.

## Source pins

OpenCentauri/OpenCentauri commit `c59a3febfcadccfa27dc53f9342e019c29a20344`:

- `docs/hardware/CC1/fel-uart-setup.md`
- `docs/hardware/CC2/mainboard.md`
- `docs/software/FEL-mode.md`

OpenCentauri/cc-fw-tools commit `1c4255f2e9eac39177ddf09a9d7f4d937a2ed2ff`: [docs/EMMC_RESTORE.md](https://github.com/OpenCentauri/cc-fw-tools/blob/1c4255f2e9eac39177ddf09a9d7f4d937a2ed2ff/docs/EMMC_RESTORE.md).
