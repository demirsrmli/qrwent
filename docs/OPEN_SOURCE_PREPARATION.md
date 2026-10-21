# Open-source preparation record

Date: 2026-10-21

## Changes

- Renamed application branding, Android label, Java package and application ID, browser globals, filenames, download paths, environment-variable names, signing aliases, and project metadata to qrwent.
- Android identity: org.qrwent.app. Received files: Download/qrwent.
- Translated Android/desktop interface text, error messages, accessibility descriptions, and documentation into English. Escaped Unicode fixture names intentionally retain Unicode test coverage.
- Added an MIT License for original project code and documentation. Preserved third-party copyright and license notices, with additional dijkstrajs and Gradle wrapper notices. Embedded complete notices in both client distributions.
- Added contributor, security, privacy, release, and browser-upload guides; issue/PR templates; ignore/line-ending/editor configuration; and GitHub Actions build/test configuration.
- Kept the existing QL1 wire protocol marker to preserve byte-level interoperability. It is not an application name.

## Publication cleanup

Removed the old prebuilt APK, duplicate distribution HTML, obsolete artifact hashes, generated browser/protocol result files, generated vector fixtures, and handoff-specific/device-specific claims. The current generated desktop HTML is rebuilt from the included readable sources.

No private signing keystore, private key, account token, credential file, personal transfer, or local SDK configuration was found in the supplied source archive. The release upload package excludes installed dependencies, SDK/compiler downloads, build directories, local test outputs, temporary signing material, and compiled APKs. Signing environment-variable names in build scripts are configuration references, not embedded passwords.

A pattern scan covers private-key headers, common access-token formats, cloud-key formats, local workspace paths, old branding, and Turkish UI text. An apparent cloud-key pattern inside the Base64-embedded font was checked as font data, not a credential. Third-party author attribution remains intact. This is a focused preparation review, not a certification that no future vulnerability can exist.

The separate qrwent.apk deliverable is newly compiled from this source with a temporary local test signing identity. It is not a permanently signed production release. Its private key is deleted after signing and is not included. Use your own permanent key for future public releases and updates.

## Validation performed

- JavaScript vectors/range validation: passed.
- Java host protocol suite using ECJ 3.39.0, JRE 17, ZXing 3.5.3, org.json 20240303, and a 32 MiB heap: passed with 65,805 assertions and 3,000 malformed-input exercises. Includes Java/JavaScript interoperability, out-of-order/duplicate handling, resume, tamper rejection, empty files, QR encode/decode, and a full 16 MiB disk-backed transfer. The assertion count depends on the generated fixtures and inclusion of browser-generated frames.
- Direct Android SDK 35 / Build Tools 35.0.0 / D8 compilation: passed.
- APK signing verification: passed; APK alignment and manifest identity/permissions inspected.
- APK application label: qrwent; application ID: org.qrwent.app; minimum API 29; target API 35; only CAMERA permission requested.
- Generated HTML rebuilt with pinned npm dependencies; no original branding remains in the prepared source tree.

- Offline browser suite: passed using Playwright 1.61.1 with Chrome Headless Shell 136.0.7103.25. The standard Chromium download/launch route was restricted in this environment, so the supplied QRWENT_CHROME executable override was used.
- Browser checks: byte-exact 8,193-byte transfer; SHA-256; missing ranges; rapid pause/resume/reset; empty-file acceptance; 16 MiB limit; widths 320/390/736/1280; light/dark; zero HTTP requests and zero runtime/CSP errors.
- Browser canvas QR decoded and browser-generated frames reassembled by the actual Java receiver: passed as part of the final 65,805-assertion suite.
- Final source package: 57 regular files, one upload ZIP, no file larger than 25 MiB, no original branding, no Turkish UI strings, no signing keys or installed dependencies. GitHub Actions, the Gradle build path, Android emulator behavior, and physical Android camera/lifecycle/performance tests have not been run in this preparation.
