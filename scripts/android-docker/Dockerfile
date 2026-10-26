# Reproducible Android build environment for Chiaki FR (chiaki-ng base)
FROM eclipse-temurin:17-jdk-jammy
RUN apt-get update && apt-get install -y --no-install-recommends \
      unzip wget perl make python3 python3-protobuf protobuf-compiler git ca-certificates autoconf automake libtool pkg-config \
    && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p $ANDROID_HOME/cmdline-tools && cd /tmp \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q commandlinetools-linux-*.zip && mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest && rm -f *.zip
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;28.2.13676358" "cmake;3.22.1" >/dev/null
WORKDIR /src/android
