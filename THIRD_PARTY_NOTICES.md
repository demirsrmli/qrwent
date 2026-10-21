# Third-party notices

The MIT License at the repository root applies to original qrwent code and documentation. It does not relicense the following upstream components. License texts and upstream copyright notices are retained in licenses/ and embedded in the generated HTML and Android asset ThirdPartyNotices.txt.

| Component | Version / scope | License | Upstream |
| --- | --- | --- | --- |
| ZXing core | 3.5.3, Android QR encoding/decoding | Apache-2.0 | https://github.com/zxing/zxing |
| qrcode | 1.5.4, browser QR generation | MIT | https://github.com/soldair/node-qrcode |
| dijkstrajs | 1.0.3, qrcode runtime dependency bundled into HTML | MIT | https://github.com/tcort/dijkstrajs |
| @noble/hashes | 1.8.0, browser SHA-256 | MIT | https://github.com/paulmillr/noble-hashes |
| Inter | 4.001, unmodified bundled variable font | SIL OFL-1.1 | https://github.com/rsms/inter |
| Gradle wrapper | 8.9 wrapper binary and launcher scripts | Apache-2.0 | https://github.com/gradle/gradle |

The Gradle wrapper launcher copyright is held by the original authors, as stated in the scripts; Gradle is developed by Gradle, Inc. and contributors. ZXing is developed by the ZXing authors. Their Apache license grants remain separate from the project MIT License.

Node build/test tooling (esbuild and Playwright), Android build tooling (AGP, SDK, and optional ECJ), and Java host-test dependency org.json:json:20240303 are fetched during development and are not included as binaries in the source upload package. The lockfile also resolves dependencies not bundled into the browser runtime. Their package licenses apply when downloaded.

The Inter font is included as an unmodified binary with its OFL license. Editable upstream font sources are linked above; no proprietary font is required. The Gradle wrapper JAR is the standard bootstrapping binary, with upstream source linked above.
