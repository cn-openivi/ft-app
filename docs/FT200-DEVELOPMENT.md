# FT200 — three OEM projection backends and one phone identity

Status: **source-only development started**, 2026-11-17. This is NOT a CarPlay-compatible APK and NOT field acceptance.

## Authority / scope

- Owner launch: «Начинай разработку ft200».
- Live Nexus instruction `openivi-project-primary` v3.2.12.
- Owner short-plan decision: `63c2a159-7dd1-41d2-9fba-844f880d33c6`.
- Numbered FT200 development master decision: `dabcb418-3998-45c1-9967-4dc2284378c1`.
- Exact FT190 branch parent: `dd50e77e74165a8a6ebc766d2937163e63a0060a`.
- Development branch: `openivi/ft200-carplay-three-backends-20261117`.

Do not alter the FT190 release branch or the separate FTMP roadmap/workers. No HU, CAN, DCM, VHAL, firmware, or platform privilege modification.

## Permanent FT200 scope

1. **FT200.1** — preserve existing Baidu CarLife and ICCOA selection, pairing and running transports without regression.
2. **FT200.2** — implement Android phone-side Apple CarPlay via iAP2/AirPlay. Audit clean-room Carjack/Slipstream and `catplay` transitive code/rights before importing.
3. **FT200.3** — one true Android handset Bluetooth identity, one Toyota pairing: CarPlay iAP2 advertisement, native HFP, A2DP, AVRCP.
4. **FT200.4** — blocking acceptance gate: real incoming/outgoing carrier calls through Toyota MIC and speakers while CarPlay is active. If Toyota drops HFP, do not invent Android unprivileged call downlink capture.
5. **FT200.5** — media/arbitration/steering-wheel and voice focus; no duplicated audio output.
6. **FT200.6** — OEM GNSS, independently proven DR only, instrument guidance and supported AVC passthrough without unnecessary transcoding.
7. **FT200.7** — all three transports regressions and exact FAW vehicle verification.

Android Auto is a CONTENT/UI source in FT, NOT a fourth transport to the Toyota HU. Only one OEM projection transport is active at once.

## FT200.1 actual initial source diff

- `app/src/main/java/app/ft/carplay/CarPlayPhoneIdentity.kt` — pure Kotlin phone identity contract. Rejects null, malformed, zero, multicast, and Android's masked `02:00:00:00:00:00` address. Compares the real local phone identity against Toyota's observed HFP device address without generating an alternate MAC. `SAME_DEVICE` is **identity only**, not evidence of audio. The actual platform address reader/iAP2 advertisement is not connected yet.
- `app/src/test/java/app/ft/carplay/CarPlayPhoneIdentityTest.kt` — exact same/mismatched/unknown HFP and masked addresses.
- `app/src/test/java/app/ft/core/CarProtocolFt200CompatibilityTest.kt` — preserves old Baidu `carlife`, ICCOA `iccoa`, persisted preferences and unknown-value default.

Original `CarProtocol`, `CarConnection`, `CarControls`, services, UI, manifest, versionCode/signing, and existing dependencies unchanged. **No CARPLAY selection appears yet**, so this source snapshot cannot accidentally connect using a fake implementation or misroute it to Baidu.

## Verification

Independent GitHub compare from exact parent shows only 3 added files, no deletions of existing code. Re-fetched original CarProtocol from exact FT200 branch: git blob `5c55fa9cb757bca2757090e4be88bfc62500d0ad`; CarPlayPhoneIdentity blob `39f3a8368b0f9b12fcd9081c4ff697f26311acf5`.

A local, isolated Kotlin/JVM smoke harness compiled the exact `CarPlayPhoneIdentity.kt` bytes (blob hash match) with `kotlinc` and exercised normalization, masked address rejection, SAME/DIFFERENT/UNKNOWN HFP: PASS. A second isolated harness compiled the exact unchanged `CarProtocol.kt` bytes and verified original Baidu/ICCOA IDs and default: PASS.

**NOT VERIFIED:** GitHub Android Gradle/JUnit full suite, APK packaging, live Toyota CarPlay handshake, iAP2, WiFi/AirPlay, actual HFP calls, A2DP, AA/cluster DR. Smoke test is not a CI gate.

## Next approved edge

Read the current Carjack/Slipstream Android Bluetooth iAP2 source and licensed `catplay` dependencies in detail. Implement the smallest independently testable phone-side CarPlay handshake adapter in the new branch, obtaining the real local Bluetooth identity by an approved Android mechanism; keep the backend unselectable until a genuine protocol session exists. Validate full Android Gradle CI and regressions before exposing protocol selection. Never market successful call audio without physical test.
