#include <SDL.h>
#include <GLES2/gl2.h>
#include <math.h>

int main(int argc, char *argv[])
{
    (void)argc;
    (void)argv;

    if (SDL_Init(SDL_INIT_VIDEO) != 0) {
        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }

    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 2);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
    SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);

    SDL_Window *win = SDL_CreateWindow("Homeworld",
                                       SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
                                       0, 0,
                                       SDL_WINDOW_OPENGL | SDL_WINDOW_FULLSCREEN);
    if (!win) {
        SDL_Log("SDL_CreateWindow failed: %s", SDL_GetError());
        SDL_Quit();
        return 1;
    }

    SDL_GLContext ctx = SDL_GL_CreateContext(win);
    if (!ctx) {
        SDL_Log("SDL_GL_CreateContext failed: %s", SDL_GetError());
        SDL_DestroyWindow(win);
        SDL_Quit();
        return 1;
    }

    SDL_Log("GL_VERSION:  %s", glGetString(GL_VERSION));
    SDL_Log("GL_RENDERER: %s", glGetString(GL_RENDERER));

    int running = 1;
    float t = 0.0f;
    while (running) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            if (e.type == SDL_QUIT) {
                running = 0;
            } else if (e.type == SDL_KEYDOWN && e.key.keysym.sym == SDLK_AC_BACK) {
                running = 0;
            }
        }

        t += 0.02f;
        glClearColor(0.10f, 0.5f + 0.5f * sinf(t), 0.35f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        SDL_GL_SwapWindow(win);
        SDL_Delay(16);
    }

    SDL_GL_DeleteContext(ctx);
    SDL_DestroyWindow(win);
    SDL_Quit();
    return 0;
}
