# FT / OpenIVI — universal GNSS + IMU + wheel-speed dead reckoning

Status: **DESIGN + SOURCE-ONLY INGESTION**, 2026-11-03.
Owner explicitly authorized universal ICCOA/Baidu DR design, raw gyro/accelerometer
widgets, and near-zero-interaction GNSS speedometer calibration.
This document **does not authorize a new FT release, HU modifications, changing
canonical Nexus CURRENT_ITEM/NEXT_EDGE, or claiming working DR**.

## 1. Architecture: same estimator for both protocols

```text
FAW HU Carlink       Baidu CarLife HU          Phone GNSS (secondary)
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
  Location.hasSpeed()/getSpeed()/hasSpeedAccuracy() or equivalent verified
  external GNSS; do not use ICCOA GearInfo as the GNSS reference;
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
- Prefer car GNSS if available, then recent independent phone GNSS according
  to the existing 7-second UI source gate; DR only if **both** absolute fixes
  are absent and calibrated wheel speed + yaw are healthy.
- A 2D EKF/ESKF would track [east,north,yaw,velocity,gyro_bias,wheel_scale],
  covariance and data quality. Continuous true GNSS measurements correct
  position/yaw and calibrations; wheel/IMU drive the prediction step.
- Short outages: smooth DR prediction. Long gap or growing uncertainty:
  show explicit **DR · estimated / accuracy unknown**, then stop reporting
  location rather than silently claiming valid GPS.
- On reacquired GNSS: innovation/outlier gate then time-limited blending,
  not a teleport, with original source annotation.
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

Recommended implementation: keep the protocol-neutral Kotlin model and
vendor only the minimum reviewed MIT-permitted EKF logic if needed, retaining
full attribution/license; evaluate TinyEKF vs lightweight Kotlin 2D estimator
before adding Android NDK. Do not copy large ROS, autopilot or Python stacks.

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
