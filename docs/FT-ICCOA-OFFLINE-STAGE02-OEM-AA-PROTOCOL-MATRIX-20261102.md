# FT / RAV4 XA60 — Stage 02 OEM and Android Auto protocol matrix
**Mode:** source-only, no APK, no device writes, combined HU acceptance deferred.
**Baseline:** signed FT169 `f258c74a6f5a9656a96f96fdf3bf67b00ac31e6c`.
**Working branch:** `openivi/ft-xa60-iccoa-offline-work-20261102` — does not match the signed-release workflow's push filter.
**Authority:** Nexus `openivi-project-primary`, plans FT original `3c69bbd8-...` and ICCOA deep `f82aca0e-...`; *do not advance protected edges by writing this document.*

## Exact HU source and trust

Owner FAW Toyota Space `/system/product/app/Carlink/Carlink.apk`,
sha256 `6292f74524a5f7eecb84fe80d7b4c63acd81835912f03cf085ee78ae44c076d7`.
Original APK inspected *again* on 2026-11-02; embedded `ucar.proto`
declares `GearInfo.GearState`, `Gps`, `NotifyNavigationInfo` and
`ControlIndex`. This evidence applies to owner's FAW HU, not GAC donor or
other brands. Android Auto cross-check:
https://github.com/mrmees/open-android-auto/blob/main/docs/channels/sensor.md
and `oaa/sensor/GearEnum.proto`, `oaa/sensor/SensorChannelConfigData.proto`.
External open-AA notes are source-level reference and are not substituted for
phone runtime traces; their GAL/service ID tables can vary with wire channel
allocation, so never blindly change FT's observed channel 2.

| Layer and exact field | Evidence | FT169 | Offline work / remaining limitation |
|---|---|---|---|
| ICCOA GATT clientInfo | FT `IccoaClientProfile`, owner HU live | Stable synthetic client MAC and 6-hex ID, misleading manufacturer Xiaomi | FT169 encrypted remembered-code pilot now with Xiaomi field tester; no new spoofing or client-ID reset pending results |
| ICCOA TCP authentication | Owner successful FT166/167 sessions, `IccoaAuth` | ECDSA identity, nonce, PIN MAC, ephemeral ECDH, session key | No documented Toyota trusted-resume token; do not call cached-PIN experiment a proven seamless pairing solution |
| ICCOA CONTROL method 11 | OEM `NotifyNavigationInfo.isNavigating = 1` | Enables `CarLinkSensorMgr`, repeated sends even if unchanged | Event-driven synchronization: send initial state on authenticated CONTROL, send immediately on real state changes, retry failed queue, reset on CONTROL reconnect; no OEM refresh interval verified |
| ICCOA SENSOR method 1 | OEM `Gps` fields 1-10 | 1024+ valid HU GPS frames received in FT167 | HU GPS producer proven; GPS on native FT home in FT168 awaits live validation, no independent CRS or speed scale acceptance |
| ICCOA SENSOR method 6 | OEM `GearInfo.gear=1, speed=2` | FT168 preserves separate `gearRaw` and `gearSpeedRaw` | Interpret exact enum only while fresh: unknown=0, **P=1, D=2, R=3, N=4**. Do NOT assume units of `speed` |
| AA SDP sensor channel | External AA 16.2/17.3 field maps and live phone report | Advertises GPS=1, status=13, night=10 | `location_characterization` optional uint32 field 2 omitted; available description treated as opaque by phone; do NOT invent fusion/DR quality or claim this alone fixes missing subscription |
| AA SENSOR request | Phone SEND `0x8001` type + interval; source `AaMessages.sensorRequestType` | In FT167 phone requested DRIVING_STATUS=13 and NIGHT_MODE=10, NOT LOCATION=1 | Count per-type requests without any location/PIN; log exact subscription evidence at 12 seconds and shutdown |
| AA LOCATION event | `GPSLocation` field 1 repeated, GPS fields 2/3 microdegrees | `AaCarGps` only after real subscription and 3-safe-fix gate | Not delivered unless phone requests; no unsolicited spoof; tunnel/source stale triggers transient error |
| AA GEAR event | `SensorType=8`, `Gear` proto field 1, AA values `PARK=101,DRIVE=100,REVERSE=102,NEUTRAL=0` | Not advertised in FT169 | Offline source branch adds `GEAR` capability and *only after phone subscribes* relays fresh OEM GearInfo mapped from exact `ucar.proto`; stale/unknown emits error instead of P. HU acceptance pending |
| AA CAR_SPEED event | type=3, `Speed.speed=1` int32 | Not advertised | **Do not send** until OEM GearInfo.speed numeric unit and scale independently measured. Yandex Maps speed resembling dash is one owner's observation, not proof of car-speed data path |
| AA PARKING_BRAKE | type=7 boolean | Not advertised | No verified OEM parking-brake signal in current ICCOA stream. Never infer from zero speed or gear P |
| AA DRIVING_STATUS | type=13 bitmask; 0 unrestricted | FT sends constant 0 on request | Currently not truthful real-motion status; source-only future plan requires authenticated fresh motion and safe fallback. Do not add 'always Park' spoof or bypass Google safety |
| ICCOA RTP/AA UIBC | HU WFD + FT/AA input | Working video/touch baseline with some mirror app click issues | Keep unchanged until combined owner HU acceptance; callback delivery receipts already added FT167 |
| Audio mirror / HFP | Android PlaybackCapture MEDIA/GAME/UNKNOWN; Huawei/Toyota HFP independent | RuTube media passes; Yandex Navigator voice often local; HU warns new BT pairing interrupts CarLink | No Android API entitlement to grab NAVIGATION_GUIDANCE; app audio attributes not verified. No auto-pairing or force A2DP/HFP |

