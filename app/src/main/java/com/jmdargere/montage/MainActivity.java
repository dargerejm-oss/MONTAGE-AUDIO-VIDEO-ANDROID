package com.jmdargere.montage;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.res.AssetFileDescriptor;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebViewClient;
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
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

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
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!"app.local".equals(u.getHost())) {
                    return null;
                }
                try {
                    String path = u.getPath();
                    if (path == null || "/".equals(path) || "/index.html".equals(path)) {
                        return new WebResourceResponse("text/html", "utf-8", getAssets().open("index.html"));
                    }
                    if ("/m".equals(path)) {
                        return media(r, Uri.parse(u.getQueryParameter("u")));
                    }
                } catch (Exception ignored) {
                }
                return null;
            }
        });
        if (Build.VERSION.SDK_INT >= 23) {
            // notifications, lecture des photos/videos et de leur position GPS (titres et carte automatiques)
            java.util.ArrayList<String> want = new java.util.ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33) {
                want.add(Manifest.permission.POST_NOTIFICATIONS);
                want.add(Manifest.permission.READ_MEDIA_IMAGES);
                want.add(Manifest.permission.READ_MEDIA_VIDEO);
            } else {
                want.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                want.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
            }
            java.util.ArrayList<String> need = new java.util.ArrayList<>();
            for (String pm : want) {
                if (checkSelfPermission(pm) != PackageManager.PERMISSION_GRANTED) {
                    need.add(pm);
                }
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), 13);
            }
        }
        web.loadUrl("https://app.local/index.html");
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

    /** Sert une photo / video / musique du telephone a la page, avec le support de la lecture partielle. */
    private WebResourceResponse media(WebResourceRequest r, Uri uri) throws Exception {
        android.content.ContentResolver cr = getContentResolver();
        String type = cr.getType(uri);
        if (type == null) {
            String ps = String.valueOf(uri.getPath()).toLowerCase();
            type = ps.endsWith(".png") ? "image/png" : ps.endsWith(".jpg") ? "image/jpeg"
                    : "application/octet-stream";
        }
        final AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r");
        if (afd == null) {
            return null;
        }
        long len = afd.getLength();
        if (len < 0) {
            len = afd.getParcelFileDescriptor().getStatSize();
        }
        long start = 0;
        long end = len - 1;
        boolean partial = false;
        String range = r.getRequestHeaders().get("Range");
        if (range != null && range.startsWith("bytes=")) {
            String[] p = range.substring(6).split("-");
            try {
                start = Long.parseLong(p[0].trim());
                if (p.length > 1 && !p[1].trim().isEmpty()) {
                    end = Long.parseLong(p[1].trim());
                }
                partial = true;
            } catch (Exception ignored) {
                start = 0;
            }
        }
        end = Math.min(end, len - 1);
        if (start > end) {
            start = 0;
            partial = false;
            end = len - 1;
        }
        final FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
        fis.getChannel().position(afd.getStartOffset() + start);
        final long[] left = {end - start + 1};
        InputStream in = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                byte[] b = new byte[1];
                int n = read(b, 0, 1);
                return n <= 0 ? -1 : (b[0] & 0xff);
            }

            @Override
            public int read(byte[] buf, int off, int n) throws java.io.IOException {
                if (left[0] <= 0) {
                    return -1;
                }
                int k = fis.read(buf, off, (int) Math.min(n, left[0]));
                if (k > 0) {
                    left[0] -= k;
                }
                return k;
            }

            @Override
            public void close() {
                try {
                    fis.close();
                    afd.close();
                } catch (Exception ignored) {
                }
            }
        };
        Map<String, String> h = new HashMap<>();
        h.put("Accept-Ranges", "bytes");
        h.put("Content-Length", String.valueOf(end - start + 1));
        h.put("Access-Control-Allow-Origin", "*");
        if (partial) {
            h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
        }
        return new WebResourceResponse(type, null, partial ? 206 : 200,
                partial ? "Partial Content" : "OK", h, in);
    }

    private File projDir() {
        File d = new File(getFilesDir(), "projets");
        d.mkdirs();
        return d;
    }

    private File projFile(String name) {
        return new File(projDir(), name.replaceAll("[^\\p{L}\\p{N} _-]", "_") + ".json");
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
        public boolean projSave(String name, String json) {
            try {
                java.io.FileOutputStream o = new java.io.FileOutputStream(projFile(name));
                o.write(json.getBytes("UTF-8"));
                o.close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String projLoad(String name) {
            try {
                File f = projFile(name);
                if (!f.exists()) {
                    return "";
                }
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try (FileInputStream i = new FileInputStream(f)) {
                    byte[] b = new byte[1 << 14];
                    int n;
                    while ((n = i.read(b)) > 0) {
                        bo.write(b, 0, n);
                    }
                }
                return bo.toString("UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String projList() {
            JSONArray a = new JSONArray();
            File[] fs = projDir().listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                for (File f : fs) {
                    String n = f.getName();
                    if (n.endsWith(".json")) {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("name", n.substring(0, n.length() - 5));
                            o.put("ts", f.lastModified());
                            a.put(o);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            return a.toString();
        }

        @JavascriptInterface
        public void projDelete(String name) {
            projFile(name).delete();
        }

        @JavascriptInterface
        public String lastPicked() {
            return lastPicked;
        }

        @JavascriptInterface
        public void exportStart(String json) {
            ExportService.pendingJson = json;
            Intent svc = new Intent(MainActivity.this, ExportService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
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
                File vd = new File(getFilesDir(), "voix");
                vd.mkdirs();
                recFile = new File(vd, "voix_" + System.currentTimeMillis() + ".m4a");
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

        /** Enregistre une image fabriquee par la page (carte, diapo, image avant/apres). Retourne son adresse. */
        @JavascriptInterface
        public String saveImage(String name, String ext, String base64) {
            try {
                File d = new File(getFilesDir(), "gen");
                d.mkdirs();
                String safe = name.replaceAll("[^A-Za-z0-9_-]", "_");
                File f = new File(d, safe + ("png".equals(ext) ? ".png" : ".jpg"));
                byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                o.write(data);
                o.close();
                return Uri.fromFile(f).toString();
            } catch (Exception e) {
                return "";
            }
        }

        /** QR code : retourne les lignes de la grille (1 = noir) separees par des retours a la ligne. */
        @JavascriptInterface
        public String qr(String text) {
            try {
                com.google.zxing.qrcode.QRCodeWriter w = new com.google.zxing.qrcode.QRCodeWriter();
                java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new HashMap<>();
                hints.put(com.google.zxing.EncodeHintType.MARGIN, 0);
                com.google.zxing.common.BitMatrix m = w.encode(text,
                        com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints);
                StringBuilder sb = new StringBuilder();
                for (int y = 0; y < m.getHeight(); y++) {
                    for (int x = 0; x < m.getWidth(); x++) {
                        sb.append(m.get(x, y) ? '1' : '0');
                    }
                    sb.append('\n');
                }
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void shareVideo(String uri) {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("video/mp4");
                i.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent c = Intent.createChooser(i, "Envoyer la vidéo");
                c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(c);
            } catch (Exception ignored) {
            }
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
