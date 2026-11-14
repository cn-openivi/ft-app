# FT185 integrated: Direct AVC first, automatic composited fallback
Owner: source + compile + unsigned test candidate authorized 2026-11-14, Nexus DECISION 0df87910-392a-4f55-a5f4-d34461253993.

Base: FT185 standard AA/WFD source branch SHA 96f4c2360a871b20a63cdcde359c597799a4c356; incorporate FT185 GPS/DR diagnostic source SHA 42bb9835079bb7d46ffa73b1f6e6cc70dd99a48a, do not reactivate unverified DR.

- Default Direct AVC=1 on fresh/unspecified installs. Explicitly persisted user/per-car normal mode=0 stays normal. Existing nonstandard manual AA resolutions continue normal path, not force-fit.
- Apply source-verified standard AA/WFD intersection by negotiated WFD mode, NOT hardcoded per brand/phone. Actual SPS visible/coded/cropping/profile/level/fps and PPS and first IDR required to stop FT compositor.
- If incompatible initial SPS/PPS, lock out Direct for this ICCOA session; stay on composited FT video, no repeated mode flips. If Direct activates then AA stream/session changes, mode changes, stalls without first IDR, or manually restores normal, fallback restores FT virtual display/compositor/encoder.
- Preserve touch mapping and floating AA controls from video branch, location and DR evidence counters from GPS branch.
- Source-only tests of mode preference and emergency compatibility; no claim of direct confirmed on real Toyota. Next physical test must observe DIRECT_VIDEO activated or a specific fallback reason.
- This workflow compiles and assembles UNSIGNED temporary APK, NOT owner signed. It cannot update already owner-signed FT184. Signing/release is a separate owner-authorized gate.
