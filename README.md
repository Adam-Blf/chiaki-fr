# Chiaki FR

<!-- adam-badges:start -->
[![version](https://img.shields.io/badge/version-1.10.0--fr.1-163767?style=flat-square)](CHANGELOG.md)
[![commits](https://img.shields.io/github/commit-activity/t/Adam-Blf/chiaki-fr?color=001329&label=commits&style=flat-square)](https://github.com/Adam-Blf/chiaki-fr/commits)
[![visites](https://hits.sh/github.com/Adam-Blf/chiaki-fr.svg?style=flat-square&label=visites&color=001329)](https://hits.sh/github.com/Adam-Blf/chiaki-fr/)
[![last commit](https://img.shields.io/github/last-commit/Adam-Blf/chiaki-fr?color=D4A437&style=flat-square&label=dernier%20push)](https://github.com/Adam-Blf/chiaki-fr/commits)
[![top language](https://img.shields.io/github/languages/top/Adam-Blf/chiaki-fr?style=flat-square)](https://github.com/Adam-Blf/chiaki-fr)
[![license](https://img.shields.io/github/license/Adam-Blf/chiaki-fr?style=flat-square&color=D4A437)](COPYING)
<!-- adam-badges:end -->

Android TV build of [chiaki-ng](https://github.com/streetpea/chiaki-ng) tuned for the Nvidia Shield: French interface, PS5-style TV layout,
low latency by default, full gamepad support and in-app PSN login. Changes are listed in
[CHANGELOG.md](CHANGELOG.md), screenshots are in [docs/screenshots](docs/screenshots).

![Home screen](docs/screenshots/01-accueil.png)

## Architecture

```mermaid
flowchart LR
    PAD["Gamepad or Shield remote"]
    PSN["PSN login in a WebView<br/>psn/"]
    CONSOLE["PS4 or PS5<br/>Remote Play host"]
    UI["Console row and settings<br/>main/, settings/, discovery/"]
    REG["Pairing<br/>regist/"]
    DB[("Registered consoles and settings<br/>AppDatabase, Preferences")]
    STREAM["StreamActivity<br/>StreamInput, StreamMenu, StatsOverlay"]
    JNI["JNI bridge<br/>chiaki-jni.c"]
    LIB["chiaki-ng core, C<br/>session, takion, feedback"]
    VID["video-decoder.c<br/>MediaCodec low-latency keys"]
    AUD["audio-output.cpp<br/>Oboe game audio"]
    TV["TV output<br/>video and sound"]
    LOSS["Packet loss<br/>reported for bitrate adaptation"]

    PSN -->|account id| REG
    UI --> REG --> DB
    DB --> UI --> STREAM
    PAD --> STREAM --> JNI --> LIB
    LIB -->|controller inputs| CONSOLE
    CONSOLE -->|video, audio, rumble| LIB
    LIB --> VID --> TV
    LIB --> AUD --> TV
    LIB -.-> LOSS -.-> CONSOLE

    classDef input fill:#2563eb,stroke:#1e3a8a,color:#fff
    classDef process fill:#7c3aed,stroke:#4c1d95,color:#fff
    classDef store fill:#f59e0b,stroke:#92400e,color:#111
    classDef ok fill:#16a34a,stroke:#14532d,color:#fff
    classDef error fill:#dc2626,stroke:#7f1d1d,color:#fff
    class PAD,PSN,CONSOLE input
    class UI,REG,STREAM,JNI,LIB,VID,AUD process
    class DB store
    class TV ok
    class LOSS error
```

## Build and install

Build the debug APK with the Docker toolchain, then install it over adb:

```sh
git submodule update --init --recursive
docker build -t chiaki-ng-build scripts/android-docker
docker run --rm -v "$PWD":/src -v chiaki-gradle:/root/.gradle chiaki-ng-build ./gradlew --no-daemon assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The build targets arm64 only; pass `-PallAbis` to Gradle for the other ABIs. The application id is
`fr.beloucif.chiaki`, so the fork installs next to the original Chiaki.

## Versioning and releases

Versions follow the upstream chiaki-ng version with a `-fr.N` suffix, stored in `VERSION`. Pushing a tag
`v<VERSION>` runs `.github/workflows/release.yml`: it checks the tag against `VERSION`, publishes the
matching `CHANGELOG.md` section and attaches the arm64 debug APK built with the Docker toolchain. The
upstream desktop release workflow only runs on manual dispatch in this fork.

## Licence and credits

Chiaki FR is a fork of [chiaki-ng](https://github.com/streetpea/chiaki-ng) by Street Pea and contributors,
itself the continuation of [Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki) by Florian Märkl and contributors.
It keeps the upstream licence, the GNU Affero General Public License v3.0 with the OpenSSL exception: see
[COPYING](COPYING) and [LICENSES](LICENSES). The Source Sans 3 font is under the SIL Open Font License,
see [docs/licenses](docs/licenses). This project is not endorsed or certified by Sony Interactive
Entertainment LLC, and no Sony logo or asset is shipped.

The rest of this README is the upstream chiaki-ng one.

---

![chiaki-ng Logo](gui/res/chiaking-logo.svg)

# [chiaki-ng](https://streetpea.github.io/chiaki-ng/)

An open source PlayStation remote play project serving as the next-generation of Chiaki with improvements and ongoing support now that the original Chiaki project is in maintenance mode only. [Click here to see the accompanying site for documentation, updates and more](https://streetpea.github.io/chiaki-ng/).

## Discord
[chiaki-ng community Discord](https://discord.gg/tAMbRuwXDH)

## Disclaimer
This project is not endorsed or certified by Sony Interactive Entertainment LLC.

Chiaki is a Free and Open Source Software Client for PlayStation 4 and PlayStation 5 Remote Play
for Linux, FreeBSD, OpenBSD, Android, macOS, Windows, Nintendo Switch and potentially even more platforms.
