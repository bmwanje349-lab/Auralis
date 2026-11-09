package com.auralis

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.OpenableColumns
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ProgressBar
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
    private var pendingQueue: Pair<List<MusicTrack>, Int>? = null
    private lateinit var contentFrame: FrameLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniSubtitle: TextView
    private lateinit var miniPlayButton: Button
    private lateinit var dspPage: View
    private val libraryTracks = mutableListOf<MusicTrack>()
    private var displayedTracks: List<MusicTrack> = emptyList()
    private var currentLibraryTab = LIBRARY_SONGS
    private var currentSearch = ""
    private var currentSort = SORT_TITLE
    private var currentAlbum: String? = null
    private var currentArtist: String? = null
    private var currentPlaylist: String? = null
    private var currentNowPlaying: MusicTrack? = null
    private val playlistStore by lazy { PlaylistStore(this) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var userSeeking = false
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
    private var replayMode = 0      // 0 None, 1 Apply, 2 Apply Peak
    private var replaySource = 0    // 0 Album, 1 Track
    private lateinit var replayModeButton: Button
    private lateinit var replaySourceButton: Button
    private lateinit var replayPreampValue: TextView
    private lateinit var replayPreampSlider: SeekBar
    private lateinit var replayDefaultValue: TextView
    private lateinit var replayDefaultSlider: SeekBar
    private lateinit var eqContainer: LinearLayout
    private lateinit var eqCount: TextView
    private val eqControls = mutableListOf<EqUiControl>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playback = (service as? PlaybackService.LocalBinder)?.service()
            bound = playback != null
            if (::status.isInitialized) status.text = if (bound) "Audio engine connected" else "Audio engine unavailable"
            pendingUri?.let { playback?.load(it); pendingUri = null }
            pendingQueue?.let { (tracks, index) -> playback?.playQueue(tracks, index); pendingQueue = null }
            if (::reverbToggle.isInitialized) syncControls()
            updateMiniPlayer()
            uiHandler.removeCallbacks(progressTicker)
            uiHandler.post(progressTicker)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playback = null
            bound = false
            if (::status.isInitialized) status.text = "Audio engine disconnected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        val serviceIntent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        bound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        requestLibraryPermissionOrLoad()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 18, 25))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(12))
            setBackgroundColor(Color.rgb(18, 28, 38))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "AURALIS"
            textSize = 22f
            letterSpacing = 0.16f
            setTextColor(Color.rgb(128, 203, 196))
        })
        brand.addView(TextView(this).apply {
            text = "MUSIC PLAYER"
            textSize = 9f
            letterSpacing = 0.18f
            setTextColor(Color.rgb(157, 176, 190))
        })
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "OPEN FILE"
            setOnClickListener { chooseAudio() }
            styleButton(this, compact = true)
        })
        root.addView(header)

        contentFrame = FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(10), dp(7))
            setBackgroundColor(Color.rgb(24, 36, 47))
            setOnClickListener { showNowPlaying() }
        }
        val miniText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        miniTitle = TextView(this).apply {
            text = "Nothing playing"
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
        }
        miniSubtitle = TextView(this).apply {
            text = "Choose music from your library"
            textSize = 11f
            setTextColor(Color.rgb(155, 174, 187))
            maxLines = 1
        }
        miniText.addView(miniTitle)
        miniText.addView(miniSubtitle)
        mini.addView(miniText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        mini.addView(Button(this).apply {
            text = "|◀"
            setOnClickListener { playback?.skipToPrevious(); updateMiniPlayer() }
            styleButton(this, compact = true)
        })
        miniPlayButton = Button(this).apply {
            text = "▶"
            setOnClickListener { togglePlayback() }
            styleButton(this, compact = true)
        }
        mini.addView(miniPlayButton)
        mini.addView(Button(this).apply {
            text = "▶|"
            setOnClickListener { playback?.skipToNext(); updateMiniPlayer() }
            styleButton(this, compact = true)
        })
        root.addView(mini)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(18, 28, 38))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        listOf("LIBRARY", "NOW PLAYING", "DSP").forEachIndexed { index, title ->
            nav.addView(Button(this).apply {
                text = title
                textSize = 10f
                setOnClickListener { when (index) { 0 -> showLibrary(); 1 -> showNowPlaying(); else -> showDsp() } }
                styleButton(this, compact = true)
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        root.addView(nav)
        setContentView(root)

        // Build once so all existing DSP controls are ready when the service binds.
        dspPage = buildDspPage()
        showLibrary()
    }

    private fun buildDspPage(): View {
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
                            3 -> "Pre-Delay: $value% · ${value * 2} ms"
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
                        normalized >= 1.0 -> "Right bias · left silent (gain exactly 0)"
                        normalized <= -1.0 -> "Left bias · right silent (gain exactly 0)"
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

        addSection(page, "REPLAYGAIN", "Reads REPLAYGAIN / R128 tags (MP3, FLAC, Ogg, Opus, M4A) when a track loads")
        replayModeButton = Button(this).apply {
            text = replayModeText()
            setOnClickListener {
                replayMode = (replayMode + 1) % 3
                text = replayModeText()
                applyReplayGain()
            }
        }
        page.addView(replayModeButton, matchWrap())
        replaySourceButton = Button(this).apply {
            text = replaySourceText()
            setOnClickListener {
                replaySource = 1 - replaySource
                text = replaySourceText()
                applyReplayGain()
            }
        }
        page.addView(replaySourceButton, matchWrap())
        replayPreampValue = label("Master preamp: ${replayDbText(150)}")
        page.addView(replayPreampValue)
        replayPreampSlider = SeekBar(this).apply {
            max = 300
            progress = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getInt(KEY_MASTER_PREAMP, 150).coerceIn(0, 300)
            replayPreampValue.text = "Master preamp: ${replayDbText(progress)}"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    replayPreampValue.text = "Master preamp: ${replayDbText(value)}"
                    applyReplayGain()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        page.addView(replayPreampSlider, matchWrap())
        replayDefaultValue = label("Gain for files without tags: ${replayDbText(150)}")
        page.addView(replayDefaultValue)
        replayDefaultSlider = SeekBar(this).apply {
            max = 300
            progress = 150
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    replayDefaultValue.text = "Gain for files without tags: ${replayDbText(value)}"
                    applyReplayGain()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        page.addView(replayDefaultSlider, matchWrap())

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
        return scroll
    }

    private fun styleButton(button: Button, compact: Boolean = false) {
        button.setTextColor(Color.rgb(220, 238, 239))
        button.backgroundTintList = ColorStateList.valueOf(if (compact) Color.rgb(37, 57, 70) else Color.rgb(30, 59, 68))
        button.textSize = if (compact) 10f else 13f
        button.isAllCaps = false
        button.minHeight = if (compact) dp(38) else dp(46)
        button.setPadding(dp(if (compact) 7 else 14), dp(5), dp(if (compact) 7 else 14), dp(5))
    }

    private fun audioPermission(): String = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun requestLibraryPermissionOrLoad() {
        val permission = audioPermission()
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            refreshLibrary()
        } else {
            requestPermissions(arrayOf(permission), REQUEST_LIBRARY_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LIBRARY_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) refreshLibrary()
            else Toast.makeText(this, "Allow audio access to build your music library. You can still open individual files.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshLibrary() {
        Thread({
            val loaded = runCatching { MediaLibraryRepository.load(this) }.getOrElse {
                runOnUiThread { Toast.makeText(this, "Could not scan audio: ${it.message ?: "unknown error"}", Toast.LENGTH_LONG).show() }
                emptyList()
            }
            runOnUiThread {
                libraryTracks.clear()
                libraryTracks.addAll(loaded)
                if (::contentFrame.isInitialized && currentLibraryTab != LIBRARY_DSP) showLibrary()
            }
        }, "Auralis-library-scan").start()
    }

    private fun showDsp() {
        contentFrame.removeAllViews()
        contentFrame.addView(dspPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showLibrary() {
        contentFrame.removeAllViews()
        contentFrame.addView(buildLibraryView(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildLibraryView(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(Color.rgb(12, 18, 25))
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply {
            text = "Your library"
            textSize = 23f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(Button(this).apply {
            text = "↻"
            contentDescription = "Rescan music library"
            setOnClickListener { requestLibraryPermissionOrLoad() }
            styleButton(this, compact = true)
        })
        page.addView(heading)
        val search = EditText(this).apply {
            hint = "Search songs, artists, albums"
            singleLine = true
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(137, 157, 169))
            backgroundTintList = ColorStateList.valueOf(Color.rgb(75, 110, 122))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setText(currentSearch)
        }
        page.addView(search, matchWrap().apply { topMargin = dp(8); bottomMargin = dp(8) })

        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(LIBRARY_SONGS, LIBRARY_ALBUMS, LIBRARY_ARTISTS, LIBRARY_FAVOURITES, LIBRARY_PLAYLISTS).forEach { tab ->
            tabs.addView(Button(this).apply {
                text = tab
                styleButton(this, compact = true)
                if (currentLibraryTab == tab) backgroundTintList = ColorStateList.valueOf(Color.rgb(24, 116, 111))
                setOnClickListener {
                    currentLibraryTab = tab
                    currentAlbum = null
                    currentArtist = null
                    currentPlaylist = null
                    showLibrary()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(5) })
        }
        tabScroll.addView(tabs)
        page.addView(tabScroll)

        val detail = currentAlbum ?: currentArtist ?: currentPlaylist
        if (detail != null) {
            page.addView(Button(this).apply {
                text = "← Back to ${currentLibraryTab.lowercase()}"
                styleButton(this, compact = true)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { currentAlbum = null; currentArtist = null; currentPlaylist = null; showLibrary() }
            }, matchWrap().apply { topMargin = dp(5); bottomMargin = dp(4) })
        }

        val sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val count = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(156, 176, 188)) }
        sortRow.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sort = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf(SORT_TITLE, SORT_ARTIST, SORT_ALBUM, SORT_RECENT))
            setSelection(arrayOf(SORT_TITLE, SORT_ARTIST, SORT_ALBUM, SORT_RECENT).indexOf(currentSort).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentSort = arrayOf(SORT_TITLE, SORT_ARTIST, SORT_ALBUM, SORT_RECENT)[position]
                    if (libraryListContainer != null) renderLibraryItems(count)
                }
            }
        }
        sortRow.addView(sort, LinearLayout.LayoutParams(dp(150), dp(42)))
        page.addView(sortRow)

        libraryListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(this).apply { isFillViewport = true; addView(libraryListContainer) }
        page.addView(listScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearch = s?.toString().orEmpty()
                renderLibraryItems(count)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderLibraryItems(count)
        return page
    }

    private var libraryListContainer: LinearLayout? = null

    private fun renderLibraryItems(countLabel: TextView? = null) {
        val container = libraryListContainer ?: return
        container.removeAllViews()
        val query = currentSearch.trim().lowercase()
        fun matches(track: MusicTrack): Boolean = query.isBlank() ||
            track.title.lowercase().contains(query) || track.artist.lowercase().contains(query) || track.album.lowercase().contains(query)
        var tracks = when (currentLibraryTab) {
            LIBRARY_FAVOURITES -> libraryTracks.filter { playlistStore.isFavourite(it.id) }
            LIBRARY_PLAYLISTS -> currentPlaylist?.let { name ->
                val ids = playlistStore.trackIds(name).toSet()
                libraryTracks.filter { it.id in ids }
            } ?: emptyList()
            else -> libraryTracks.filter { track ->
                (currentAlbum == null || track.album == currentAlbum) &&
                (currentArtist == null || track.artist == currentArtist)
            }
        }.filter(::matches)
        tracks = when (currentSort) {
            SORT_ARTIST -> tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist }.thenBy { it.title })
            SORT_ALBUM -> tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.album }.thenBy { it.title })
            SORT_RECENT -> tracks.sortedByDescending { it.dateAddedSeconds }
            else -> tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
        displayedTracks = tracks
        countLabel?.text = when (currentLibraryTab) {
            LIBRARY_PLAYLISTS -> if (currentPlaylist == null) "${playlistStore.playlists().size + 1} playlists" else "${tracks.size} tracks"
            LIBRARY_ALBUMS -> "${libraryTracks.map { it.album to it.artist }.distinct().size} albums"
            LIBRARY_ARTISTS -> "${libraryTracks.map { it.artist }.distinct().size} artists"
            else -> "${tracks.size} songs"
        }

        if (currentLibraryTab == LIBRARY_ALBUMS && currentAlbum == null) {
            val groups = libraryTracks.groupBy { it.album to it.artist }.entries.sortedBy { it.key.first.lowercase() }
                .filter { query.isBlank() || it.key.first.lowercase().contains(query) || it.key.second.lowercase().contains(query) }
            groups.forEach { entry ->
                val representative = entry.value.first()
                addLibraryCollectionRow(container, entry.key.first, entry.key.second, "${entry.value.size} songs") {
                    currentAlbum = entry.key.first
                    showLibrary()
                }
            }
        } else if (currentLibraryTab == LIBRARY_ARTISTS && currentArtist == null) {
            libraryTracks.groupBy { it.artist }.entries.sortedBy { it.key.lowercase() }
                .filter { query.isBlank() || it.key.lowercase().contains(query) }
                .forEach { entry -> addLibraryCollectionRow(container, entry.key, "Artist", "${entry.value.size} songs") {
                    currentArtist = entry.key
                    showLibrary()
                } }
        } else if (currentLibraryTab == LIBRARY_PLAYLISTS && currentPlaylist == null) {
            addLibraryCollectionRow(container, PlaylistStore.FAVOURITES_NAME, "Your saved tracks", "${playlistStore.favourites().size} songs") {
                currentPlaylist = PlaylistStore.FAVOURITES_NAME
                showLibrary()
            }
            playlistStore.playlists().forEach { name ->
                addLibraryCollectionRow(container, name, "Playlist", "${playlistStore.trackIds(name).size} songs") {
                    currentPlaylist = name
                    showLibrary()
                }
            }
            container.addView(Button(this).apply {
                text = "+ Create playlist"
                styleButton(this)
                setOnClickListener { showCreatePlaylistDialog() }
            }, matchWrap().apply { topMargin = dp(10) })
        } else {
            if (tracks.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = if (libraryTracks.isEmpty()) "No music found yet. Add audio to your device or use OPEN FILE." else "No matching tracks. Try another search or category."
                    textSize = 14f
                    setTextColor(Color.rgb(157, 176, 188))
                    gravity = Gravity.CENTER
                    setPadding(dp(22), dp(44), dp(22), dp(44))
                })
            } else {
                tracks.forEachIndexed { index, track -> addTrackRow(container, track, tracks, index) }
            }
        }
    }

    private fun addLibraryCollectionRow(parent: LinearLayout, title: String, subtitle: String, trailing: String, click: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(22, 33, 43))
            setOnClickListener { click() }
        }
        val art = TextView(this).apply {
            text = "♫"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(128, 203, 196))
            setBackgroundColor(Color.rgb(36, 57, 68))
        }
        row.addView(art, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply { this.text = title; textSize = 15f; setTextColor(Color.WHITE); maxLines = 1 })
        text.addView(TextView(this).apply { this.text = subtitle; textSize = 12f; setTextColor(Color.rgb(154, 175, 188)); maxLines = 1 })
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply { this.text = trailing; textSize = 11f; setTextColor(Color.rgb(154, 175, 188)) })
        parent.addView(row, matchWrap().apply { topMargin = dp(5) })
    }

    private fun addTrackRow(parent: LinearLayout, track: MusicTrack, queueTracks: List<MusicTrack>, index: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(4), dp(8))
            setBackgroundColor(Color.rgb(20, 30, 40))
        }
        val art = TextView(this).apply {
            text = "♫"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(128, 203, 196))
            setBackgroundColor(Color.rgb(36, 57, 68))
        }
        row.addView(art, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply { text = track.title; textSize = 14f; setTextColor(Color.WHITE); maxLines = 1 })
        info.addView(TextView(this).apply { text = "${track.artist}  ·  ${track.album}"; textSize = 11f; setTextColor(Color.rgb(153, 173, 186)); maxLines = 1 })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply { text = track.durationLabel; textSize = 11f; setTextColor(Color.rgb(153, 173, 186)); setPadding(dp(4), 0, dp(5), 0) })
        row.addView(Button(this).apply {
            text = "⋮"
            contentDescription = "More actions for ${track.title}"
            styleButton(this, compact = true)
            setOnClickListener { showTrackActions(track) }
        })
        row.setOnClickListener { playTracks(queueTracks, index) }
        row.setOnLongClickListener { showTrackActions(track); true }
        parent.addView(row, matchWrap().apply { topMargin = dp(5) })
    }

    private fun playTracks(tracks: List<MusicTrack>, index: Int) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        currentNowPlaying = tracks[index]
        if (playback != null) playback?.playQueue(tracks, index) else pendingQueue = tracks to index
        if (::trackTitle.isInitialized) trackTitle.text = tracks[index].title
        if (::status.isInitialized) status.text = "Preparing decoder…"
        updateMiniPlayer()
        showNowPlaying()
    }

    private fun showTrackActions(track: MusicTrack) {
        val favourite = playlistStore.isFavourite(track.id)
        val actions = arrayOf("Play now", "Play next", "Add to queue", if (favourite) "Remove from favourites" else "Add to favourites", "Add to playlist")
        AlertDialog.Builder(this).setTitle(track.title).setItems(actions) { _, which ->
            when (which) {
                0 -> {
                    val list = displayedTracks.ifEmpty { libraryTracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) }
                    playTracks(list, list.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0)
                }
                1 -> { playback?.addToQueue(track, true); Toast.makeText(this, "Queued to play next", Toast.LENGTH_SHORT).show() }
                2 -> { playback?.addToQueue(track, false); Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show() }
                3 -> { playlistStore.toggleFavourite(track.id); renderLibraryItems() }
                4 -> showAddToPlaylistDialog(track)
            }
        }.show()
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this).apply { hint = "Playlist name"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("New playlist").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                if (playlistStore.createPlaylist(input.text.toString())) {
                    currentPlaylist = input.text.toString().trim()
                    currentLibraryTab = LIBRARY_PLAYLISTS
                    showLibrary()
                } else Toast.makeText(this, "Enter a new playlist name", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showAddToPlaylistDialog(track: MusicTrack) {
        val names = playlistStore.playlists()
        if (names.isEmpty()) { showCreatePlaylistDialog(); return }
        AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names.toTypedArray()) { _, which ->
            val added = playlistStore.addToPlaylist(names[which], track.id)
            Toast.makeText(this, if (added) "Added to ${names[which]}" else "Already in ${names[which]}", Toast.LENGTH_SHORT).show()
        }.setNeutralButton("New playlist") { _, _ -> showCreatePlaylistDialog() }.show()
    }

    private fun showNowPlaying() {
        contentFrame.removeAllViews()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(Color.rgb(12, 18, 25))
        }
        val entry = playback?.currentEntry()
        val track = entry?.let { active -> libraryTracks.firstOrNull { it.uri == active.uri } } ?: currentNowPlaying
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(29, 47, 59))
            track?.albumArtUri?.let { runCatching { setImageURI(it) } }
        }
        page.addView(art, LinearLayout.LayoutParams(dp(248), dp(248)).apply { bottomMargin = dp(22) })
        val title = TextView(this).apply {
            text = entry?.title ?: track?.title ?: "Nothing playing"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        page.addView(title, matchWrap())
        page.addView(TextView(this).apply {
            text = listOfNotNull(entry?.artist ?: track?.artist, entry?.album ?: track?.album).distinct().joinToString(" · ")
            textSize = 13f
            setTextColor(Color.rgb(158, 180, 192))
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(12))
        }, matchWrap())
        val position = TextView(this).apply { text = "0:00  /  ${track?.durationLabel ?: "0:00"}"; textSize = 11f; setTextColor(Color.rgb(170, 190, 200)) }
        nowPlayingSeekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) userSeeking = true
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = false
                    val duration = playback?.playbackDurationMs() ?: track?.durationMs ?: 0L
                    if (duration > 0L) playback?.seekTo(duration * (seekBar?.progress ?: 0) / 1000L)
                }
            })
        }
        page.addView(nowPlayingSeekBar, matchWrap())
        page.addView(position)
        nowPositionLabel = position

        val transport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        transport.addView(Button(this).apply {
            text = if (playback?.isShuffleEnabled() == true) "🔀 ON" else "SHUFFLE"
            styleButton(this, compact = true)
            setOnClickListener { playback?.setShuffle(playback?.isShuffleEnabled() != true); showNowPlaying() }
        })
        transport.addView(Button(this).apply { text = "|◀"; styleButton(this); setOnClickListener { playback?.skipToPrevious(); updateMiniPlayer() } })
        transport.addView(Button(this).apply { text = if (playback?.isPlaying() == true) "Ⅱ" else "▶"; styleButton(this); setOnClickListener { togglePlayback(); showNowPlaying() } })
        transport.addView(Button(this).apply { text = "▶|"; styleButton(this); setOnClickListener { playback?.skipToNext(); updateMiniPlayer() } })
        transport.addView(Button(this).apply {
            text = when (playback?.getRepeatMode() ?: 0) { PlaybackService.REPEAT_ALL -> "REPEAT ALL"; PlaybackService.REPEAT_ONE -> "REPEAT ONE"; else -> "REPEAT OFF" }
            styleButton(this, compact = true)
            setOnClickListener { playback?.setRepeatMode(((playback?.getRepeatMode() ?: 0) + 1) % 3); showNowPlaying() }
        })
        page.addView(transport, matchWrap().apply { topMargin = dp(18) })
        val activeTrack = track
        if (activeTrack != null) page.addView(Button(this).apply {
            text = if (playlistStore.isFavourite(activeTrack.id)) "♥  REMOVE FROM FAVOURITES" else "♡  ADD TO FAVOURITES"
            styleButton(this, compact = true)
            setOnClickListener {
                val saved = playlistStore.toggleFavourite(activeTrack.id)
                text = if (saved) "♥  REMOVE FROM FAVOURITES" else "♡  ADD TO FAVOURITES"
            }
        }, matchWrap().apply { topMargin = dp(10) })
        val queueButton = Button(this).apply { text = "PLAYBACK QUEUE (${playback?.queueSize() ?: 0})"; styleButton(this); setOnClickListener { showQueueDialog() } }
        page.addView(queueButton, matchWrap().apply { topMargin = dp(16) })
        page.addView(Button(this).apply { text = "OPEN DSP / SOUND SETTINGS"; styleButton(this); setOnClickListener { showDsp() } }, matchWrap().apply { topMargin = dp(8) })
        contentFrame.addView(ScrollView(this).apply { addView(page) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateMiniPlayer()
    }

    private lateinit var nowPlayingSeekBar: SeekBar
    private lateinit var nowPositionLabel: TextView

    private fun showQueueDialog() {
        val entries = playback?.queueSnapshot().orEmpty()
        if (entries.isEmpty()) {
            Toast.makeText(this, "The queue is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val current = playback?.currentQueueIndex() ?: -1
        val labels = entries.mapIndexed { index, entry ->
            (if (index == current) "▶  " else "    ") + entry.title + " — " + entry.artist
        }
        AlertDialog.Builder(this).setTitle("Playback queue").setItems(labels.toTypedArray()) { _, which ->
            currentNowPlaying = libraryTracks.firstOrNull { it.uri == entries[which].uri } ?: currentNowPlaying
            playback?.skipToQueueIndex(which)
            updateMiniPlayer()
        }.setNeutralButton("Clear queue") { _, _ -> playback?.clearQueue(); currentNowPlaying = null; updateMiniPlayer() }.show()
    }

    private fun togglePlayback() {
        if (playback?.isPlaying() == true) playback?.pausePlayback()
        else if (playback?.currentEntry() != null) playback?.resumePlayback()
        else currentNowPlaying?.let { playTracks(listOf(it), 0) }
        updateMiniPlayer()
    }

    private fun updateMiniPlayer() {
        if (!::miniTitle.isInitialized) return
        val entry = playback?.currentEntry()
        val track = entry?.let { e -> libraryTracks.firstOrNull { it.uri == e.uri } } ?: currentNowPlaying
        miniTitle.text = entry?.title ?: track?.title ?: "Nothing playing"
        miniSubtitle.text = entry?.artist ?: track?.artist ?: "Choose music from your library"
        if (::miniPlayButton.isInitialized) miniPlayButton.text = if (playback?.isPlaying() == true) "Ⅱ" else "▶"
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            val service = playback
            if (service != null) {
                val duration = service.playbackDurationMs()
                val position = service.playbackPositionMs()
                if (::nowPlayingSeekBar.isInitialized && !userSeeking && duration > 0L) {
                    nowPlayingSeekBar.progress = ((position.coerceAtMost(duration) * 1000L) / duration).toInt()
                    if (::nowPositionLabel.isInitialized) nowPositionLabel.text = "${formatTime(position)}  /  ${formatTime(duration)}"
                }
                updateMiniPlayer()
            }
            uiHandler.postDelayed(this, 500L)
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
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
        if (::trackTitle.isInitialized) trackTitle.text = displayName(uri)
        if (::status.isInitialized) status.text = "Preparing decoder…"
        if (playback != null) playback?.load(uri) else pendingUri = uri
        miniTitle.text = displayName(uri)
        miniSubtitle.text = "Preparing audio file"
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

    private fun replayModeText() = when (replayMode) {
        0 -> "Mode: None"
        1 -> "Mode: Apply"
        else -> "Mode: Apply Peak (limit to peak headroom)"
    }

    private fun replaySourceText() = if (replaySource == 0) "Source: Album (falls back to track)" else "Source: Track"

    // Slider progress 0..300 maps to -15.0..+15.0 dB in 0.1 dB steps.
    private fun replayDbText(progress: Int) = "%+.1f dB".format((progress - 150) / 10.0)

    private fun applyReplayGain() {
        if (!::replayDefaultSlider.isInitialized || !::replayPreampSlider.isInitialized) return
        playback?.setReplayGain(replayMode, replaySource,
            (replayDefaultSlider.progress - 150) * 10)
        playback?.setMasterGainDb((replayPreampSlider.progress - 150) / 10.0)
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putInt(KEY_MASTER_PREAMP, replayPreampSlider.progress).apply()
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
        applyReplayGain()
        eqControls.forEach { c ->
            val db = (c.gain.progress - 150) / 10.0
            playback?.setEqBand(c.index, c.enabled.isChecked, c.type, c.frequency, c.q, db, c.channel)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        uiHandler.removeCallbacks(progressTicker)
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
        private const val REQUEST_LIBRARY_PERMISSION = 8202
        private const val PREFERENCES = "auralis.player"
        private const val KEY_MASTER_PREAMP = "master_preamp_progress"
        private const val LIBRARY_SONGS = "Songs"
        private const val LIBRARY_ALBUMS = "Albums"
        private const val LIBRARY_ARTISTS = "Artists"
        private const val LIBRARY_FAVOURITES = "Favourites"
        private const val LIBRARY_PLAYLISTS = "Playlists"
        private const val LIBRARY_DSP = "DSP"
        private const val SORT_TITLE = "Title"
        private const val SORT_ARTIST = "Artist"
        private const val SORT_ALBUM = "Album"
        private const val SORT_RECENT = "Recently added"
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
