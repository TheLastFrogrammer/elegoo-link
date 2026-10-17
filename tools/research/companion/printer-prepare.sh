#!/bin/sh
# Run explicitly on the stock owner's Linux shell. Creates one NEW /tmp bundle.
# No console request, network change, startup change or firmware write.
set -eu
umask 077
set -C
[ "$#" -eq 1 ] || { echo 'Usage: sh printer-prepare.sh /tmp/cc2-bundle-NAME' >&2; exit 2; }
case "$1" in /tmp/cc2-bundle-*) ;; *) exit 2 ;; esac
# Disallow nested paths and shell metacharacters in the suffix.
cc2_suffix=${1#/tmp/cc2-bundle-}
case "$cc2_suffix" in ''|*[!a-zA-Z0-9_-]*) exit 2 ;; esac
[ "$(id -u)" = 0 ] || { echo 'Owner root shell required' >&2; exit 1; }
cc2_sum=$(/usr/bin/sha256sum /opt/bin/elegoo_printer)
cc2_sum=${cc2_sum%% *}
cc2_boot=$(cat /proc/sys/kernel/random/boot_id)
[ "${#cc2_sum}" = 64 ] && [ "${#cc2_boot}" = 36 ]
case "$cc2_sum$cc2_boot" in *[!0-9a-f-]*) exit 1 ;; esac
/bin/mkdir -m 700 "$1"
/usr/bin/printf '{"format":"cc2-companion-identity-v1","printer_executable_sha256":"%s","boot_id":"%s","uid":0}\n' \
    "$cc2_sum" "$cc2_boot" > "$1/identity.json"
/bin/chmod 600 "$1/identity.json"
/usr/bin/printf '%s\n' 'CC2_BUNDLE_PREPARED (identity only; no console request made)'
