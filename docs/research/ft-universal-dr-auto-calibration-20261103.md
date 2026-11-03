# FT / OpenIVI — universal GNSS + IMU + wheel-speed dead reckoning

Status: **DESIGN + SOURCE-ONLY INGESTION**, 2026-11-03.
Owner explicitly authorized universal ICCOA/Baidu DR design, raw gyro/accelerometer
widgets, and near-zero-interaction GNSS speedometer calibration.
This document **does not authorize a new FT release, HU modifications, changing
canonical Nexus CURRENT_ITEM/NEXT_EDGE, or claiming working DR**.

## 1. Architecture: same estimator for both protocols

```text
FAW HU Carlink       Baidu CarLife HU          Selected PHONE GNSS
     |                     |                        |
ICCOA adapter         Baidu adapter           Android GPS_PROVIDER
GPS / GearInfo /      GPS / Velocity /        Location with speed /
Gyro / Accel          Gyro / Accel            bearing / accuracy
     |                     |                        |
     +-----------> Protocol-neutral measurements <--+
                   VehicleTelemetry
                   VehicleMotionTelemetry
                   WheelSpeedAutoCalibration
                             |
                  DR state estimator (future)
                             |
              FT widgets / separate AA LOCATION gate
```

- A decoder is protocol-specific; a source-tagged timestamped observation is not.
- Baidu has original protobuf service messages 0x00018011 Gyroscope,
  0x00018012 Acceleration, 0x0001800F velocity and 0x00018010 GPS.
  Source: public **asysbang/baiducarlife** generated proto2 files,
  Gyroscope fields type=1/X=2/Y=3/Z=4/timestamp=5,
  Acceleration X=1/Y=2/Z=3/timestamp=4. All 3 axes are required in proto2.
- ICCOA from original owner **Carlink.apk ucar.proto**: SensorIndex
  GyroScope=3, Acceleration=4, GPS=1, GearInfo=6; both inertial messages
  support three double axes and a uint64 device timestamp.
- Observed incoming ICCOA category 2 methods 3 and 4 are real frames, but
  their numeric values, unit basis, orientation and underground continuity
  are **not** established. Never fill them from the phone when labeling CAR.
- Vehicle protocols may report device timestamps in unknown time units:
  preserve raw; use Android monotonic receive clocks for bounded event-order
  estimates until verified.

## 2. Raw instruments on the car launcher

Two optional-by-source-but-automatically-visible instrument chips:
**Гироскоп** and **Акселерометр**, current X/Y/Z with raw-unit notation,
packet frequency in Hz, source **ICCOA or BAIDU**, validity counters and
**LIVE / HOLD / Нет данных**. No new settings and no interaction. Home redraw
is throttled; every incoming frame is still counted and retained. Switching
protocol preserves source isolation. Empty sensor streams must show missing
data, not invented zeros. Do not log continuous raw IMU values.

## 3. Wheel-size/speed calibration: zero actions for driver

The ECU wheel-derived speed can differ from true road speed after tire size
changes. Never modify GearInfo, factory gauge, or ordinary FT speed widget.
Correct **only the distance term used by future DR**.

```text
k_car = median( v_independent_GNSS / v_wheel_kmh )
v_DR_mps = k_car * v_wheel_kmh / 3.6
distance += v_DR_mps * delta_t
```

Automatically accept a sample while driving normally only when:
- wheel-speed units have been **verified for this protocol/vehicle**;
- independent GNSS location has **fresh, accurate speed**, sourced from
  Android Location GPS_PROVIDER, hasSpeed(), hasSpeedAccuracy() and
  hasAccuracy(), with a monotonic getElapsedRealtimeNanos() check. Never
  interpret a newly delivered but old batched Location as fresh. The source
  metadata is now retained in CarInfo.PhoneGpsFix and checked by
  PhoneGnssQuality before calling WheelSpeedAutoCalibration.observePhoneGnss.
  Both OEM wheel and motion unit-verification flags must be explicit;
  no production caller is enabled until OEM units are validated.
  GPS_PROVIDER and accuracy alone do NOT establish anti-spoof authenticity;
  do not use ICCOA GearInfo as the GNSS reference;
- both speeds 35–140 km/h, GNSS speed accuracy <=0.6 m/s, near-straight
  |yaw|<=2 deg/sec, |a|<=0.2 m/s²; readings synchronized;
