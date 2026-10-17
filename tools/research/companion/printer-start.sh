#!/bin/sh
# OPTIONAL persistent launcher, after physical qualification only.
# Policy and route files below are data, never sourced as shell code.
set -eu
umask 077
cc2_base=/opt/usr/cc2-companion
cc2_expected=8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074
cc2_firmware=$(/usr/bin/sha256sum /opt/bin/elegoo_printer)
[ "${cc2_firmware%% *}" = "$cc2_expected" ] || exit 1
cc2_binary=$(/usr/bin/sha256sum "$cc2_base/usb-export")
cc2_binary=${cc2_binary%% *}
[ "$(cat "$cc2_base/QUALIFIED")" = "CC2_COMPANION_QUALIFIED_V1 $cc2_expected $cc2_binary" ] || exit 1
cc2_device=$(cat "$cc2_base/device")
case "$cc2_device" in /dev/ttyACM[0-9]) ;; *) exit 1 ;; esac
[ -c "$cc2_device" ] && [ ! -L "$cc2_device" ] || exit 1
cc2_tty=${cc2_device#/dev/}
cc2_route=$(readlink -f "/sys/class/tty/$cc2_tty/device")
[ -n "$cc2_route" ] && [ "$cc2_route" = "$(cat "$cc2_base/usb-device-path")" ] || exit 1
cc2_bundle=/tmp/cc2-bundle-service-$$
/bin/sh "$cc2_base/printer-prepare.sh" "$cc2_bundle" >/dev/null
# Only these files in the fresh directory are ours. Never remove arbitrary trees.
trap '/bin/rm -f "$cc2_bundle/identity.json"; /bin/rmdir "$cc2_bundle" 2>/dev/null || true' EXIT
trap 'exit 0' INT TERM
"$cc2_base/usb-export" --device "$cc2_device" --bundle "$cc2_bundle" &
cc2_child=$!
trap 'kill "$cc2_child" 2>/dev/null || true; wait "$cc2_child" 2>/dev/null || true; exit 0' INT TERM
wait "$cc2_child"
