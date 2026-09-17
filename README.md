# Elitegram

Elitegram is a feature-rich, open-source Telegram client for Android focused on privacy controls, customization, and power-user functionality.

> Elitegram is an independent client and is not affiliated with or endorsed by Telegram.

## Download

- [Download the latest official release](https://github.com/SkyBotsDeveloper/Elitegram/releases/latest)
- [Visit the official website](https://skybotsdeveloper.github.io/Elitegram/)

Only install APKs published through the official Elitegram repository and links above.

## Highlights

- Advanced Ghost Mode and privacy controls
- Read-message, Seen on Reply, typing/recording, and story-view controls
- Keep Deleted Messages for eligible, locally received cloud messages
- Up to 100 account slots with distinct Java/native account mapping
- Data-center information in profiles
- Selected Audio, Voice Effects, and device-audio improvements for calls
- No Mic Blink option
- A dedicated Elitegram Features hub
- Polished light and dark theme support

Privacy-sensitive options are intentionally bounded. Elitegram does not advertise protected-content or view-once bypasses. Some call features depend on device, Android version, audio route, and Telegram call conditions.

## Installation

1. Open the [latest GitHub Release](https://github.com/SkyBotsDeveloper/Elitegram/releases/latest).
2. Download the single `Elitegram-v*.apk` asset.
3. Verify its SHA-256 value against the release notes.
4. Allow installation from your browser or file manager when Android asks, then install the APK.

Updating with a newer official APK preserves app data when the signing identity matches.

## Build from source

### Requirements

- Git with submodule support
- JDK 17
- Android Studio 2025.1.4 or a compatible command-line Android toolchain
- Android SDK 36 and Build Tools 36.0.0
- Android NDK 27.2.12479018

### Setup

```bash
git clone --recursive https://github.com/SkyBotsDeveloper/Elitegram.git
cd Elitegram
```

If submodules were not initialized:

```bash
git submodule update --init --recursive
```

Create private local configuration from the committed templates:

- `BuildVars.example.java` documents the Telegram API and provider values required by `BuildVars.java`.
- `TMessagesProj/google-services.json.example` documents the Firebase Android configuration.
- `local.properties.example` documents the Android SDK and optional release-signing settings.
- The manifest placeholder `YOUR_GOOGLE_MAPS_API_KEY` must be replaced with a package-restricted Android Maps key if map features are required.

Obtain your own Telegram `api_id` and `api_hash` from [my.telegram.org](https://my.telegram.org). Never commit production credentials or signing keys.

Build the release variant on Windows:

```powershell
.\gradlew.bat :TMessagesProj_App:assembleAfatRelease
```

On macOS or Linux, use `./gradlew` with the same task. Release signing requires your own private keystore and the values described in `local.properties.example`.

## Privacy and transparency

Elitegram is open source: its implementation can be inspected and independently audited. As a Telegram client, it communicates with Telegram infrastructure and with platform services enabled by the configured build, including push-notification services. Open source improves transparency, but it is not a guarantee that software is free from vulnerabilities.

Elitegram includes no project website analytics, tracking pixels, or cookies. See the [privacy and transparency statement](PRIVACY.md) for details.

## Official links

- Source: https://github.com/SkyBotsDeveloper/Elitegram
- Telegram channel: https://t.me/aboutelite
- Creator on Telegram: https://t.me/iflexsid
- Instagram: https://instagram.com/elite.sid

## Creator

Created and maintained by **Siddhartha Abhimanyu** ([@iflexsid](https://t.me/iflexsid), [@elite.sid](https://instagram.com/elite.sid)).

## License and attribution

Elitegram is derived from Telegram for Android and is distributed under the GNU General Public License, version 2. See [LICENSE](LICENSE). Upstream and third-party copyright, license, and source-file notices remain applicable. Project branding and statements of maintainership do not replace or diminish upstream authorship and attribution.
