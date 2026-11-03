# FT/ICCOA critical P0-A: Android Auto transport proof — 2026-11-03

Owner-priority order (Nexus `FT_ICCOA_CRITICAL_REPRIORITIZATION_20261103_V1`):
P0 stable ICCOA + AA Error2 / TLS / Quick, then OEM GPS + AA LOCATION,
then Xiaomi touch/latency, then mirrored Yandex audio. This source-only patch
does **not** claim to fix field-reported Error2 or early EOF; the owner vehicle
remains in the garage pending one combined campaign.

## 1. Documented frame schema and current-source comparison

Existing `AaProtocol` defines a 4-byte frame header:
channel (u8), flags (u8), payload length (big-endian u16); a
FIRST fragment carries additional big-endian u32 total size.
Exact constants: `FRAME_MIDDLE=0`, `FRAME_FIRST=1`,
`FRAME_LAST=2`, `FRAME_BULK=3`; maximum plain chunk 0x4000
bytes. Encrypted and control flags are orthogonal to fragment kind.

Previously `AaFraming.encode` treated only lengths **strictly less** than
0x4000 as BULK. A payload of exactly 0x4000 was emitted as an orphan LAST
frame (without a preceding FIRST). Previously `AaFraming.Assembler.feed`
created a new buffer for MIDDLE or LAST even if a FIRST had never arrived.
Both issues are directly verified from existing source code; no new field
numbers or guessed OEM message IDs were added.

Source-only repair:
- An exact 0x4000-byte payload is now a single legal BULK frame.
- An orphan MIDDLE/LAST is discarded and cannot be presented as a valid AA
  control/audio/video message. New BULK abandons a prior incomplete chain.
- Incomplete fragmented data is bounded to 16 MiB to avoid unbounded memory
  if a malformed peer sends a long series without LAST. The current build's
  legitimate 1080p H.264 frames are well below this limit; larger unknown
  frames are not assumed valid.
- No new assumptions regarding the FIRST `total` field or TLS ciphertext
  size were introduced. The existing MAX_FRAME_PAYLOAD, encoding of other
  sizes, event IDs, stream priorities, audio and GNSS schemas are unchanged.

Exact `AaFramingTest`: <=0x4000 BULK header, 0x4001 FIRST/LAST
including 32-bit big-endian total, cross-channel interleaving, orphan
rejection, interrupted chain replacement and per-fragment encrypted
transform round trip. These are byte-level fixtures from current FT's
documented AA wire conventions, not claimed HU-captured packets.

## 2. Auth completion after TLS

Existing `AaSession.handleControl(SSL_HANDSHAKE)` called
`AaCrypto.handshakeStep`, then sent AUTH_COMPLETE **every time**
`crypto.handshakeDone` was true. A delayed/repeated SSL_HANDSHAKE
after the initial handshake therefore duplicated AUTH_COMPLETE and
rewound the UI phase to AUTHENTICATED, despite an already discovered
or streaming AA session.

`AaAuthCompletionGate` now makes AUTH_COMPLETE once per connection
lifetime, regardless of late TLS handshake messages. Every new AaSession
creates an independent fresh gate. Its unit test verifies a multi-record
handshake and 20 subsequent duplicate callbacks. Existing crypto handshake
contents, keys, TLS version and protocol field numbering are unchanged.

## 3. Acceptance boundary

The implementation is a **source-verified client reliability correction**,
not a claim to have reproduced Xiaomi Error2, TLS early EOF or NAV
subscription on a live XA60. Retain exact GitHub SHA, terminal
`:app:testUnsignedTransitUnitTest` and explicit no-APK receipt.
No signed release, merge or owner car trip until final joint acceptance.
