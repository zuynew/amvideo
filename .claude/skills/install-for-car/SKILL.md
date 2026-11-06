---
name: install-for-car
description: Install this app on the phone so Android Auto will show it on a real car head unit. Use whenever a build needs to reach the car, after any rebuild, or when the app's icon has stopped appearing on the head unit while the phone app still works. Also use for questions about why a sideloaded Car App Library app is invisible in the car, or about initiatingPackageName / installerPackageName / the Google Play install mark.
---

# Install for the car

Android Auto refuses to show a Car App Library app unless the install looks like
it came from Google Play. **A normal install will not work**, and the only
symptom is a missing icon — no error, no log line, nothing on the phone.

## The rule

The host reads **two** fields and needs both:

| field | must be | set by |
| --- | --- | --- |
| `initiatingPackageName` | the system package installer, or Play | the system, from the real caller |
| `installerPackageName` | `com.android.vending` | the installer, from an intent extra |

`adb install -i com.android.vending` sets only the second. The first comes from
the real caller, so any sideload is `com.android.shell` and the app stays
invisible. This was confirmed in the car: the installing package matched Tuya's
and WhatsApp's exactly, and the launcher still enumerated 18 apps without ours.

**Never reach for `adb install` here.** It looks like it worked and costs a
drive to disprove.

## What makes it work

Three things together, all required:

1. The **system installer** performs the install, so it becomes the initiator.
2. The **shell** launches that installer. This phone drops the Play name when an
   app launches it — the inverted case UnknownAuto's source warns about for some
   Android 17 builds.
3. The Play attribution rides along as four intent extras, not one.

UnknownAuto (`com.mag.unknownauto`) is needed only to stage the APK behind a
`content://` URI the installer can read; it is not doing the install. `adb`
cannot serve such a URI, which is the whole reason a second app is involved.

**Prerequisite.** If it is not installed, stop and point the user at
[README.md](../../../README.md#you-need-unknownauto) rather than installing
third-party software on their phone unasked. Check with:

```bash
adb shell pm list packages | grep mag.unknownauto
adb shell appops get com.mag.unknownauto REQUEST_INSTALL_PACKAGES
```

## Steps

Everything is in `tools/install-for-car.sh`, as phases so they can be driven one
at a time. Two steps happen on the phone and need the user.

1. Build if needed. **Hand the user the Gradle command rather than running it**
   (see the `gradle-runs-belong-to-the-user` memory):
   `./gradlew :app:assembleMobileDebug`
2. `./tools/install-for-car.sh push` — copies the APK to `/sdcard/Download/`.
3. Ask the user to open **UnknownAuto** on the phone, tap **Load APK…** and pick
   `Download/amvideo-auto.apk`. Tell them explicitly **not** to tap UnknownAuto's
   own Install button — that is step 2's failure mode, and it silently produces
   `installer=com.google.android.packageinstaller`. Wait for them.
4. `./tools/install-for-car.sh install` — launches the system installer as shell.
5. Ask the user to tap **Install** on the phone. Wait for them.
6. `./tools/install-for-car.sh verify` — restarts the app, reads its own
   `getInstallSourceInfo()` readout, and resets Android Auto so it rescans.
   Exits non-zero if the install was rejected.

Success looks like:

```
initiating=com.google.android.packageinstaller installing=com.android.vending androidAutoWouldAccept=true
```

## Checking an existing install

The app logs its provenance at every startup:

```bash
adb logcat -d -s AmVideoInstall
```

Neither field is readable from a shell — `dumpsys package` prints only the
installing one and `cmd package` has no install-source subcommand — which is why
`InstallSource.kt` exists. Do not try to answer this with `dumpsys`.

## If it comes back rejected

Read which field is wrong; they fail for different reasons.

- `initiating=com.android.shell` — the shell did the install itself. Something
  ran `adb install`. Redo from step 2.
- `installing=com.google.android.packageinstaller` — the Play extra was dropped.
  UnknownAuto's own Install button was tapped instead of step 4, or this phone
  wants the opposite launcher. UnknownAuto learns which path works per phone;
  let it retry and read what it prints.

## Do not run `verify` during a live car session

`verify` force-stops the app to get a fresh startup log line, which drops the
host's binding and kills the car screen mid-drive. Check first:

```bash
adb shell "dumpsys activity services com.zuynew.amvideo.auto" | grep -c gearhead
```

Non-zero means a session is live — wait, or read the last readout from the log
instead of restarting the app.

## Keep in mind

- **The mark is per-install.** Every rebuild, every `gradlew installDebug`,
  every tap on an APK file resets it and the icon vanishes again.
- The install is an update with the same signing key, so app data survives.
  That matters: the store holds the session token and **logins are rate-limited
  per day**. Never uninstall to fix this.
- Not a supported path. Google can close it. For anything beyond this one phone,
  use Play Internal App Sharing — see README.md.
