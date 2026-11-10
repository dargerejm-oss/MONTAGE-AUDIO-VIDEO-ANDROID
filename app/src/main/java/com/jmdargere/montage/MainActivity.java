package com.jmdargere.montage;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * MONTAGE AUDIO VIDEO : la page de montage (assets/index.html) dans un WebView,
 * l'export se fait avec le codeur materiel du telephone (Media3 Transformer, voir Exporter).
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 11;
    private static final int REQ_MIC = 12;

    private WebView web;
    private ValueCallback<Uri[]> chooserCb;
    private volatile String lastPicked = "[]";
    private MediaRecorder rec;
    private File recFile;
    private MediaPlayer voicePlayer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#232a31"));
        root.addView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        web.setBackgroundColor(Color.parseColor("#232a31"));

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooserCb != null) {
                    chooserCb.onReceiveValue(null);
                }
                chooserCb = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                String[] types = p.getAcceptTypes();
                if (types != null && types.length > 0 && types[0] != null && !types[0].isEmpty()) {
                    java.util.ArrayList<String> list = new java.util.ArrayList<>();
                    for (String t : types) {
                        for (String x : t.split(",")) {
                            if (!x.trim().isEmpty()) {
                                list.add(x.trim());
                            }
                        }
                    }
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, list.toArray(new String[0]));
                } else {
                    i.setType("*/*");
                }
                try {
                    startActivityForResult(i, REQ_PICK);
                } catch (Exception e) {
                    chooserCb = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK) {
            return;
        }
        ValueCallback<Uri[]> cb = chooserCb;
        chooserCb = null;
        if (cb == null) {
            return;
        }
        if (res != RESULT_OK || data == null) {
            lastPicked = "[]";
            cb.onReceiveValue(null);
            return;
        }
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int k = 0; k < cd.getItemCount(); k++) {
                uris.add(cd.getItemAt(k).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        JSONArray arr = new JSONArray();
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            try {
                JSONObject o = new JSONObject();
                o.put("uri", u.toString());
                o.put("name", nameOf(u));
                o.put("type", String.valueOf(getContentResolver().getType(u)));
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        lastPicked = arr.toString();
        cb.onReceiveValue(uris.toArray(new Uri[0]));
    }

    private String nameOf(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    return c.getString(i);
                }
            }
        } catch (Exception ignored) {
        }
        return "media";
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_MIC) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            web.evaluateJavascript("window.onMic&&window.onMic(" + ok + ")", null);
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack?window.onBack():false", value -> {
            if (!"true".equals(value)) {
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRec();
        if (voicePlayer != null) {
            voicePlayer.release();
        }
    }

    private String stopRec() {
        String path = "";
        if (rec != null) {
            try {
                rec.stop();
                path = recFile.getAbsolutePath();
            } catch (Exception e) {
                path = "";
            }
            try {
                rec.release();
            } catch (Exception ignored) {
            }
            rec = null;
        }
        return path;
    }

    /** Fonctions appelees par la page (JavaScript). */
    private final class Bridge {
        @JavascriptInterface
        public String lastPicked() {
            return lastPicked;
        }

        @JavascriptInterface
        public void exportStart(String json) {
            Exporter.start(getApplicationContext(), json);
        }

        @JavascriptInterface
        public int exportState() {
            return Exporter.state();
        }

        @JavascriptInterface
        public int exportProgress() {
            return Exporter.progress();
        }

        @JavascriptInterface
        public String exportError() {
            return Exporter.error();
        }

        @JavascriptInterface
        public String exportResult() {
            return Exporter.result();
        }

        @JavascriptInterface
        public void exportCancel() {
            Exporter.cancel();
        }

        @JavascriptInterface
        public void viewVideo(String uri) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse(uri), "video/mp4");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void askMic() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 23
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                } else {
                    web.evaluateJavascript("window.onMic&&window.onMic(true)", null);
                }
            });
        }

        @JavascriptInterface
        public boolean recStart() {
            try {
                stopRec();
                recFile = new File(getCacheDir(), "voix_" + System.currentTimeMillis() + ".m4a");
                MediaRecorder r = new MediaRecorder();
                r.setAudioSource(MediaRecorder.AudioSource.MIC);
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioEncodingBitRate(128000);
                r.setAudioSamplingRate(44100);
                r.setOutputFile(recFile.getAbsolutePath());
                r.prepare();
                r.start();
                rec = r;
                return true;
            } catch (Exception e) {
                rec = null;
                return false;
            }
        }

        @JavascriptInterface
        public String recStop() {
            return stopRec();
        }

        @JavascriptInterface
        public void playVoice(String path) {
            runOnUiThread(() -> {
                try {
                    if (voicePlayer != null) {
                        voicePlayer.release();
                    }
                    voicePlayer = new MediaPlayer();
                    voicePlayer.setDataSource(path);
                    voicePlayer.prepare();
                    voicePlayer.start();
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void stopVoice() {
            runOnUiThread(() -> {
                try {
                    if (voicePlayer != null) {
                        voicePlayer.stop();
                    }
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void buzz() {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) {
                    v.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 120, 200, 120, 400}, -1));
                }
            } catch (Exception ignored) {
            }
        }
    }
}
