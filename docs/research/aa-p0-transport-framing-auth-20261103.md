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

## 4. Same-session OEM GPS fallback compatibility (P0-B)

The formerly separate source-only [PR #5](https://github.com/cn-openivi/ft-app/pull/5)
was independently verified by CI run 41658414546. Its two exact
GPS regression tests have now been ported into the consolidated FT/ICCOA
PR #6: (a) fresh OEM WGS84 ICCOA packets with zero satellites remain the
car-primary selection through its seven-second hysteresis, and only
actual absence of new position packets triggers phone fallback;
(b) zero satellite counters do not alter the exact Android Auto GPS
protobuf serialization. These tests do not claim that the XA60 supplies
new coordinates underground, or that phone AA subscribed to LOCATION.

PR #5 itself remains untouched, so source history and its original CI
receipt can be independently audited. Live vehicle acceptance is still
reserved for the owner's single final combined FT+ICCOA campaign.

## 5. TLS partial-record continuity across AA frames

Fresh comparison of existing `AaCrypto.decrypt` found that SSLEngine's
`BUFFER_UNDERFLOW` returned with encrypted input bytes still remaining,
but the caller discarded that ByteBuffer. If a peer's TLS record spans
multiple encrypted AA frames, the next decode sees only the suffix and
will fail; this is a correctness issue, not proof that Samsung's Error2
was caused by a split TLS record.

The new bounded `AaTlsCiphertextCarry` retains the unconsumed bytes and
prepends them to the next encrypted frame. `AaCrypto.decrypt` treats
`BUFFER_UNDERFLOW` as a pause, not a silent discard; `CLOSED` is a real
error, and no-progress `OK` stops busy-looping. Each AaCrypto instance
owns its private 64 KiB carry buffer, so a subsequent AA session cannot
inherit old ciphertext. Unit fixtures verify two successive underflows,
consumed-prefix exclusion, new session isolation, and oversized malformed
input rejection. No protocol IDs, TLS cipher configuration, signing keys,
new permissions or user-facing settings were changed.

## 6. Added regression for empirically working Samsung NAV SDP

- The owner FT172 AA 17.9 session successfully opened NAVIGATION with NavigationChannelConfig type=2, interval=1000ms. Changing this field to type=1 in FT173 coincided with repeated early EOF/AA Error2. Protocol meaning of enum remains unverified; preserve the exact field-tested bytes, not an imagined capability.
- New `AaSensorAdvertisementTest.empiricallyWorkingFt172NavDescriptorIsExactAndSurvivesGpsAdvertisement` locks NAV submessage field8 to `08 E8 07 10 02`, both with and without the car GPS sensor advertised. This validates the existing source, not a live Error2 fix.
- `aaLocationSubscriptionWireIsPhoneInitiatedAndNotAnSdpHint` independently pins phone->HU LOCATION request body `08 01 10 E8 07`, success response `08 00` and transient error body `08 01 10 02`. The production session only emits car GPS after real phone subscription; no unsolicited injection.
- P0-A and P0-B automated tests will be checked together in source-only CI at an exact branch SHA. Physical Samsung/Xiaomi session survival is pending the single final owner field campaign; no APK generated at this stage.
