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
| ICCOA CONTROL method 11 | OEM `NotifyNavigationInfo.isNavigating = 1` | Enables `CarLinkSensorMgr`, repeated sends even if unchanged | Coalesce duplicates, resend after bounded 15-second window, ALWAYS immediately send state changes, reset on new control channel |
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

1. **OEM Control11 duplicate suppression:** pure `IccoaControl11Gate` keeps last
   successful enqueue payload; sends promptly on changed navigation state,
   refreshes unchanged payload at most every 15 seconds when invoked; resets when
   connection closes or new CONTROL lane authenticates. Bounded retry logic
   remains in service. This targets the verified Control11 over-transmission in
   FT167 logs and does not alter H264 or navigation truth.
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

## Unit-test matrix added to development branch

- Duplicate/changed/reset/retry Control11 payload gating.
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
