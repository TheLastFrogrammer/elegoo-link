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
cc2_tty=${cc2_device#/dev/}
cc2_expected_route=$(cat "$cc2_base/usb-device-path")
[ -n "$cc2_expected_route" ] || exit 1
# The Pi may boot after the printer, or be unplugged for a while. Waiting here (bounded) keeps procd's small respawn
# budget for real failures: an absent device used to exit at once and exhaust it within about a minute of boot.
cc2_device_ready() {
    [ -c "$cc2_device" ] && [ ! -L "$cc2_device" ] || return 1
    cc2_route=$(readlink -f "/sys/class/tty/$cc2_tty/device") || return 1
    [ -n "$cc2_route" ] && [ "$cc2_route" = "$cc2_expected_route" ]
}
cc2_wait_device() {
    cc2_tries=0
    while ! cc2_device_ready; do
        cc2_tries=$((cc2_tries + 1))
        [ "$cc2_tries" -le 120 ] || exit 1   # ten minutes; procd may start us again later
        sleep 5
    done
}
cc2_wait_device
cc2_bundle=/tmp/cc2-bundle-service-$$
/bin/sh "$cc2_base/printer-prepare.sh" "$cc2_bundle" >/dev/null
# Only these files in the fresh directory are ours. Never remove arbitrary trees.
trap '/bin/rm -f "$cc2_bundle/identity.json"; /bin/rmdir "$cc2_bundle" 2>/dev/null || true' EXIT
cc2_child=
trap 'if [ -n "$cc2_child" ]; then kill "$cc2_child" 2>/dev/null || true; wait "$cc2_child" 2>/dev/null || true; fi; exit 0' INT TERM
# The exporter fails closed on anything unexpected (a malformed request, the Pi going away mid-transfer). Start it again
# after a pause, rechecking the device and its USB route each time, instead of leaving the service stopped until reboot.
while :; do
    cc2_wait_device
    "$cc2_base/usb-export" --device "$cc2_device" --bundle "$cc2_bundle" &
    cc2_child=$!
    wait "$cc2_child" || true
    cc2_child=
    sleep 15
done
