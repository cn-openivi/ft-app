# FT185 — GPS/DR path audit staged, NO BUILD

Parent: FT185 first-touch welcome branch (which includes FT184 + SPS guard work).
This isolated source-only branch deliberately does NOT trigger CI/Gradle, sign or release APK.

Actual 2026-11-13 Samsung SM-S916B field ZIP: 2,424 GPS, 1,400 GearInfo, 8,983 Gyro, 12,495 Accel. Raw GNSS stopped ~23:22:45 while Gear/IMU continued into 23:24:24; displayed coordinates aged 88.6 s. AA session ended BEFORE GNSS loss, so AA tunnel DR was not tested.

Existing source: ICCOA raw event ingress -> VehicleTelemetry/VehicleMotionTelemetry -> FT RAW position -> AA GPS socket. The verified EKF classes FtDrRuntime/FtDrFusionEngine/DrNavigationTelemetry already exist, but live ICCOA code does not call acceptVerifiedGnss / acceptVerifiedMotion; no verified wheel speed, yaw-axis/units, GNSS horizontal accuracy or fix acquisition clock is present. Hence NO genuine DR is produced. Do not turn raw GearInfo or coarse raw gyro (x/y mostly -1 or 0; z always 0 in this archive) into fabricated yaw/position.

Changes: DrPathEvidence tracks per session 4 raw sensor counts, verified GNSS/motion ingress, EKF publication, FT FUSED acceptance, and actual raw/FUSED AA SOCKET flush. Export dr-path-evidence.txt, retain last completed session after disconnect, no raw coordinates in diagnostic summary. New pure regression tests for blocked raw input and verified synthetic path; NOT RUN under owner no-compile order.

Gear raw speed vs GPS m/s in original trace correlates strongly (r=0.9965; median ratio3.8 due to floored GPS); compatible with km/h but not independently verified. Need proof and calibrated axis/yaw/time/accuracy before enabling real DR. No new protocol fields, no PHONE fallback, no thermal or other changes.

Status: SOURCE_STAGED / NO_BUILD / FIELD_NOT_TESTED.