- ratio 0.90–1.10; at most one accepted sample per >=900ms; at least
  60 accepted valid samples before marking confident. Use median, then
  slowly adjust; bounded gain avoids spuriously copying stop/start lag.
- while no GNSS fix exists, **freeze the last accepted coefficient**.
  Never learn from estimated DR positions or unverified AA coordinates.

Persist ratio/confidence under exact remembered trusted vehicle identity.
A factory-reset/repaired car or changed identity gets a fresh calibration.
When a driver changes tires, normal GNSS driving gradually updates k without
any button. Invalidation rules and stability thresholds must be unit-tested.
Unknown Baidu wheel velocity unit -> calibration disabled until independently
verified; never guess. No configuration screens needed.

The standalone `WheelSpeedAutoCalibration` module in this branch is
**not yet wired into production location output**; that is intentional.

## 4. Estimator stages (no new drives required by this design)

**Source ingest:** timestamped GNSS(lat/lon, accuracy, speed), vehicle velocity,
gyro(X/Y/Z), accelerometer(X/Y/Z), source identity, provenance and freshness.

**Calibration online while GNSS works:**
- Learn wheel-speed ratio as above and gyro zero-rate bias while vehicle speed
  is zero; identify mounting axis/sign for vehicle yaw by correlating measured
  inertial axes with *real* GNSS course changes during normal turns.
- Determine protocol-specific units and axes; do not assume Android axes map
  onto car body axes or assume gyro values are rad/s.
- Never estimate position from accelerometer double integration alone;
  it drifts rapidly. Acceleration is an auxiliary consistency/quality check.

**Loss-of-GNSS prediction:**
- Start from last *valid* position plus verified heading; project in local
  East/North tangent coordinates, heading from gyro yaw increment minus bias,
  distance from calibrated wheel speed.
- Example 2D kinematics:
  `east += distance * sin(heading)`,
  `north += distance * cos(heading)`,
  `heading += (omega_yaw - bias) * dt`.
- LATEST CANONICAL P0-B (2026-11-03 Nexus rev39): the FT on-screen GPS
  selector is **car-primary with automatic 7-second car/phone fallback and
  7-second continuous-car reacquisition**, restored from signed FT174.
  This supersedes the earlier hard dashboard CAR/PHONE manual mode above.
  A fresh GearInfo timestamp MUST NOT be treated as fresh GPS positionAtMs.
  Separately, the future TinyEKF GNSS correction source gate remains strict:
  CAR accepts verified OEM GNSS only, PHONE accepts handset GNSS only; that
  never disables the independent FT dashboard 7s source selector.
  Verified vehicle speed/gyro may drive DR prediction without GNSS; no
  second filter or unverified handset IMU substitution.
  When the selected GNSS disappears, DR predicts immediately using verified
  car motion, never waiting seven seconds. Without verified OEM motion
  the estimate is not advanced by guessed IMU data.
- A 2D EKF/ESKF would track [east,north,yaw,velocity,gyro_bias,wheel_scale],
  covariance and data quality. Continuous true GNSS measurements correct
  position/yaw and calibrations; wheel/IMU drive the prediction step.
- At any duration without trustworthy GNSS, maintain the 2D DR prediction
  while **verified** wheel speed and vehicle yaw remain available. Do not
  switch DR off solely because 120 seconds elapsed or uncertainty exceeded
  150 metres. These two old arbitrary hard cutoffs were owner-rejected.
- Continue propagating covariance and show **DR · расчётное положение**,
  elapsed time since trustworthy GNSS and growing estimated horizontal
  uncertainty. High covariance means increasingly uncertain, not GPS-valid.
  Never claim those coordinates are a genuine GNSS fix.
- When current verified wheel speed / yaw or numerical integrity is lost,
  do **not** invent movement; hold last reported estimate with a stale/
  unavailable-data label until prediction can be safely resumed.
- The selected source's GNSS is assimilated through the SAME TinyEKF 2D
  measurement update, not a separate smoothing filter. The update now
  calculates full 2D normalized innovation squared (NIS) from East/North
  residuals AND predicted cross-covariance. NIS >25 rejects single
  implausible jumps; moderately inconsistent NIS 9..25 inflates the
  measurement covariance for smaller EKF gain, reducing 10–100m
  urban-canyon/mountain multipath displacement.
