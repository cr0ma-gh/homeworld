package org.homeworld.gok;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * First screen shown on launch. The game data is NOT bundled in the APK: the
 * player installs it themselves. This launcher checks whether the data is
 * already in place and, if not, asks the user to point at the folder on the
 * device that holds the Homeworld data files. The chosen files are copied into
 * the app's external files dir -- which is exactly where the native engine
 * looks (SDL_AndroidGetExternalStoragePath) -- and then the game is started.
 */
public class LauncherActivity extends Activity {

    private static final int REQ_PICK_DATA_DIR = 1;

    /** Data files the engine (HW_GAME_HOMEWORLD) reads, by their canonical
     *  on-device names. Homeworld.big is mandatory; the rest are copied when
     *  present (Update.big is optional, the others provide music/speech). */
    private static final String[] DATA_FILES = {
        "Homeworld.big", "HW_Music.wxd", "HW_comp.vce", "Update.big",
    };
    private static final String REQUIRED_FILE = "Homeworld.big";

    private TextView statusView;
    private Button   pickButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Data already installed from a previous run -> straight into the game.
        if (dataInstalled()) {
            startGame();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Gardens of Kadesh");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);

        statusView = new TextView(this);
        statusView.setText("The Homeworld game data is not installed.\n\n"
            + "Tap below and select the folder on your device that contains "
            + "Homeworld.big (and, if you have them, HW_Music.wxd, HW_comp.vce "
            + "and Update.big). The files will be copied into the game.");
        statusView.setTextColor(Color.LTGRAY);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, pad, 0, pad);

        pickButton = new Button(this);
        pickButton.setText("Select data folder");
        pickButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickDataFolder(); }
        });

        root.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(statusView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(pickButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    /** TRUE once the mandatory data file is present in the engine's data dir. */
    private boolean dataInstalled() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return false;
        File f = new File(dir, REQUIRED_FILE);
        return f.exists() && f.length() > 0;
    }

    private void pickDataFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_DATA_DIR);
        } catch (Exception e) {
            Toast.makeText(this, "No folder picker available on this device",
                Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_DATA_DIR || resultCode != RESULT_OK || data == null) {
            return;
        }
        final Uri tree = data.getData();
        if (tree == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { /* not fatal; we only read during this session */ }

        pickButton.setEnabled(false);
        statusView.setText("Copying game data…");
        new Thread(new Runnable() {
            @Override public void run() { copyAndLaunch(tree); }
        }, "gok-data-copy").start();
    }

    /** Copy every recognised data file from the picked folder into the engine's
     *  data dir, then start the game (or report what's missing). Runs off the UI
     *  thread because the retail archives can be hundreds of MB. */
    private void copyAndLaunch(Uri tree) {
        final File outDir = getExternalFilesDir(null);
        String error = null;
        try {
            if (outDir == null) throw new Exception("No external files dir");
            outDir.mkdirs();

            DocumentFile root = DocumentFile.fromTreeUri(this, tree);
            if (root == null || !root.isDirectory()) throw new Exception("Cannot read the selected folder");

            // Index the folder's files once, lower-cased, for case-insensitive match.
            Map<String, DocumentFile> byName = new HashMap<>();
            for (DocumentFile child : root.listFiles()) {
                String name = child.getName();
                if (child.isFile() && name != null) byName.put(name.toLowerCase(), child);
            }

            int copied = 0;
            for (String canonical : DATA_FILES) {
                DocumentFile src = byName.get(canonical.toLowerCase());
                if (src == null) continue;
                File dest = new File(outDir, canonical);
                Log.i("GoK", "Copying " + canonical + " (" + src.length() + " bytes)");
                try (InputStream in = getContentResolver().openInputStream(src.getUri());
                     OutputStream out = new FileOutputStream(dest)) {
                    if (in == null) throw new Exception("Cannot open " + canonical);
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    copied++;
                } catch (Exception e) {
                    dest.delete();   /* don't leave a partial file behind */
                    throw new Exception("Failed copying " + canonical + ": " + e.getMessage());
                }
            }

            if (!new File(outDir, REQUIRED_FILE).exists()) {
                error = "The selected folder does not contain " + REQUIRED_FILE
                      + ". Pick the folder that holds the Homeworld data files.";
            } else {
                Log.i("GoK", "Installed " + copied + " data file(s)");
            }
        } catch (Exception e) {
            Log.e("GoK", "Data install failed", e);
            error = e.getMessage();
        }

        final String err = error;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                if (err == null) {
                    startGame();
                } else {
                    pickButton.setEnabled(true);
                    statusView.setText(err);
                    Toast.makeText(LauncherActivity.this, err, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void startGame() {
        startActivity(new Intent(this, HomeworldActivity.class));
        finish();
    }
}
