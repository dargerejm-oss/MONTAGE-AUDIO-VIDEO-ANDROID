package com.jmdargere.montage;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.effect.TextOverlay;

/** Un titre affiche entre deux instants de la video finale. */
@UnstableApi
final class TitleOverlay extends TextOverlay {
    private final SpannableString text;
    private final long fromUs;
    private final long toUs;
    private final float anchorY;

    /** pos : 0 haut, 1 centre, 2 bas. sizePx : hauteur du texte en pixels de la video finale. */
    TitleOverlay(String s, int color, int sizePx, int pos, long fromUs, long toUs) {
        SpannableString sp = new SpannableString(s);
        sp.setSpan(new AbsoluteSizeSpan(sizePx), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new ForegroundColorSpan(color), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        this.text = sp;
        this.fromUs = fromUs;
        this.toUs = toUs;
        this.anchorY = pos == 0 ? 0.75f : (pos == 1 ? 0f : -0.75f);
    }

    @Override
    public SpannableString getText(long presentationTimeUs) {
        return text;
    }

    @Override
    public OverlaySettings getOverlaySettings(long t) {
        boolean on = t >= fromUs && t <= toUs;
        return new OverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, anchorY)
                .setAlphaScale(on ? 1f : 0f)
                .build();
    }
}
