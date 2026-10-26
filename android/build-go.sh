#!/bin/sh
# Builds app/libs/rcsmobile.aar (libgm for Android) with gomobile.
# Needs Go, gomobile (go install golang.org/x/mobile/cmd/gomobile@latest; gomobile init),
# the Android SDK (ANDROID_HOME) and an NDK (ANDROID_NDK_HOME).
set -e
cd "$(dirname "$0")"
mkdir -p app/libs
cd rcsmobile
gomobile bind -target=android/arm64,android/amd64 -androidapi 29 -javapkg dev.prism.rcs.go \
	-o ../app/libs/rcsmobile.aar .
