package com.jmdargere.montage;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.TextOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.common.collect.ImmutableList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Export du montage avec le codeur materiel du telephone (Media3 Transformer).
 * La page appelle start(json), puis surveille state() / progress() ; cancel() pour arreter.
 * Etats : 0 repos, 1 en cours, 2 termine, 3 erreur (ou annule).
 */
@UnstableApi
public final class Exporter {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int st = 0;
    private static volatile int pg = 0;
    private static volatile String err = "";
    private static volatile String res = "";
    private static Transformer current;

    private Exporter() {
    }

    public static int state() {
        return st;
    }

    public static int progress() {
        return pg;
    }

    public static String error() {
        return err;
    }

    public static String result() {
        return res;
    }

    private static void finish(int s, String msg) {
        current = null;
        err = msg == null ? "" : msg;
        if (s == 2) {
            pg = 100;
        }
        st = s;
    }

    public static void start(final Context ctx, final String json) {
        st = 1;
        pg = 0;
        err = "";
        res = "";
        MAIN.post(() -> {
            try {
                run(ctx, new JSONObject(json));
            } catch (Throwable e) {
                finish(3, String.valueOf(e));
            }
        });
    }

    private static ImmutableList<AudioProcessor> volume(float v) {
        if (Math.abs(v - 1f) < 0.01f) {
            return ImmutableList.of();
        }
        ChannelMixingAudioProcessor p = new ChannelMixingAudioProcessor();
        p.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(v));
        p.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(v));
        return ImmutableList.<AudioProcessor>of(p);
    }

    private static void run(final Context ctx, JSONObject spec) throws Exception {
        final int w = spec.getInt("w");
        final int h = spec.getInt("h");
        final long fadeUs = spec.optLong("fadeMs", 0) * 1000L;
        JSONArray clips = spec.getJSONArray("clips");

        // ---------- pistes video / photos
        EditedMediaItemSequence.Builder vseq = new EditedMediaItemSequence.Builder();
        long totalUs = 0;
        for (int i = 0; i < clips.length(); i++) {
            JSONObject c = clips.getJSONObject(i);
            Uri u = Uri.parse(c.getString("uri"));
            boolean image = "image".equals(c.getString("type"));
            long durUs;
            EditedMediaItem.Builder eb;
            if (image) {
                long ms = c.getLong("imgMs");
                durUs = ms * 1000L;
                MediaItem mi = new MediaItem.Builder().setUri(u).setImageDurationMs(ms).build();
                eb = new EditedMediaItem.Builder(mi).setDurationUs(durUs).setFrameRate(30);
            } else {
                long in = c.getLong("in");
                long out = c.getLong("out");
                durUs = (out - in) * 1000L;
                MediaItem.ClippingConfiguration cc = new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(in).setEndPositionMs(out).build();
                MediaItem mi = new MediaItem.Builder().setUri(u).setClippingConfiguration(cc).build();
                eb = new EditedMediaItem.Builder(mi);
            }
            List<Effect> vfx = new ArrayList<>();
            int rot = ((c.optInt("rot", 0) % 360) + 360) % 360;
            if (rot != 0) {
                vfx.add(new ScaleAndRotateTransformation.Builder().setRotationDegrees(-rot).build());
            }
            vfx.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            double bright = c.optDouble("bright", 0) / 100.0;
            double contrast = c.optDouble("contrast", 0) / 100.0;
            double sat = c.optDouble("sat", 0);
            if (Math.abs(bright) > 0.001) {
                vfx.add(new Brightness((float) bright));
            }
            if (Math.abs(contrast) > 0.001) {
                vfx.add(new Contrast((float) contrast));
            }
            if (Math.abs(sat) > 0.5) {
                vfx.add(new HslAdjustment.Builder().adjustSaturation((float) sat).build());
            }
            if (fadeUs > 0 && c.optBoolean("fade", true)) {
                long f = Math.min(fadeUs, durUs / 2);
                vfx.add(new OverlayEffect(ImmutableList.of(new FadeOverlay(w, h, f, f, durUs))));
            }
            float vol = (float) (c.optDouble("vol", 100) / 100.0);
            eb.setEffects(new Effects(volume(vol), ImmutableList.copyOf(vfx)));
            vseq.addItem(eb.build());
            totalUs += durUs;
        }
        if (totalUs <= 0) {
            throw new IllegalStateException("Montage vide");
        }
        final long totalMs = totalUs / 1000L;

        List<EditedMediaItemSequence> seqs = new ArrayList<>();
        seqs.add(vseq.build());

        // ---------- musique
        JSONObject music = spec.optJSONObject("music");
        JSONObject voice = spec.optJSONObject("voice");
        float musicVol = music == null ? 1f : (float) (music.optDouble("vol", 100) / 100.0);
        if (music != null && voice != null && spec.optBoolean("duck", false)) {
            musicVol *= 0.35f;
        }
        if (music != null) {
            MediaItem.ClippingConfiguration cc = new MediaItem.ClippingConfiguration.Builder()
                    .setEndPositionMs(totalMs).build();
            MediaItem mi = new MediaItem.Builder().setUri(Uri.parse(music.getString("uri")))
                    .setClippingConfiguration(cc).build();
            EditedMediaItem em = new EditedMediaItem.Builder(mi).setRemoveVideo(true)
                    .setEffects(new Effects(volume(musicVol), ImmutableList.<Effect>of())).build();
            seqs.add(new EditedMediaItemSequence.Builder().addItem(em).setIsLooping(true).build());
        }

        // ---------- voix off
        if (voice != null) {
            EditedMediaItemSequence.Builder sb = new EditedMediaItemSequence.Builder();
            long startMs = voice.optLong("startMs", 0);
            if (startMs > 0) {
                sb.addGap(startMs * 1000L);
            }
            MediaItem mi = new MediaItem.Builder().setUri(Uri.fromFile(new File(voice.getString("path")))).build();
            float vv = (float) (voice.optDouble("vol", 100) / 100.0);
            sb.addItem(new EditedMediaItem.Builder(mi).setRemoveVideo(true)
                    .setEffects(new Effects(volume(vv), ImmutableList.<Effect>of())).build());
            seqs.add(sb.build());
        }

        // ---------- titres (sur toute la video finale)
        List<Effect> topFx = new ArrayList<>();
        JSONArray titles = spec.optJSONArray("titles");
        if (titles != null && titles.length() > 0) {
            List<TextOverlay> ovs = new ArrayList<>();
            for (int i = 0; i < titles.length(); i++) {
                JSONObject t = titles.getJSONObject(i);
                int px = Math.max(12, (int) (h * t.optDouble("size", 0.07)));
                ovs.add(new TitleOverlay(t.getString("text"),
                        android.graphics.Color.parseColor(t.optString("color", "#FFFFFF")),
                        px, t.optInt("pos", 2),
                        t.optLong("from", 0) * 1000L, t.optLong("to", totalMs) * 1000L));
            }
            topFx.add(new OverlayEffect(ImmutableList.<androidx.media3.effect.TextureOverlay>copyOf(ovs)));
        }

        Composition.Builder cb = new Composition.Builder(ImmutableList.copyOf(seqs));
        if (!topFx.isEmpty()) {
            cb.setEffects(new Effects(ImmutableList.<AudioProcessor>of(), ImmutableList.copyOf(topFx)));
        }
        Composition comp = cb.build();

        final File tmp = new File(ctx.getCacheDir(), "export_" + System.currentTimeMillis() + ".mp4");
        VideoEncoderSettings enc = new VideoEncoderSettings.Builder()
                .setBitrate(spec.optInt("bitrate", 0) > 0 ? spec.getInt("bitrate") : bitrateFor(w, h)).build();
        DefaultEncoderFactory ef = new DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(enc).build();
        Transformer t = new Transformer.Builder(ctx)
                .setEncoderFactory(ef)
                .setVideoMimeType(spec.optBoolean("hevc", false) ? MimeTypes.VIDEO_H265 : MimeTypes.VIDEO_H264)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult exportResult) {
                        new Thread(() -> {
                            try {
                                res = save(ctx, tmp);
                                finish(2, "");
                            } catch (Throwable e) {
                                finish(3, "Enregistrement impossible : " + e);
                            }
                        }).start();
                    }

                    @Override
                    public void onError(Composition composition, ExportResult exportResult,
                                        ExportException e) {
                        finish(3, e.getErrorCodeName() + " " + e.getMessage());
                    }
                })
                .build();
        current = t;
        t.start(comp, tmp.getAbsolutePath());
        poll(t);
    }

    private static int bitrateFor(int w, int h) {
        long px = (long) w * h;
        if (px >= 3000000L) {
            return 40000000;
        }
        if (px >= 1500000L) {
            return 16000000;
        }
        return 8000000;
    }

    /** Copie le fichier final dans la galerie (Films / MONTAGE) et retourne son adresse. */
    private static String save(Context ctx, File tmp) throws Exception {
        String name = "MONTAGE_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.US).format(new java.util.Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MONTAGE");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                throw new IllegalStateException("galerie inaccessible");
            }
            try (InputStream in = new FileInputStream(tmp);
                 OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            tmp.delete();
            return uri.toString();
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MONTAGE");
        dir.mkdirs();
        File dst = new File(dir, name);
        try (InputStream in = new FileInputStream(tmp);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        tmp.delete();
        android.media.MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, null, null);
        return Uri.fromFile(dst).toString();
    }

    private static void poll(final Transformer t) {
        if (st != 1 || current != t) {
            return;
        }
        try {
            ProgressHolder ph = new ProgressHolder();
            if (t.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) {
                pg = ph.progress;
            }
        } catch (Throwable ignore) {
        }
        MAIN.postDelayed(() -> poll(t), 500);
    }

    public static void cancel() {
        MAIN.post(() -> {
            try {
                if (current != null) {
                    current.cancel();
                }
            } catch (Throwable ignore) {
            }
            if (st == 1) {
                finish(3, "annulé");
            }
        });
    }
}
