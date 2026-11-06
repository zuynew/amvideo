# Working on this repo

An Android Auto app that opens physical barriers. Read [README.en.md](README.en.md)
for what it is and how it talks to the vendor. This file is the things that are
easy to get wrong.

## This app opens real gates

`type=open` actuates hardware at a real address. **Never call it against a live
account to test something** — not from a script, not from a debugger, not "just
once to check". `panels`, `login` and `pin` are reads or account operations;
`open` is not. `pin` sends the account holder an SMS or a voice call, so it is
not free either.

The unit tests never touch the network. Keep it that way: if a change needs a
new API behaviour proven, add a case to the fake in
`app/src/test/.../data/Fakes.kt`.

## Build and test

Two product flavours, because Android Auto (`app-projected`) and Android
Automotive OS (`app-automotive`) need host libraries that cannot coexist in one
APK. `mobile` is the Android Auto one and is what you almost always want.
Unflavoured task names like `assembleDebug` build both and are rarely what you
mean.

```bash
./gradlew :app:assembleMobileDebug
./gradlew :app:testMobileDebugUnitTest :app:lintMobileDebug
```

- **Never pass `--no-daemon`.** It turns a 2-second build into 25 seconds of JVM
  startup. Repeated parallel invocations also block on the daemon lock and can
  sit for tens of minutes.
- Lint runs with `warningsAsErrors`. That includes `AndroidGradlePluginVersion`,
  so a new AGP release breaks the build until the version in
  `gradle/libs.versions.toml` is bumped.
- AGP 9 has **built-in Kotlin**. Do not add the `org.jetbrains.kotlin.android`
  plugin or a `jvmTarget` block; both are rejected.
- `minSdk` is 26 for `mobile`, 29 for `automotive` (the automotive library's
  floor).

## Installing so the car can see it

**`adb install` does not work here and the only symptom is a missing icon.**
Android Auto refuses a Car App Library app unless two fields say it came from
Play, and one of them is set by the system from the real caller, so a sideload
can never forge it.

```bash
./tools/install-for-car.sh
```

This needs [UnknownAuto](https://github.com/GitMag/UnknownAuto) installed on the
phone with `REQUEST_INSTALL_PACKAGES` granted — it stages the APK behind a
`content://` URI the system installer can read, which `adb` cannot do. Setup is
in [README.en.md](README.en.md#you-need-unknownauto). Two steps need a human at the
phone, so this cannot run unattended.

The full reasoning, the failure modes and what each wrong field means are in
[.claude/skills/install-for-car/SKILL.md](.claude/skills/install-for-car/SKILL.md).
Read it before touching installation. The mark is per-install, so every rebuild
needs this again.

## Invariants worth not breaking

- **One `DoorRepository`.** The phone UI, the car screen and the notification
  action all share the instance held by `OpenerApp`. That shared instance is
  what makes the 20-second cooldown and the silent session renewal hold however
  the driver got there. Constructing a second one quietly breaks both.
- **A proximity failure must never take down the app.** Location, the car
  connection and the notifier are all best-effort: callbacks are wrapped in
  `runCatching`, and a dead watcher still leaves a working gate button. Opening
  the gate is the feature; knowing you arrived is a convenience.
- **Keystore writes are best-effort too.** A keystore that refuses to encrypt
  costs the next launch a sign-in; it must not throw at the gate.
- **Results are named outcomes, not strings.** `SignInResult`, `OpenResult` and
  friends are worded by each screen through `ui/Notice.kt`. Server messages are
  already Russian and are passed through untouched; everything else is a string
  resource.

## Conventions

- Comments explain **why**, not what. The codebase leans on them for decisions
  that look arbitrary without the reason — most were paid for in a real car.
- Test names are backtick sentences describing the behaviour:
  `` `an adb sideload is rejected even when it claims Play installed it` ``.
- Tests are JVM-only, against a fake API and a fake store. No device, no
  network, no Robolectric.
- UI strings are Russian and live in `res/values/strings.xml`.

## Never commit

`keystore.properties`, `*.jks`, `local.properties`, `*.apk`, `*.aab`. All are
gitignored; verify with `git check-ignore` rather than by reading patterns.

Do not put credentials, session tokens or real addresses into source, tests or
commit messages. The fixtures in `ApiModelsTest.kt` are deliberately synthetic —
they keep the server's structural quirks and invent every identifying value.
