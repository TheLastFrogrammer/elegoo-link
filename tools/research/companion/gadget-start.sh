#!/bin/sh
# Pi only. Never steal a UDC already bound to another USB gadget.
set -eu
for cc2_function in /sys/class/udc/*/function; do
    [ -f "$cc2_function" ] || continue
    [ -z "$(cat "$cc2_function")" ] || {
        echo 'cc2-companion: another USB gadget owns the controller' >&2
        exit 1
    }
done
modprobe g_serial use_acm=1
cc2_attempt=0
while [ "$cc2_attempt" -lt 20 ]; do
    [ ! -c /dev/ttyGS0 ] || exit 0
    cc2_attempt=$((cc2_attempt + 1))
    sleep 1
done
echo 'cc2-companion: ttyGS0 unavailable; check dwc2 peripheral configuration' >&2
modprobe -r g_serial || true
exit 1
