#!/bin/sh
# Run only in an independently established owner shell on an idle printer.
# Reads selected non-credential facts. Installs nothing, loads no driver, opens
# no serial device, starts no service and writes no printer configuration.
# Keep the terminal log private. This script has not run on a physical CC2.
set -eu

printf 'CC2 companion observation v1\n'
id
uname -m
uname -r

printf '\nImage and startup fingerprints\n'
for cc2_file in /opt/bin/elegoo_printer /etc/inittab /etc/rc.local /usr/bin/ssh; do
    if [ -f "$cc2_file" ] && command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$cc2_file"
    fi
done

printf '\nRelevant mounts\n'
awk '$2 == "/" || $2 == "/rom" || $2 == "/overlay" || $2 == "/opt/usr" || $2 == "/mnt/exUDISK" {print}' /proc/mounts

printf '\nKernel USB options, if exposed\n'
if [ -r /proc/config.gz ] && command -v zcat >/dev/null 2>&1; then
    zcat /proc/config.gz | awk '/^(# )?CONFIG_(USB_ACM|USB_SERIAL|USB_NET_DRIVERS|USB_STORAGE|USB_HID|USB_CONFIGFS|USB_CONFIGFS_ACM|USB_CONFIGFS_SERIAL|USB_CONFIGFS_RNDIS|OVERLAY_FS)(=| is not set)/ {print}'
else
    printf 'Embedded configuration unavailable through this path.\n'
fi

printf '\nUSB vendor/product IDs (up to 32 nodes; unique serial strings omitted)\n'
cc2_count=0
for cc2_usb in /sys/bus/usb/devices/*; do
    [ -r "$cc2_usb/idVendor" ] && [ -r "$cc2_usb/idProduct" ] || continue
    printf '%s vendor=%s product=%s\n' "${cc2_usb##*/}" "$(cat "$cc2_usb/idVendor")" "$(cat "$cc2_usb/idProduct")"
    cc2_count=$((cc2_count + 1))
    [ "$cc2_count" -lt 32 ] || break
done

printf '\nCDC-ACM nodes (metadata only; up to 16)\n'
cc2_count=0
for cc2_tty in /dev/ttyACM[0-9]*; do
    [ -c "$cc2_tty" ] || continue
    ls -l "$cc2_tty"
    cc2_name=${cc2_tty##*/}
    if [ -L "/sys/class/tty/$cc2_name/device/driver" ]; then
        readlink "/sys/class/tty/$cc2_name/device/driver"
    fi
    cc2_count=$((cc2_count + 1))
    [ "$cc2_count" -lt 16 ] || break
done

printf '\nStartup locations and stock socket (metadata only)\n'
for cc2_path in /etc/hotplug.d/tty /etc/udev/rules.d /etc/rc.d/S94done /etc/rc.d/S95printer /tmp/elegoo_uds; do
    if [ -e "$cc2_path" ] || [ -L "$cc2_path" ]; then ls -ld "$cc2_path"; fi
done
printf '\nObservation finished.\n'
