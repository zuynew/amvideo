# АМВИДЕО Авто

Opens your gate or front door (КПУ) from the car, through Android Auto.

*[Русская версия](README.ru.md)*

AM Video ships a phone app, but reaching for the phone at a barrier is the
problem. This is an Android Auto app in Google's `IOT` category — the category
whose documented example is, word for word, opening a garage door — so the gate
sits on the car's screen as a tile you tap once.

There is a small phone app too. It exists because Android Auto will not show a
sign-in form while the car can move, so the account has to be set up on the
phone; it opens doors as well, which is useful on foot.

> **Unofficial.** This project is not affiliated with, endorsed by, or supported
> by AM Video. It talks to their service the same way their own web client does,
> using your own account. You are responsible for your own account and for
> whatever terms apply to it. It opens physical barriers — read
> [Worth knowing](#worth-knowing) before you install it.

## How it talks to AM Video

There is no public API. The contract below was read off the vendor's own web
client (`https://lk.amvideo-msk.ru/app/js/app.js`), which is a Cordova wrapper
around a single PHP endpoint:

    POST https://lk.amvideo-msk.ru/api/api4.php      (form-encoded, JSON back)

| `type`   | fields sent                                      | answer |
|----------|--------------------------------------------------|--------|
| `login`  | `login` (11 digits), `password`, `device_key`, `pin` | `result`, `sid`, `str`, `change_device` |
| `pin`    | `login`, `pin_type` (`device`), `sms` (`0`, then `1`) | `str` |
| `panels` | `sid`                                            | `result`, `data[]` of `{id_shlag, adress, relay, gps, cam}` |
| `open`   | `sid`, `id_shlag`, `relay`                       | `str`, sometimes `result` |

Three things about this endpoint shape the app:

- **`device_key` must never change.** It is a UUID the client invents once. The
  server ties the account to it and demands a fresh PIN whenever it sees a new
  one, so it is generated at first launch and kept for good — it even survives
  signing out.
- **A PIN is never sent on its own.** A login that comes back `change_device`
  only says a PIN is needed; the client has to ask for one with `type=pin`.
  The first delivery can be a voice call, and asking again with `sms=1` forces
  an SMS. The vendor allows one request every three minutes, and the app keeps
  the same wait.
- **There are no status codes.** An expired session comes back as nothing more
  than `result:false` with the message `нет доступа!`, so that text is what the
  app matches on to decide a session needs renewing.
- **Fields change type.** The same column arrives as `1`, `"1"` or `true`
  depending on which branch of the PHP answered, so every flag goes through a
  lenient reader and panel rows are mapped from raw JSON rather than typed up
  front. A type change on the server should not crash the app at the gate.

## How it is put together

One Gradle module, three packages, and one object that matters.

    data/      AmVideoApi (seam) + HttpAmVideoApi (OkHttp), KeystoreStore,
               DoorRepository, Door, ApiModels, Proximity, Geo
    car/       DoorCarAppService, DoorSession, DoorScreen
    phone/     MainActivity, MainViewModel, SignInScreen, DoorsScreen
    proximity/ DoorProximityService, ProximityNotifier, OpenDoorReceiver,
               BootReceiver
    ui/        Notice — one wording of a result, shared by both screens
    diag/      InstallSource — reports how this copy was installed

`DoorRepository` is the only place that knows how to open a door, and the phone
and the car share a single instance of it. That is the whole point of the
design: it is what makes these three behaviours hold no matter which screen the
driver last touched.

- **A 20-second cooldown**, matching the vendor client's own button lockout, so
  the car cannot hammer the gate. A request that never reached the server does
  not start it.
- **Silent session renewal.** When the server says the session is gone, the
  repository signs in again with the stored credentials and retries the call
  once. Without this, a car that has sat for a week would show an empty grid
  until the driver picked up the phone — which is exactly the moment this app is
  supposed to save.
- **A cached door list**, so a tile is tappable the instant Android Auto
  connects instead of after a network round trip.

Results are returned as named outcomes rather than UI text, so each screen words
things itself — except messages the server already wrote in Russian, which are
passed through untouched.

### Where the password lives

Renewing a session silently means keeping the password, so it is encrypted with
an AES-256-GCM key held in the Android keystore, in app-private preferences.
This is done by hand because Jetpack Security's `EncryptedSharedPreferences` was
deprecated in favour of plain, unencrypted preferences, which is not a
reasonable place for the password to your front door.

The keystore key cannot leave the device, so `allowBackup="false"` is set rather
than let the app carry ciphertext it will never be able to read to a new phone.
A value that fails to decrypt is treated as absent: that costs one sign-in
instead of leaving the app wedged.

## Build

Needs JDK 17+ and the Android SDK with platform 37 and build-tools 37. Point
`local.properties` at your SDK (`sdk.dir=/path/to/android-sdk`), or set
`ANDROID_HOME`; Android Studio writes `local.properties` for you on first open.

There are two product flavours, because Android Auto and Android Automotive OS
need different host libraries that cannot ship in one APK. `mobile` is the
Android Auto one and is almost certainly what you want.

```bash
./gradlew :app:assembleMobileDebug
```

The APK lands at `app/build/outputs/apk/mobile/debug/app-mobile-debug.apk`.

```bash
./gradlew :app:testMobileDebugUnitTest :app:lintMobileDebug
```

65 unit tests cover the repository's cooldown, session renewal and caching, the
proximity maths, the lenient JSON reading and the install-provenance rule. They
run on the JVM against a fake API and a fake store, so no device or network is
needed. Lint runs with `warningsAsErrors`.

## Install it on the phone

```bash
adb install -r app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
```

This is enough for the phone app. It is **not** enough to reach a car — see
[Getting it onto the car screen](#getting-it-onto-the-car-screen), and note that
a plain `adb install` actively undoes it.

Open **АМВИДЕО Авто** on the phone and sign in with the phone number and
password you use for the AM Video app. The first sign-in on a new device will
ask for a PIN: a **Запросить ПИН** button and a field appear when the server
says the device is new. Tap the button to have the code sent — nothing arrives
until you do — then type it in and sign in.

The phone app works fully on its own — it lists your КПУ and opens them.

## Getting it onto the car screen

**A sideloaded build will not appear in a real car.** Android Auto has a
developer option called *Unknown sources*, but
[Google's testing docs](https://developer.android.com/training/cars/testing) are
explicit that it

> applies to media, messaging notifications, and parked apps but doesn't apply
> to apps built using the Android for Cars App Library.

and that "to test your app in real vehicles, you must install it from a trusted
source such as Google Play". This app is a Car App Library app, so that toggle
is not the answer and no manifest flag is either.

This is why the app can show nothing on a real head unit even though the same
build renders correctly in the Automotive emulator and the DHU. The
manifest is not at fault; the installer is. Which is also the way out — what
the host rejects is the *install record*, and that can be changed two ways.

### Free route: a Play-attributed local install

Android Auto reads **two** fields and needs both:

| field | must be | set by |
| --- | --- | --- |
| `initiatingPackageName` | the system package installer, or Play | the system, from the real caller |
| `installerPackageName` | `com.android.vending` | the installer, from an intent extra |

`adb install -i com.android.vending` sets only the second. The first is filled
in by the system and cannot be forged, so a sideload is always
`com.android.shell`. This was confirmed in a real car: with the installing
package matching that of apps genuinely installed from Play, the launcher still
enumerated every other car app on the phone and ours was not among them.

The way through is to let the **system installer** perform the install, so it
becomes the initiator, while the **shell** launches it — some Android 17 builds
drop the Play name when an app launches it — with the attribution passed as
extras.

#### You need UnknownAuto

The system installer will only install from a `content://` URI it is allowed to
read, and `adb` cannot serve one: a `file://` path in `/sdcard` is not readable
by the installer, and nothing on the shell side can grant it. That one gap is
why a second app is involved.

[UnknownAuto](https://github.com/GitMag/UnknownAuto) fills it. It stages an APK
behind its own content provider and grants the system installer read access —
and that is the whole of its role here. It does **not** perform the install in
this flow; the shell does, which is what keeps the Play attribution on this
phone.

Install it once, from its [releases](https://github.com/GitMag/UnknownAuto/releases):

```bash
adb install -r UnknownAuto-1.3.apk
```

It needs permission to install packages, which you can grant from the shell
instead of hunting through Settings:

```bash
adb shell appops set com.mag.unknownauto REQUEST_INSTALL_PACKAGES allow
```

Revoke it later with `deny` in place of `allow`.

It is third-party software that asks for the right to install apps, so it is
worth a look before you trust it. Its source is three Java files, its release
APK declares four permissions — install packages, query packages, the Shizuku
API and internet — and the only hosts in its binary are Shizuku's GitHub
releases, a Ko-fi link and a Play Store URL. No telemetry. That is consistent
with what it claims, though it is self-signed with no reproducible build, so the
binary cannot be proven to match the source.

Its README offers a Shizuku-based path as well. **On Android 17 you do not need
Shizuku** — that route is for Android 14 and older. There the mark
survives only when the shell launches the installer, which `adb` does directly.

#### Running the install

```bash
./tools/install-for-car.sh
```

The script pushes the APK, waits while you load it in UnknownAuto, launches the
system installer from the shell, and prints the verdict; `push`, `install` and
`verify` run the phases separately. Two steps happen on the phone:

1. In **UnknownAuto**, tap **Load APK…** and pick `Download/amvideo-auto.apk`.
   **Do not tap UnknownAuto's own Install button** — that makes the app the
   launcher, and the phone then records
   `installer=com.google.android.packageinstaller` and the icon stays missing.
   Wait for *Ready* and let the script continue.
2. Tap **Install** in the system dialog the script raises.

The script refuses to push an APK older than the sources, so a stale build
cannot cost you the whole tap-through.

Claude Code has the same thing as a skill — `/install-for-car` — which carries
the reasoning above so it does not regress to `adb install`. Verified on
Android 17:

```
initiating=com.google.android.packageinstaller installing=com.android.vending androidAutoWouldAccept=true
```

**The mark is per-install.** Every rebuild resets it, so this is the install
command from now on — a plain `adb install` puts the app back out of the car.

Because neither field is readable from a shell (`dumpsys package` prints only
the installing one and `cmd package` has no install-source subcommand), the app
reports them itself at startup:

```bash
adb logcat -d -s AmVideoInstall
```

### Paid route: Play, for distribution

Google's own page names the two ways around Play review:

> You can use Internal App Sharing or an Internal Test Track to distribute your
> app to devices without going through the Google Play review process.

Both need a Play developer account (one-time $25), and they are the only route
that works for anyone other than you. **Internal App Sharing** is the lighter
one — no store listing, no review, no tester list.

### Internal App Sharing, start to finish

The signed artifacts are built and ready:

```bash
./gradlew :app:assembleMobileRelease :app:bundleMobileRelease
```

They land in `app/build/outputs/apk/mobile/release/` and
`app/build/outputs/bundle/mobileRelease/`; copies of the current pair sit in the
project root as `amvideo-auto-release.apk` and `amvideo-auto-release.aab`. Then:

1. Create a Play developer account at
   [play.google.com/console](https://play.google.com/console) ($25, one-time).
2. In the Play Console, open **Internal app sharing** and upload
   `amvideo-auto-release.aab`. No listing, no review — you get a share link.
3. On the phone, turn on internal app sharing in the **Play Store** app:
   **Settings → About → Play Store version**, tap it seven times, then
   **Settings → General → Internal app sharing** on.
4. Uninstall the sideloaded build first. It is signed with a different key, so
   the Play install will refuse to replace it.
5. Open the share link on the phone and install. The installer is now the Play
   Store, which is what Android Auto checks.
6. Plug into the car. If the icon is still missing, check the app is ticked in
   **Android Auto settings → Customize launcher** on the phone.

### Signing

`keystore.properties` and `amvideo-upload.jks` hold the upload key. Both are
gitignored and neither is in the repo. **Back them up together** — losing both
means no further updates under the same Play listing. The key is RSA-4096 and
valid to 2054.

If `keystore.properties` is missing, no release signing config is created and
release builds come out unsigned; debug builds and tests are unaffected.

## Try it in the Desktop Head Unit

The DHU lives at `$ANDROID_HOME/extras/google/auto/`; install it from the SDK
Manager as *Android Auto Desktop Head Unit emulator* if it is not there.

First enable Android Auto developer mode on the phone: open the **Android Auto**
settings, scroll to **Version and permission info** and tap it ten times, then
confirm. Developer options appear in the **⋮** menu — choose **Start head unit
server** there.

Then, with the phone connected by USB:

```bash
adb forward tcp:5277 tcp:5277
```

```bash
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

The car screen opens on the desktop, with the app in its launcher.

## Worth knowing

- **Arrival watching is off until you turn it on.** The switch — *Открывать КПУ
  при подъезде* — is the first card on the phone's door list. Before location
  and notification permissions are granted, a card asking for them stands in its
  place instead; the switch appears once both are in. Once on, the watcher is
  armed at boot and follows the car connection itself — you do not have to open
  the app first.
  It holds a standing notification for as long as it is armed, which is the price
  of being ready without being asked; the same switch turns it off.
- **`open` actuates a physical barrier, so nothing automated calls it.** The
  unit tests run against a fake API and the diagnostics stick to reads. The
  endpoint works — it is what the app does all day — it is simply not something
  to fire off to check a change. The door list, its `latitude`/`longitude`
  columns and `shlag_adress` were read back from a live account, which is where
  the geofence gets its coordinates.
- **The release build is signed with the upload key**, not the debug key. The two
  cannot replace each other on a phone — uninstall before switching between them.
- **One tap opens the gate.** That was deliberate — a confirmation step is a
  second interaction while driving — but it does mean a stray tap on the car
  screen opens your door. The 20-second cooldown is the only guard.
- **More doors than the head unit will show are not reachable from the car.**
  Both the grid and the list are capped by the host's own content limit, which
  is around six while driving. With a long list the phone can still open any of
  them; paging the car screen would be the fix if it ever matters.
- **Panel rows carry a `cam` field the app ignores.** Showing the gate camera
  would build on data the server already sends. The `gps` and `latitude`/
  `longitude` columns are used — they are where the arrival geofence gets its
  coordinates, with no map SDK involved.

## Licence

[MIT](LICENSE).

The API contract documented above was read from the vendor's own publicly served
web client. Nothing here circumvents authentication: every call needs your own
account's credentials or session.
