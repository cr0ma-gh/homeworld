cmake_minimum_required(VERSION 3.22)

project(GardensOfKadesh C CXX)

# SDL2 (full source tree cloned into jni/SDL). Builds target "SDL2".
add_subdirectory(SDL)

# gl4es: translates desktop/immediate-mode GL -> GLES2. Build as a STATIC lib
# with manual init (we drive the GLES2 context via SDL, then call gl4es_init()).
# NOTE: the NDK toolchain already sets the CMake var ANDROID=1, so gl4es's
# Android path (-DNOX11 -DNO_GBM -DDEFAULT_ES=2) is selected automatically.
set(STATICLIB          ON CACHE BOOL "" FORCE)
set(NOX11              ON CACHE BOOL "" FORCE)
set(NOEGL              ON CACHE BOOL "" FORCE)
set(NO_LOADER          ON CACHE BOOL "" FORCE)
set(NO_INIT_CONSTRUCTOR ON CACHE BOOL "" FORCE)
set(USE_ANDROID_LOG    ON CACHE BOOL "" FORCE)
add_subdirectory(gl4es)

# The Homeworld/GoK engine (builds libmain.so, exporting SDL_main).
add_subdirectory(src)