- Verified longitudinal OEM acceleration (if the orientation and SI units
  are independently established) cross-checks wheel-speed change and
  inflates motion-process covariance during disagreement. Gyro yaw already
  drives EKF heading prediction. Do not double-integrate the accelerometer
  or create a second filter.
- Time delta, non-finite values, impossible raw sensor measurements and
  suspicious GNSS jumps are still validated. Adaptive NIS is only
  *statistical consistency*, not a guarantee against slow, coordinated
  or plausible GNSS spoofing. Other-source GNSS is never substituted
  automatically. No hard 120s/150m DR shutdown.
- On reacquired GNSS: innovation/outlier gate then time-limited blending,
  not a teleport, with original source annotation.
- Android Auto's own AaGpsGate previously cleared its trusted emitted
  location after a 3-second GNSS outage; three stable but distant spoofed
  fixes could then be emitted. The source-only AA fix now preserves the
  last *delivered* trusted anchor across any signal outage, while resetting
  only the candidate triple. Subsequent fixes must be plausible relative to
  the last actually delivered position and elapsed time; explicitly restarting
  a new AA sensor session may still reset the anchor. This gate rejects
  implausible jumps, NOT physically plausible spoofed GNSS drift. Independent
  wheel/IMU-based plausibility gating remains future work pending verified
  units and directional calibration.
- Do not inject generated locations into Android OS or mirrored Yandex
  applications. Android Auto LOCATION requires actual phone SENSOR subscription
  and appropriate characterization; existing RAW_GPS_ONLY must not label
  synthetic DR as genuine GNSS.

## 5. Open-source candidates examined

| Project | License | Suitability |
|---|---|---|
| `simondlevy/TinyEKF` | MIT (LICENSE.md verified) | Best *small reference/library*: header-only C/C++ EKF, compile-time storage, low overhead; NDK/JNI wrapper would add build complexity. No ready-made Toyota DR. |
| `commaai/rednose` | MIT (LICENSE verified) | More complete Kalman/EKF framework, Python/SymPy-based model generation; useful mathematical reference, heavier integration path. |
| `cra-ros-pkg/robot_localization` | BSD-family (LICENSE verified) | Proven GNSS + IMU + wheel odometry EKF/UKF and navsat conversion; **ROS 2 stack/Eigen/TF** is excessive for an Android FT client. Use as functional reference only. |
| `PX4/PX4-ECL` | BSD-3-Clause (repo license) | Mature aviation-oriented estimator, over-complex for this ground-car 2D problem. |

Implementation decision (owner-approved 2026-11-03): **TinyEKF selected and embedded**.
Frozen original Simon D. Levy MIT header and full license are at
`third_party/TinyEKF/{src/tinyekf.h,LICENSE.md}` (upstream exact commit
`ba0d2a90e22f2f4e4120a91cb05ca2ca6a8e8da3`). Its EKF matrix
prediction/update were ported directly to bounded pure Kotlin double
precision as `app.ft.dr.TinyEkf` and used by source-neutral
`app.ft.dr.DeadReckoningEkf`. The latter has 4 states
[east_m, north_m, yaw_rad, gyro_bias_rad_per_sec], a 2D GNSS observation,
Cholesky-positive innovation validation, outlier gates, unrestricted
outage duration **while inputs remain verified** and propagated covariance
(without an arbitrary 150m uncertainty cutoff). This avoids JNI, NDK, CMake,
native ABI packaging and additional user setup on weak Android phones.
The implementation includes exact synthetic source-independent tests.

**Source integrated is NOT yet a functioning live car DR**: raw OEM axes/units
and wheel-speed units require verification before an adapter sets
`wheelAndGyroVerified`; the existing calibrator and TinyEKF are not
wired into live production location output. FT launcher source selection
uses the restored FT174 **automatic 7-second car-primary switch**; AA offers
OEM LOCATION in the CAR+ICCOA provenance mode after negotiation only when
the phone explicitly subscribes. DR GNSS gate
`correctSelectedGnss` enforces the chosen origin inside the same TinyEKF
kernel. Smartphone magnetometer/gyro are excluded because handset
mounting is unknown; verified vehicle IMU may be used in both modes.
No new APK is built. Do not copy large ROS/autopilot stacks.