## Source changes made while FT169 is under owner/Xiaomi acceptance

1. **OEM Control11 state synchronization:** pure `IccoaControl11Gate` retains the last
   successfully queued payload. A fresh authenticated CONTROL channel receives the
   actual current state once; changed navigation/sensor state is sent immediately;
   identical payloads are not re-sent. A failed queue operation stays eligible for
   existing bounded retries. On reconnect the cache resets. No 5-second polling,
   15-second refresh or unverified OEM TTL assumption. H.264 and real navigation
   data are unchanged.
2. **AA sensor subscription audit:** `AaSensorSubscriptions` stores only sensor
   type/counts (LOCATION, GEAR, NIGHT, DRIVING, etc.). Log at 12 seconds and AA
   shutdown. No precise coordinates, PINs, MACs or raw packets.
3. **Gear semantics:** `IccoaGearState` reflects OEM `ucar.proto` enum exactly;
   `VehicleCoordinateLog` annotates only *fresh* GearInfo with decoded state.
   UNKNOWN/stale is never displayed as Park.
4. **Gear forwarding, experimental:** `AaCarGear` maps OEM P1 to Android Auto
   PARK101, D2→DRIVE100, R3→REVERSE102, N4→NEUTRAL0. Advertise GEAR only in
   ICCOA AA mode, deliver only on phone subscription and fresh OEM data.
   Transient SENSOR_ERROR when unavailable; recovery status on returning live
   GearInfo. **Never advertise unsupported speed or parking-brake capability.**

## 2026-11-02 — Separate opt-in constant PARK experiment

Owner clarification: FT's large central passenger screen is used by occupants
while driver relies on instrument cluster; owner explicitly requests an OFF-by-
default toggle to send PARK to Android Auto regardless of real gear, specifically
to test keyboard search restrictions in Yandex Maps. This is separate from the
existing truthful GEAR diagnostic toggle.

Source-only implementation:
- New persisted `Prefs.aaForcePark`, **default false**, distinct from
  `Prefs.aaGearTest` (also default false).
- In Android Auto settings: independent `Android Auto: всегда передавать P
  (эксперимент)` switch; require reconnecting AA to renegotiate capabilities.
- If ON with ICCOA protocol, include sensor type `GEAR=8` in AA SDP even if
  normal gear diagnostics is OFF; only if the AA phone SUBSCRIBES `GEAR`, send
  `SensorEventIndication` gear 101 (AA PARK), every 1s while enabled, even if
  the latest OEM GearInfo says DRIVE, REVERSE or is stale/unavailable.
- Do not change OEM ICCOA GearInfo, actual vehicle state, HFP, car display
  controls or existing GPS. If OFF, normal mode gives actual fresh OEM gear when
  `aaGearTest` is enabled, or does not advertise gear at all when both
  switches are OFF.
- Added regression tests proving override with DRIVE/REVERSE/unknown/no sample,
  correct AA protobuf encoding and lack of mutation of OEM telemetry.
- Diagnostic logs explicitly flag when AA never subscribes to GEAR (nothing can
  be forwarded to Yandex Maps then). If both gear switches are turned OFF
  mid-session, send SENSOR_ERROR permanent/unavailable so Android Auto cannot
  retain a stale synthetic PARK value; reconnect to renegotiate capabilities.

**Runtime limitation:** Previous Samsung FT167 logs show AA subscribing to
NIGHT_MODE and DRIVING_STATUS, but *not* to GEAR. If AA does not subscribe
to `GEAR`, the override sends nothing; it is not proof that Yandex Maps
allows keyboard, and Yandex may apply its own policy. The protocol semantics
are not the same as the physical transmission selector. Restrict passenger
screen interactions to passengers when vehicle is moving.

