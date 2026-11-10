package com.jmdargere.montage;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.RgbMatrix;

/**
 * Transparence qui monte de 0 a 1 pendant toute la duree de l'element :
 * c'est la couche du dessus du fondu enchaine (elle apparait par-dessus l'element en cours).
 */
@UnstableApi
final class AlphaRamp implements RgbMatrix {
    private final long durUs;

    AlphaRamp(long durUs) {
        this.durUs = Math.max(1, durUs);
    }

    @Override
    public float[] getMatrix(long presentationTimeUs, boolean useHdr) {
        float a = Math.max(0f, Math.min(1f, (float) presentationTimeUs / (float) durUs));
        return new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, a};
    }
}