## 6. Proof gates & acceptance

1. **DOCUMENTED_SCHEMA:** original ICCOA ucar.proto and generated Baidu proto2
   identify exact field numbers and wire formats.
2. **CURRENT_SOURCE_COMPARISON:** no existing OEM navigation/AA/GPS write paths
   altered; source-by-source motion telemetry uses new read-only methods.
3. **EXACT_MESSAGE_FORMAT_TEST:** direct/wrapped ICCOA samples, strict Baidu
   required-field protobuf, malformed packet rejection, source isolation,
   freshness and GNSS wheel-ratio gates.
4. **BUILD_VERIFICATION:** source-only Gradle tests; no signed APK or new release.
5. **LIVE_VEHICLE_TEST:** pending, **not requested from owner again**.
   Owner's existing 18:02–18:05 parking logs remain preserved evidence.
   We cannot infer IMU units or working DR from method presence alone.

Preserve owner-approved current FT/ICCOA stage numbers. Do not advance
Nexus plan or claim runtime DR until independently verified.

## 7. Owner acceptance: tunnel, canyon and Kremlin (2026-11-03)

The owner defined THREE critical **real navigation outcomes**, not merely
"GPS numbers visible in the FT overlay":

1. **Tunnel / missing satellites:** when the selected GPS signal is lost,
   TinyEKF continues IMMEDIATELY with independently verified OEM wheel
   speed + mapped yaw/gyro, including turns inside the tunnel. No seven-
   second wait for the DR predictor itself, while the independent FT widget
   retains its automatic 7-second fallback; no 120-second timeout.
   An existing destination/turn-by-turn navigation client must actually
   consume the resulting position, otherwise this acceptance case fails.
2. **Urban-canyon / mountain multipath:** if the selected receiver reports
   fresh positions but reflections introduce 10-100 m lateral excursions,
   the SAME TinyEKF position update checks 2D normalized innovation squared
   against the verified wheel/yaw trajectory. Reject gross jumps and
   down-weight moderate innovations with adaptive measurement noise;
   acceleration-to-wheel derivative disagreement increases process
   uncertainty. Do not create a second GPS smoothing filter. Arbitrary
   hard stops and fake map-matching claims are forbidden.
3. **Kremlin-like jamming and spoofing:** absent, non-finite or impossible
   jumps to an airport or another district cannot overwrite the trusted
   odometric trajectory, even when the GNSS receiver claims excellent
   precision and plenty of satellites. Remain on current selected CAR/PHONE
   GNSS origin without covert substitution. Continue labelled DR with
   verified car wheel/IMU, rising covariance, and validated correction
   when the genuine selected GPS returns.

**Limitations accepted as implementation blockers, not successes:**
- TinyEKF innovation gating cannot guarantee detection of smoothly
  drifting, plausible spoofing. Both car and handset can be attacked by
  the same source. Require independent verified odometry for cross-check.
- Verified OEM wheel units, IMU mounting-axis mapping, yaw sign and
  inertial timestamp units remain P0 hardware-evidence gates; do NOT
  enable prediction from unknown raw methods 3/4 merely because packets
  exist. Never invent confidence or mark synthetic DR as genuine GPS.
- **Navigation output remains unimplemented:** the current standalone
  `DeadReckoningEkf` is NOT yet wired to FT's user-facing location stream
  or Android Auto's phone-subscribed LOCATION sensor channel. Android
  Auto must negotiate genuine sensor subscription and suitable fused
  location characterization before delivering a derived estimate.
  A mirrored phone Yandex app reads Android's own LocationManager, not
  FT's private TinyEKF, and will not automatically receive our estimated
  position; do not claim tunnel turn-by-turn works for mirroring.
- Source-only deterministic tests:
  `app/src/test/java/app/ft/dr/DrRealWorldScenariosTest.kt`
  cover tunnel exit turn without new GNSS, 10-100m canyon excursions,
  bad-coordinate jamming, stable airport teleport spoof, PHONE-only
  GNSS mode with shared verified car odometry, and explicit fail-closed
  for unverified yaw. Synthetic fixtures are **not on-car acceptance**.

All three scenarios require the fifth owner gate only at the single final
combined FT+ICCOA acceptance. Continue source/tests/CI in between, do not
require another parking trip or interim signed APK.
