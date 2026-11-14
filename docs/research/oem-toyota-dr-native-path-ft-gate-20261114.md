# OEM Toyota Space Dead Reckoning: native call graph and FT integration gate (2026-11-14)

Status: READ_ONLY_RESEARCH / FT_RUNTIME_OWNERSHIP_UNCHANGED / DIRECT_OEM_DR_INTEGRATION_BLOCKED.
Target: owner's FAW RAV4 XA60 (P0 runtime logs and Carlink.apk), compared with GAC Wildlander UFS donor (P1 ELF). Donor files are NOT byte-identical proof of the owner's HU.

## Exact native chain reconstructed from donor ARM64 symbols/disassembly

1. `vendor.iauto.hardware.drinfo@1.0-service`, `/vendor/lib64/vendor.iauto.hardware.drinfo@1.0-impl.so`: `DRInfoController::initSensor`, `initVehicle`, `parseDcmData`, `getGnssData`, `getSensorData`, `pollGnssThread`, `pollSensorThread`. Gets GNSS, Android SensorManager IMU and vehicle/ECU telemetry (VHAL/DCM/SOME-IP path seen in donor). Emission: HIDL `IDRInfoCallback::notifyGnssData(iADRGnssData)` and `notifySensorData(iADRSensorData)`. Exact vehicle sensor calibration/sign and timestamp origin for owner HU remain unverified.
2. `/vendor/lib64/libdeadreckservice.so`: `MyDRInfoCallback::notifyGnssData/notifySensorData` -> `DreckDrlib::setGnssData/setSensorData` -> symbols in `/vendor/lib64/libDeadReckoning.so`: `iADR_SetGnssData`, `iADR_SetSensorData`; `DreckDrlib::startLocInfo` -> `iADR_StartLocationInfo`. Library uses compiled binary algorithm, not Kotlin/Java source lines.
3. `libDeadReckoning.so`: `GpsSnsDR::MakeDRResult/ReadDRResult`, `GSDR_SFProcessProxy::SetKalmanWorkingMode`, `GSDR_SFGpsDirCorr::DirCorr`, `GSDR_SFRoadDirCorr::DirCorr`, `NHInsSpeed::ReverseMainProc/FrontReverseDecide`, `NHInsAttitude::SlopeAngleCalc`, `GSDR_PkUnderGround::JudgeParkType`, `GSDR_PkGroundFloor::JudgeParkType`, `GSDR_PkInsideStructure::JudgeParkType`, `GSDR_PkWorkingState::CheckOnSlopeToSecondFloor/CalcParkingRecognize`. Presence of functions is NOT a field test of positioning without satellites or floor level.
4. `DreckDrlib::locationInfoCb(char*,uint32)` splits output into up to three newline-delimited buffers, each at most 132 bytes, with special `$PASCD` recognition. `DreckStub::drLocInfoCb` packs three `hidl_string`s and signals registered `IDRListener`s via `vendor.iauto.hardware.location@1.0::IDRProxy/default`. `DreckStub` exports `registerListener`, `unregisterListener`, `startLocationInfo`, `stopLocationInfo`, `clearLearnData`.
5. `/system/lib64/vendor.iauto.hardware.location@1.0.so` HIDL `LocationInfo` is three consecutive 16-byte `hidl_string`s: offset 0x00 GGA, 0x10 RMC, 0x20 `$PASCD`. `NHInsCarplayManager::makeLocInfo()` generates `makeGGANmea`, `makeRMCNmea`, `makePASCDNmea`. PASCD formatter native offset 0xcb398; verified time, numeric formatting and checksum, NOT the field semantic contract. Do not guess GPS accuracy, fix time, yaw, wheel units or parking-floor fields from PASCD.
6. `/system_ext/lib64/libnmcarplayservice.so` imports HIDL location library and `IDRProxy::getService`, calls `NMCarPlayLocationManager::requestStartLocationInfo` and `NMCarPlayLocationListener::notifyLocationInfo`, then `NMCarPlayAuthManager::requestSendLocationInfo` and `NMCarPlayIap2Manager::requestSendLocationInfo` to CarPlay iAP2. P1 proof of direct OEM DR consumer; NO equivalent direct ICCOA/Android FT client observed.

## Separate provider injection chain (NOT proven linked to IDRProxy)

