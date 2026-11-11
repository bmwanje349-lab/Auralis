package com.auralis

import android.content.Context
import android.content.SharedPreferences

/**
 * The transition preferences recovered from Poweramp build-1031's res/xml/fade.xml.
 * Values intentionally keep Poweramp's option indices, limits, steps and defaults.
 */
data class PowerampTransitionSettings(
    val autoAdvanceFading: Int = AUTO_NO_FADING,
    val manualAdvanceFading: Int = MANUAL_SHORT_CROSSFADE,
    val fadePlayPause: Boolean = true,
    val fadeSeek: Boolean = true,
    val gaplessPreloadMs: Int = 0,
    val trackEndSilenceMs: Int = 0,
    val crossfadeLengthMs: Int = 5_000,
    val shortManualCrossfadeMs: Int = 400,
    val shortFadeMs: Int = 400,
    val seekFadeMs: Int = 100
) {
    fun normalized() = copy(
        autoAdvanceFading = autoAdvanceFading.coerceIn(AUTO_NO_FADING, AUTO_SHUFFLED_SONGS),
        manualAdvanceFading = manualAdvanceFading.coerceIn(MANUAL_NO_FADING, MANUAL_CROSSFADE),
        gaplessPreloadMs = snap(gaplessPreloadMs, 0, 5_000, 100),
        trackEndSilenceMs = snap(trackEndSilenceMs, 0, 5_000, 100),
        crossfadeLengthMs = snap(crossfadeLengthMs, 100, 15_000, 50),
        shortManualCrossfadeMs = snap(shortManualCrossfadeMs, 10, 1_000, 10),
        shortFadeMs = shortFadeMs.coerceIn(10, 1_000),
        seekFadeMs = seekFadeMs.coerceIn(10, 500)
    )

    /**
     * Poweramp's recovered automatic modes: none, non-gapless/cue, all songs,
     * or shuffled songs. Unknown cue/gapless metadata is deliberately not guessed.
     */
    fun automaticCrossfadeMs(shuffleEnabled: Boolean, isGapless: Boolean? = null, isCue: Boolean? = null): Int {
        val selected = when (autoAdvanceFading) {
            AUTO_NON_GAPLESS_CUE -> (isGapless == false || isCue == true)
            AUTO_ALL_SONGS -> true
            AUTO_SHUFFLED_SONGS -> shuffleEnabled
            else -> false
        }
        return if (selected) crossfadeLengthMs else 0
    }

    /** Manual values recovered from Poweramp: none, short crossfade, full crossfade. */
    fun manualCrossfadeMs(): Int = when (manualAdvanceFading) {
        MANUAL_SHORT_CROSSFADE -> shortManualCrossfadeMs
        MANUAL_CROSSFADE -> crossfadeLengthMs
        else -> 0
    }

    fun write(context: Context) {
        val s = normalized()
        preferences(context).edit()
            .putInt(KEY_AUTO_ADVANCE, s.autoAdvanceFading)
            .putInt(KEY_MANUAL_ADVANCE, s.manualAdvanceFading)
            .putBoolean(KEY_FADE_PLAY_PAUSE, s.fadePlayPause)
            .putBoolean(KEY_FADE_SEEK, s.fadeSeek)
            .putInt(KEY_GAPLESS_PRELOAD, s.gaplessPreloadMs)
            .putInt(KEY_TRACK_END_SILENCE, s.trackEndSilenceMs)
            .putInt(KEY_CROSSFADE_LENGTH, s.crossfadeLengthMs)
            .putInt(KEY_SHORT_MANUAL_CROSSFADE, s.shortManualCrossfadeMs)
            .putInt(KEY_SHORT_FADE, s.shortFadeMs)
            .putInt(KEY_SEEK_FADE, s.seekFadeMs)
            .apply()
    }

    companion object {
        const val AUTO_NO_FADING = 0
        const val AUTO_NON_GAPLESS_CUE = 1
        const val AUTO_ALL_SONGS = 2
        const val AUTO_SHUFFLED_SONGS = 3

        const val MANUAL_NO_FADING = 0
        const val MANUAL_SHORT_CROSSFADE = 1
        const val MANUAL_CROSSFADE = 2

        private const val PREFS = "auralis.poweramp.transitions"
        private const val KEY_AUTO_ADVANCE = "crossfade_auto_advance"
        private const val KEY_MANUAL_ADVANCE = "fade_manual_advance"
        private const val KEY_FADE_PLAY_PAUSE = "fade_play_pause"
        private const val KEY_FADE_SEEK = "fade_seek"
        private const val KEY_GAPLESS_PRELOAD = "gapless_preload_ms"
        private const val KEY_TRACK_END_SILENCE = "track_end_silence_ms"
        private const val KEY_CROSSFADE_LENGTH = "crossfade_length_ms"
        private const val KEY_SHORT_MANUAL_CROSSFADE = "fade_short_xfade_ms"
        private const val KEY_SHORT_FADE = "fade_short_ms"
        private const val KEY_SEEK_FADE = "fade_seek_ms"

        private fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun read(context: Context): PowerampTransitionSettings {
            val p = preferences(context)
            return PowerampTransitionSettings(
                autoAdvanceFading = p.getInt(KEY_AUTO_ADVANCE, AUTO_NO_FADING),
                manualAdvanceFading = p.getInt(KEY_MANUAL_ADVANCE, MANUAL_SHORT_CROSSFADE),
                fadePlayPause = p.getBoolean(KEY_FADE_PLAY_PAUSE, true),
                fadeSeek = p.getBoolean(KEY_FADE_SEEK, true),
                gaplessPreloadMs = p.getInt(KEY_GAPLESS_PRELOAD, 0),
                trackEndSilenceMs = p.getInt(KEY_TRACK_END_SILENCE, 0),
                crossfadeLengthMs = p.getInt(KEY_CROSSFADE_LENGTH, 5_000),
                shortManualCrossfadeMs = p.getInt(KEY_SHORT_MANUAL_CROSSFADE, 400),
                shortFadeMs = p.getInt(KEY_SHORT_FADE, 400),
                seekFadeMs = p.getInt(KEY_SEEK_FADE, 100)
            ).normalized()
        }

        private fun snap(value: Int, min: Int, max: Int, step: Int): Int {
            val bounded = value.coerceIn(min, max)
            return (min + ((bounded - min + step / 2) / step) * step).coerceIn(min, max)
        }
    }
}