FT169 in owner/Xiaomi field acceptance is untouched; no APK/CI triggered by
this development branch. Combined physical acceptance and Android unit tests
remain pending. Both canonical Nexus plans' protected edges unchanged.

## Unit-test matrix added to development branch

- Event-driven Control11: repeated unchanged-state suppression regardless of elapsed
  time, immediate changed-state delivery, failed-enqueue retry, reconnect reset and
  defensive payload copying.
- Sensor subscription counting without user data.
- Complete OEM gear enumeration including rejecting AA enum input as OEM.
- Source/freshness/unknown-gear validation for AA GEAR events.
- AA service discovery advertises LOCATION + GEAR only when enabled and
  deliberately omits CAR_SPEED/PARKING_BRAKE.
- Gear logger emits decoded fresh enum and no exact coordinates by default.

**Not yet claimed:** full Android Gradle/JUnit CI green on development head,
signed APK, HU runtime behavior, AA receiving GEAR request, successful Google
Maps keyboard in P, validated car-speed multiplier, PIN-free Xiaomi reconnect.
Keep source commits on non-release branch until all approved software work is
ready for *one* combined build and HU test.

## 2026-11-02 — Control11 event-only correction

The original owner HU Carlink.apk P1 bytecode chain calls
`CarLinkSensorMgr.onStart()/onStop()` from navigation-state notifications.
The OEM protocol has no established 15-second refresh TTL. The prior offline
experiment used a 5-second heartbeat plus a 15-second duplicate gate; both are
removed in this source-only correction. The event-driven rule is exact: initial
authenticated CONTROL state, real payload changes and bounded retries on
send failure. There is no timer in the success path.

Verified in source from the development branch; regression tests have been
updated. **No Android Gradle test, new APK or HU P0 acceptance is claimed.**
An actual GPS dropout remains a separate runtime diagnostic, not evidence
that the OEM needs periodic duplicate commands.

## 2026-11-02 — AA sensor subscription acknowledgement corrected

Exact source audit found `AaSession.handleSensor` acknowledged every
`SENSOR_START_REQUEST` with `STATUS_OK`, even when the requested sensor
was absent from service discovery and had no handler. This could leave AA
believing FT supplies `CAR_SPEED=3`, `PARKING_BRAKE=7`, or unknown sensors.

Source-only patch enforces matching capability and response semantics:
- Supported unconditionally: `DRIVING_STATUS=13`, `NIGHT_MODE=10`.
- Supported for ICCOA only: `LOCATION=1`; a valid subscription can still
  receive transient `SENSOR_ERROR` when the OEM location stream is stale.
- `GEAR=8` is supported for ICCOA only when its explicitly configured
  test option is active; phone subscription is still necessary.
- All other request types return `SENSOR_START_RESPONSE` with
  `Status.INVALID_SENSOR=-9` (protobuf enum field 1), not false OK;
  rejected types are not recorded as active subscriptions.

Reference: external Android Auto sensor protocol source
`mrmees/open-android-auto/oaa/common/StatusEnum.proto` and
`oaa/sensor/SensorStartResponseMessage.proto` (static P2; phone runtime
still remains P0). FT source `AaProtocol`, `AaMessages`, `AaSession`,
and `AaSensorCapabilityTest` updated. No inference of vehicle speed units,
location subscription by the AA phone, or Yandex restrictions.
**NOT YET PASSED:** Android Gradle/JUnit execution and HU/phone acceptance.
FT169 signed pilot remains unchanged; both Nexus plan edges protected.

## 2026-11-02 — optional compatibility reactivation for non-Toyota ICCOA head units

Owner requested preserving compatibility with other Chinese ICCOA head units,
not assuming the Toyota Carlink.apk stateful behavior is universal.
This is an **explicit user-facing option, OFF by default** in
`Settings → Car → ICCOA` (`iccoaSensorRefresh`).

- OFF: event-driven OEM Control11 once per authenticated CONTROL readiness,
  immediate payload changes and bounded send-failure retries. Zero refresh
  timer tasks in the successful steady state.
- ON: after each successfully queued **active** navigation/sensor request,
  schedule one refresh at 15 seconds; no 5-second polling, no refresh for
  inactive/stop messages, and re-arm only on the next successful send.
  Changed payloads and OFF transitions are not delayed. Disconnect resets
  the gate and cancels pending timer.
- The switch is read when the ICCOA CONTROL connection becomes ready. Change
  it in settings and **reconnect ICCOA** to apply.
- This is a compatibility experiment. Some units do not implement the
  Toyota-specific activation command at all; retrying it cannot create
  unsupported car GPS, and may increase control traffic.
- Applies only to ICCOA OEM `NotifyNavigationInfo` ControlIndex11;
  it is **not** an Android Auto SENSOR subscription or a change in
  vehicle gear/parking state.

