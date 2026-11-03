# FT174 source-only: TinyEKF -> Android Auto SENSOR_LOCATION source and subscription audit
Date: 2026-11-03. Owner P0: tunnel+urban multipath, turn-by-turn through Android Auto Yandex. No new APK, no garage trip. Canonical plans stay unchanged.

## Already implemented before this audit
- AaMessages.serviceDiscoveryResponse announced `SENSOR_LOCATION=1`, `GEAR=8`, etc in Android Auto SensorChannelConfig.
- AaSession.handleSensor handled `SENSOR_START_REQUEST 0x8001`, returned `SENSOR_START_RESPONSE 0x8002`, and launched `startCarGps` only if LOCATION was requested.
- The existing `AaCarGps.event` generated `SENSOR_EVENT_INDICATION 0x8003` with nested repeated GPSLocation field1 and int32 microdegree lat2/lon3.
- The stream was **only raw, fresh ICCOA GNSS**. A packet gap exceeding 3.5 seconds caused `SENSOR_ERROR`, not wheel/gyro DR.
- The ported `DeadReckoningEkf` did not produce any WGS84 location and was not read by AA.

## Real owner FT174 log evidence, NOT speculation
Source: owner Drive ZIP `FT-report-samsung-SM-S916B-20261103-180641.zip` (sha256 ea52e5d627fbcb63ad1f4a637fd392ae50926928ec9bbb0388fda5f60cadbf79), analyzed `ft-log.txt`.
- The AA sensor SDP advertised `[1,8,13,10]` (LOCATION, GEAR, driving status, night) **27 times**.
- **ZERO** phone-to-HU `SENSOR_START_REQUEST` type1 LOCATION were logged, despite working SENSOR channel; requests normally contained `8,10,13`.
- The code itself repeatedly emitted `AA phone did not request car LOCATION; cannot forward absent subscription`.
- At 2026-11-03 16:41:10.742, SDP log explicitly showed `locationCharacterization=256`, `characterizationPresent=true` and `oemGpsFreshAtDiscovery=true`.
- Therefore unconditionally emitting location into sensor channel is not an approved fix: phone did NOT request it and may not consume unsolicited events. This blocker remains **NOT PROVEN RESOLVED** until the final on-car test or independent phone-side protocol analysis.

## Authentic schema/provenance and correct fused output
Protobuf provenance: https://github.com/mrmees/open-android-auto/blob/main/oaa/sensor/GPSLocationData.proto
- `GPSLocation` required signed int32 microdegree latitude (field2) and longitude (field3), optional uint32 accuracy (field4), optional altitude/speed/bearing fields5,6,7.
- Nested `SensorEventIndication` field1 = repeated `GPSLocation`; `0x8003` is HU->phone delivery after genuine subscription `0x8001`.
- `SensorType` field14 `DEAD_RECKONING` is NOT a geographic DR location: `SteeringWheelData.proto` says optional steering angle and wheel speed ticks, so do NOT emit TinyEKF ENU there.
- Authentic AOSP Automotive `LocationCharacterization` bit definitions, e.g. `automotive/vehicle/2.0/default/.../LocationCharacterization`: `PRIOR_LOCATIONS=0x1`, `GYROSCOPE_FUSION=0x2`, `WHEEL_SPEED_FUSION=0x10`, `DEAD_RECKONED=0x80`, `RAW_GNSS_ONLY=0x100` (mutually exclusive). Source: https://android.googlesource.com/platform/hardware/interfaces.git/+/d448cb63d10c6c3c94f667a258b4d3cdb748e041%5E%21/
- Current FT AA SensorChannelConfig **field2** already carried `0x100`. It cannot truthfully characterize fused DR as raw GNSS. New source sets `0x93` ONLY if verified wheel/yaw TinyEKF WGS84 fix was already available at the time of session SDP; otherwise it retains `0x100`.
- No guessed fields, no mock-location API, no unapproved HEAD_UNIT or Android LocationManager writes. No new permission. A GPSLocation has no on-wire 'estimated' boolean, so we differentiate it by SDP characterization, sensible uncertainty in field4 and internal diagnostics, never present a fused packet in a raw session.

## Source-only implementation boundary
- `app.ft.dr.DrNavigationTelemetry.publishFromVerifiedEkf` translates a source-neutral verified TinyEKF east/north estimate relative to a fixed real WGS84 reference using ellipsoidal local metre-to-degree conversion. It refuses unverified wheel+gyro, invalid positions and false timestamps; `DrNavigationFix.eligibleForAa` insists on selected CAR mode, verified motion and current source age.
- `AaLocationCharacterization` freezes `RAW 0x100` or `FUSED 0x93` at session discovery. The existence of the code alone is **not** considered proof of live DR readiness.
- `AaCarGps.fusedEvent` encodes a real `SENSOR_EVENT_INDICATION` GPSLocation with 2/3 microdegrees plus optional 4 horizontal accuracy. It cannot return an event under RAW characterization, wrong GPS mode, unverified/held input or invalid values.
- `AaSession.startCarGps` selects fused only if the SESSION declared `DEAD_RECKONED`, and emits only if the phone has actually sent `SENSOR_START_REQUEST LOCATION`. Otherwise the existing raw OEM path and stale SENSOR_ERROR remain.
- Source-only tests `AaVerifiedDrLocationTest` assert exact byte sequences for nested LOCATION and SDP; reference conversion, wrong-origin/fake inputs fail closed.

## Blockers to owner's real acceptance
1. **AA phone LOCATION subscription (observed absent in 27 sessions).** Need phone-side 17.9 diagnostic/source evidence or final combined owner HU acceptance to establish when/how it subscribes. Cannot force a subscription by sending unsolicited frames; changing SDP flags *may* affect subscription but remains unproven.
2. **Verified actual vehicle motion.** OEM ICCOA `SensorIndex.GyroScope` and `Acceleration` raw axes/units/timebase are not yet normalized or certified. ICCOA `GearInfo.speed` likely km/h in prior logs but provenance remains unverified. Therefore there is NO production caller of `publishFromVerifiedEkf` yet. The AA fused path is completed for verified inputs, **not a working live DR**.
3. **Android Auto/Yandex consumption** is not proven by HU-side protocol tests or public docs; require real subscribed phone `LOCATION` effect after final field acceptance. Phone's own GNSS positioning still exists, especially if it never subscribes.
4. PHONE hard source mode intentionally does not advertise OEM LOCATION; Android Auto uses its own handset GPS. Its future fused PHONE-mode navigation would require a separate explicitly approved phone-side location routing mechanism; avoid mock-location/ADB and spoofing.
5. Even if projected AA uses supplied fused position, *ordinary ICCOA screen mirroring* is a separate system. The mirrored Yandex app reads Android LocationManager; SENSOR_LOCATION to AA cannot change it.

No plan advance, no success claim for Yandex routing, no new signed APK. Code is source-only under PR#6. Final acceptance is one shared FT/ICCOA field campaign after full source CI.
