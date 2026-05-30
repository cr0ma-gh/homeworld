package org.homeworld.gok;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.libsdl.app.SDLActivity;

/**
 * Gardens of Kadesh Android launcher. Subclasses SDLActivity to load our native
 * libraries and to overlay a small bar of on-screen control buttons (F, ESC, Shift,
 * Tab, Z, right-click) that inject SDL events into the engine, so common Homeworld
 * keyboard/mouse controls are reachable on a touch device.
 */
public class HomeworldActivity extends SDLActivity {

    /* SDL_keycode.h SDLK_* values. The "scancode" keys (e.g. LSHIFT) use the
     * SDLK_SCANCODE_MASK (1 << 30) ORed with the SDL_SCANCODE_* number. */
    private static final int SDLK_F         = 'f';
    private static final int SDLK_Z         = 'z';
    private static final int SDLK_B         = 'b';
    private static final int SDLK_TAB       = 9;
    private static final int SDLK_ESCAPE    = 27;
    private static final int SDLK_BACKSPACE = 8;
    private static final int SDLK_SPACE     = ' ';
    private static final int SDLK_LSHIFT    = (1 << 30) | 225; /* SDL_SCANCODE_LSHIFT */
    private static final int SDL_BUTTON_LEFT  = 1;
    private static final int SDL_BUTTON_RIGHT = 3;

    public native void nativeInjectSDLKey(int sdlKey, boolean down);
    public native void nativeInjectSDLMouseButton(int sdlBtn, boolean down);
    public native void nativeMoveShips();      /* M  = Homeworld movement disk */
    public native void nativeForceAttack();    /* ATK = Ctrl+Shift+LMB at cursor */
    public native void nativeSetAtkMode(boolean on);  /* drag-band turns into a band-attack */
    public native boolean nativeIsInMenu();           /* true in front-end/menus, false in live gameplay */

    /* "Attack mode" toggle: when ON, the LMB overlay button issues
       Ctrl+Shift+LMB (force-attack) instead of a normal LMB click. Lets the
       player rain attack orders on multiple targets without reaching for ATK
       between each one. */
    private boolean atkMode = false;
    private Button  atkBtn  = null;

    /* Whole-overlay show/hide: the small chevron button toggles the visibility
       of the two-row button bar so the user can reclaim the bottom of the
       screen during gameplay. */
    private LinearLayout overlayContainer = null;  /* whole [chevron][stack] bar; hidden in menus */
    private android.os.Handler overlayPoll = null; /* polls nativeIsInMenu() to show/hide the bar */
    private LinearLayout overlayStack = null;
    private Button       overlayToggleBtn = null;
    private boolean      overlayVisible = true;

