# swarmwire-mobile

Android app for [swarmwire](https://github.com/tibocub/swarmwire): send and get files peer to peer, no server.
Built straight on [bare-kit](https://github.com/holepunchto/bare-kit): a small Kotlin UI around a [Bare](https://github.com/holepunchto/bare) worklet that runs swarmwire. No React Native.

> **Status: early.** Works end to end against the [swarmwire-cli](https://github.com/tibocub/swarmwire-cli)


## Support

Android 10 and above
**No** iOS support planned for now


## Features

| Screen | What it does |
| --- | --- |
| **Send** | choose a file (served in place, never copied) and show its link as text and a QR code; optionally scan a receiver's code (`swarmwire get --qr` on a computer) to hand the link over |
| **Get** | scan a link's QR code, or paste the link; downloads into app storage, then **Save to phone…** moves it where you pick (a file, or a folder for folder links) and frees the app copy |
| **Bridge** | scan a sender's QR code, then a receiver's: the receiver downloads straight from the sender, the file never goes through the phone |

While a transfer or a shared file is active, a foreground service (a notification) keeps the app running with the screen off or another app in front. "Stop sharing in the background" ends it.

Not yet: sending folders from the phone, resuming from a stored link, a release (signed) build.

Planned:
- Preview files from a link/code and validate before starting download
- Resume download
- Sync (update an already downloaded file/folder)
- Make transfer faster (currently ~5x slower on bare than node)
- Optional direct fs access (download directly to desired folder instead of downloading first in the app's storage)


## Quickstart
[Download the APK from this page's releases](https://github.com/tibocub/swarmwire-mobile/releases/latest)
for Linux/Windows/Mac/BSD, see [swarmwire-cli](https://github.com/tibocub/swarmwire-cli)


## How it fits together

```
Kotlin UI (MainActivity.kt)  <-- JSON lines over IPC -->  Bare worklet (app/src/main/js/app.js)
   picker, buttons, camera                                  swarmwire-lib + hyperswarm
```

- The worklet is bundled with `bare-pack` into `app/src/main/assets/app.bundle` during the Gradle build.
- Scanning uses [ZXing](https://github.com/journeyapps/zxing-android-embedded) (no Google Play Services needed).
- Files picked on Android have no readable path, so Kotlin hands the worklet a raw descriptor (`fd:N`) and the worklet uses `fstat`/`read` on it.


## Build

You need Node 20+, JDK 17+, the Android SDK (platform 35) and the [GitHub CLI](https://cli.github.com).

```sh
npm install   # swarmwire-lib is taken from ../swarmwire for now

# one-off: the bare-kit prebuild (not in git)
gh release download --repo holepunchto/bare-kit v2.5.5 --pattern prebuilds.zip
unzip prebuilds.zip "android/*"
mv android/bare-kit app/libs/

echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # your SDK path

./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. The first build takes a couple of minutes.
It is about 140 MB because the debug build ships unstripped bare-kit libraries for `arm64-v8a` and `x86_64`.


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

For the emulator, which has no camera, the app takes the scanned text from an intent instead:

```sh
adb shell "am start -n to.holepunch.bare.android/.MainActivity --es screen get --es scan '<link>'"
# --es screen get|send|bridge   opens that screen
# --es scan '<text>'            acts as if that QR code had just been scanned
```

On a debug build, `adb shell run-as to.holepunch.bare.android find files/incoming` lists unsaved downloads.


## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/.../MainActivity.kt` | the screens, file picker, scanner, saving |
| `app/src/main/java/.../KeepAlive.kt` | the foreground service that keeps transfers running in the background |
| `app/src/main/java/.../Worker.kt` | the worklet, spoken to in JSON lines |
| `app/src/main/java/.../Qr.kt` | QR bitmap |
| `app/src/main/js/app.js` | the worklet: swarmwire (serve, get, deliver) behind IPC |
| `app/libs/bare-kit/` | bare-kit prebuild (git-ignored, see Build) |


## License

MIT
