# Uploading this project to GitHub

The delivered upload archive contains 57 repository files (below the 99-file limit). All project text and interfaces are English.

1. Extract the upload ZIP on your computer. GitHub does not unpack ZIP files placed in a repository.
2. Create an empty repository named **qrwent**. Choose Public if you want to publish the source. Do not add an automatically generated README, license, or .gitignore; this package includes them.
3. Use **Add file → Upload files**. Drag the contents of the extracted folder into the repository root. Keep directories intact. Do not drag the outer folder itself or upload the ZIP as the only source file.
4. Include the .github directory and the .gitignore, .gitattributes, and .editorconfig files. Enable hidden-file display if your file manager hides them. Dotfiles are intentional project configuration.
5. Check that README.md, LICENSE, android/, web/, tests/, docs/, licenses/, and .github/ are at the root. Commit the upload.
6. Enable Actions if prompted. Read the workflow result before treating CI as passing. A successful run provides a debug APK and desktop sender artifact; it does not publish a release.
7. Under repository settings, enable private vulnerability reporting if available. Add the description below and suitable topics.

Suggested description:

> Offline file transfers through animated QR codes. Native Android sender/receiver and a self-contained desktop HTML sender. No internet, Bluetooth, or shared Wi-Fi required.

Suggested topics: android, offline, qr-code, file-transfer, java, javascript, privacy, open-source.

Before publishing, review the MIT License and dependency notices. Production signing keys, credentials, local SDK paths, node_modules, generated test outputs, and obsolete binaries are excluded from this source package. No GitHub account or token is embedded.

If browser upload becomes inconvenient, an alternative is GitHub Desktop: create/clone an empty repository, copy this complete tree into it, commit, and publish. Running Gradle through bash avoids depending on executable bits lost during browser upload.
