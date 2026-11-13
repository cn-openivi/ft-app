# FT182 — Android Auto car GPS negotiation and self-mode evidence

Status: **SOURCE_ONLY / NOT_COMPILED / NOT_FIELD_TESTED** (2026-11-13).
Source branch: `openivi/ft182-supercast-ux-source-20261113`.
Owner-approved scope: instrumentation and reliable diagnostics in FT182, **not** architecture changes.

## Grounded protocol

- Android Auto phone initiates `SensorRequest` on the SENSOR channel: message **0x8001**, field 1 `sensor_type=LOCATION(1)` and field 2 `min_update_period` (int64). HU responds with **0x8002** and may send GPS data as **0x8003** only after this request.
- `SensorChannelConfig` field 1 lists sensor types; field 2 is `location_characterization`. RAW only is **0x100**. Never replace it with a guessed refresh interval or claim DR without verified OEM wheel/yaw telemetry.
- `GPSLocation` fields 2 and 3 are signed `int32` latitude/longitude in degrees ×10^7; optional fields 4–7 are accuracy, altitude, speed, bearing.
- Exact original proto and independent structural reverse-engineering: [Open Android Auto SensorChannelConfig](https://github.com/mrmees/open-android-auto/blob/main/oaa/sensor/SensorChannelConfigData.proto), [SensorRequest](https://github.com/mrmees/open-android-auto/blob/main/oaa/sensor/SensorRequestMessage.proto), [GPSLocation](https://github.com/mrmees/open-android-auto/blob/main/oaa/sensor/GPSLocationData.proto).
- FT already has a byte-exact source test of SENSOR_LOCATION request (`08 01 10 E8 07`) and reply (`08 00`) in `AaSensorAdvertisementTest`. The FT182 instrumentation does **not** change outgoing protobuf bytes.

## New FT182 passive instrumentation

`AaGpsEvidence.kt` captures one session at a time, with generation isolation:
1. AA SDP requested, location listed as capability, characterization and whether a verified ICCOA fix was fresh at discovery.
2. SENSOR channel opened/closed and reopened; a closed channel invalidates active subscription.
3. A supported, actual inbound LOCATION SensorRequest.
4. Valid fresh FT position event submitted to the AA output writer.
5. Encrypted output frame **successfully written and flushed** to the socket.
6. Session termination with anonymous counters and diagnosis.

The service passes `selfServer` into `AaSession`: `DEVELOPER_SELF_MODE` means the existing AA developer headunit-server transport; `INBOUND_TRANSPORT` does not prove any physically separate car HU.

The same evidence is included in the existing diagnostic report ZIP, `aa-car-gps-evidence.txt`, and the `android auto` section of `report.txt`. Logging includes **no coordinates, location track, paired identity or PIN**, and no setup change is exposed to regular users.

### Possible diagnoses

- `NO_SERVICE_DISCOVERY` — a session never reached capability negotiation
- `CAR_LOCATION_NOT_ADVERTISED` — FT chose no verified car GPS capability
- `AA_SENSOR_CHANNEL_NOT_OPENED` — cannot conclude AA refused LOCATION
- `AA_LOCATION_NOT_REQUESTED` — channel opened, but no inbound LOCATION request
- `LOCATION_REQUESTED_NO_SOCKET_WRITE` — AA requested but no eligible event was flushed
- `LOCATION_SOCKET_WRITTEN_PHONE_CONSUMPTION_UNKNOWN` — output transport succeeded, **not** proof of Maps/Yandex/Magic Earth GPS selection

In all cases, **phone GPS use remains UNKNOWN** from the headunit side: Android Auto may retain phone GPS internally. Never claim an automatic phone fallback was observed only because no car subscription appeared.

## Field test matrix (after owner-authorized APK build)

1. Samsung AA18 + FT182 self-mode developer server; keep Android Location ON and CAR+ICCOA selected, open Google Maps; collect ZIP after 2+ minutes.
2. Same Samsung, same AA, Google Desktop Head Unit with `[sensors] location=true`; compare exact SDP and SensorRequest. A phone itself still chooses whether to subscribe.
3. If possible, external real Android Auto HU on the same phone (not FAW ICCOA unless it natively supports AA); no change to product architecture. A physical vehicle HU cannot be inferred from an inbound socket.
4. Repeat with a genuinely live OEM GNSS fix at discovery, then with a verified DR fix. Do not invent accuracy, change `location_characterization`, force a SensorRequest, or insert unsolicited GPS events.
5. Report separate observed gates: capability declared, channel opened, request received, transport write flushed, navigation app consumption **unconfirmed without independent app-level proof**.

Reference prior owner field findings: Nexus `53689938-e238-4ab7-84e5-3efdc217c26b` and `8b976b80-f6e9-48ae-9890-c7d73f62ff2d` (AA17.9/18.0 respectively), both found no LOCATION request in developer self-mode, including live OEM GPS at discovery.

Source-only tests: `AaGpsEvidenceTest.kt` plus existing `AaSensorAdvertisementTest.kt`.
**BUILD_VERIFICATION: NOT RUN. LIVE_VEHICLE_TEST: NOT RUN.** No signed APK or release, no FTMP plan advance.

## Controlled same-phone DHU A/B — physical experiment NOT RUN

Keep phone **Location ON**, the same Samsung/Android Auto version and navigation app in both branches. Disconnect one headunit before starting the other.

1. **FT182 self-mode:** establish actual car ICCOA GNSS if available, collect `aa-car-gps-evidence.txt`. Check discovery, GPS advertisement, open SENSOR channel, actual inbound `SensorRequest(LOCATION)`, eligible location events and flushed AA socket writes separately.
2. **DHU 2.x via USB Accessory (AOA):** preferred *external-headunit* comparison. On a workstation with Google's DHU installed, load an .ini containing:

   ```ini
   [general]
   touch = true
   [sensors]
   location = true
   ```

   Connect the **same phone** directly to DHU with the officially documented `./desktop-head-unit --usb` mode. Use DHU's `location LAT LON [accuracy ...]` only as **labeled synthetic diagnostic input**, not as an OEM GPS claim. Compare AA/navigation response and any trustworthy sensor-channel traces; DHU alone is not a raw protocol logger.
3. **DHU via ADB tunnel:** separate control run with the same phone and same .ini, using the Android Auto developer headunit server (normally port 5277) over ADB. This reuses the *developer-server path* also used by FT's self-mode; it is **not equivalent** to an independently negotiated external phone/HU runtime mode. If ADB-DHU and FT both omit LOCATION but AOA-DHU does not, investigate transport/mode discrimination before changing FT protobuf.
4. **Capability-negative control:** perform a separate AOA-DHU session with `[sensors] location = false` and compare navigation/cache effects. If needed also repeat on ADB-DHU. Never interpret a negative control's app behavior as a byte-exact subscription proof without a trace.
5. **Optional** real external Android Auto HU, if available; the FAW ICCOA receiver is not itself a native AA HU.

Record phone/AA versions, transport and session IDs without personal coordinates. Compare actual SDP capabilities and active SENSOR subscriptions. Treat absent trustworthy DHU wire capture as `SensorRequest=UNKNOWN` rather than claiming it never happened. Neither successful DHU `location` command nor FT socket write proves Maps consumed the location. No guessed AA preference fields, unsolicited FT LOCATION events, disabled phone Location or mock-location configuration changes.

Source: https://developer.android.com/training/cars/testing/dhu . A/B phone experiment and live `SensorRequest` observation are pending physical access.