    /* LAN multiplayer: Android drops inbound UDP broadcast/multicast frames
       unless a MulticastLock is held, which would make Homeworld's broadcast-
       based LAN game discovery (NetworkInterface.c) never see any hosts. Held
       for the whole activity lifetime -- cheap enough for a game and avoids
       races with the engine's network threads starting on their own schedule. */
    private WifiManager.MulticastLock multicastLock = null;

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "SDL2",
            "main"
        };
    }

    /** Retail .big files bundled in the APK assets. The engine expects these
     *  exact names (case-sensitive on Android) in the external files dir, which
     *  is what SDL_AndroidGetExternalStoragePath() points the native side at.
     *  Listed as {assetName -> destFilename}. */
    private static final String[][] BUNDLED_GAME_DATA = {
        { "Homeworld.big", "Homeworld.big" },
        { "HW_Music.wxd",  "HW_Music.wxd"  },
        { "HW_comp.vce",   "HW_comp.vce"   },
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        /* Extract the bundled retail data into the external files dir BEFORE
           calling super.onCreate() -- SDLActivity starts the native thread
           inside its own onCreate (via the surface lifecycle), and the engine
           reads Homeworld.big right away. Doing the copy first guarantees the
           data is in place before any native code runs. Only happens on first
           launch (or after a re-install): subsequent runs see the files
           already on disk and the copy is a no-op. */
        extractBundledGameData();

        super.onCreate(savedInstanceState);

        /* Draw edge-to-edge across the full physical display, including under
           the camera cutout / hole-punch and the gesture/3-button navigation
           area (the latter shows up as a black bar on the right in landscape
           on a Pixel 9). SDL's own immersive flags only apply when the engine
           toggles fullscreen via SDL_SetWindowFullscreen, which we never call,
           so we apply the SYSTEM_UI_FLAG_* set ourselves. */
        Window w = getWindow();
        if (w != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                /* Android 11+: LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3.
                   ALWAYS lets the window extend into the cutout area on EVERY
                   edge -- which we need because in landscape the camera hole-
                   punch sits on a "long edge" of the landscape view (so
                   SHORT_EDGES would leave a black bar there). */
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = 3;
                w.setAttributes(lp);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                /* Android 9-10: only SHORT_EDGES exists -- best-effort. */
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                /* Android 11+: tell the window not to fit decor (status/nav
                   bars and cutout) into the content area. Combined with the
                   immersive flags below this lets our surface span the full
                   physical display. */
                w.setDecorFitsSystemWindows(false);
            }
            applyImmersiveFlags();
        }

        if (mLayout != null) {
            /* SDLActivity adds mSurface with default WRAP_CONTENT params; in
               immersive + cutout-always mode we want the SurfaceView itself to
               fill the whole RelativeLayout (which now spans the full physical
               display). MATCH_PARENT guarantees no black gap on any side. */
            if (mSurface != null) {
                ViewGroup.LayoutParams slp = mSurface.getLayoutParams();
                if (slp != null) {
                    slp.width  = ViewGroup.LayoutParams.MATCH_PARENT;
                    slp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    mSurface.setLayoutParams(slp);
                }
            }
            addControlOverlay();
            startOverlayMenuPoll();
        }

        acquireMulticastLock();
    }

    /** Poll the engine a few times a second and hide the on-screen control
     *  overlay while in menus/front-end (where it isn't needed -- menus are
     *  tapped directly -- and would overlap the centred menu panel), showing it
     *  again during live gameplay. */
    private void startOverlayMenuPoll() {
        overlayPoll = new android.os.Handler(getMainLooper());
        final Runnable tick = new Runnable() {
            private boolean lastInMenu = true;   /* start hidden until in-game */
            @Override public void run() {
                if (overlayContainer != null) {
                    boolean inMenu;
                    try { inMenu = nativeIsInMenu(); }
                    catch (Throwable t) { inMenu = true; }
                    if (inMenu != lastInMenu) {
                        lastInMenu = inMenu;
                        overlayContainer.setVisibility(inMenu ? View.GONE : View.VISIBLE);
                    }
                }
                if (overlayPoll != null) overlayPoll.postDelayed(this, 250);
            }
        };
        overlayContainer.setVisibility(View.GONE);   /* hidden at boot (main menu) */
        overlayPoll.postDelayed(tick, 500);
    }

    /** Grab a WifiManager MulticastLock so the kernel delivers inbound UDP
     *  broadcast/multicast packets to us (needed for LAN game discovery). Safe
     *  no-op if Wi-Fi is off; released in onDestroy. */
    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager)
                getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("gok-lan");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                Log.i("GoK", "MulticastLock acquired for LAN discovery");
            }
        } catch (Exception e) {
            Log.e("GoK", "Failed to acquire MulticastLock", e);
        }
    }

    @Override
    protected void onDestroy() {
        if (overlayPoll != null) {
            overlayPoll.removeCallbacksAndMessages(null);
            overlayPoll = null;
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        super.onDestroy();
    }

    /** Copy each entry in BUNDLED_GAME_DATA from the APK assets into the
     *  external files dir, but only if it isn't already there (matched by
     *  filename + size -- the file is rewritten if the size differs, which
     *  catches a stale partial copy from a previous failed extract). */
    private void extractBundledGameData() {
        File outDir = getExternalFilesDir(null);
        if (outDir == null) {
            Log.e("GoK", "getExternalFilesDir(null) returned null; cannot extract data");
            return;
        }
        outDir.mkdirs();

        AssetManager am = getAssets();
        for (String[] pair : BUNDLED_GAME_DATA) {
            String assetName = pair[0];
            String destName  = pair[1];
            File   dest      = new File(outDir, destName);

            long assetSize = -1;
            try {
                assetSize = am.openFd(assetName).getLength();
            } catch (IOException ignored) {
                /* openFd fails for compressed assets; fall back to streaming
                   the whole asset to /dev/null just to size it. We mark these
                   noCompress in build.gradle so openFd should succeed. */
            }

            if (dest.exists() && (assetSize < 0 || dest.length() == assetSize)) {
                continue;   /* already extracted, sizes match -- skip */
            }

            Log.i("GoK", "Extracting " + assetName + " (" + assetSize + " bytes)");
            try (InputStream in = am.open(assetName);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                Log.e("GoK", "Failed to extract " + assetName, e);
                dest.delete();   /* don't leave a partial file behind */
            }
        }
    }

    /** Hide the status and navigation bars in IMMERSIVE_STICKY mode, with
     *  LAYOUT_HIDE_NAVIGATION / LAYOUT_FULLSCREEN so our surface is sized to
     *  cover their reserved areas. Call from onCreate and re-apply whenever
     *  focus comes back, because the system restores visibility on user input. */
    private void applyImmersiveFlags() {
        Window w = getWindow();
        if (w == null) return;
        View decor = w.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decor.setSystemUiVisibility(flags);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            /* SDLActivity.onWindowFocusChanged already runs, but it only does
               anything when SDL is in fullscreen mode. We are not, so re-apply
               our own immersive flags here (and not in onResume -- the bars
               un-hide on user touch and we'd miss those). */
            applyImmersiveFlags();
        }
    }

    private void addControlOverlay() {
        /* Two horizontal rows stacked vertically, grouped by purpose:
             row 1 (input/modifiers): LMB RMB ⇧ ESC BKSP SPACE TAB
             row 2 (gameplay):        F Z B M ATK
           Plus a small toggle chevron to the LEFT of the stack that hides /
           shows the entire two-row bar. */
        overlayStack = new LinearLayout(this);
        overlayStack.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        addLmbDispatchButton(row1);          /* click or force-attack depending on atkMode */
        addCtrlButton(row1, "RMB",   SDL_BUTTON_RIGHT, true);
        addCtrlButton(row1, "⇧",     SDLK_LSHIFT,      false);
        addCtrlButton(row1, "ESC",   SDLK_ESCAPE,      false);
        addCtrlButton(row1, "SPACE", SDLK_SPACE,       false);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        addCtrlButton(row2, "F",     SDLK_F,           false);
        addCtrlButton(row2, "B",     SDLK_B,           false);
        addActionButton(row2, "M",   new Runnable() { @Override public void run() { nativeMoveShips();   } });
        addAtkToggleButton(row2);

        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        overlayStack.addView(row1, row1Lp);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = 12;
        overlayStack.addView(row2, row2Lp);

        /* Wrap [toggle][stack] in a horizontal container so the chevron stays
           in the same screen position whether the stack is shown or hidden. */
        overlayContainer = new LinearLayout(this);
        LinearLayout container = overlayContainer;
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.BOTTOM);

        overlayToggleBtn = newOverlayToggleButton();
        overlayToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                overlayVisible = !overlayVisible;
                overlayStack.setVisibility(overlayVisible ? View.VISIBLE : View.GONE);
                overlayToggleBtn.setText(overlayVisible ? "‹" : "›");
            }
        });

        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleLp.rightMargin = 16;
        toggleLp.gravity = Gravity.BOTTOM;
        container.addView(overlayToggleBtn, toggleLp);

        LinearLayout.LayoutParams stackLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        container.addView(overlayStack, stackLp);

        /* SDLActivity.mLayout is a RelativeLayout, so use its own LayoutParams + rules. */
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        lp.addRule(RelativeLayout.ALIGN_PARENT_START);
        lp.leftMargin   = 240;
        lp.bottomMargin = 32;
        mLayout.addView(container, lp);
    }

    /** Small chevron button used to hide/show the whole control overlay. */
    private Button newOverlayToggleButton() {
        Button b = new Button(this);
        b.setText("‹");                                  /* visible -> arrow points to collapse */
        b.setTextColor(Color.WHITE);
        b.setTextSize(22);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setAllCaps(false);
        b.setPadding(28, 14, 28, 14);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(10);
        bg.setColor(Color.argb(140, 0, 0, 0));
        bg.setStroke(2, Color.argb(200, 180, 180, 180));
        b.setBackground(bg);
        b.setAlpha(0.85f);
        return b;
    }

    private void addCtrlButton(LinearLayout bar, final String label,
                               final int code, final boolean isMouse) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(20);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setAllCaps(false);
        b.setPadding(36, 14, 36, 14);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(10);
        bg.setColor(Color.argb(110, 0, 0, 0));   /* translucent dark */
        bg.setStroke(2, Color.argb(180, 255, 200, 80));
        b.setBackground(bg);
        b.setAlpha(0.85f);

        b.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    if (isMouse) nativeInjectSDLMouseButton(code, true);
                    else         nativeInjectSDLKey(code, true);
                    v.setAlpha(1.0f);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    if (isMouse) nativeInjectSDLMouseButton(code, false);
                    else         nativeInjectSDLKey(code, false);
                    v.setAlpha(0.85f);
                }
                return true;
            }
        });

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = 12;
        bar.addView(b, blp);
    }

    /** Like addCtrlButton but the tap fires an arbitrary native action (not just
     *  a key/mouse inject). Used for M (movement disk) and ATK (force-attack). */
    private void addActionButton(LinearLayout bar, final String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(20);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setAllCaps(false);
        b.setPadding(36, 14, 36, 14);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(10);
        bg.setColor(Color.argb(110, 0, 0, 0));
        bg.setStroke(2, Color.argb(200, 120, 220, 120));  /* greenish: action vs key */
        b.setBackground(bg);
        b.setAlpha(0.85f);

        b.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    action.run();
                    v.setAlpha(1.0f);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.setAlpha(0.85f);
                }
                return true;
            }
        });

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = 12;
        bar.addView(b, blp);
    }

    /** LMB button with conditional dispatch: normal click, unless attack-mode is
     *  on, in which case it issues a force-attack (Ctrl+Shift+LMB) at the cursor. */
    private void addLmbDispatchButton(LinearLayout bar) {
        Button b = newOverlayButton("LMB", false);
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    if (atkMode) {
                        nativeForceAttack();
                    } else {
                        nativeInjectSDLMouseButton(SDL_BUTTON_LEFT, true);
                    }
                    v.setAlpha(1.0f);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    if (!atkMode) {
                        nativeInjectSDLMouseButton(SDL_BUTTON_LEFT, false);
                    }
                    v.setAlpha(0.85f);
                }
                return true;
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = 12;
        bar.addView(b, blp);
    }

    /** ATK button as a sticky toggle: turns LMB into a force-attack until pressed
     *  again. Button glows red while attack-mode is on, so it's obvious. */
    private void addAtkToggleButton(LinearLayout bar) {
        atkBtn = newOverlayButton("ATK", true);
        atkBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    atkMode = !atkMode;
                    nativeSetAtkMode(atkMode);
                    updateAtkVisual();
                }
                return true;
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = 12;
        bar.addView(atkBtn, blp);
    }

    private void updateAtkVisual() {
        if (atkBtn == null) return;
        GradientDrawable bg = (GradientDrawable) atkBtn.getBackground();
        if (atkMode) {
            bg.setColor(Color.argb(180, 200, 40, 40));
            bg.setStroke(2, Color.argb(255, 255, 80, 80));
            atkBtn.setAlpha(1.0f);
        } else {
            bg.setColor(Color.argb(110, 0, 0, 0));
            bg.setStroke(2, Color.argb(200, 120, 220, 120));
            atkBtn.setAlpha(0.85f);
        }
    }

    /** Shared button styling: yellow border for key/mouse keys, green for actions. */
    private Button newOverlayButton(String label, boolean greenBorder) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(20);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setAllCaps(false);
        b.setPadding(36, 14, 36, 14);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(10);
        bg.setColor(Color.argb(110, 0, 0, 0));
        bg.setStroke(2, greenBorder ? Color.argb(200, 120, 220, 120)
                                    : Color.argb(180, 255, 200, 80));
        b.setBackground(bg);
        b.setAlpha(0.85f);
        return b;
    }
}