Tests in `IccoaControl11GateTest` cover default OFF, 15-second threshold,
payload change, failed-send retry, reset, and immutable payload snapshot.
P0 HU runtime testing and Android Gradle/JUnit execution pending combined
owner acceptance. No intermediate APK built.

## 2026-11-02 — AA location subscription and speed/rotation source work

**LOCATION**: Confirmed FT's current ICCOA discovery serializes SENSOR LOCATION=1,
DRIVING_STATUS=13, and NIGHT_MODE=10 in SensorChannelConfig. The phone
independently initiates SENSOR_START_REQUEST 0x8001; advertising LOCATION
does not guarantee subscription. Current Samsung FT166 P0 logs saw real
OEM GPS SENSOR/1 frames and zero Android Auto LOCATION requests. Source
`AaSession` now records the *serialized* advertised sensor IDs,
whether SENSOR channel opened, and actual requests after 12 seconds
(commit d8441e9). No force-pushed unsolicited LOCATION event,
GPS spoof, speculative head unit field, or fabricated AA subscription.

P2: `mrmees/open-android-auto` sensor protocol reference confirms
subscription direction and message IDs. Its `SensorChannelConfig`
optional field 2 is described inconsistently between proto
(`location_characterization`) and docs (`default update interval`);
this is **not** enough evidence to set an undocumented field on
the production wire. Do not alter it without version-matched proof.

**SPEED UNIT EVIDENCE** (source only):
- FT keeps `GearInfo.speed` and `Gps.speed` as independent
  integers `gearSpeedRaw` and `gpsSpeedRaw`. It does not invent
  a conversion, represent them as km/h, or advertise AA CAR_SPEED.
- When the existing FT phone GPS speed widget is running and its
  GNSS sample arrived in the last 2 seconds, diagnostic `VehicleGPS`
  records `phoneGpsSpeedKmh` beside the contemporaneous OEM raw
  integers. This uses the existing widget listener, no new GPS
  permission or background listener, and omits coordinates unless
  the existing explicit privacy switch is ON.
- At combined owner field acceptance, include several steady,
  nonzero vehicle speeds with this widget active, compare synchronous
  raw values and phone GNSS km/h, exclude stale and changing-speed
  samples, then independently validate any candidate multiplier
  across at least two speeds. Matching one sample is NOT a verified
  unit. No vehicle CAN/gear state is modified.
- Added pure unit checks for formatting, privacy and rejecting
  unavailable/invalid independent speed; no Gradle run at this stage.

**POST-ROTATION MIRROR TOUCH** (source only):
- `IccoaProjection` logs changing triple: actual phone display size,
  Android capture content size, and mirrored output Surface size,
  on display-change/capture-resize/touch; no coordinates or package names.
- `MirrorTouchMapTest` now checks a 15/50/85% grid in stabilized
  portrait and landscape layouts with explicit letterboxing.
- This targets persistent X/Y mismatch *after* a rotation.
  No mid-gesture rotation handler, speculative touch multiplier or
  touch mapping change was introduced. Physical 5-point tap checks
  and actual Accessibility callback receipts remain combined P0.

**Owner-approved UI wording**: Russian GPS fallback toggle
`Удерживать GPS активным`, English `Keep car GPS active`.
Event-driven default OFF; opt-in 15-second active-request repetition.
GitHub commits c6ca1ac12a8528b37981faad8ffc4c8a665b2764 and
431e15b1fa4b01f89408cf61d5e1b1c229d45323.

All entries are software/source evidence only. Do NOT claim a passing
HU trial, inferred speed units, or plan edge advancement.

## 2026-11-02 — owner-confirmed seek recovery and authenticated-link reconnect

Owner's latest physical feedback: **audio and video timeline seeking WORKS**
after the previously implemented touch correction. This is scoped to the
seek gesture: it is not proof that all small/edge taps, portrait/landscape
mirroring and Android Auto touch are accepted. **Remove seeking from the
open defects**, protect the existing input-path behavior from unrelated
changes, and do not request another interim HU test.

Reconnect source repair (no APK built): after an actual established
ICCOA projection connection drops unexpectedly, the service previously
restarted BLE discovery with `autoConnectAttempted=true`, which blocked
`maybeAutoConnect` from selecting the remembered car. It now resets the
attempt flag **once** for that authenticated-link loss using
`IccoaDropRecovery`. A second failure in the same service lifetime
does not auto-loop. A manual Connect or Scan resets this bounded
recovery budget. Failed GATT/bootstrap/PIN/TCP authentication, or
disabled auto-connect, never authorizes an automatic retry. Reconnection
still performs the original cryptographic handshake and cannot claim
that the car accepts an earlier encrypted PIN.

