package com.auralis

import android.app.Activity
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
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private var playback: PlaybackService? = null
    private var bound = false
    private var pendingUri: Uri? = null
    private lateinit var trackTitle: TextView
    private lateinit var status: TextView
    private lateinit var stereoToggle: CheckBox
    private lateinit var stereoValue: TextView
    private lateinit var stereoSlider: SeekBar
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

        val open = Button(this).apply { text = "OPEN AUDIO FILE"; setOnClickListener { chooseAudio() } }
        page.addView(open, matchWrap())
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

        addSection(page, "STEREO WIDE", "Recovered Mid-Side implementation · g = 1 + 2p")
        stereoToggle = CheckBox(this).apply {
            text = "Enable StereoWide"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> playback?.setStereoWide(checked, stereoSlider.progress) }
        }
        page.addView(stereoToggle)
        stereoValue = label("Side gain: 1.00")
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

        addSection(page, "PARAMETRIC EQ", "64-band native engine · six recovered biquad filter types")
        addEqBand(page, "Bass shelf · 90 Hz · Q 0.8", 0, 4, 90, 0.8)
        addEqBand(page, "Treble shelf · 10,000 Hz · Q 0.6", 1, 5, 10000, 0.6)

        page.addView(TextView(this).apply {
            text = "Auralis uses Android's supported media decoders and native Float32 stereo processing. DSP coefficients and the StereoWide mapping are implemented from the supplied Poweramp research record."
            textSize = 11f
            setTextColor(Color.rgb(143, 160, 170))
            setPadding(0, dp(26), 0, dp(8))
        })
        setContentView(scroll)
    }

    private fun addEqBand(parent: LinearLayout, title: String, index: Int, type: Int, frequency: Int, q: Double) {
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
        val gain = SeekBar(this).apply { max = 300; progress = 150 }
        val control = EqUiControl(index, type, frequency, q, toggle, gain, value)
        eqControls.add(control)
        fun update() {
            val db = (gain.progress - 150) / 10.0
            value.text = "Gain: ${"%.1f".format(db)} dB"
            playback?.setEqBand(index, toggle.isChecked, type, frequency, q, db, 0)
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
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
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
        playback?.setStereoWide(stereoToggle.isChecked, stereoSlider.progress)
        eqControls.forEach { c ->
            val db = (c.gain.progress - 150) / 10.0
            playback?.setEqBand(c.index, c.enabled.isChecked, c.type, c.frequency, c.q, db, 0)
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
        val index: Int, val type: Int, val frequency: Int, val q: Double,
        val enabled: CheckBox, val gain: SeekBar, val value: TextView
    )

    companion object { private const val REQUEST_AUDIO = 8201 }
}
