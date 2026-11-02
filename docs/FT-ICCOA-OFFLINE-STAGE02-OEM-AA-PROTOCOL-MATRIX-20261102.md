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
