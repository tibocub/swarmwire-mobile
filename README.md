# swarmwire-mobile

Android app for [swarmwire](https://github.com/tibocub/swarmwire): send and get files peer to peer, no server.
Built straight on [bare-kit](https://github.com/holepunchto/bare-kit): a small Kotlin UI around a [Bare](https://github.com/holepunchto/bare) worklet that runs swarmwire. No React Native.

> **Status: early.** Works end to end against the [swarmwire-cli](https://github.com/tibocub/swarmwire-cli)


## Support

Android 10 and above
**No** iOS support planned for now


## Features

The main screen lists everything you are downloading or sharing, with progress, up/down speed and seeders/peers for each. The ✕ stops one (a download in app storage is deleted with it). Tap an entry to **copy its link**, **show its QR code**, **send it to a device** (scan the code `swarmwire get --qr` shows on a computer), or **save it to phone** when it sits in app storage.

Send, Get and Bridge are buttons at the bottom that open popups, which stay open so you can do several things in a row:

| Popup | What it does |
| --- | --- |
| **Send** | choose a file (served in place, never copied), optionally **private** (only people holding the link can find it), and show its link as text and a QR code; scan as many receivers' codes as you like to hand the link over |
| **Get** | scan a link's QR code, or paste the link; shows what it holds (a folder's files too) and lets you rename it, then downloads once you validate. Scan another right away |
| **Bridge** | scan a sender's QR code once, then any number of receivers' codes: each receiver downloads straight from the sender, the file never goes through the phone |
| **Stop all** | stops every download and share |

Downloads go to app storage by default, then **Save to phone…** moves them where you pick. Tick *Save straight to Downloads/swarmwire* to skip that: it needs "all files access", which Android only lets you grant in Settings (the app opens the page). A finished download is kept and shared on until you remove it.

A foreground notification keeps the app running with the screen off or another app in front, and shows the total download progress, or "Seeding" when it is only sharing. The back button leaves the app running; ✕ or Stop all ends the transfers.

Not yet: sending folders from the phone, keeping the list across app restarts (so resuming a download from a stored link), diff and sync.

Planned:
- Resume download
- Diff & Sync (update an already downloaded file/folder without re-downloading everyting)
- Maybe some other non-swarmwire QoL tools and features (send/get clipboard through QR code)


## Quickstart
[Download the APK from this page's releases](https://github.com/tibocub/swarmwire-mobile/releases/latest).

for Linux/Windows/Mac/BSD, see [swarmwire-cli](https://github.com/tibocub/swarmwire-cli).


## How it fits together

```
Kotlin UI (MainActivity.kt)  <-- JSON lines over IPC -->  Bare worklet (app/src/main/js/app.js)
  picker, buttons, camera                                     swarmwire-lib + hyperswarm
```

- The worklet is bundled with `bare-pack` into `app/src/main/assets/app.bundle` during the Gradle build.
- Scanning uses [ZXing](https://github.com/journeyapps/zxing-android-embedded) (no Google Play Services needed).
- Files picked on Android have no readable path, so Kotlin hands the worklet a raw descriptor (`fd:N`) and the worklet uses `fstat`/`read` on it.


## Build

You need Node 20+, JDK 17+, the Android SDK (platform 35) and the [GitHub CLI](https://cli.github.com).

```sh
npm install

# one-off: the bare-kit prebuild (not in git)
gh release download --repo holepunchto/bare-kit v2.5.5 --pattern prebuilds.zip
unzip prebuilds.zip "android/*"
mv android/bare-kit app/libs/

echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # your SDK path

./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. The first build takes a couple of minutes.
It is about 140 MB because the debug build ships unstripped bare-kit libraries for `arm64-v8a` and `x86_64`;
`./gradlew assembleRelease -Pabis=arm64-v8a` makes the 75 MB phone-only release (signed with the debug key unless a keystore is configured, see Releasing).


## Releasing

`package.json` holds the version; the app's version name and code follow it. To release:

1. Edit `"version"` in `package.json` (and keep to plain `major.minor.patch`), commit and push to `main`.
2. The *Build and release* workflow builds a signed APK and publishes it as release `v<version>` with generated notes. A push that does not change the version only builds.

**One-off setup** (the signing key, which must stay the same for updates to install over older versions; back up `~/swarmwire-release.jks` and its password):

```sh
keytool -genkeypair -keystore ~/swarmwire-release.jks -alias swarmwire -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 ~/swarmwire-release.jks | gh secret set KEYSTORE_BASE64 --repo tibocub/swarmwire-mobile
gh secret set KEYSTORE_PASSWORD --repo tibocub/swarmwire-mobile        # paste the password; use it for the key too
```


## Run

**On your phone:** enable *Developer options* → *USB debugging*, plug it in, then:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n to.holepunch.bare.android/.MainActivity
```

Or copy the APK to the phone and open it (allow installs from unknown sources).

**On the emulator** (needs Android 10+, the app is "swarmwire" in the app drawer):

```sh
~/Android/Sdk/emulator/emulator -avd <your_avd> -no-audio -no-snapshot -cores 2   # opens a window
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n to.holepunch.bare.android/.MainActivity
adb emu kill            # when done: shuts the emulator down properly
```

Add `-no-window -gpu swiftshader_indirect` for a headless run (no window, less CPU). First boot takes a few minutes.

**Try a transfer** (computer and phone on any network; no camera needed for the first two):

```sh
# Get: serve something on your computer, then paste the link in the app's Get screen
npx swarmwire-cli send ./some-file

# Send: pick a file in the app's Send screen, then on your computer
npx swarmwire-cli get "<the link shown in the app>"
npx swarmwire-cli get --qr          # or: show a code, scan it with Send's "Scan a receiver's code"
```

For the emulator, which has no camera, the app takes the scanned text from an intent instead (quote the link: `&` ends an adb shell command):

```sh
adb shell "am start -n to.holepunch.bare.android/.MainActivity --es screen get --es scan '<link>'"
# --es screen get|send|bridge   opens that popup
# --es scan '<text>'            acts as if that QR code had just been scanned (Get then waits for you to tap Download)
```

On a debug build, `adb shell run-as to.holepunch.bare.android find files/incoming` lists downloads still in app storage.


## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/.../MainActivity.kt` | the list, the popups, file picker, scanner, saving |
| `app/src/main/java/.../KeepAlive.kt` | the foreground service that keeps transfers running in the background |
| `app/src/main/java/.../Worker.kt` | the worklet, spoken to in JSON lines |
| `app/src/main/java/.../Qr.kt` | QR bitmap |
| `app/src/main/js/app.js` | the worklet: the table of transfers (inspect, get, serve, deliver, stop) behind IPC |
| `.github/workflows/release.yml` | builds every push, publishes a release for a new version |
| `app/libs/bare-kit/` | bare-kit prebuild (git-ignored, see Build) |


## License

MIT
