#!/bin/sh
# Run only in an owner-controlled shell on the printer. This is not a remote-login tool.
# Reads a small set of non-credential facts; changes no mode, files, services or printer controls.
set -eu
printf 'Machine and kernel\n'
uname -m
uname -r
printf '\nPrinter executable fingerprint\n'
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum /opt/bin/elegoo_printer
else
    printf 'sha256sum unavailable; fingerprint needs a separate read-only collection.\n'
fi
printf '\nReported versions\n'
if [ -f /opt/usr/versions.json ]; then cat /opt/usr/versions.json; fi
printf '\nRelevant mounts\n'
awk '$2 == "/opt/usr" || $2 == "/overlay" || $2 == "/" {print}' /proc/mounts
printf '\nKnown G-code locations (directory metadata only)\n'
ls -ld /opt/usr/gcode/local /opt/usr/gcode/udisk 2>/dev/null || true
printf '\nLocal IPC socket presence\n'
if [ -S /tmp/elegoo_uds ]; then printf 'elegoo_uds exists\n'; else printf 'elegoo_uds absent\n'; fi
printf '\nExisting rsync capability\n'
if command -v rsync >/dev/null 2>&1; then rsync --version | head -n 3; else printf 'rsync unavailable\n'; fi
printf '\nFinished. No services started or configuration changed.\n'