Source: `IccoaDropRecovery.kt`, `IccoaService.kt`;
`IccoaDropRecoveryTest.kt` has four unexecuted JUnit cases (first
authenticated-link loss, repeated drop, failed initial auth, owner
manual reset). Owner combined HU acceptance and source CI remain
pending. Keep both canonical plan NEXT_EDGE/CURRENT_ITEM untouched.

## 2026-11-02 — owner-approved three-attempt transport reconnect

The owner raised the previously implemented **one** automatic recovery attempt
to **three** after an unexpectedly lost, formerly established ICCOA link.
This replaces the one-attempt policy in the preceding source work; no APK
or owner field retest is requested until the combined release.

- Attempt **1/3**: scan for the remembered peer after the established link
  unexpectedly disconnects. BLE address rotation is handled by existing
  `IccoaRememberedCar.select` (unique hinted car name only).
- Attempts **2/3** and **3/3**: only if discovery completes without the
  remembered peer, or a *reconnecting attempt* fails with a typed transport
  failure (Bluetooth, Wi-Fi loss, TCP connect, control/media socket loss).
  Delays between the attempts are 3 and 6 seconds respectively.
- Every attempt runs the original ICCOA GATT, PIN-bound cryptographic
  handshake, and media setup. Failed initial pairing does not start recovery.
  Incorrect/rejected PIN, auth cryptographic failure or a new PIN prompt
  immediately stop the automatic sequence. Wi-Fi consent denial is not retried.
  No automatic credential guessing.
- Successful live projection, explicit owner Connect/Scan/Disconnect and
  service shutdown stop/reset the sequence; exhausted attempts do not
  produce a fourth retry. The owner may manually initiate a fresh connection.
- Recoverable control-channel loss is now signaled to the service, rather than
  silently leaving the old connection without its control socket.
- `IccoaDropRecoveryTest`: 8 source-authored regression scenarios.
  Pure gate source separately matched exact GitHub blob
  `c637a7129b7b6fba2accb21ba7edb2394262dc37`; the JVM smoke exercise
  passed 22 assertions. Full Android build/JUnit and live HU compatibility
  remain UNTESTED until the planned combined candidate.

This change does not prove OEM reused-PIN support or resolve the independent
Android Auto LOCATION subscription issue. No canonical plan status/edge advance.

## 2026-11-02 — verified credential-save race fixed

Critical reconnect/auth integrity correction: `IccoaSession.authenticate()`
previously stored the encrypted remembered PIN immediately after writing
`AuthConfirm`, before any authenticated CONTROL lane was opened by the car.
A successful outbound write does not prove successful receiver acceptance.

The new `IccoaTrustedCommitGate` requires BOTH events before the single
credential write:
- cryptographic SignIn response verified and `AuthConfirm` sent;
- the car opens the CONTROL socket for the same active ICCOA Attempt.

Both event orders are supported (asynchronous socket accept can race the
confirmation write), but only one save can occur. Abandoned/failed attempts
cancel the gate and discard the pending in-memory secret. No PIN, key, or
network identity is logged. Prior valid encrypted credentials are not
overwritten by an unaccepted new attempt. This is a source-level integrity
repair, NOT proof that the car supports future reuse of the PIN.

Source: `IccoaTrustedCommitGate.kt`, `IccoaSession.kt`,
`IccoaTrustedCommitGateTest.kt` (4 new unit scenarios, NOT YET CI-run).
Preserves three bounded reconnection opportunities and existing
authentication cryptography. No release APK produced.

AA GPS P1 cross-check, 2026-11-02: `mrmees/open-android-auto`
`SensorChannelConfigData.proto`, `SensorRequestMessage.proto`,
`SensorErrorStatusEnum.proto` confirm the sensor descriptor's nested
`supported_sensors=1` and phone-initiated `SensorRequest` subscription
requirement. `SENSOR_ERROR(status=1)` is expressly **SENSOR_OK** (legitimate
recovery notice), status 2 is transient failure. The service documentation's
alternate description of SDP field 2 conflicts with its vetted proto name
`location_characterization`; do not guess that field or send unsolicited
car locations. P0 remains absent phone LOCATION subscription; the existing
wire encoding is not changed without exact evidence.

## 2026-11-02 — critical auto-reconnect transition and PIN retention fixes

**Source-only changes after signed FT169, still no new APK.** Owner says
continue critical work before a single combined automatic-test run/build.

1. `IccoaDiscovery.start()` previously called `stop()`, which emitted
   `SCAN_COMPLETE` for the **interrupted** scan. The service's three-attempt
   recovery interpreted this as a real unsuccessful scan, consumed another
   retry budget and could restart again. Now a new scan invalidates the
   previous BLE callbacks and closes the prior GATT/scan silently before
   publishing the NEW `SCANNING` phase. True scan timeout still publishes
   `SCAN_COMPLETE`. Commit `e78cd355c227852c448579df244079ab3c409c48`.
