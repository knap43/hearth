# Hearth

A classic Android home screen: wallpaper, clock, a grid of pinned apps, a dock,
and a fuzzy-searchable app drawer. Kotlin + Jetpack Compose, no third-party
dependencies, minSdk 26 (Android 8), works on LineageOS and GrapheneOS.

## Using it

| Where | Action | Result |
|---|---|---|
| Home | swipe up | app drawer |
| Home | swipe down | notification shade |
| Home | swipe right | drawer with keyboard open |
| Home | double tap | lock screen (needs one-time Accessibility toggle) |
| Home | long-press empty space | Wallpaper · Settings |
| Home | tap the clock | alarms |
| Home | tap the weather | refresh it (first tap asks for approximate location) |
| Any icon | long-press | pin to home / dock, reorder, app info, uninstall |
| Drawer | open | most-used apps first (or A–Z, in Settings) |
| Drawer | type | fuzzy search — `gm` finds Google Maps, `тел` finds Telegram |
| Drawer | Enter | open the top result |
| Drawer | drag down at the top, Back, or Home | close |

Every gesture can be rebound in Settings to any built-in action or to any app.

### Embers

Apps you stop using cool in the drawer: first their colour drains, then they
dim, and after 2, 4 or 8 weeks (your choice) they turn to ash and gather behind
the **Ashes** chip above the grid, where a long-press lets them go.

Out of the box only launches made through Hearth count, and every app starts
warm on the day Hearth first sees it. Grant usage access in Settings and the
system's own history is folded in: apps opened from notifications stay warm,
and long-ignored apps cool from the start.

### Appearance

- **Accent** — six presets, or follow the wallpaper (8.1+) or Material You (12+).
- **Tinted icons** — apps with an Android 13 themed icon get their glyph on an
  accent plate; the rest are redrawn as an accent duotone.
- **Blur wallpaper** — blurs the wallpaper behind the drawer and settings.
  Android 12+ only, and only while the system allows window blurs (battery
  saver and some devices turn them off; the switch greys out when unavailable).

### Weather

One line under the clock — `7° · light rain` — from [Open-Meteo](https://open-meteo.com):
no account, no key. Only your coordinates, rounded to about a kilometre, leave the
phone. It refreshes when you return home, at most every 30 minutes, and hides
itself once a reading is more than three hours old. Turn it off in Settings.

## Building on Arch

Requires JDK 17 or 21 (Gradle 8.11 cannot run on newer JDKs) and the Android SDK.

```sh
sudo pacman -S jdk21-openjdk android-tools
yay -S android-sdk-cmdline-tools-latest          # or install Android Studio from the AUR

# SDK components (sdkmanager lives in /opt/android-sdk/cmdline-tools/latest/bin)
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"

cd hearth
echo "sdk.dir=/opt/android-sdk" > local.properties
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk      # fish: set -x JAVA_HOME /usr/lib/jvm/java-21-openjdk

./gradlew installRelease        # phone connected with USB debugging on
# APK alone: ./gradlew assembleRelease → app/build/outputs/apk/release/app-release.apk
```

Use the release build for everyday use. Debug builds of Compose apps run
without R8 optimisation and with debugging hooks enabled, and stutter
noticeably; `installDebug` is only worth it while developing. The first few
minutes after installing are also a little slower while Android compiles the
bundled baseline profiles.

Then press Home on the phone and choose **Hearth**, or open Settings → Apps →
Default apps → Home app.

The release build is signed with your local debug key for convenience; give it
a real signing config before sharing it.

## Layout

```
app/src/main/java/app/hearth/launcher/
  MainActivity.kt        Activity, intents, system actions
  LauncherViewModel.kt   UI state, pin/dock/reorder logic
  LockService.kt         optional accessibility service for screen lock
  data/
    AppRepository.kt     LauncherApps enumeration, icon cache, live updates
    Fuzzy.kt             search ranking
    Gestures.kt          gesture → action model
    Prefs.kt             persistence (SharedPreferences)
  ui/
    LauncherRoot.kt      wires screens and overlays together
    HomeScreen.kt        clock, pinned grid, dock, gesture detection
    AppDrawer.kt         drawer, search, pull-to-close
    SettingsScreen.kt    gesture bindings, columns, wallpaper
    StatusHeader.kt      clock · date · battery
```
