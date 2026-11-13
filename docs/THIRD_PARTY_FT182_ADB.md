# FT182 — ADB TLS dependency and provenance

- `rhythmcache/adb-kt`, Apache-2.0, v1.0.6 source studied:
  https://github.com/rhythmcache/adb-kt/tree/v1.0.6
- `PinnedTlsPacketTransport.kt` is adapted from `lib/src/main/kotlin/io/github/rhythmcache/adb/TlsPacketTransport.kt`.
  Explicit fix: verify the SHA-256 of the TLS server's leaf certificate against the exact
  peer certificate learned ONLY after SPAKE2 authenticated pairing. Never use a trust-all
  transport for automatic reconnect. Restrict host to phone's own loopback.
- `AdbConnection.connect(..., skipInitialCnxn=true)` is essential after the plaintext STLS
  handshake already sent by the transport.
- `FileKeyProvider` persists the app-private RSA key; the peer SHA-256 is stored
  in `ft_adb_tls.peer.sha256`; no pairing codes are persisted.
- Dependency coordinates `io.github.rhythmcache:adb-kt:1.0.0` are publisher-documented,
  artifact resolution is NOT VERIFIED until owner permits Gradle/CI. If resolution fails,
  source stays blocked; do not fake PASS or silently weaken pinning.
- AOSP protocol details:
  https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/adb_wifi.md
- Preserve upstream license/attribution in derivative files.