7. `/vendor/lib64/vendor.iauto.hardware.gnssdr@1.0-impl.so`: `Gnssdrd::injectGnssData` (ARM64 offset 0x12de0) uses `android.hardware.gnss@1.0::IGnss::getService`, transmits external location/NMEA/SV; no direct ELF import of IDRProxy or libDeadReckoning found.
8. `/vendor/lib64/hw/android.hardware.gnss@1.0-impl.so`: `Gnss::injectExtLocation` (0x1406c) -> `helperReportLocation` (0x140d4) -> registered GNSS callback (`gnssLocationCb`). This CAN feed Android GPS provider, but donor binaries do not prove origin in `libDeadReckoning`.
9. Owner FAW XA60 2026-08-20 exact logcat: PID 957 has 2616 `injectExtLocation enter`, 2616 `helperReportLocation Fused location` and 2616 external SV events over approximately 262s, median source timestamp increment 100ms. 2165 `GnssLocationProvider Unexpected GNSS Location report after location turned off`; framework-disabled reports are not evidence Android clients received them. No captured GNSS satellite-loss interval or matching OEM `$PASCD`/RMC payload. `IDRProxy` shell find denied by SELinux on exact owner HU.
10. Owner FAW original `Carlink.apk` (`com.iauto.carlink`): `CarLinkSensorMgr$CarLinkLocationListener.onStarted` DEX code 0x2cc2f0 -> Android `LocationManager.requestLocationUpdates("gps",900L,0f,listener,looper)`. `onLocationChanged` 0x2cc2a0 -> `updateLocationInfo` 0x2ccb04 -> `UCarAdapter.sendGPSInfo` classes3 0x39fe60 -> ICCOA sensor category 2, method 1. GPS latitude/longitude/altitude come from Android Location; speed and bearing are floored; wire timestamp is `System.currentTimeMillis()` at pack time, NOT GNSS fix clock; no horizontal accuracy. No direct IDRProxy symbols/calls in 4 APK DEX or JNI `libovmsink.so`, `libusbio.so`.
11. OEM CarLink `onSensorChanged` 0x2cc6e0 calls `Math.floor` on all gyro/accel XYZ before ICCOA methods 3/4: sub-radian vehicle yaw irrecoverably lost. Gear callback DEX 0x2cd07c floors speed, wire unit unspecified (statistically ~km/h, not independently guaranteed). FT cannot reconstruct verified GNSS acquisition epoch/accuracy, non-lossy yaw or wheel calibration from these packets.

## Existing FT source and exact boundary

`IccoaService.onSensorMessage` -> `VehicleTelemetry.gpsUpdates` and `IccoaDrInputBridge.rawGps/rawGear/rawGyro` already receive actual CarLink sensor data. `IccoaDrInputBridge.submitQualifiedGnss/Motion` rejects those raw packets without independently verified source, units and synchronized clocks. `FtDrRuntime` -> `FtDrFusionEngine` -> `DrNavigationTelemetry` -> `FtPositionTelemetry` -> AA `AaCarGps.fusedEvent` exists for independently verified measurements. AA Google developer server in FT175 field captures did not request SENSOR_LOCATION, so even an FT fused event is not a proved AA app-consumed position.

## HU security boundary and integration decision

Donor SELinux `vendor_sepolicy.cil` line 416: `system_app_32_0` belongs to `hal_deadreckoningd_client`; line 4041 permits `find` on `hal_deadreckoningd_hwservice`. Exact FAW live ADB `shell` gets `avc: denied { find }` for `IDRProxy`; ordinary HU apps not proven permitted. FT is on phone; Android HIDL does not cross ICCOA/Wi-Fi automatically.
Do NOT port native Toyota ELF into Android phone, fake a PASCD decoder's field units, alter SELinux, inject fake location into vehicle, or assume CarPlay iAP2 is available to Android Auto.

Current usable path: Toyota Android GPS provider -> OEM CarLink -> ICCOA -> FT RAW position, with OEM DR provenance UNKNOWN. An OEM DR direct path requires either (A) P0 evidence that provider already emits OEM DR with genuine no-GNSS validation; or (B) an authorized HU-side OEM HIDL consumer and explicitly defined secure HU->FT transport supported by the owner and approved architecture. Neither is proven. Do not mark OEM DR active.
NEXT_EDGE: capture synchronized read-only `IDRProxy` GGA/RMC/PASCD from an OEM-authorized client and Android `gps`/CarLink on the exact owner's HU during naturally degraded GNSS while vehicle movement is logged, or verify a pre-existing sanctioned OEM route. Confirm source, clock, checksum, accuracy, sensor epoch and identity; then build source adapter under current FT admission gate. Owner approves runtime work separately; no HW writes, build, CI, APK or FTMP mutation in this audit.

## Evidence

- P1 exact disassembly report: https://drive.google.com/file/d/1q6kkf6G92Rs4oNUg6KMM_sFxRYO1V0wt/view
- P1 exact CarPlay/HIDL/GNSS provider trace: https://drive.google.com/file/d/1LeKpwKmr-OoeWhUqEiYTmxpuxltc4LeP/view
- P0 original owner logcat: https://drive.google.com/file/d/1xBe-rFvgjTNJf1m0qEs20m2AR779wTm2/view
- P0+P1 CarLink DEX report: https://drive.google.com/file/d/1euc4KHkJkVBuZFU3MTX_cxPWnUbi-tYZ/view
- Current FT185 source: cn-openivi/ft-app, branch openivi/ft185-video-default-gps-build-20261114
- Nexus active_instruction openivi-project-primary v3.2.12; live dump_schema overview 20114 rows (RE1=10052, RE2=10062), the indexed donor is not live FAW binary.
