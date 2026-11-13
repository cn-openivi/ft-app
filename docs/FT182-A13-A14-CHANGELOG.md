# FT182 A13/A14 review

Status: **SOURCE_PATCHED / BUILD_NOT_RUN / DEVICE_NOT_TESTED**.

A13 before: AppCatalog distinctBy(packageName), Compose list key=package, CarControls only accepted pkg, CarLife and ICCOA both launched default package entry. After: distinct launcher identity (package, activity), stable component-scoped drawer key, both backends explicitly launch selected MAIN/LAUNCHER ComponentName. Existing package-only saved tiles remain default-launch, with no changed wire protocol bytes.

A13 JVM test: LauncherComponentsTest checks two activities of one package survive, duplicate resolver rows collapse, and component keys are unique/stable. Added but NOT RUN. Pending: device with two exported launcher activities, confirm launch/mirror under both CarLife and ICCOA and behavior if an app is removed.

A14: README removed duplicated Compact FT button copy and misleading backup/GPS descriptions; ICCOA-DEVELOPMENT no longer labels ICCOA 'experimental'; wireless-pairing guide corrects stale source SHA and A13 status; GPS guide defines same-phone DHU comparison with phone Location ON.

Acceptance: no Gradle/unit/CI/APK/sign/release until separate owner approval. No Google DHU physical phone test and no HU test. FTMP plans and worker assignments unchanged.
