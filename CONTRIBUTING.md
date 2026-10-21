# Contributing to qrwent

Bug fixes, documentation, accessibility work, and camera compatibility reports are welcome.

1. Open an issue describing the problem or proposed change. For vulnerabilities, follow SECURITY.md instead.
2. Fork the repository and create a branch.
3. Make focused changes in the readable Java/JavaScript/template sources. Rebuild the generated HTML when web code or bundled notices change.
4. Run the applicable protocol/browser tests and build Android if Java or resources changed.
5. Submit a pull request with a concise explanation, test results, and any device/browser limitations.

Keep the app offline at runtime. Do not add telemetry, network permissions, automatic file execution, or unbounded memory/thread queues without an explicit design discussion. Preserve the 16 MiB transfer cap unless its implications are reviewed and tested.

Do not include personal test files, access tokens, signing keys, passwords, local.properties, node_modules, build outputs, or logs containing private paths. Use synthetic fixtures for reproductions.

Changes to the QL1 wire format require interoperability tests and protocol documentation. Non-ASCII fixture names intentionally test Unicode handling; UI and documentation are English.

By submitting your contribution, you agree to license your original contribution under this repository's MIT License. Keep third-party attribution and applicable licenses intact. Be respectful and constructive in all project discussions.
