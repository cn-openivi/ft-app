# Samsung AA 17.9 LOCATION differential — P0-B source-only handoff

Status: RESEARCH_ONLY / LIVE_SAMSUNG_TEST_NOT_RUN. 2026-11-04. No new APK, HU write or release request.

## Grounded blocker
- Signed FT174 owner logs: 27 Android Auto SENSOR_LOCATION=1 service advertisements, zero actual phone->FT SENSOR_START_REQUEST(LOCATION=1). GEAR/NIGHT_MODE/DRIVING_STATUS requests did appear. Sensor channel connectivity is not proof of GPS subscription.
- AA protocol: phone->HU 0x8001 SensorRequest with enum field1 and int64 refresh interval field2; HU->phone 0x8002 response, 0x8003 SensorEventIndication, 0x8004 SensorError. A standalone service advertisement is not a request.
- Independent AA proto implementation https://github.com/aa-proxy/aa-proxy-rs/blob/main/src/protos/protos.proto uses latitude_e7/longitude_e7 and accuracy_e3; https://github.com/aa-proxy/aa-proxy-rs/blob/main/src/mitm.rs divides by 1e7/1e3, confirmed independently by https://github.com/andreknieriem/open-headunit/blob/main/app/src/main/java/com/andrerinas/openheadunit/aap/protocol/messages/LocationUpdateEvent.kt . The repaired FT branch has exact source-only fixtures.
- Official Google DHU reference https://developer.android.com/training/cars/testing/dhu says its simulator location sensor is DISABLED by default unless enabled in the launch INI [sensors] location = true. It supports terminal command location LAT LON [accuracy_m] [altitude_m] [speed_mps] [bearing_deg]. A default DHU test is NOT a valid comparison.

## Future authorized same-Samsung reference test (NOT RUN)
1. Record same Samsung/AA version, nav app and permissions; preserve signed FT174 reference and PR#6 source SHA. Use locally installed Google SDK DHU only if computer and phone are available and owner permits.
2. Enable [sensors] location = true in DHU config before launch. If supported, also enable gear/night_mode/driving_status and match display DPI/resolution without guessing unsupported options.
3. Start the official AA head-unit server and DHU on the same Samsung. Standard USB DHU transport can use adb forward tcp:5277 tcp:5277 as documented. The developer-supported command location 55.755826 37.617299 5 supplies simulated GPS for REFERENCE only, not for the real Toyota HU.
4. Check whether nav actually follows DHU location with sensor enabled versus disabled. Only if phone-side diagnostic logs expose exact bytes, capture the real 0x8001 LOCATION request and 0x8002 response; a moving map alone is NOT proof the request was parsed.
5. Compare byte-accurate DHU/reference SDP with FT: sensor enum list, sensor config field2, head-unit metadata, NAV type2, channel opens and request timing. Do not blindly change characterization or emit unsolicited 0x8003 events.
6. Final FT acceptance on actual FAW XA60 requires Samsung->FT real LOCATION request, FT success response, continuous authentic OEM E7 updates, and nav-consumption verification. Do not confuse projected AA GPS with ordinary Android LocationManager.

## Verified / unknown
- VERIFIED: missing Samsung FT174 LOCATION request in owner log; existing FT send path subscription-gated; branch fixes E7/E3 payload, GEAR/LOCATION capability independence; exact tests.
- UNKNOWN: why Android Auto 17.9 did not request LOCATION, result of any DHU same-phone test, real navigation consumption, wheel/IMU physical units, live DR.
- Gate: SOURCE_VERIFIED_FIELD_UNVERIFIED; one owner-approved final combined FT+ICCOA vehicle campaign. Preserve canonical FT CURRENT_ITEM02/NEXT_EDGE02 and ICCOA CURRENT_ITEM01/NEXT_EDGE01.
