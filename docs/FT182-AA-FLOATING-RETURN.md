# FT182 — drag-to-move AA return button

Status: SOURCE_PATCHED, RUNTIME_NOT_TESTED.

Owner change request (2026-11-13): Android Auto navigation search controls can be hidden by FT's fixed return icon. In FT182 the same button (compact and normal appearance) is draggable following an intentional 4-second stationary press. A short tap returns to FT; touching and holding without dragging never activates the return command.

**Scope**: only the FT overlay in Android Auto, on CarLife and ICCOA when their FT-composited display is active. FT's normal non-AA corner pill and its existing settings remain unchanged. Current compact/normal appearance selection stays intact.

Implementation: `AaFloatingReturnPlacement` provides pure normalized (0..1) geometry, clamping and exact corner initial position. `AaFloatingReturnButton` draws on the AA surface. Coordinates persist atomically in shared preferences `aaFloatingReturnX/Y` on completed drag and in the existing FT appearance backup. At first run, the existing AA bottom-corner preference applies. A normalized position is reprojected when resolution, aspect ratio or button size changes. No screen coordinate/GPS data logged. CarTouchZones dynamically follows moved button via onGloballyPositioned, allowing the existing CarLife/ICCOA FT-vs-AA touch routing to follow the icon rather than intercept its old location. No AA protobuf or HU transport change.

Acceptance: pure unit tests cover four-second constant, corner default, drag clamp, scaling to two resolutions and zero-size safety. Must additionally compile Android/Compose and run full JVM tests. On a real phone/HU: verify short tap, hold 4s then drag, hold and release without returning, old screen position becomes tappable in AA, new icon remains interactive, restart and aspect ratio change, compact and normal button, both CarLife and ICCOA on a real vehicle. Note: ICCOA direct H.264 passthrough bypasses FT's UI compositor; this feature cannot appear over its direct stream. Do not claim direct-video overlay support without separate integration.

No APK/signing/release has been performed by this source change alone.
