# Embedded TinyEKF — FT universal Dead Reckoning

Upstream project: https://github.com/simondlevy/TinyEKF

Frozen upstream commit: `ba0d2a90e22f2f4e4120a91cb05ca2ca6a8e8da3` (master as inspected 2026-11-03).

Vendor files:
- `src/tinyekf.h` — verbatim source from upstream `src/tinyekf.h`.
- `LICENSE.md` — verbatim original MIT notice. Copyright © Simon D. Levy.

FT Kotlin adaptation:
- `app/src/main/java/app/ft/dr/TinyEkf.kt` — Kotlin translation of TinyEKF's `ekf_initialize`, `ekf_predict`, and `ekf_update` equations with bounded dimensions and `Double` arrays.
- `app/src/main/java/app/ft/dr/DeadReckoningEkf.kt` — FT-specific 2D motion model: East, North, yaw, yaw-rate bias; supports true GNSS location correction and guarded wheel-speed/gyro prediction.
- Unit tests `TinyEkfTest` and `DeadReckoningEkfTest`.

Why the translation instead of JNI: TinyEKF is a header-only C/C++ algorithm, not a ready-made Android AAR. FT runs on many Qualcomm/MediaTek Android phones. Building JNI binaries for their ABIs would add NDK, Gradle CMake, ABI package size, loading failures, and maintenance for a four-state model. The translated Kotlin implementation retains the EKF mathematical equations, references the exact frozen MIT source, and adds host-runnable deterministic unit tests. It is **not** an unrelated EKF.

**Current integration boundary:**
- TinyEKF filter and reusable `DeadReckoningEkf` are in app sources, not just documentation.
- OEM raw gyro units, axes, sign and timestamp units have **not** been confirmed. `DeadReckoningEkf.predict` requires `wheelAndGyroVerified = true` from an upstream trusted adapter. This flag is **not** set automatically by an ICCOA or Baidu packet.
- Universal `WheelSpeedAutoCalibration` already exists in source but does not yet directly feed `DeadReckoningEkf` from production telemetry. A later, explicitly verified source adapter must provide wheel speed (m/s), yaw (rad/s), dt (s), and an accurate genuine GNSS anchor (local East/North metres).
- No estimated coordinates are injected into vehicle OEM, Android LocationManager, FT widget GPS selector or Android Auto.
- No new signed APK or vehicle run was requested or triggered. P0 on-car validation remains pending.
