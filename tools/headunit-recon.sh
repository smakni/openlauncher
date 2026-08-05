#!/usr/bin/env bash
# Head unit reconnaissance — profiles an Android head unit so the launcher can be
# tailored to it (grid sizing, radio backend, sensor availability, OBD support).
#
# Usage:
#   USB:      adb devices            # confirm the unit is listed
#   Network:  adb connect <unit-ip>:5555
#   Then:     ./tools/headunit-recon.sh > recon.txt
#
# Everything here is read-only — nothing is installed or modified on the unit.

set -uo pipefail

sh() { adb shell "$@" 2>&1; }
section() { printf '\n\n===== %s =====\n' "$1"; }

if ! adb get-state >/dev/null 2>&1; then
    echo "No device: connect over USB or run 'adb connect <unit-ip>:5555' first." >&2
    exit 1
fi

printf 'Head unit recon — %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

section "IDENTITY"
for p in ro.product.manufacturer ro.product.brand ro.product.model ro.product.name \
         ro.product.device ro.product.board ro.board.platform ro.hardware \
         ro.build.version.release ro.build.version.sdk ro.build.display.id \
         ro.build.characteristics ro.build.fingerprint; do
    printf '%-32s %s\n' "$p" "$(sh getprop "$p")"
done

section "DISPLAY  (drives the widget grid geometry)"
echo "wm size    : $(sh wm size)"
echo "wm density : $(sh wm density)"
sh dumpsys display | grep -iE 'mBaseDisplayInfo|real [0-9]|density' | head -5

section "SENSORS  (compass viability — many head units have no magnetometer)"
sh dumpsys sensorservice | sed -n '1,40p' | grep -iE 'accelerometer|magnetic|gyro|orientation|pressure' \
    || echo "(no matching sensors reported)"

section "GPS / LOCATION"
sh dumpsys location | grep -iE 'gps|fused|providers' | head -15

section "VENDOR PACKAGES  (radio, MCU, canbus, camera, projection)"
sh pm list packages | grep -iE \
  'radio|mcu|canbus|can_bus|tuner|dvr|camera|carplay|autobox|projection|android.*auto|gaia|syu|fyt|szchoiceway|topway|hct|xy|zlink|autokit|carbit|ecarplay' \
  | sort || echo "(none matched)"

section "SZCHOICEWAY MCU PROBE  (exactly what LauncherViewModel.hasSzchoicewayMcu tests)"
for pkg in com.szchoiceway.radio com.szchoiceway.eventcenter; do
    if sh pm list packages | grep -q "$pkg"; then
        echo "$pkg : PRESENT"
    else
        echo "$pkg : absent"
    fi
done
echo "SYS_MEDIA_INFO_JSON : $(sh settings get global SYS_MEDIA_INFO_JSON)"

section "SETTINGS.GLOBAL  (vendor keys often carry radio / vehicle state)"
sh settings list global | grep -iE 'media|radio|sys_|mcu|car|canbus|speed|acc' | head -40 \
    || echo "(none matched)"

section "ACTIVE MEDIA SESSIONS  (radio mirroring fallback path)"
sh dumpsys media_session | grep -iE 'package|state=|Media button session|metadata' | head -30

section "BLUETOOTH  (required for the OBD-II ELM327 dongle)"
echo "BLE feature : $(sh pm list features | grep -i bluetooth_le || echo absent)"
sh dumpsys bluetooth_manager | grep -iE 'enabled|address|name:' | head -10

section "CURRENT HOME  (can the stock launcher actually be replaced?)"
sh cmd package resolve-activity -c android.intent.category.HOME
echo "--- all HOME candidates ---"
sh cmd package query-activities -c android.intent.category.HOME 2>/dev/null \
    | grep -iE 'packageName|name=' | head -20 \
    || sh pm list packages -f | grep -iE 'launcher|home' | head

section "DEVICE CONFIG"
echo "ABI      : $(sh getprop ro.product.cpu.abilist)"
echo "RAM      : $(sh cat /proc/meminfo | grep MemTotal)"
echo "CPU      : $(sh cat /proc/cpuinfo | grep -m1 -i 'hardware\|model name')"
echo "cores    : $(sh nproc)"

printf '\n\nDone. Send this file back to have the launcher tailored to the unit.\n'