2. `IccoaService.connectTo()` previously called `discovery.stop()`
   *before* marking `connecting=true`. Its synchronous end-scan callback
   could auto-select the remembered car while a manually chosen connection
   was being initialized. Set `connecting=true` after old session teardown
   and before stopping discovery. Commit
   `1e1cac5e019436c873f57c07189f1bee39b390b3`.
3. The original three-attempt recovery only activated **after** a successful
   projected session was unexpectedly lost. Owner's request covers ordinary
   connections too: initial automatic selection of the remembered peer
   now counts attempt **1/3**; only typed transport failures may consume
   **2/3** and **3/3**. Manual PIN prompt, rejected credentials and
   unrecognized errors stop retries. Existing failed initial manual pairing
   never authorizes retries. Updated `IccoaDropRecovery`,
   `IccoaService`, `IccoaDropRecoveryTest` (+2 tests). Commits
   `e19125420ea3fad9c70e6f29cae0aef535643954`,
   `64ef2378083deaeb9ed21dbb53883f870be8d29f`,
   `6a1815641867b9ea13ab2e3eb1a8575d4ff18df0`.
   Exact gate source SHA `44acb7af665325bec365beebfaafe1a7b43b3c2f`
   verified against local `git hash-object`; `kotlinc` JVM gate smoke
   passed **32 assertions**.
4. Previously the cached PIN was cleared after *any* TCP auth exception,
   GATT write failure, ATT local queue failure, or BLE response timeout.
   These are not proof that the car rejected the credential and cause the
   next transport-recovery attempt to fall back to manual PIN. The new
   `IccoaPinCachePolicy` clears only after an explicit `PIN_MAC`
   rejection or OEM `RESPONSE_RESULT=1` (request fresh code).
   Network/transport errors and non-PIN protocol errors retain the
   encrypted credential while still stopping unsafe automatic retries.
   Five new JUnit source tests; exact source SHA
   `45d27ac6571dbf8e24b69b6afb0cc0af94e5bc21`.
   A compiled standalone Kotlin smoke using this exact policy source
   passed **9 assertions**. Commits
   `4d01b9b462b2e290cb677392d920ae4e08304829`,
   `bbd3f0c617cd86d74a7916a5ebea49a4d6dc3bac`,
   `bae18e583f207e69c0747f67b33d4ce721f3a288`.

No full Android Gradle CI or verified HU release yet. Android Auto does
not request LOCATION in past owner field logs despite OEM car GPS producer
being active and LOCATION advertised; do not claim that the source-only
subscription instrumentation solves this. Mirror seek audio/video is
already owner-confirmed working; no seek gesture rewrite.

## 2026-11-02 — Android capture contract for missing navigator voice

Source-verified OS restriction, not an inferred FT ICCOA codec failure:
Android `AudioPlaybackCaptureConfiguration` may capture *other apps'
playback* only when the player uses `AudioAttributes.USAGE_MEDIA`,
`USAGE_GAME`, or `USAGE_UNKNOWN`, **and** the source app permits capture.
`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` is not eligible for this Android
MediaProjection playback-capture mechanism even if added to a matching-usage
filter. Official evidence:
https://developer.android.com/media/platform/av-capture
and
https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration

FT `AudioCapture.kt` already matches exactly those three permitted usages.
Thus adding NAVIGATION_GUIDANCE to its matching list would NOT repair a
navigation app playing guidance with that attribute. Nor can changing
ICCOA AAC/PCM packet fields recover audio Android never supplies.
Do not disturb the working general media playback and owner-confirmed
audio/video seek gesture.

Owner observed some Yandex Navigator prompts playing on the phone
instead of the car; **we have not independently verified the Yandex
player's AudioAttributes at runtime**, so the specific cause remains P0
open. Next evidence: distinguish mirrored phone guidance from speech
inside Android Auto (AA SPEECH/SYSTEM lanes are distinct from Android
playback capture), and whether OS sends any eligible PCM during the
prompt. Any alternative routing needs independently approved/confirmed
Android Bluetooth audio behavior; do not silently pair or change routes.

No source-based audio workaround and no APK is claimed by this note.

## 2026-11-02 — AA channel closure fixes: stale GEAR and ghost SPEECH

Two **source-proven** Android Auto lifecycle defects fixed, without changing
wire protocol messages or working audio/video seek implementation.

