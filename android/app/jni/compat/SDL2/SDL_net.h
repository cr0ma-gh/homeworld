/* Compat shim: the engine includes <SDL2/SDL_net.h>, but the vendored
   SDL2_net (jni/SDL_net) ships its header as SDL_net.h with no SDL2/ prefix.
   Forward to it -- jni/SDL_net is on the include path (see src/CMakeLists.txt). */
#include <SDL_net.h>
