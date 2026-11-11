package com.auralis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One preset for the ten-band graphic equaliser; [gains] are dB for [SoundPresets.BAND_FREQUENCIES]. */
internal class EqPreset(val name: String, val blurb: String, val gains: FloatArray)

internal object SoundPresets {
    const val BAND_COUNT = 10
    const val MAX_GAIN_DB = 12f
    const val FLAT = "Flat"
    const val CUSTOM = "Custom"

    val BAND_FREQUENCIES = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val BAND_LABELS = arrayOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    /** First band is a low shelf, last band is a high shelf, the rest are peaking filters. */
    fun bandType(index: Int): Int = when (index) {
        0 -> 4
        BAND_COUNT - 1 -> 5
        else -> 3
    }

    fun bandQ(index: Int): Double = if (index == 0 || index == BAND_COUNT - 1) 0.7 else 1.3

    val all: List<EqPreset> = listOf(
        EqPreset(FLAT, "Untouched, exactly as recorded.",
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Bass boost", "A deeper, fuller low end.",
            floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Treble boost", "Crisper highs and more sparkle.",
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
        EqPreset("Vocal", "Voices and dialogue come forward.",
            floatArrayOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
        EqPreset("Rock", "Punchy lows and bright highs.",
            floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
        EqPreset("Pop", "Warm, lively and easy to enjoy.",
            floatArrayOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f)),
        EqPreset("Hip-hop", "Heavy bass with clear vocals.",
            floatArrayOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, -1f, 2f, 3f)),
        EqPreset("Electronic", "Big lows and shimmering highs.",
            floatArrayOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 2f, 4f, 5f)),
        EqPreset("Jazz", "Smooth, natural and open.",
            floatArrayOf(3f, 2f, 1f, 2f, -2f, -2f, 0f, 1f, 2f, 3f)),
        EqPreset("Classical", "Gentle and spacious.",
            floatArrayOf(3f, 2f, 1f, 0f, -1f, -1f, 0f, 1f, 2f, 3f)),
        EqPreset("Acoustic", "Natural strings and warm vocals.",
            floatArrayOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 3f, 2f)),
        EqPreset("Loudness", "Fuller sound at low volume.",
            floatArrayOf(5f, 3f, 0f, 0f, -1f, 0f, 0f, 1f, 3f, 5f))
    )

    fun byName(name: String): EqPreset? = all.firstOrNull { it.name == name }
}

/** A user-defined parametric EQ band (band index is assigned from its position in the list). */
internal data class ParametricBand(
    val type: Int,
    val frequency: Int,
    val q: Double,
    val gainDb: Double,
    val channel: Int,
    val enabled: Boolean
)

/** Everything on the Sound page except the master preamp, which has its own preference. */
internal class SoundSnapshot(
    val stereoOn: Boolean,
    val stereoWidth: Int,
    val balanceOn: Boolean,
    val balance: Int,
    val mono: Boolean,
    val reverbOn: Boolean,
    val reverb: IntArray,
    val limiterOn: Boolean,
    val limiterPath: Int,
    val replayMode: Int,
    val replaySource: Int,
    val replayDefault: Int,
    val eqOn: Boolean,
    val eqGains: FloatArray,
    val eqPreset: String,
    val bands: List<ParametricBand>
)

/** Remembers the listener's sound settings between launches. */
internal class SoundStore(context: Context) {
    private val prefs = context.getSharedPreferences("auralis.sound", Context.MODE_PRIVATE)

    fun load(): SoundSnapshot? {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { parse(JSONObject(raw)) }.getOrNull()
    }

    fun save(snapshot: SoundSnapshot) {
        prefs.edit().putString(KEY_SNAPSHOT, toJson(snapshot).toString()).apply()
    }

    private fun toJson(s: SoundSnapshot): JSONObject = JSONObject().apply {
        put("stereoOn", s.stereoOn)
        put("stereoWidth", s.stereoWidth)
        put("balanceOn", s.balanceOn)
        put("balance", s.balance)
        put("mono", s.mono)
        put("reverbOn", s.reverbOn)
        put("reverb", JSONArray().also { array -> s.reverb.forEach { array.put(it) } })
        put("limiterOn", s.limiterOn)
        put("limiterPath", s.limiterPath)
        put("replayMode", s.replayMode)
        put("replaySource", s.replaySource)
        put("replayDefault", s.replayDefault)
        put("eqOn", s.eqOn)
        put("eqGains", JSONArray().also { array -> s.eqGains.forEach { array.put(it.toDouble()) } })
        put("eqPreset", s.eqPreset)
        put("bands", JSONArray().also { array ->
            s.bands.forEach { band ->
                array.put(JSONObject().apply {
                    put("type", band.type)
                    put("frequency", band.frequency)
                    put("q", band.q)
                    put("gainDb", band.gainDb)
                    put("channel", band.channel)
                    put("enabled", band.enabled)
                })
            }
        })
    }

    private fun parse(o: JSONObject): SoundSnapshot {
        val reverbArray = o.optJSONArray("reverb")
        val gainArray = o.optJSONArray("eqGains")
        val bandArray = o.optJSONArray("bands")
        val bands = ArrayList<ParametricBand>()
        if (bandArray != null) {
            for (i in 0 until bandArray.length()) {
                val b = bandArray.optJSONObject(i) ?: continue
                bands.add(ParametricBand(
                    type = b.optInt("type", 3).coerceIn(0, 5),
                    frequency = b.optInt("frequency", 1000).coerceIn(1, 24000),
                    q = b.optDouble("q", 1.0).coerceIn(0.1, 12.0),
                    gainDb = b.optDouble("gainDb", 0.0).coerceIn(-15.0, 15.0),
                    channel = b.optInt("channel", 0).coerceIn(0, 2),
                    enabled = b.optBoolean("enabled", false)
                ))
            }
        }
        return SoundSnapshot(
            stereoOn = o.optBoolean("stereoOn", false),
            stereoWidth = o.optInt("stereoWidth", 0).coerceIn(0, 100),
            balanceOn = o.optBoolean("balanceOn", false),
            balance = o.optInt("balance", 100).coerceIn(0, 200),
            mono = o.optBoolean("mono", false),
            reverbOn = o.optBoolean("reverbOn", false),
            reverb = IntArray(7) { i ->
                (reverbArray?.optInt(i, DEFAULT_REVERB[i]) ?: DEFAULT_REVERB[i]).coerceIn(0, 100)
            },
            limiterOn = o.optBoolean("limiterOn", false),
            limiterPath = o.optInt("limiterPath", 0).coerceIn(0, 1),
            replayMode = o.optInt("replayMode", 0).coerceIn(0, 2),
            replaySource = o.optInt("replaySource", 0).coerceIn(0, 1),
            replayDefault = o.optInt("replayDefault", 150).coerceIn(0, 300),
            eqOn = o.optBoolean("eqOn", false),
            eqGains = FloatArray(SoundPresets.BAND_COUNT) { i ->
                (gainArray?.optDouble(i, 0.0) ?: 0.0).toFloat()
                    .coerceIn(-SoundPresets.MAX_GAIN_DB, SoundPresets.MAX_GAIN_DB)
            },
            eqPreset = o.optString("eqPreset", SoundPresets.FLAT),
            bands = bands
        )
    }

    private companion object {
        const val KEY_SNAPSHOT = "snapshot_v1"
        val DEFAULT_REVERB = intArrayOf(55, 50, 45, 15, 100, 50, 50)
    }
}