1. `AaSession.handleControl(CHANNEL_CLOSE_NOTIFICATION)` stopped the
   ICCOA-derived GPS sender on closing SENSOR, but **left GEAR subscribed**
   and its coroutine running. The now-closed sensor channel could still
   receive gear events, including the owner-opt-in override state; reopening
   could also inherit the stale subscription count. The new
   `stopCarSensorStreams()` cancels BOTH GPS and GEAR coroutines, clears
   subscription booleans, and resets the type/count-only subscription
   evidence on SENSOR close. The same helper runs during full session
   cleanup. Source commit `c1fc9b0587513d33ba924830873a080a4f11bcc2`;
   `AaSensorSubscriptionsTest` includes channel-reopen audit reset
   `b4501be45186ea89ee65775b227dda4d2b6cc8f2`.
   No unsolicited Android Auto LOCATION request was added: the AA phone
   still must subscribe on its own.

2. AA AUDIO `CHANNEL_CLOSE_NOTIFICATION` did NOT release active
   SPEECH/SYSTEM/MEDIA lanes when the phone closed a channel without
   sending AV_STOP. `CarAudioBus` could retain `speaking=true` and
   stale navigation guidance ducking. Full AA session cleanup previously
   ended only SPEECH/SYSTEM unconditionally and not a live MEDIA lane.
   A small `AaAudioChannelRegistry` now tracks distinct AV_START
   sessions; both AV_STOP and CHANNEL_CLOSE release the corresponding
   live lane once, shutdown releases remaining active lanes, a new
   AV_START can reopen it, and late PCM after close/stop is discarded
   rather than resurrecting a ghost voice lane. AA channel ACK retains
   the correct current session number. Source:
   `58f172f8707d040bc2748fe41e2e5b8d684438f3`,
   `2b7c930773cfe44cbfbfa0ab918366cb3c215509`,
   `4908c6fe4efdb043b1253bce9eecb8730e94a11d`,
   `e36b28d5a757de2a63aedda0aaa9ff78396ec594`;
   tests `6440f952af8f541b9d8c72cf9c7f04ac5faa8f38`,
   `8a9c70a347a8fe9ede3e1e9fd00d1d00cebab3c3`.
   GitHub audio registry blob `38c82eab900c70661cde5684584986e3dda9ca4d`
   exactly matched local `git hash-object`, Kotlin compiled, 17
   standalone JVM smoke assertions PASSED.

This repairs **AA speech/audio-lane state on closure**, NOT the separate
Yandex Navigator-on-phone source-audio restriction, whose player usage
remains unknown without live evidence. Owner confirmed media seeking
working; no changes to gesture code.

STATUS: All changes SOURCE ONLY; Gradle Android tests have not run;
no new APK, signed release, physical HU P0 approval or protected Nexus
plan-edge advancement. Owner requested combined acceptance after
four critical directions.

## 2026-11-02 — mandatory FT153→candidate full regression and release gate

**Owner direction**: before creating the next single owner-signed APK, perform
a comprehensive audit of everything modified or added from **FT153**,
including faults, omissions, missed conditions and regression tests.
Keep all four critical lines as release blockers; cosmetic work remains later.
This is an acceptance gate under the existing two Nexus plans, not a
renumbering, architecture change, topology expansion or premature closure.

**Source inventory as of 2026-11-02**:
- Repository `cn-openivi/ft-app`; experimental branch
  `openivi/ft-xa60-iccoa-offline-work-20261102`. The verified FT169
  source branch head `f258c74a6f5a9656a96f96fdf3bf67b00ac31e6c`
  is **76 commits behind** the active work branch; compare currently
  spans **39 changed files**.
- The repository default `main` at
  `5e308c6a9a8556eb8d2d612947e5a61545ce7432`
  compares **257 commits / 262 files** behind the active branch.
  **Do not equate main with FT153** without verifying the exact FT153
  release/tag SHA. A proposed literal tag `xa60-ft153` returned
  NOT_FOUND; the workflow file named `xa60-ft153.yml` is now the FT169
  workflow and is not a trustworthy FT153 baseline by itself.
- Current source tree has **101** `app/src/test/**/**Test.kt`
  files: 18 AA, 28 ICCOA, 26 CarLife, 14 core, 10 projection,
  3 UI, 2 other. This is **test source inventory**, not test PASS.
- No release build, Actions job or signed APK was run from this branch.

### Mandatory pre-release QA matrix

