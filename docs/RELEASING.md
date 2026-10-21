# Releasing qrwent

1. Review README.md, docs/STABILITY.md, third-party notices, and any open security reports.
2. Run the protocol/browser tests and a clean Android build. Test receive/send, permissions, rotation, backgrounding, process death/resume, storage exhaustion, and thermals on physical devices.
3. Update versionCode and versionName in android/app/build.gradle and the corresponding direct SDK build version arguments, web/package.json, the HTML footer, and CHANGELOG.md. Regenerate package-lock.json if the package version changes.
4. Rebuild web/qrwent-PC.html from the committed sources. Keep the dependency notices embedded.
5. Build a release APK with a permanent private signing key kept outside the repository. Back it up securely; preserve the application ID and signing identity for future updates. Do not distribute a temporary-key build as a production update.
6. Verify APK signature, alignment, manifest permissions, application ID, app label, language, and installed behavior. Generate SHA-256 checksums for final artifacts.
7. Create a tag and GitHub release manually. Attach qrwent.apk, qrwent-PC.html, and checksums as release assets, with license notices as applicable. GitHub generates source archives from the tag automatically.

The workflow's Android artifact is a **debug build** for testing, not a permanently signed production release. CI does not publish releases or handle a production signing key.
