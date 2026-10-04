# Bundled LAME

LAME 3.100, upstream release archive:
https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
Project: https://lame.sourceforge.io/ (also https://www.mp3dev.org/).
Per-file source integrity manifest: `app/src/main/cpp/third_party/lame-3.100.sha256`.
Downloaded archive SHA-256:
`ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`.

The complete release source is vendored, unchanged, at
`app/src/main/cpp/third_party/lame/`. This is the exact 3.100 release, not a
moving source branch or an unverified binary. Upstream `COPYING` contains the
GNU Library General Public License version 2 (June 1991); individual library
source headers permit version 2 or later. Upstream `LICENSE` explains library
use and acknowledgement. Both files and all source copyright notices are
preserved. The complete upstream archive also includes components with their
own notices; the build compiles only the portable libmp3lame encoder sources
listed in CMake, not the frontend or mpglib decoder.

Modifications to upstream source: none. Our Android `config.h`, CMake build,
JNI bridge, and Kotlin decoder integration live outside the upstream tree.
The encoder is built as a separate `libmp3lame.so`, dynamically linked by
`libsmd_mp3.so`, for arm64-v8a, armeabi-v7a, x86, and x86_64. NDK is pinned to
27.2.12479018. No downloads occur in the native build.

Source, build scripts, and license are provided here so recipients can rebuild
or replace the library. A distributor must retain these notices, provide the
corresponding library source/build material with binaries, and review applicable
LGPL redistribution and relinking requirements for their distribution method.
This notice documents the reviewed bundled license; it is not a blanket legal
compliance certification. Required text is additionally packaged in application
assets under `licenses/lame/`.
