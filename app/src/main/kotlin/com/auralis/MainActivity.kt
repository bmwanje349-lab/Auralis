package com.auralis

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private var playback: PlaybackService? = null
    private var bound = false
    private var pendingUri: Uri? = null
    private lateinit var trackTitle: TextView
    private lateinit var status: TextView
    private lateinit var reverbToggle: CheckBox
    private val reverbSliders = arrayOfNulls<SeekBar>(7)
    private val reverbValueLabels = arrayOfNulls<TextView>(7)
    private val reverbInitial = intArrayOf(55, 50, 45, 15, 100, 50, 50)
    private lateinit var stereoToggle: CheckBox
    private lateinit var stereoValue: TextView
    private lateinit var stereoSlider: SeekBar
    private lateinit var balanceToggle: CheckBox
    private lateinit var balanceValue: TextView
    private lateinit var balanceSlider: SeekBar
    private lateinit var monoToggle: CheckBox
    private lateinit var limiterToggle: CheckBox
    private lateinit var limiterPathButton: Button
    private var limiterPath = 0
    private lateinit var eqContainer: LinearLayout
    private lateinit var eqCount: TextView
    private val eqControls = mutableListOf<EqUiControl>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playback = (service as? PlaybackService.LocalBinder)?.service()
            bound = playback != null
            status.text = if (bound) "Audio engine connected" else "Audio engine unavailable"
            pendingUri?.let { playback?.load(it); pendingUri = null }
            syncControls()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playback = null
            bound = false
            status.text = "Audio engine disconnected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        val serviceIntent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        bound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun buildInterface() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Color.rgb(16, 24, 32))
        }
        val scroll = ScrollView(this).apply { addView(page) }

        page.addView(TextView(this).apply {
            text = "AURALIS"
            textSize = 27f
            letterSpacing = 0.18f
            setTextColor(Color.rgb(128, 203, 196))
        })
        page.addView(TextView(this).apply {
            text = "NATIVE AUDIO PLAYER"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(Color.rgb(166, 184, 194))
            setPadding(0, dp(3), 0, dp(20))
        })

        page.addView(Button(this).apply {
            text = "OPEN AUDIO FILE"
            setOnClickListener { chooseAudio() }
        }, matchWrap())
        trackTitle = TextView(this).apply {
            text = "No track selected"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, dp(18), 0, dp(4))
        }
        page.addView(trackTitle)
        status = TextView(this).apply {
            text = "Connecting to audio engine…"
            textSize = 12f
            setTextColor(Color.rgb(166, 184, 194))
            setPadding(0, 0, 0, dp(16))
        }
        page.addView(status)

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        transport.addView(Button(this).apply {
            text = "PLAY / RESUME"
            setOnClickListener { playback?.resumePlayback() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        transport.addView(Button(this).apply {
            text = "PAUSE"
            setOnClickListener { playback?.pausePlayback() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        page.addView(transport)
        page.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener { playback?.stopPlayback(); status.text = "Playback stopped" }
        }, matchWrap().apply { topMargin = dp(6) })

        addSection(page, "REVERB", "Poweramp-mapped controls · experimental stereo reverb while matrix fidelity is verified")
        reverbToggle = CheckBox(this).apply {
            text = "Enable Reverb"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> playback?.setReverbEnabled(checked) }
        }
        page.addView(reverbToggle)
        val reverbNames = arrayOf("Size", "Filter", "Fade", "Pre-Delay", "Mix", "Pre-Delay Mix", "Damp")
        for (index in reverbNames.indices) {
            val label = label("${reverbNames[index]}: ${reverbInitial[index]}%")
            reverbValueLabels[index] = label
            page.addView(label)
            val slider = SeekBar(this).apply {
                max = 100
                progress = reverbInitial[index]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                        label.text = when (index) {
                            1 -> "Filter: $value% · cutoff ${100 + 185 * value} Hz"
                            2 -> "Fade: $value%"
                            3 -> "Pre-Delay: $value% · ${"%.1f".format(value * 1.9921875)} ms"
                            6 -> "Damp: $value% · cutoff ${18600 - 185 * value} Hz"
                            else -> "${reverbNames[index]}: $value%"
                        }
                        playback?.setReverbParameter(index, value / 100.0)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            reverbSliders[index] = slider
            page.addView(slider, matchWrap())
        }

        addSection(page, "STEREO WIDE", "Recovered Mid-Side implementation · g = 1 + 2p")
        stereoToggle = CheckBox(this).apply {
            text = "Enable StereoWide"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                playback?.setStereoWide(checked, stereoSlider.progress)
            }
        }
        page.addView(stereoToggle)
        stereoValue = label("Slider: 0%    Side gain: 1.00")
        page.addView(stereoValue)
        stereoSlider = SeekBar(this).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    val gain = 1.0 + 2.0 * value / 100.0
                    stereoValue.text = "Slider: $value%    Side gain: ${"%.2f".format(gain)}"
                    playback?.setStereoWide(stereoToggle.isChecked, value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        page.addView(stereoSlider, matchWrap())

        addSection(page, "BALANCE", "Positive values attenuate left; negative values attenuate right")
        balanceToggle = CheckBox(this).apply {
            text = "Enable Balance"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                playback?.setBalance(checked, (balanceSlider.progress - 100) / 100.0)
            }
        }
        page.addView(balanceToggle)
        balanceValue = label("Center · left 0.0 dB · right 0.0 dB")
        page.addView(balanceValue)
        balanceSlider = SeekBar(this).apply {
            max = 200
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    val normalized = (value - 100) / 100.0
                    val db = 48.0 * kotlin.math.abs(normalized)
                    balanceValue.text = when {
                        normalized > 0.0 -> "Right bias · left −${"%.1f".format(db)} dB"
                        normalized < 0.0 -> "Left bias · right −${"%.1f".format(db)} dB"
                        else -> "Center · left 0.0 dB · right 0.0 dB"
                    }
                    playback?.setBalance(balanceToggle.isChecked, normalized)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        page.addView(balanceSlider, matchWrap())

        addSection(page, "MONO", "Native (L+R)/2 fold-down")
        monoToggle = CheckBox(this).apply {
            text = "Enable Mono"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> playback?.setMono(checked) }
        }
        page.addView(monoToggle)

        addSection(page, "PEAK LIMITER", "Linked stereo detector · native envelope attack/release")
        limiterToggle = CheckBox(this).apply {
            text = "Enable Limiter"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> playback?.setLimiter(checked, limiterPath) }
        }
        page.addView(limiterToggle)
        limiterPathButton = Button(this).apply {
            text = "Path: Hard ceiling (1.0)"
            setOnClickListener {
                limiterPath = if (limiterPath == 0) 1 else 0
                text = if (limiterPath == 0) "Path: Hard ceiling (1.0)" else "Path: Pre-gain protection"
                playback?.setLimiter(limiterToggle.isChecked, limiterPath)
            }
        }
        page.addView(limiterPathButton, matchWrap())

        addSection(page, "PARAMETRIC EQ", "Up to 64 bands · recovered six-filter biquad model")
        eqCount = label("0 / 64 bands")
        page.addView(eqCount)
        eqContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(eqContainer, matchWrap())
        addEqBand(eqContainer, "Bass shelf · 90 Hz · Q 0.8", 0, 4, 90, 0.8)
        addEqBand(eqContainer, "Treble shelf · 10,000 Hz · Q 0.6", 1, 5, 10000, 0.6)
        page.addView(Button(this).apply {
            text = "ADD PARAMETRIC BAND"
            setOnClickListener { showAddEqBandDialog() }
        }, matchWrap().apply { topMargin = dp(10) })
        updateEqCount()

        page.addView(TextView(this).apply {
            text = "This player uses Android media decoding and native Float32 stereo processing. The recovered playback chain includes StereoWide, Mono fold-down, Balance and the linked-peak Limiter, alongside the six EQ coefficient families. Channel code meanings below the documented 0–2 range use Auralis's local UI mapping."
            textSize = 11f
            setTextColor(Color.rgb(143, 160, 170))
            setPadding(0, dp(26), 0, dp(8))
        })
        setContentView(scroll)
    }

    private fun addEqBand(
        parent: LinearLayout,
        title: String,
        index: Int,
        type: Int,
        frequency: Int,
        q: Double,
        channel: Int = 0,
        initialGainDb: Double = 0.0
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setBackgroundColor(Color.rgb(25, 36, 45))
        }
        val toggle = CheckBox(this).apply {
            text = title
            setTextColor(Color.WHITE)
        }
        val value = label("Gain: 0.0 dB")
        val gain = SeekBar(this).apply {
            max = 300
            progress = ((initialGainDb.coerceIn(-15.0, 15.0) * 10.0) + 150.0).toInt()
        }
        val control = EqUiControl(index, type, frequency, q, channel, toggle, gain, value)
        eqControls.add(control)

        fun update() {
            val db = (gain.progress - 150) / 10.0
            value.text = "Gain: ${"%.1f".format(db)} dB"
            playback?.setEqBand(index, toggle.isChecked, type, frequency, q, db, channel)
        }
        toggle.setOnCheckedChangeListener { _, _ -> update() }
        gain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = update()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        box.addView(toggle)
        box.addView(value)
        box.addView(gain)
        parent.addView(box, matchWrap().apply { topMargin = dp(8) })
        updateEqCount()
    }

    private fun showAddEqBandDialog() {
        if (eqControls.size >= MAX_EQ_BANDS) {
            Toast.makeText(this, "The recovered model supports 64 bands.", Toast.LENGTH_SHORT).show()
            return
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val frequencyInput = EditText(this).apply {
            hint = "Frequency (1–24000 Hz)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1000")
        }
        val qInput = EditText(this).apply {
            hint = "Q (0.1–12)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("1.0")
        }
        val gainInput = EditText(this).apply {
            hint = "Gain (-15 to +15 dB)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText("0.0")
        }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, FILTER_LABELS)
            setSelection(3)
        }
        val channelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, CHANNEL_LABELS)
            setSelection(0)
        }
        form.addView(label("Filter type"))
        form.addView(typeSpinner, matchWrap())
        form.addView(label("Frequency in Hz"))
        form.addView(frequencyInput, matchWrap())
        form.addView(label("Quality factor"))
        form.addView(qInput, matchWrap())
        form.addView(label("Band gain in dB"))
        form.addView(gainInput, matchWrap())
        form.addView(label("Channel selection (Auralis-local numeric mapping)"))
        form.addView(channelSpinner, matchWrap())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add parametric EQ band")
            .setView(form)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("ADD", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val frequency = frequencyInput.text.toString().trim().toIntOrNull()
                val q = qInput.text.toString().trim().toDoubleOrNull()
                val gain = gainInput.text.toString().trim().toDoubleOrNull()
                val type = typeSpinner.selectedItemPosition
                val channel = channelSpinner.selectedItemPosition

                when {
                    frequency == null || frequency !in 1..24000 ->
                        Toast.makeText(this, "Frequency must be 1–24000 Hz.", Toast.LENGTH_SHORT).show()
                    q == null || q < 0.1 || q > 12.0 ->
                        Toast.makeText(this, "Q must be between 0.1 and 12.", Toast.LENGTH_SHORT).show()
                    gain == null || gain < -15.0 || gain > 15.0 ->
                        Toast.makeText(this, "Gain must be between -15 and +15 dB.", Toast.LENGTH_SHORT).show()
                    else -> {
                        val index = eqControls.size
                        val title = "${FILTER_SHORT_NAMES[type]} · $frequency Hz · Q ${"%.2f".format(q)}"
                        addEqBand(eqContainer, title, index, type, frequency, q, channel, gain)
                        playback?.setEqBand(index, false, type, frequency, q, gain, channel)
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun updateEqCount() {
        if (::eqCount.isInitialized) eqCount.text = "${eqControls.size} / $MAX_EQ_BANDS bands"
    }

    private fun addSection(parent: LinearLayout, title: String, subtitle: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 16f
            letterSpacing = 0.08f
            setTextColor(Color.rgb(128, 203, 196))
            setPadding(0, dp(26), 0, dp(3))
        })
        parent.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Color.rgb(143, 160, 170))
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 12f
        setTextColor(Color.rgb(201, 214, 220))
        setPadding(0, dp(2), 0, dp(4))
    }

    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_AUDIO)
    }

    @Deprecated("The platform document picker callback is retained for minimum-dependency Android builds")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { }
        trackTitle.text = displayName(uri)
        status.text = "Preparing decoder…"
        if (playback != null) playback?.load(uri) else pendingUri = uri
    }

    private fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) ?: "Selected audio" else "Selected audio"
        } catch (_: Throwable) {
            "Selected audio"
        } finally {
            cursor?.close()
        }
    }

    private fun syncControls() {
        playback?.setReverbEnabled(reverbToggle.isChecked)
        reverbSliders.forEachIndexed { index, slider ->
            slider?.let { playback?.setReverbParameter(index, it.progress / 100.0) }
        }
        playback?.setStereoWide(stereoToggle.isChecked, stereoSlider.progress)
        playback?.setBalance(balanceToggle.isChecked, (balanceSlider.progress - 100) / 100.0)
        playback?.setMono(monoToggle.isChecked)
        playback?.setLimiter(limiterToggle.isChecked, limiterPath)
        eqControls.forEach { c ->
            val db = (c.gain.progress - 150) / 10.0
            playback?.setEqBand(c.index, c.enabled.isChecked, c.type, c.frequency, c.q, db, c.channel)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        if (bound) unbindService(connection)
        bound = false
        playback = null
        super.onDestroy()
    }

    private data class EqUiControl(
        val index: Int,
        val type: Int,
        val frequency: Int,
        val q: Double,
        val channel: Int,
        val enabled: CheckBox,
        val gain: SeekBar,
        val value: TextView
    )

    companion object {
        private const val REQUEST_AUDIO = 8201
        private const val MAX_EQ_BANDS = 64
        private val FILTER_LABELS = arrayOf(
            "Low-pass (0)", "High-pass (1)", "Band-pass (2)",
            "Peaking (3)", "Low shelf (4)", "High shelf (5)"
        )
        private val FILTER_SHORT_NAMES = arrayOf(
            "Low-pass", "High-pass", "Band-pass", "Peaking", "Low shelf", "High shelf"
        )
        private val CHANNEL_LABELS = arrayOf("Both channels (0)", "Left channel (1)", "Right channel (2)")
    }
}
