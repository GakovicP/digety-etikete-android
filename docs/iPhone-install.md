# NetPlanet link on your own iPhone (free Apple ID)

No App Store, no paid Apple Developer membership. A free Apple ID gives you a
**Personal Team**, which can sign the app for your own devices.

**Personal Team limits (set by Apple, not by this project):**

- The installed app works for **7 days**. After that it won't open: just install it again
  (Run from Xcode, or re-sign with Sideloadly). Your data in the app stays.
- At most 3 sideloaded apps per device, and 10 new App IDs per week.
- A Personal Team **cannot export a signed .ipa** (Archive → Distribute App needs the paid
  membership). That's why there are two routes below.

## What the project is

| Part | Technology |
|---|---|
| Shared app (screens, Digety API, login, history, reports) | Kotlin Multiplatform 2.0.21 + Compose Multiplatform 1.7.0 (`composeApp/src/commonMain`) |
| iOS bridge | Kotlin/Native (`composeApp/src/iosMain`) → static `ComposeApp.framework` |
| iOS host app | Swift/SwiftUI (`iosApp/iosApp`): camera scanner (AVFoundation), GPS (CoreLocation), photos, sharing |
| Xcode project | Generated from `iosApp/project.yml` with XcodeGen (the `.xcodeproj` is not in git) |
| Libraries | Ktor 3.0.1 (Darwin engine), kotlinx serialization/coroutines/datetime, Coil 3 |

It is not React Native or Flutter. It uses no paid capabilities (no push, iCloud, App Groups,
Keychain sharing), only camera, location and photo library permissions, so a Personal Team
can sign it. Minimum iOS: 15.0. All network calls are HTTPS, so no ATS exceptions are needed.

---

## Route A: Xcode on your Mac (recommended)

### What you need once

1. A Mac with **Xcode 16** (Mac App Store). Open it once and let it install components.
2. **Homebrew** (https://brew.sh).
3. In Xcode: **Xcode → Settings → Accounts → +** → Apple ID → sign in with your normal Apple ID.
   A team called "*Your Name* (Personal Team)" appears.
4. iPhone: connect it with a cable, tap **Trust This Computer**.
   Then turn on **Settings → Privacy & Security → Developer Mode** (iOS 16+). The phone restarts.
   If you don't see Developer Mode, it appears after Xcode first sees the phone (step 3 below).

### Build and install

```bash
git clone https://github.com/GakovicP/digety-etikete-android.git
cd digety-etikete-android
./setup-iphone.sh
```

`setup-iphone.sh` installs what's missing (XcodeGen, JDK 17, Android command-line tools,
which Gradle needs because the same project also builds the Android app), finds your
Team ID if Xcode already made a signing certificate, generates `iosApp/iosApp.xcodeproj`
and opens it.

In Xcode:

1. Top bar: scheme **iosApp**, destination **your iPhone** (not a simulator).
2. Click the **iosApp** project → target **iosApp** → **Signing & Capabilities**.
   Check **Automatically manage signing** is on and **Team** = *Your Name (Personal Team)*.
   - If it says the bundle id `com.gakovic.etikete` is not available, open
     `iosApp/Configs/Local.xcconfig` and add a unique one, e.g.
     `PRODUCT_BUNDLE_IDENTIFIER = com.pavle.netplanetlink`, then run `./setup-iphone.sh` again.
3. **Product → Run** (⌘R). The first build compiles the Kotlin code for the iPhone and takes
   5 to 10 minutes; later builds are faster.
4. First launch on the phone shows "Untrusted Developer". On the iPhone go to
   **Settings → General → VPN & Device Management → Apple Development: *your Apple ID* → Trust**.
   Then open NetPlanet link.

To remember your team permanently (the project is regenerated from `project.yml`, which would
reset a team picked only in the Xcode UI), run once with your Team ID:
`./setup-iphone.sh AB12CD34EF`. The Team ID is in Xcode → target → Build Settings →
"Development Team", or Apple's developer site → Membership.

**Every 7 days**, plug in the phone and press Run again.

### Files involved

| File | What it does |
|---|---|
| `setup-iphone.sh` | One-time setup + regenerate the Xcode project |
| `iosApp/project.yml` | XcodeGen project; includes the **Build Kotlin framework** build phase |
| `iosApp/Configs/App.xcconfig` | Signing (automatic), bundle id, Kotlin framework path |
| `iosApp/Configs/Local.xcconfig` | *Yours, not in git*: `DEVELOPMENT_TEAM`, optional bundle id |
| `gradlew`, `gradle/wrapper/` | Gradle 8.9, downloaded automatically |
| `composeApp/build/xcode-frameworks/<Debug/Release>/<iphoneos/iphonesimulator>/ComposeApp.framework` | Kotlin framework built by the build phase |
| `~/Library/Developer/Xcode/DerivedData/iosApp-*/Build/Products/Debug-iphoneos/NetPlanetLink.app` | The built app (Xcode installs it for you) |

---

## Route B: ready-made .ipa + Sideloadly (no Xcode builds; Mac or Windows)

Every build on `main` publishes on the GitHub **Releases** page:

- `NetPlanet-link-1.1.N.apk`: Android
- `NetPlanet-link-1.1.N-iOS-simulator.zip`: iPhone simulator / Appetize.io
- `NetPlanet-link-1.1.N-iPhone-unsigned.ipa`: **real iPhone**, unsigned

The unsigned .ipa can't be installed as-is. A sideloading tool signs it with your free Apple ID:

1. Install **Sideloadly** (https://sideloadly.io) on your Mac or Windows PC
   (on Windows it also needs iTunes and iCloud from Apple's website, not the Microsoft Store).
2. Connect the iPhone by cable and trust the computer. Turn on Developer Mode (as above).
3. Drag `NetPlanet-link-1.1.N-iPhone-unsigned.ipa` into Sideloadly, enter your Apple ID, click **Start**.
4. On the iPhone: **Settings → General → VPN & Device Management → your Apple ID → Trust**.
5. Repeat every 7 days (Sideloadly can refresh automatically while the phone is on the same Wi-Fi).

AltStore (https://altstore.io) works the same way.

---

## Troubleshooting

| Message | Fix |
|---|---|
| `error: JDK 17 not found` | `brew install --cask temurin@17` |
| `error: Android SDK not found` / `SDK location not found` | `brew install --cask android-commandlinetools`, or install Android Studio |
| `No such module 'ComposeApp'` | The Build Kotlin framework phase failed; read the first error above it in the build log (Report navigator, ⌘9) |
| "Failed to register bundle identifier" / "not available" | Set a unique `PRODUCT_BUNDLE_IDENTIFIER` in `Local.xcconfig` |
| "Signing requires a development team" | Pick your Personal Team in Signing & Capabilities, or `./setup-iphone.sh <TEAM_ID>` |
| "Developer Mode disabled" | iPhone → Settings → Privacy & Security → Developer Mode → On |
| "Untrusted Developer" | iPhone → Settings → General → VPN & Device Management → Trust |
| App stops opening after a week | Normal for a free account: Run from Xcode (or Sideloadly) again |
| "maximum number of apps for free development profiles" | Delete another sideloaded app from the phone |
