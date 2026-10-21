# Security

## Reporting vulnerabilities

Do not publish exploit details or sensitive sample files in a public issue. If the repository has GitHub private vulnerability reporting enabled, use **Security → Report a vulnerability**. If it is unavailable, open a minimal issue asking the maintainer to enable a private reporting channel; omit exploit details and secrets.

Include the affected commit/version, a synthetic reproduction, expected impact, and relevant environment information once a private channel is available. Never send a private signing key or unrelated personal files.

## Security model

qrwent transfers file bytes through visible QR codes. CRC32 and SHA-256 detect corruption; they do not authenticate the sender or encrypt the payload. Treat every sender and received file as untrusted. A file header requires acceptance and received files are not opened automatically.

The Android app requests camera permission only. The desktop sender blocks network connections through its Content Security Policy. Build tools download dependencies; that is separate from offline operation.

The repository must never contain production keystores, private keys, access tokens, credential files, device backups, or personal transfers. The ignore rules help prevent accidental additions but do not replace review.

## Supported scope

This initial source release has no published long-term support policy or guaranteed response time. Check releases and repository activity for maintenance status.
