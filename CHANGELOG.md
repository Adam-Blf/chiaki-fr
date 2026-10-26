# Changelog

All notable changes to Chiaki FR, the Android TV fork of chiaki-ng, are listed here.
Versions follow the upstream chiaki-ng version with a `-fr.N` suffix.

## 1.10.0-fr.1 - 2026-10-26

### Added
- Own application id `fr.beloucif.chiaki`, so the fork installs next to the original Chiaki.
- arm64 build and a Docker toolchain (`scripts/android-docker/Dockerfile`).
- French interface, plus 28 more languages with declared supported locales.
- In-app PSN login that fills the account ID during pairing.
- Full controller mapping, controller rumble, in-stream menu (statistics, display, resume, standby, quit) and a stats overlay.
- OK long press on a console tile (remote or gamepad) opens its actions: wake, edit, delete.

### Changed
- Low-latency pipeline by default: low-latency MediaCodec keys (Nvidia included), newest-frame rendering, 3 ms input wait, Oboe game audio capped at 40 ms queued, 256 KB socket buffer, loss reporting for bitrate adaptation.
- Interface restyled after the PS5 home screen: deep blue background, PlayStation-blue accent, white focus ring with glow, a horizontal row of console tiles, Source Sans 3 font. No Sony logo or asset is shipped.
- Home header fits on one row (Settings moved to the top right) and tiles are 240 dp high, so they no longer overlap the hint on a 1080p TV.

### Fixed
- D-pad right on the last console tile, or left on the first, no longer jumps to the header buttons.

### Known limits
- Measured on an Nvidia Shield with a PS5 on Wi-Fi: 60 frames/s, 2 to 3 ms network latency, about 1.5 ms decoding, 0 % loss.
- The OK long press is verified with a touch long press only; `adb input keyevent --longpress` releases too fast to test it.
