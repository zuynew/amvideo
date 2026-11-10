#!/bin/bash
# Installs a build so Android Auto will show it on a real head unit.
#
# Android Auto reads TWO fields and needs both:
#   initiatingPackageName -> the system package installer (or Play)
#   installerPackageName  -> com.android.vending
#
# `adb install -i com.android.vending` sets only the second. The first is filled
# in by the system from the real caller, so a sideload is always
# com.android.shell and the app stays invisible in the car. Confirmed in a real
# car: the installing package matched that of apps genuinely installed from Play
# and the launcher still never listed us.
#
# The way through: the SYSTEM INSTALLER performs the install, so it becomes the
# initiator, while the SHELL launches it -- some Android 17 builds drop the Play
# name when an app launches it -- with the Play attribution passed as intent extras.
# UnknownAuto is used only to stage the APK behind a content:// URI the
# installer can read.
#
# The mark is per-install: every rebuild needs this again.
#
# Usage:
#   install-for-car.sh            interactive, all phases
#   install-for-car.sh push [apk] copy the APK to the phone
#   install-for-car.sh install    launch the system installer as shell
#   install-for-car.sh verify     read the provenance back; non-zero if rejected
set -u

# adb, in order of preference: $ADB, the one on PATH, $ANDROID_HOME, the usual
# Homebrew location. Override with ADB=/path/to/adb if none of these fit.
if [ -z "${ADB:-}" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB=$(command -v adb)
  elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
    ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
  else
    ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
  fi
fi
[ -x "$ADB" ] || { echo "adb not found - set ADB=/path/to/adb" >&2; exit 1; }
PKG=com.zuynew.amvideo.auto
VENDING=com.android.vending
STAGED=content://com.mag.unknownauto.apk/install.apk
DEFAULT_APK=app/build/outputs/apk/mobile/debug/app-mobile-debug.apk

device() {
  local s
  s=$("$ADB" devices | awk '/\tdevice$/{print $1; exit}')
  [ -n "$s" ] || { echo "no authorised device - is USB debugging on?" >&2; return 1; }
  echo "$s"
}

do_push() {
  local apk=${1:-$DEFAULT_APK} ser stale
  [ -f "$apk" ] || { echo "no APK at $apk" >&2; return 1; }
  # Refuse a build older than the sources. Installing a stale APK costs a full
  # tap-through on the phone and tests nothing; it has happened once already,
  # and the only clue was a byte-identical file size.
  # Only main sources: a newer test file does not change what is in the APK.
  stale=$(find app/src/main \( -name '*.kt' -o -name '*.xml' \) -newer "$apk" 2>/dev/null | head -3)
  if [ -n "$stale" ]; then
    echo "APK is older than these sources - rebuild first:" >&2
    echo "$stale" | sed 's/^/  /' >&2
    return 1
  fi
  ser=$(device) || return 1
  "$ADB" -s "$ser" push "$apk" /sdcard/Download/amvideo-auto.apk >/dev/null || return 1
  echo "pushed $(basename "$apk") -> /sdcard/Download/amvideo-auto.apk"
}

do_install() {
  local ser
  ser=$(device) || return 1
  "$ADB" -s "$ser" shell am start \
    -n com.google.android.packageinstaller/com.android.packageinstaller.InstallStart \
    -a android.intent.action.INSTALL_PACKAGE \
    -d "$STAGED" \
    -t application/vnd.android.package-archive \
    --es android.intent.extra.INSTALLER_PACKAGE_NAME "$VENDING" \
    --ez android.intent.extra.NOT_UNKNOWN_SOURCE true \
    --ei android.intent.extra.INSTALL_REASON 1 \
    --es android.intent.extra.REFERRER_NAME "android-app://$VENDING" >/dev/null || return 1
  echo "installer launched as shell - tap Install on the phone"
}

do_verify() {
  local ser line i
  ser=$(device) || return 1

  # Play Protect scans before the package registers, so an install that is
  # still in flight looks exactly like one that failed. Wait for it rather
  # than report a verdict about an app that is not there yet.
  for i in $(seq 1 30); do
    "$ADB" -s "$ser" shell "pm list packages" 2>/dev/null | tr -d '\r' | grep -qx "package:$PKG" && break
    [ "$i" = 30 ] && { echo "$PKG never appeared - the install did not complete" >&2; return 1; }
    sleep 1
  done

  "$ADB" -s "$ser" logcat -c
  "$ADB" -s "$ser" shell am force-stop "$PKG"
  "$ADB" -s "$ser" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 5
  line=$("$ADB" -s "$ser" logcat -d -s AmVideoInstall | grep -m1 "initiating=")
  echo "${line:-no readout - did the app start?}"
  case "$line" in
    *androidAutoWouldAccept=true*)
      "$ADB" -s "$ser" shell am force-stop com.google.android.projection.gearhead
      echo "ACCEPTED - Android Auto reset so it rescans on the next connect"
      return 0 ;;
    *)
      echo "REJECTED - the icon will not appear" >&2
      return 1 ;;
  esac
}

case "${1:-all}" in
  push)    shift; do_push "${1:-}" ;;
  install) do_install ;;
  verify)  do_verify ;;
  all)
    do_push "${2:-}" || exit 1
    cat <<'EOF'

On the phone: open UnknownAuto, tap "Load APK...", pick
Download/amvideo-auto.apk. Do NOT tap its Install button - the shell
must launch the installer, not the app.
EOF
    read -r -p "Press Enter once it says Ready... " _
    do_install || exit 1
    read -r -p "Tap Install on the phone, then press Enter... " _
    do_verify ;;
  *) echo "usage: $0 [all|push [apk]|install|verify]" >&2; exit 2 ;;
esac
