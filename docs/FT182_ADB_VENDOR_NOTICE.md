# FT182 — vendored ADB/SPAKE2 and provenance

- adb-kt v1.0.6: https://github.com/rhythmcache/adb-kt/tree/v1.0.6 — Apache License 2.0, Copyright 2026 rhythmcache.
- spake2-kt v1.0.2: https://github.com/rhythmcache/spake2-kt/tree/v1.0.2 — Apache License 2.0, Copyright 2026 rhythmcache.
- Both source sets are retained in FT's app/source tree under original package names. Versions pinned by exact upstream tags, not an uncertain unverified binary dependency.
- Upstream AdbClient.kt and the unpinned upstream TlsPacketTransport.kt were NOT copied. AdbConnection.kt was changed only to disable implicit TLS autodetection, requiring explicit pin-aware transport.
- PinnedTlsPacketTransport.kt is an adaptation of TlsPacketTransport.kt, checking the paired server certificate SHA-256 before permitting encrypted ADB CNXN or shell commands. PairingClient.pair is SPAKE2 authenticated.
- Store peer SHA-256 in app-private ft_adb_tls.peer.sha256 and a stable app-private RSA key; never store six-digit codes.
- Compatible build dependencies: Maven Central Okio 3.18.1, BouncyCastle 1.85 (bcprov/bcpkix/bctls); no JitPack requirement.
- Verify binary dependencies, source compile, exact TLS handshake and physical phone after separate authorization. This is SOURCE_ONLY/NOT_COMPILED.
- FT source derivative must preserve Apache-2.0 notices and original authorship.