| Scope | Required source audit / tests | Evidence state |
| --- | --- | --- |
| ICCOA BLE/GATT/PKI/PIN | Discovery races, stable remembered identity, 3 bounded retries, fresh encrypted PIN persistence only after CONTROL, invalid secret clearing only after protocol proof; callbacks exactly once | Source changes + Kotlin smoke available, full Android CI & real HU trust reuse pending |
| ICCOA TCP/auth/transport | Network gateway, port handshakes, heartbeats, frame lengths/CRC/crypto, RTSP and socket close/error paths, no runaway reconnect | Source audit partial; global regression pending |
| OEM GPS/SensorMgr | Real HU fix, Control11 opt-in, wrapper decoding, conversion/CRS unknowns, no synthetic fix, stale fallback, GearInfo units independent | OEM live GPS previously seen; AA location consumption P0 open |
| Android Auto sensors/gear | Serialized advertised LOCATION/GEAR, actual phone SENSOR_START subscriptions, GEAR P override opt-in/off by default, real OEM telemetry unchanged; SENSOR close cancels both location+gear streams | Source tests present; missing phone LOCATION/GEAR subscription behavior not physically accepted |
| Touch/input | FT UI accepted; MirrorTouchSequence complete tap and real drag; four-point calibration, Android rotation geometry, AA viewport, buttons, keyboard, swipe, owner-confirmed audio+video seek | Seek accepted, other touch cases P0 open |
| WFD/codec/video | H264 connection stability, portrait/landscape capture, mirror fit, fallback, keyframe, orientation, CPU/load, callbacks and Android projection consent | Source changes partial, field acceptance pending |
| Audio | Android Auto PCM MEDIA/SPEECH/SYSTEM, late packets and channel close, audio focus, voice/ducking; distinguish mirrored Yandex Navigator (Android playback capture restrictions) from AA speech path | Route evidence partial, navigator voice still unconfirmed |
| UI/preferences | Default OFF optional GPS refresh, experimental P value, correct persistence, locale, settings, reset, no duplicated flags or unexpected activation | P toggle **already present**, state semantics code-verified, owner test pending |
| Telemetry/privacy | No PIN or raw encrypted session keys in ZIP, GPS coordinates logged only on owner opt-in; stable unit and speed sample timestamps; no uncontrolled raw packet dumps | Source logging review partial |
| CI/build/signing | All unit tests, source/lint/static analysis (include `lintVitalRelease` for final), debug/release compile, pinned PKCS12 signature/cert, versionCode & applicationId, non-debuggable, exact SHA-256, one prerelease APK and terminal job receipt | **NOT STARTED**; currently workflow hardcodes FT169 version 169 and excludes `lintVitalRelease`, and push trigger is old branch; revise gate before final run |

### P0/P1 defects and decision limits

- **P0 GPS:** actual ICCOA GPS is observed but Android Auto phone has
  not requested LOCATION in available logs. Fixing codec without such
  subscription cannot solve AA location; do not send unsolicited frames.
- **P0 audio hypothesis:** Android `AudioPlaybackCaptureConfiguration`
  only permits other-app playback with usage MEDIA/GAME/UNKNOWN and
  allowed capture policy. If Yandex Navigator uses
  `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` or `USAGE_NOTIFICATION`,
  mirrored phone audio cannot be captured with this API; actual app usage
  remains unverified. Separately investigate AA SPEECH/SYSTEM PCM
  from the phone, not the AA NAVIGATION **metadata** channel.
  Official Android reference:
  https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration
- **P1 audit instrumentation already committed**:
  `AaAudioRouteAudit` counts **only** AA incoming MEDIA/SPEECH/SYSTEM
  PCM packet counts and NAVIGATION message count, without collecting
  user audio or route content; `AaSession` writes a bounded summary
  on shutdown (source commits
  `142395abb8a4d80932d010f2a2cf1f96adffdeea`,
  `0854219e5dd35ebacfe9e163cab611c89ba73476`).
  **Test-file creation was explicitly denied by platform safety**.
  Stop that operation, do not bypass it by another path; current audit
  helper is source-authored but without its intended new dedicated unit
  tests. Do not claim tests or CI passed.
- **P0 compatibility:** owner approved diagnostic permanent P on the
  Android Auto **GEAR** sensor only; existing
  `Prefs.aaForcePark` defaults OFF, existing SettingsScreen toggle
  `Android Auto: всегда передавать P (эксперимент)`, and
  `AaCarGear.aaGear(... forcePark=true)` encode 101 independent of OEM
  physical gear. This does **not** modify the actual car and works only
  when the AA phone requests `SENSOR_GEAR=8`. It does not guarantee
  app-level search becomes available, since AA may use DRIVING_STATUS
  and other motion restrictions. Leave OFF for ordinary driving;
  do not change DRM/car safety gates.
- **P0 PINless:** cached *previous* six-digit code is an experiment,
  not confirmed OEM trust token or proven seamless reconnect.
- **P0 owner combined HU acceptance**: one session after the complete
  QA & single signed APK, not after every small source change.

**Gate**: do not call this full audit PASSED until every listed required
source/module check has its actual evidence and blockers are reconciled
against the same candidate SHA; do not run the existing
release-and-publish workflow as a substitute for preliminary audit.
