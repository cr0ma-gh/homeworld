cmake_minimum_required(VERSION 3.22)

# Repo's engine source root (android/app/jni/src -> ../../../../src)
set(GAME_ROOT    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../src)
set(GL4ES_INC    ${CMAKE_CURRENT_SOURCE_DIR}/../gl4es/include)
set(COMPAT_INC   ${CMAKE_CURRENT_SOURCE_DIR}/../compat)
set(GEN_MISSIONS ${CMAKE_CURRENT_SOURCE_DIR}/../generated/missions)

# ---- Engine source lists (mirrors meson, minus platform-specific files) ----
file(GLOB GAME_SRC  ${GAME_ROOT}/Game/*.c)
list(REMOVE_ITEM GAME_SRC
    ${GAME_ROOT}/Game/File_Mac.c
    ${GAME_ROOT}/Game/StubTransform.c
    # Matrix-mult.c is an alternate matMultiplyMatByMat impl NOT in the upstream
    # meson source list; including it alongside Matrix.c duplicates the symbol.
    ${GAME_ROOT}/Game/Matrix-mult.c)

file(GLOB SHIPS_SRC   ${GAME_ROOT}/Ships/*.c)
file(GLOB MISSION_SRC ${GEN_MISSIONS}/*.c)

# Full SDL/platform layer (mirrors meson src_sdl), now INCLUDING the GL pipeline
# and entry point (render.c, rinit.c, prim2d/3d, rglu, texreg, main.c).
set(SDL_SRC
    avi font main mainrgn mixfft mouse NetworkInterface prim2d prim3d Queue
    render rglu rinit screenshot smixer soundlow sstream texreg TimeoutTimer
    Titan TitanInterfaceC utility fqcodec fqeffect fquant dct)
list(TRANSFORM SDL_SRC PREPEND ${GAME_ROOT}/SDL/)
list(TRANSFORM SDL_SRC APPEND .c)

set(TP_SRC
    ${GAME_ROOT}/ThirdParty/CRC/CRC32.c
    ${GAME_ROOT}/ThirdParty/LZSS/BitIO.c
    ${GAME_ROOT}/ThirdParty/LZSS/LZSS.c)

# SDL2_net (vendored at jni/SDL_net, release-2.2.0): UDP broadcast + TCP that the
# engine's LAN multiplayer layer (NetworkInterface.c, gated by HW_ENABLE_NETWORK)
# is written against. Only the 4 library sources -- the chat*/showinterfaces are
# standalone demo programs with their own main().
set(SDL_NET_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../SDL_net)
set(SDL_NET_SRC
    ${SDL_NET_DIR}/SDLnet.c
    ${SDL_NET_DIR}/SDLnetTCP.c
    ${SDL_NET_DIR}/SDLnetUDP.c
    ${SDL_NET_DIR}/SDLnetselect.c)

# libmain.so — SDLActivity loads "main" and calls SDL_main (from src/SDL/main.c).
add_library(main SHARED ${GAME_SRC} ${SHIPS_SRC} ${SDL_SRC} ${TP_SRC} ${MISSION_SRC} ${SDL_NET_SRC})

target_include_directories(main PRIVATE
    ${GAME_ROOT}/Game
    ${GAME_ROOT}/Ships
    ${GAME_ROOT}/SDL
    ${GAME_ROOT}/ThirdParty/CRC
    ${GAME_ROOT}/ThirdParty/stb
    ${GAME_ROOT}/ThirdParty/Titan
    ${GAME_ROOT}/ThirdParty/LZSS
    ${GAME_ROOT}/Missions/SinglePlayer
    ${GAME_ROOT}/Missions/Tutorials
    ${GEN_MISSIONS}
    ${COMPAT_INC}
    ${SDL_NET_DIR}
    ${GL4ES_INC})

target_compile_definitions(main PRIVATE
    # HW_GAME_HOMEWORLD = full retail data (Homeworld.big, HW_Music.wxd,
    # HW_comp.vce). HW_GAME_DEMO builds against the freely-redistributable demo
    # data (HomeworldDL.big, DL_demo.vce, DL_Music.wxd, Update.big), which the
    # Android CI fetches and bundles into the APK. main.h:19 requires exactly
    # one of HW_GAME_HOMEWORLD/HW_GAME_RAIDER_RETREAT/HW_GAME_DEMO.
    HW_GAME_DEMO
    ARM
    HW_PTR_64
    HW_PTR_64_FIX_ME
    GENERIC_ETGCALLFUNCTION
    _LINUX_FIX_ME
    _GNU_SOURCE
    HAVE_CONFIG_H
    _REENTRANT
    HW_BUILD_FOR_DISTRIBUTION
    # Enable the LAN multiplayer networking layer (NetworkInterface.c /
    # TitanInterfaceC.c). Without this the engine's net code is compiled out.
    HW_ENABLE_NETWORK)

# Old codebase + modern Clang (NDK r27): silence warnings, signed char (x86
# semantics), and disable the legacy-C diagnostics Clang promotes to errors.
target_compile_options(main PRIVATE
    -w -std=gnu11 -fsigned-char -ferror-limit=200
    -Wno-int-conversion
    -Wno-incompatible-pointer-types
    -Wno-incompatible-function-pointer-types
    -Wno-implicit-function-declaration
    -Wno-implicit-int
    -Wno-return-type)

# GL = gl4es static lib; with STATICLIB it links nothing itself, so libmain.so
# pulls in the real GLES2/EGL/log here.
target_link_libraries(main
    SDL2
    GL
    GLESv2
    EGL
    log
    android
    m)
