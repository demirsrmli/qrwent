# qrwent validation and resource limits

Preparation date: 2026-10-21. This document describes the current source package. Earlier handoff reports and generated results were removed rather than presented as fresh tests.

## Implemented bounds

| Area | Implementation |
| --- | --- |
| File size | Maximum 16 MiB; one receiving session and one sending file |
| File memory | Disk-backed transfers; 64 KiB hash/read/copy buffers; chunks at most 768 bytes; received-chunk BitSet at most 8 KiB |
| Camera | Streams no larger than 1280×960; ImageReader with at most two buffered images; one grayscale buffer and one QR analysis job |
| QR rendering | Native sender uses a 600×600 RGB_565 bitmap and a temporary pixel array; frames are generated one at a time |
| Analysis rate | At least 120 ms between completed camera analyses; actual rate depends on processing time |
| Threads | One camera HandlerThread and one IO worker; IO queue limited to four pending jobs; atomic gates limit duplicate heavy work |
| Main thread | Interface updates and bitmap display; file IO, hashes, parsing, persistence, and QR generation run in workers |
| Timeouts | File preparation/hash/copy: 30 seconds; camera open: 8 seconds; active scan/display session: 30 minutes |
| Lifecycle | onPause stops scanning/display and cancels file-provider preparation; no service, boot receiver, or network worker |
| Resources | Severe thermal status, low-memory conditions, and unplugged battery below 10% stop or prevent work |
| Storage | Receiving requires at least 2×file size + 8 MiB free space; MediaStore.IS_PENDING prevents incomplete exports being marked complete |
| Inputs | Frames limited to 1,800 characters and manifest JSON to 1,024 bytes; nested metadata rejected; filename/MIME/index/chunk bounds checked |
| Downloads | MediaStore queried in pages of 20 visible files; Cursors and streams are closed |
| Network | Android requests CAMERA only; generated HTML blocks connections with connect-src 'none' |

These bounds cover buffers and work scheduled by the application. They are not a fixed ceiling for total Android/browser/driver process memory.

## Recovery and integrity

Chunks are written at their file offsets. Duplicate and foreign-session chunks do not advance completion. Receiving progress is checkpointed every 32 chunks, on pause, and before completion. A crash after a checkpoint can require rescanning up to 31 recent chunks. Incomplete sessions expire after 24 hours.

CRC32 rejects corrupted QR frames and SHA-256 checks the complete file before export. The user accepts file metadata before receiving. Files never open automatically. Pending MediaStore exports are cleaned on startup if incomplete.

Permission denial, camera failures, unsupported streams, low storage, timeout, malformed input, and full work queues produce controlled messages. Large-image paths catch OutOfMemoryError and stop. OS process termination cannot be caught as an ordinary exception; disk resume addresses that case.

Repeated passes vary QR masks. The senders use generation/epoch checks so an old frame job cannot advance a new display sequence after pause/reset changes.

## Current validation

See docs/OPEN_SOURCE_PREPARATION.md for actual results from this preparation. Run the included tests again after modifying the source. The supplied workflow has not been executed on GitHub during this preparation.

Host Java tests exercise the actual Android protocol classes with a 32 MiB JVM heap, including a full 16 MiB disk-backed transfer. This does not mean the Android application process uses 32 MiB or less. Browser tests exercise offline file URLs, workers/CSP, byte-exact transfer, missing ranges, pause/reset, empty/oversized files, theme variants, and responsive widths.

## Remaining limitations

- No physical Android device or Android emulator test was performed during this preparation. Camera orientation/focus, permissions, rotation, process death, and device-specific battery behavior require installed-app tests.
- Android runtime memory, CPU use, battery life, sustained heat, ANRs, and leaks have not been profiled on a device. Host tests cannot establish those properties.
- The direct SDK build can validate compilation/signing; it does not validate the separate Gradle/Android Studio path or a remote CI run.
- A broken third-party document provider may ignore cancellation or stall reads. Restarting the app may be required.
- Slow CPUs, glare, camera movement, and dense symbols can require reduced QR speed and multiple passes.
- Storage can fill or power can fail mid-transfer. Incomplete exports are not treated as completed files; resuming or resetting may be required.
- Transfer data is visible and unencrypted. CRC/SHA-256 do not authenticate the sender. External apps selected to open/share a file have their own behavior.

No guarantee of zero crashes, ANRs, or OS risk is implied by these source controls or host tests.
