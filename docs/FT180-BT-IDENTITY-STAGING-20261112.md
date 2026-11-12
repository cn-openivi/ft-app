# FT180 (SOURCE ONLY) — OEM ICCOA/Bluetooth HFP identity association

Base: FT179 owner-signed field source `b399c20a89a8ed513394bb7febd4f53ca54d89ad`.
Owner approved 2026-11-12; FT180 branch only. **DO NOT RUN GRADLE / CI / APK / SIGN / FIELD** before separate owner command.

## Verified OEM contract
- Exact XA60 factory Carlink.apk SHA256: `6292f74524a5f7eecb84fe80d7b4c63acd81835912f03cf085ee78ae44c076d7`
- `ucar.proto`: ControlIndex.bluetooth_mac = 22; BluetoothMacInfo op_type = 1 (ADD0, DELETE1); bluetooth_mac = 2 (string)
- OEM UCarAdapter stores phoneId->supplied hashed BT MAC in `car_bluetooth_sha265_config`; it looks up SHA256(exact raw HU Classic phone MAC string). Not Android Bluetooth bond key and not synthetic ICCOA Wi-Fi MAC.
- ADD body = protobuf field2 length64 SHA256 lower-hex: `12 40` + 64 ASCII bytes (66 total).
- On FT179, no CONTROL22 sender. Android 17 public API cannot be assumed to reveal phone's Classic MAC.

## Implementation scope
- `IccoaBluetoothMacInfo`: protocol codec + SHA256 exact HU-rendered MAC, fail closed on malformed/02:00 masked.
- `IccoaBluetoothIdentityLinkStore`: holds only SHA256 and off-by-default owner consent, outside FT ordinary Prefs export.
- Settings > Car > ICCOA: manually enter phone MAC as shown by HU, explicit confirmation, save hash, separately enable experimental sending; can disable/forget without OEM DELETE.
- `IccoaSession`: callback fires only once after signed AuthConfirm and authenticated CONTROL lane BOTH present; Quick/Normal authentication logic unchanged.
- `IccoaService`: send category1/method22 ADD once per authenticated session if opt-in and valid hash; log queued vs failed, NEVER claim OEM ACK.
- No automatic Bluetooth reconnect/HFP workarounds, no system pairing edits, no phone/car key substitutions.

## Pending gates
1. OEM schema and original code: P1 source verified.
2. Source staging and deterministic wire regression: added but tests UNEXECUTED (no compilation by owner order).
3. Gradle build, full release/unsigned APK CI: NOT STARTED.
4. Real parked-car A/B: NOT STARTED. HFP drops BEFORE ICCOA authentication in recent logs; cannot claim this post-auth message prevents the initial drop. Verify OEM mapping, next-session HFP, actual call audio, and HU single-row display separately.
5. Exact Samsung Classic BT MAC as presented by the HU is not yet available; do not guess it.

Research: https://drive.google.com/file/d/18_WhW2FGjOfEdI4Y-7anq4PQaw4gQb9S/view
Nexus owner decision: a6b906c3-612c-4c9c-9b03-3cd52f7eb087
