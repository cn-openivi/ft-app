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

## 7. P0-A source-verified ICCOA GEAR/LOCATION capability bug

- Prior source `AaSession.handleSensor` called `AaProtocol.supportsSensor(type, prefs.gpsSourceMode.advertisesCarLocation(prefs.carProtocol))`. This passed the **optional LOCATION policy** as if it were the **ICCOA transport identity**. When source mode was PHONE in ICCOA, SDP continued advertising OEM GEAR=8 while runtime rejected `SensorRequest(GEAR=8)` as `INVALID_SENSOR`; that is a protocol contradiction even without a GPS fix.
- `AaProtocol.supportsSensor` now separately accepts `isIccoa` and `carLocationOffered`. GEAR depends only on ICCOA support; LOCATION depends on both ICCOA and the separate offered-location gate. The session passes both independently. Existing 0x8001 request, 0x8002 response and 0x8003 gear event wire message IDs are unchanged.
- `AaSensorCapabilityTest.iccoaGearRemainsSupportedWhenGnssLocationIsDisabled` asserts exact advertised sensor enum list `[8,13,10]` and matching accept/reject decisions for ICCOA, Baidu and LOCATION-disabled conditions. This is a code-level regression fix; it is NOT evidence that Samsung requested LOCATION.

## 8. P0-C calibration-first geometry guard (no speculative touch fix)

- Owner correction: Xiaomi tester had **not** performed the existing four-point touch calibration. Therefore the observed ~36px Y offset is not an established geometry defect. Added deterministic `TouchQuadTest.xiaomiLikeThirtySixPixelYShiftIsRemovedBySavedFourPointCalibration` covering saved/reloaded 4-point vertical-offset correction and lower keyboard row.
- Added `AaViewportTest.aaPadded1080FrameStillRoutesTouchesToNegotiated1008Content`: independent AA negotiated touch content 1920×1008 must remain distinct from video frame 1920×1080; exact center, top, bottom and outside-content tap coordinates asserted. The production touch mapper is unchanged.
- Live Xiaomi FT/AA/mirror tap/drag/seek proof remains postponed until one combined owner HU acceptance, after actual calibration. Source-only CI is not a field touch result.

## 9. Clarification: proportional calibration targets; pixel-based measured errors (owner 2026-11-04)

- **Supersedes the mistaken interpretation in the preceding revision**: targets are drawn at `15%/85%` of the actual FT viewport width and height (`CarScreen.kt` already does this). The raw touch samples are recorded **in physical input pixels**, and the 4-point correction must remain an absolute-pixel calibration for **that same current resolution**. A measured vertical shift of `36px` means `36px`, NOT `3%` at any other resolution.
- The mistaken prior implementation attempted to persist normalized measured quadrilaterals under a global `ft_quad4_normalized_v1` key and reuse them at other screen resolutions. The correction **reverts** `CarTouchCalibration.kt` to its prior pixel-based `_quad4` persistence keyed by `${width}x${height}`; `TouchQuad.fromSamples/map` calculates offsets using recorded pixel points while mapping them toward proportional 15%/85% targets. Existing older two-point pixel offset remains intact. No normalized profile is consumed or written; no user APK with this wrong profile was released.
- `TouchQuadTest.calibrationTargetsUseScreenFractionsButMeasuredErrorsRemainPixels` checks the same 15%/85% targets across five screen sizes, but **each** size has independently measured integer pixel offsets (`+7`, `-13`, `+36`, `+36`, `-26` in Y, with independent X offsets). It saves/restores pixel tuples and checks bottom rows. A separate regression ensures the pixel tuple from 800x480 is not accepted at 2560x1440 or 640x360. New test tolerance is only `<=2px` for round-trip rounding.
- Android Auto is a distinct mapper: `AaVideoLayout.forFrame` and `AaViewport.map` already derive per-session content and decoded frame sizes and use ratios for viewport mapping. Tests include 640/1024/2560-wide physical screens, varying negotiated AA content-to-frame dimensions, and release-outside bounds. **No hard-coded 1920x1008/1920x1080 crop rule** was introduced into production.
- Live Xiaomi 4-point calibration still requires owner HU acceptance. This is SOURCE-VERIFIED/FIELD-UNVERIFIED; no standalone APK or additional garage trip.
## 10. Three concurrent critical source fixes (2026-11-04)

**P0-A fragmented Android Auto transport: verify original FIRST uint32 declared total**

- Current `AaFraming.readFrame()` formerly read the AA FIRST header's extra 4 bytes and discarded them; `Assembler` bounded total bytes globally but would dispatch a LAST message even when its size contradicted the explicit original `FIRST` length. This is a demonstrable parser/state integrity mismatch, independent of Samsung physical Error2 claims.
- Fixed `Frame.declaredMessageBytes` (unsigned big-endian uint32), `Assembler` checks exact assembled decrypted plaintext length per channel, detects forged zero/oversized/short/long values and discards ONLY the affected fragmented message; unrelated channel and next BULK recover. The existing AA encoder already writes this exact total in the FIRST extension. No message IDs or service discovery fields altered.
- Exact wire tests in `AaFramingTest`: `MAX_FRAME_PAYLOAD+17` correct FIRST size, modified 4-byte FIRST totals +1/-1/0/0x7fffffff/0xffffffff, sensor multiplex unaffected, valid subsequent BULK. This is source-level parser hardening; physical peer framing remains field-unverified.

**P0-B seven-second real OEM GPS availability for subscribed Android Auto LOCATION**

- Existing AA sensor job reported `SensorError(LOCATION,UNAVAILABLE=2)` after **3.5 seconds** with no accepted position. Owner's agreed car-primary transfer hysteresis is **7 seconds**; different UI selector and AA sensor timing could disagree. New `AaGpsAvailabilityGate` enforces seven elapsedRealtime seconds since the **last actually delivered** car GPS message, not GearInfo/phone/diagnostics, and sends the existing sensor error ONCE; on a valid recovered OEM message sends the existing one-time recovery error `SensorError(LOCATION,1)`.
- Exact input-output tests cover t=6999/7000ms, no false GearInfo reset, recovery once, repeated outage, monotonic backwards protection. Existing exact AA 0x8004 sensor-error body `[08 01 10 02]` and recovery `[08 01 10 01]` remain unchanged. **This does not make Samsung subscribe to LOCATION** or force Android Auto's independent phone GPS policy.

**P0-C weak phone H.264 reader head-of-line blocking**

- `AaSession` processes AA frames in a single reader/dispatch path and calls `AaVideoDecoder.onFrame` synchronously; `AaVideoDecoder.feedLocked` formerly used `MediaCodec.dequeueInputBuffer(20_000)` i.e. **up to 20ms per incoming frame**, long enough to cause frame backlog and delay control/ping/sensor processing on a slow decoder. Bounded to 2,000 microseconds without changing MediaCodec codec profile, FPS or stream protocol; unavailable slot means current late frame is dropped instead of blocking control for 20ms.
- Mockito-based test verifies exact `MediaCodec.dequeueInputBuffer(2_000L)` call under codec mock and bounded 60fps frame budget. Whether slow Xiaomi actual throughput improves and whether drop rate requires a different strategy are **field-unverified**; preserve logs for later combined owner acceptance. No invented AA protocol changes or extra release APK.

All changed source requires terminal source-only :app:testUnsignedTransitUnitTest and independent signed-FT174-base PR readback; one final owner combined car validation remains mandatory. Do not mark any physical P0 fixed yet.
