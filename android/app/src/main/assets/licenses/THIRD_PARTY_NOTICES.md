# Third-party notices

The Android protocol implementation is derived from ELEGOO's Elegoo Link SDK, copyright © 2025 Elegoo, Apache License 2.0. The original SDK and its copyright and license are retained in this repository. Android modifications were made in 2026.

Eclipse Paho Java MQTT client 1.2.5, copyright Eclipse Foundation contributors, is distributed under the Eclipse Public License 2.0 and Eclipse Distribution License 1.0. Source and license texts: https://github.com/eclipse-paho/paho.mqtt.java/tree/v1.2.5 .

Build and test tools are not app runtime dependencies. JUnit 4.13.2 is EPL-1.0; JSON-java 20240303 is public domain. The Gradle wrapper is Apache-2.0.

- **Agora RTM SDK** (`io.agora:agora-rtm` 2.2.6, Maven Central), used for cloud printer commands. Proprietary software of Agora, Inc., included under Agora's SDK terms (https://www.agora.io/en/terms-of-service/); not covered by this repository's Apache-2.0 license.
- **AndroidX WebKit** (`androidx.webkit:webkit`), Apache-2.0.
- **Agora Web SDK** (`agora-rtc-sdk-ng` 4.22.0, npm), bundled in `assets/camera/` for the cloud camera. Distributed under the MIT license according to its package metadata; copyright Agora, Inc.
- **ElegooSlicer** slicing engine (libslic3r and its bundled libraries, with Elegoo's printer, process and filament presets), from https://github.com/ELEGOO-3D/elegooslicer, AGPL-3.0. Included as `liblinkslicer.so` and `assets/slicer/` only in builds made after `slicer/scripts/install-into-app.sh`. Its source and build scripts are in this repository's `slicer/` directory, which is AGPL-3.0. The library also statically links Boost (BSL-1.0), oneTBB (Apache-2.0), CGAL (GPL-3.0+/LGPL-3.0+), GMP and MPFR (LGPL-3.0+), Eigen (MPL-2.0), OpenCASCADE (LGPL-2.1 with exception), OpenCV (Apache-2.0), OpenVDB (MPL-2.0), Blosc (BSD-3-Clause), OpenEXR (BSD-3-Clause), Draco (Apache-2.0), NLopt (MIT/LGPL), libnoise (LGPL-2.1), cereal (BSD-3-Clause), Qhull (Qhull license), FreeType (FTL), libpng (libpng license), libjpeg-turbo (IJG/BSD), zlib (zlib license) and OpenSSL (Apache-2.0).
