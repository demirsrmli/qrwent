# qrwent QL1 optical transfer protocol

Files are sent as actual bytes in sequential QR symbols, rather than as download links. Communication is screen light → camera. No network or reverse channel is required. Android and the desktop sender use the same wire format; `QL1` is a protocol version marker, independent of application branding.

## Encoding

The sender uses M-level QR error correction. Base64 makes binary contents portable through the text QR path, at roughly 33% overhead. Desktop chunk choices are 256, 512, and 768 bytes; Android sends 512-byte chunks. Very dense symbols can be difficult for cameras to scan.

### Manifest frame

```text
QL1|M|<16 lowercase hexadecimal session ID>|<Base64 UTF-8 JSON>|<8 lowercase hexadecimal CRC32>
```

| JSON field | Meaning / validation |
| --- | --- |
| `name` | Sanitized filename; separators/control characters removed; up to about 100 UTF-16 units and 180 UTF-8 bytes |
| `mime` | At most 96 characters; invalid values become application/octet-stream |
| `size` | 0–16,777,216 bytes |
| `chunk` | 256–768 bytes; sender choices are 256/512/768 |
| `count` | ceil(size/chunk), at most 65,536 |
| `sha256` | Original file SHA-256, 64 lowercase hexadecimal characters |

The manifest is displayed at the start and before each group of up to 20 chunks. An empty file uses only manifest frames. Repeated passes change QR masks through 0–7 to avoid repeatedly missing a particular pattern. Masks do not change frame text or file bytes.

The receiver chooses its own internal temporary paths; the untrusted transmitted name is used only as a sanitized display/export name.

### Data frame

```text
QL1|D|<session ID>|<zero-based chunk index>|<Base64 bytes>|<CRC32>
```

CRC32 is computed over all ASCII text preceding the final `|CRC32`. The receiver limits frames to 1,800 characters and manifest JSON to 1,024 bytes. It validates checksum, Base64, session ID, index, and chunk length. Only the last chunk may be shorter than the configured chunk size.

The Android receiver writes bytes at `index × chunk` in a RandomAccessFile. A disk-backed BitSet journal tracks received chunks. Duplicate chunks are ignored; out-of-order arrival is supported. Missing ranges are reported with one-based numbers for the UI.

Before completion, SHA-256 is recomputed over the complete disk file. A mismatch fails the transfer. Valid bytes are copied to MediaStore.Downloads using `IS_PENDING`; the pending flag is cleared only after a successful copy. Progress checkpoints occur every 32 new chunks and on pause/completion. Following abrupt termination, recent uncheckpointed chunks may need to be scanned again.

## Limits and trust

- One active receiving session, one sending file, and one frame job at a time.
- Same-ID manifests with different metadata are rejected. Data from a foreign session is ignored.
- The user accepts a manifest before receiving. Files never open or execute automatically.
- CRC32 and SHA-256 provide integrity checks, not authentication or encryption.
- There is no fountain coding or acknowledgment channel. Unstable scanning can need many repeated passes.
- Android uses Camera2 + ImageReader and embedded ZXing; there is no on-demand model download.

## Upstream references

These links identify the technologies used; dependency versions are pinned in the build files.

- [DENSO WAVE: QR versions and capacity](https://www.qrcode.com/en/about/version.html)
- [ZXing source](https://github.com/zxing/zxing)
- [Android ImageReader](https://developer.android.com/reference/android/media/ImageReader)
- [Android shared media storage](https://developer.android.com/training/data-storage/shared/media)
