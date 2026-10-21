# qrwent

**Offline file transfers through animated QR codes.** No internet, Bluetooth, shared Wi-Fi network, account, or server is required at runtime.

qrwent sends the actual file bytes through a sequence of QR codes. The Android app can send and receive; the self-contained desktop HTML file sends to a phone camera. Project code is open source under the [MIT License](LICENSE); third-party components retain their [own licenses](THIRD_PARTY_NOTICES.md).

## Quick start

1. Build and install the Android app using the instructions below, or use a debug artifact produced by this repository's Actions workflow after it succeeds.
2. Download [`web/qrwent-PC.html`](web/qrwent-PC.html) as a raw file and open it in a current Chrome or Edge release. Opening the file needs no installation or internet.
3. Select a small file on the computer. On the phone, open **Receive → Start scanning** and grant camera permission.
4. Keep the complete QR area and its white border in view. Accept the file header on the phone, then select **Start transfer** on the computer.
5. After every chunk is collected, the phone verifies SHA-256 and saves the file in **Download/qrwent**. Stop the computer when the phone shows **File received**.

**Phone to phone:** On the sender, choose **Send → Choose file → Start sending**. Use **Receive** on the other phone.

**Downloads:** Tap a received file to open, share, or delete it. Deletion requires confirmation. Lists use 20 files per page.

## Transfer limits

- Maximum file size: **16 MiB**. One file and one receiving session at a time.
- Default: **512 bytes per chunk and 4 QR/s**. A 1 MiB file needs at least about **9 minutes** for the first pass; missed scans and QR generation add time. Start with a small text file. Large videos are impractical for this optical transfer method.
- If scanning is unreliable, use **2 QR/s** and, on the desktop, **256-byte chunks**. Changing chunk size creates a new session; reset the incomplete receiving session first.
- Chunks repeat in passes. Missing chunks can arrive later and duplicates are ignored.
- On Android, **Missing chunks** lists up to 128 ranges. Enter a list such as `1-8,15,30-42` in the desktop field and select **Apply list** to resend those chunks. Clear the field to send all chunks.
- Sessions pause after 30 minutes and can be resumed. Backgrounding pauses camera/display activity. Rotation pauses Android sending.
- Receiving progress is checkpointed to disk every 32 new chunks and on pause. Up to 31 chunks since the last checkpoint may need rescanning after abrupt process termination. Incomplete sessions expire after 24 hours.
- The desktop progress bar counts displayed chunks; there is no return channel confirming receiver completion.

CRC32 and SHA-256 check integrity. **Transfers are not encrypted or authenticated.** Anyone who can scan the screen can read the data. Received files never open or execute automatically.

## Requirements

- Android **10 / API 29 or later**, target API 35, with a supported rear camera stream no larger than 1280×960.
- Desktop: a browser supporting Blob Workers, Canvas, and the CSS used by the interface. Chrome is covered by the automated browser test; other browsers and physical Android devices need their own validation.
- The Android manifest requests **CAMERA** only. No `INTERNET` permission, analytics, advertising SDK, or background service is included.
- Build-time dependency downloads require internet; the built applications run offline.

## Build the desktop sender

Use Node.js 22 or later and npm:

```bash
cd web
npm ci
npm run build
```

This regenerates `web/qrwent-PC.html` from readable source, including JavaScript, the font, and license notices. No CDN or npm installation is needed to run the generated file. The generated HTML is included for convenient offline use; edit the source template and scripts, then rebuild it.

## Build Android

Open `android/` in Android Studio with **JDK 17**, Android SDK Platform **35**, and Build Tools **35.0.0**. The project pins **AGP 8.7.3**, **Gradle 8.9**, and **ZXing 3.5.3**.

From a terminal with `ANDROID_HOME` configured:

```bash
cd android
# Linux/macOS; using bash also works after browser uploads lose executable bits:
bash gradlew assembleDebug
# Windows:
# gradlew.bat assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. A debug APK uses a development signing key. For public releases, generate a signed release APK in Android Studio with your own permanent key. Keep that key and its passwords outside the repository.

A checked-in GitHub Actions workflow builds a debug APK, runs host protocol tests and offline browser tests, and saves build artifacts. It does not publish a release or contain a private signing key. The workflow itself has not been run on GitHub as part of this preparation.

### Optional direct SDK build

`android/build_apk.py` supports a JRE/JDK 17 build without Gradle. Supply Android platform 35, Build Tools 35.0.0, ECJ 3.39.0, and ZXing 3.5.3 JARs:

```bash
python3 android/build_apk.py --sdk /path/to/sdk --ecj /path/to/ecj.jar --zxing /path/to/zxing.jar --out /path/to/qrwent.apk
```

Without `--keystore`, the script creates and deletes a temporary local signing key. Each such build has a different signing identity. For a permanent key, use `--keystore /path/to/release.jks`, alias `qrwent`, and environment variable `QRWENT_KEYSTORE_PASSWORD`. Never commit the key or password.

The Android application ID is `org.qrwent.app`. Changing the application ID creates a separate Android installation; this source does not migrate data from differently identified apps.

## Tests

```bash
cd web
npm ci
npm run build
npm run test:protocol
npm run test:browser:install
npm run test:browser
```

The browser test uses Playwright/Chromium. `QRWENT_CHROME` can point to an installed browser executable. Screenshots and machine-readable results are written to ignored `qa/`.

To run Java host tests, first generate vectors with `npm run test:protocol`. Download the pinned ZXing and `org.json:json:20240303` test JARs into an ignored local directory, then run the helper with a JDK 17 installation:

```bash
python3 tests/run_java.py --zxing /path/to/core-3.5.3.jar --json /path/to/json-20240303.jar
```

The helper compiles the real `QrProtocol`, `ReceiveSession`, and `SenderSequence` Android source classes. Tests cover Java/JavaScript interoperability, shuffled/duplicate chunks, disk resume, malformed inputs, empty files, SHA-256 tampering, QR encoding/decoding, and a full 16 MiB transfer under a 32 MiB host JVM heap. These are not physical Android camera tests.

See [validation and limitations](docs/STABILITY.md), [protocol](docs/PROTOCOL.md), [privacy](PRIVACY.md), and [release preparation](docs/RELEASING.md).

## Repository layout

| Path | Purpose |
| --- | --- |
| `android/` | Native Java Android application and build scripts |
| `web/` | Desktop sender sources and generated offline HTML |
| `tests/` | Protocol fixtures and Java/browser test runners |
| `docs/` | Protocol, validation, publishing, and release guides |
| `licenses/` | Third-party license texts |
| `.github/` | CI and issue/pull request templates |

## Contributing and licensing

Read [CONTRIBUTING.md](CONTRIBUTING.md) for changes and [SECURITY.md](SECURITY.md) for vulnerability reports. This project does not require a hosted service, paid API, proprietary font, or closed-source runtime dependency supplied by the project. Android and browser platform implementations are separate from this repository. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for upstream sources and license scope.
