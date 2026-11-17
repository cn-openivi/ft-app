# FT200 — Android phone-side CarPlay: Bluetooth/iAP2 evidence gate

Status: **research and preflight only**, 2026-11-17. FT200.1 is still active pending full unsigned CI. This document does not advance FT200.2 or enable CarPlay in the application.

## Objective

Implement a third *real* FAW Toyota projection transport in FT200, preserving existing Baidu and ICCOA, using the **same physical phone and the same existing Toyota Bluetooth pairing** for CarPlay control, HFP voice calls, and A2DP/AVRCP media. Android Auto is an internal screen/audio source, not a fourth HU transport.

## Verified starting evidence

- Exact FAW Toyota XA60 `/system_ext/app/Carplay/Carplay.apk` static DEX/JNI inspection: package `com.iauto.carplay`, platform service, native `mediacarplay_jni` interface. `CarPlayAppCMMgr.getPhoneCallStatus` matches active CarPlay device address against HFP address. Static APK observation **does not prove** how an ordinary Android phone can initiate iAP2 or preserve Toyota's HFP connection.
- FT200 CarProtocol enumerates CARLIFE / ICCOA / CARPLAY; CarPlay has `connectionReady=false`. Existing `CarConnection` refuses starting it before stopping a working transport; FT200 UI shows a nonselectable Apple CarPlay entry.
- Android's `BluetoothAdapter.getAddress()` needs privileged `LOCAL_MAC_ADDRESS` (in addition to runtime `BLUETOOTH_CONNECT` on modern Android). This is **not** the same as the address of a paired *remote* `BluetoothDevice`, which is available to appropriately permitted apps.
- Public `BluetoothAdapter.listenUsingRfcommWithServiceRecord(name, uuid)` can register an **ordinary** secure RFCOMM service record and operate on the phone's system Bluetooth adapter. It does **not** establish the Apple iAP2-specific SDP attributes, MFi authentication, HFP audio route, or a CarPlay session merely by opening a socket.
- Android OS uses its real adapter identity for a normal Bluetooth link; a third-party app **does not necessarily need to read or spoof its local MAC** to share the physical pairing. The exact Toyota iAP2 identity and post-handover HFP behavior must be observed on the live FAW HU rather than inferred.

Sources:
- https://developer.android.com/reference/android/bluetooth/BluetoothAdapter
- https://developer.android.com/reference/android/bluetooth/BluetoothDevice
- https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
- Canonical OpenIVI Nexus FAW Carplay.apk claim object: ad666a61-ca0e-4221-b0da-f370fd1c842c
- Detailed OEM analysis: https://docs.google.com/document/d/1H4p1UUopCVqCbbiQQ1jlT0Y27ivgXPqL0BpREbHGaUI/edit

## No CatPlay source import

FT200 remains Apache-2.0 with its existing `NOTICE` and OneAboveAll1964 attribution. No CatPlay, Carjack or AGPL source is imported. Read public references as background; for implementation use original design and original Kotlin/Java source, and separately confirm third-party rights before any future reuse.

## Safe, measurable next implementation sequence (FT200.2 only after FT200.1 acceptance)

1. Implement *passive*, permission-gated paired-device and remote SDP capability collection: the remote Toyota BT address + `getUuids()` / explicitly requested `fetchUuidsWithSdp()`, tagged with time, Android version, and source. Never assume all UUIDs equal iAP2 support.
2. Add bounded connection state machine `UNAVAILABLE -> BT_DISCOVERED -> IAP2_NEGOTIATING -> WIFI_NEGOTIATING -> AIRPLAY_NEGOTIATING -> ACTIVE` with explicit failures and no synthetic success. Existing two transports remain unaffected.
3. Establish the real required iAP2 service identity/profile from exact OEM evidence or live paired-device traces. Do **not** hardcode a guessed CarPlay UUID, arbitrary fake iAP2 packet, or invented MFi certificate.
4. Connect Bluetooth/iAP2 using approved Android interfaces only if actual SDP and authentication requirements can be satisfied. Any non-public API requirement is a documented blocker, not a reason to request HU privilege or spoof another MAC.
5. Independently verify the Wi-Fi handover and AirPlay service/control handshake, then integrate verified compressed AVC path without unnecessary transcoding when compatible.
6. Confirm on the FAW vehicle that `CarPlayAppCMMgr` observes the same phone as HFP and that real inbound/outbound cellular calls use Toyota mic and speakers **while projection stays active**. Without this, FT200.4 remains blocked regardless of video success.

## Required acceptance and prohibitions

No new Toyota bond, no virtual MAC, no privilege escalation, no write to HU/cluster/DCM/CAN/VHAL. No CatPlay dependency. Do not set `CarProtocol.CARPLAY.connectionReady=true` until a verified real protocol session is available. Do not claim CarPlay, HFP, Wi-Fi, AirPlay or OEM DR verified from static donor files or standalone Kotlin smoke tests.

CURRENT FT200.1: GitHub unsigned source-only CI run https://github.com/cn-openivi/ft-app/actions/runs/44146033261 was initiated for FT200 branch; terminal outcome must be independently read before accepting stage.
