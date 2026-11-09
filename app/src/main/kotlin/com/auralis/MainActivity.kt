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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.BaseAdapter
import android.widget.AbsListView
import android.widget.ListView
import android.widget.CompoundButton
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private var playback: PlaybackService? = null
    private var bound = false
    private var pendingUri: Uri? = null
    private var pendingQueue: Pair<List<MusicTrack>, Int>? = null
    private lateinit var contentFrame: FrameLayout
    private val destinationPages = mutableMapOf<String, View>()
    private val libraryPages = mutableMapOf<String, LibraryPageState>()
    private var nowPlayingPage: View? = null
    private lateinit var miniPlayerCard: MaterialCardView
    private lateinit var miniArtwork: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSubtitle: TextView
    private lateinit var miniPlayButton: MaterialButton
    private lateinit var topHeader: LinearLayout
    private lateinit var bottomNavigation: LinearLayout
    private val navigationItems = linkedMapOf<String, LinearLayout>()
    private lateinit var dspPage: View
    private var activeDestination = DEST_HOME
    private var returnDestination = DEST_HOME
    private var soundReturnDestination = DEST_HOME
    private var libraryCountLabel: TextView? = null
    private var librarySearchField: EditText? = null
    private var isSearchPage = false
    private val libraryTracks = mutableListOf<MusicTrack>()
    @Volatile private var librarySnapshot: List<MusicTrack> = emptyList()
    @Volatile private var libraryTrackByUri: Map<Uri, MusicTrack> = emptyMap()
    @Volatile private var libraryIndexById: Map<Long, Int> = emptyMap()
    @Volatile private var librarySearchIndex: Map<Long, String> = emptyMap()
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
    private lateinit var reverbToggle: CompoundButton
    private val reverbSliders = arrayOfNulls<Slider>(7)
    private val reverbValueLabels = arrayOfNulls<TextView>(7)
    private val reverbInitial = intArrayOf(55, 50, 45, 15, 100, 50, 50)
    private lateinit var stereoToggle: CompoundButton
    private lateinit var stereoValue: TextView
    private lateinit var stereoSlider: Slider
    private lateinit var balanceToggle: CompoundButton
    private lateinit var balanceValue: TextView
    private lateinit var balanceSlider: Slider
    private lateinit var monoToggle: CompoundButton
    private lateinit var limiterToggle: CompoundButton
    private lateinit var limiterPathButton: Button
    private var limiterPath = 0
    private var replayMode = 0      // 0 None, 1 Apply, 2 Apply Peak
    private var replaySource = 0    // 0 Album, 1 Track
    private lateinit var replayModeButton: Button
    private lateinit var replaySourceButton: Button
    private lateinit var replayPreampSlider: Slider
    private lateinit var replayDefaultSlider: Slider
    private lateinit var eqContainer: LinearLayout
    private lateinit var eqCount: TextView
    private lateinit var nowPlayingSeekBar: Slider
    private lateinit var nowPositionLabel: TextView
    private lateinit var nowDurationLabel: TextView
    private val eqControls = mutableListOf<EqUiControl>()
    private val artworkExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val libraryQueryExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Auralis-library-query").apply { isDaemon = true }
    }
    private var pendingLibraryQuery: Future<*>? = null
    private var libraryQueryGeneration = 0
    private var homeAlbumTracks: List<MusicTrack> = emptyList()
    private var homeRecentTracks: List<MusicTrack> = emptyList()
    private val artworkCache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private var activityStarted = false
    private var pendingSearchRender: Runnable? = null
    private var libraryPageTracks: List<MusicTrack> = emptyList()
    private var libraryRenderedCount = 0
    private var libraryCollectionItems: List<LibraryCollectionEntry> = emptyList()
    private var libraryCollectionRenderedCount = 0


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
            if (activityStarted) uiHandler.post(progressTicker)
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

    private val cBackground = Color.rgb(8, 13, 18)
    private val cSurface = Color.rgb(18, 27, 36)
    private val cSurfaceRaised = Color.rgb(26, 38, 48)
    private val cSurfaceSoft = Color.rgb(31, 45, 55)
    private val cAccent = Color.rgb(168, 231, 213)
    private val cAccentDeep = Color.rgb(24, 74, 66)
    private val cText = Color.rgb(241, 245, 246)
    private val cMuted = Color.rgb(157, 174, 183)
    private val cBorder = Color.rgb(47, 62, 73)
    private val cLavender = Color.rgb(185, 176, 255)

    private fun uiText(value: String, size: Float = 14f, color: Int = cText, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }

    private fun roundedCard(backgroundColor: Int = cSurface, radius: Int = 22): MaterialCardView =
        MaterialCardView(this).apply {
            setRadius(dp(radius).toFloat())
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = cBorder
            setCardBackgroundColor(backgroundColor)
            useCompatPadding = false
        }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun materialAction(label: String, primary: Boolean = false, compact: Boolean = false): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = if (compact) 11f else 13f
            cornerRadius = dp(if (compact) 14 else 17)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(if (compact) 40 else 50)
            setPadding(dp(if (compact) 12 else 17), dp(5), dp(if (compact) 12 else 17), dp(5))
            backgroundTintList = ColorStateList.valueOf(if (primary) cAccent else cSurfaceSoft)
            setTextColor(if (primary) Color.rgb(6, 29, 23) else cText)
            strokeWidth = if (primary) 0 else dp(1)
            strokeColor = ColorStateList.valueOf(cBorder)
            elevation = 0f
        }

    private fun iconControl(icon: Int, description: String, size: Int = 44, background: Int = cSurfaceSoft,
                            tint: Int = cText, onClick: () -> Unit): MaterialCardView {
        val card = roundedCard(background, 16).apply {
            strokeWidth = 0
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { onClick() }
        }
        val image = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = description
        }
        card.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return card
    }

    private fun artwork(track: MusicTrack?, size: Int, radius: Int = 17): MaterialCardView {
        val card = MaterialCardView(this).apply {
            this.radius = dp(radius).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(Color.rgb(34, 58, 63))
            clipToOutline = true
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(38, 91, 83), Color.rgb(48, 58, 89), Color.rgb(30, 40, 54)))
        }
        bindArtwork(image, track?.albumArtUri, size)
        card.addView(image, FrameLayout.LayoutParams(dp(size), dp(size)))
        card.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return card
    }

    private fun setPlaceholderArtwork(image: ImageView, targetSizeDp: Int) {
        image.setImageResource(R.drawable.ic_music_note)
        image.imageTintList = ColorStateList.valueOf(Color.rgb(170, 229, 213))
        val inset = dp(if (targetSizeDp <= 64) 11 else 22)
        image.setPadding(inset, inset, inset, inset)
        image.scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    private fun artworkThumbnail(track: MusicTrack?, size: Int, radius: Int = 12): ImageView =
        ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(38, 91, 83), Color.rgb(48, 58, 89), Color.rgb(30, 40, 54))).apply {
                cornerRadius = dp(radius).toFloat()
            }
            clipToOutline = true
            bindArtwork(this, track?.albumArtUri, size)
        }

    /**
     * Album art is decoded away from the main thread and cached. The URI tag prevents
     * a slow image request from replacing artwork after a view has been rebound.
     */
    private fun bindArtwork(image: ImageView, uri: Uri?, targetSizeDp: Int) {
        if (uri == null) {
            if (image.tag != NO_ARTWORK_TAG) {
                image.tag = NO_ARTWORK_TAG
                setPlaceholderArtwork(image, targetSizeDp)
            }
            return
        }

        val key = uri.toString()
        if (image.tag == key) return
        image.tag = key

        val cached = artworkCache.get(key)
        if (cached != null) {
            image.imageTintList = null
            image.setPadding(0, 0, 0, 0)
            image.setImageBitmap(cached)
            return
        }

        setPlaceholderArtwork(image, targetSizeDp)
        val targetPx = dp(targetSizeDp).coerceAtLeast(64)
        artworkExecutor.execute {
            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    runCatching { contentResolver.loadThumbnail(uri, Size(targetPx, targetPx), null) }
                        .getOrNull() ?: decodeLegacyArtwork(uri, targetPx)
                } else {
                    decodeLegacyArtwork(uri, targetPx)
                }
            }.getOrNull()

            if (bitmap != null) artworkCache.put(key, bitmap)
            if (!isFinishing && !isDestroyed) {
                runOnUiThread {
                    if (image.tag == key) {
                        if (bitmap != null) {
                            image.imageTintList = null
                            image.setPadding(0, 0, 0, 0)
                            image.setImageBitmap(bitmap)
                        } else {
                            setPlaceholderArtwork(image, targetSizeDp)
                        }
                    }
                }
            }
        }
    }

    private fun decodeLegacyArtwork(uri: Uri, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (largestSide / sample > targetPx * 2 && sample < 32) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun sectionHeading(parent: LinearLayout, title: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(27), 0, dp(14))
        }
        row.addView(uiText(title, 20f, cText, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null && onAction != null) {
            row.addView(MaterialButton(this).apply {
                text = action
                isAllCaps = false
                textSize = 12f
                cornerRadius = dp(14)
                insetTop = 0; insetBottom = 0
                minHeight = dp(38)
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                setTextColor(cAccent)
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { onAction() }
            })
        }
        parent.addView(row, matchWrap())
    }

    private fun createNavItem(key: String, title: String, icon: Int, onClick: () -> Unit): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(6), dp(8), dp(6), dp(6))
            setOnClickListener { onClick() }
        }
        val glyph = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(cMuted)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        item.addView(glyph, LinearLayout.LayoutParams(dp(25), dp(25)))
        val label = uiText(title, 11f, cMuted, false).apply { setPadding(0, dp(4), 0, 0) }
        item.addView(label)
        item.tag = key
        navigationItems[key] = item
        return item
    }

    private fun updateNavigationState() {
        navigationItems.forEach { (key, item) ->
            val selected = activeDestination == key || (activeDestination == DEST_NOW_PLAYING && key == returnDestination)
            item.background = if (selected) roundedBackground(Color.rgb(29, 57, 56), 16) else null
            (item.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(if (selected) cAccent else cMuted)
            (item.getChildAt(1) as? TextView)?.setTextColor(if (selected) cAccent else cMuted)
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBackground)
            setPadding(0, 0, 0, dp(7))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setBackgroundColor(cBackground)
        }
        val mark = roundedCard(cAccentDeep, 15).apply { strokeWidth = 0 }
        mark.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_auralis_mark)
            imageTintList = ColorStateList.valueOf(cAccent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(mark, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(11) })

        val wordmark = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wordmark.addView(uiText("AURALIS", 18f, cText, true).apply { letterSpacing = 0.15f })
        wordmark.addView(uiText("MUSIC, IN ITS ELEMENT", 9.5f, cMuted, true).apply { letterSpacing = 0.15f; setPadding(0, dp(3), 0, 0) })
        header.addView(wordmark, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconControl(R.drawable.ic_search, "Search music", 42, cSurface) { showSearch() },
            LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) })
        header.addView(iconControl(R.drawable.ic_add, "Open an audio file", 42, cSurface) { chooseAudio() },
            LinearLayout.LayoutParams(dp(42), dp(42)))
        topHeader = header
        root.addView(header)

        contentFrame = FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        miniPlayerCard = roundedCard(cSurfaceRaised, 20).apply {
            visibility = View.GONE
            setOnClickListener { showNowPlaying() }
        }
        val miniContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        miniArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(cAccentDeep, 12)
        }
        miniContent.addView(miniArtwork, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(14) })
        val miniText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        miniTitle = uiText("Nothing playing", 14f, cText, true).apply { maxLines = 1 }
        miniSubtitle = uiText("Choose a track from your library", 12f, cMuted).apply { maxLines = 1; setPadding(0, dp(4), 0, 0) }
        miniText.addView(miniTitle)
        miniText.addView(miniSubtitle)
        miniContent.addView(miniText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        miniPlayButton = MaterialButton(this).apply {
            text = ""
            setIconResource(R.drawable.ic_play)
            iconTint = ColorStateList.valueOf(Color.rgb(6, 29, 23))
            iconSize = dp(22)
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            cornerRadius = dp(17)
            insetTop = 0; insetBottom = 0
            minWidth = dp(44); minHeight = dp(44)
            backgroundTintList = ColorStateList.valueOf(cAccent)
            setOnClickListener { togglePlayback() }
        }
        miniContent.addView(miniPlayButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
        miniPlayerCard.addView(miniContent)
        root.addView(miniPlayerCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(18); marginEnd = dp(18); topMargin = dp(9); bottomMargin = dp(9)
        })

        bottomNavigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = roundedBackground(cSurface, 22)
        }
        bottomNavigation.addView(createNavItem(DEST_HOME, "Home", R.drawable.ic_home) { showHome() },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        bottomNavigation.addView(createNavItem(DEST_SEARCH, "Search", R.drawable.ic_search) { showSearch() },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        bottomNavigation.addView(createNavItem(DEST_LIBRARY, "Library", R.drawable.ic_library) { showLibrary() },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        bottomNavigation.addView(createNavItem(DEST_SOUND, "Sound", R.drawable.ic_tune) { showDsp() },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        root.addView(bottomNavigation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(13); marginEnd = dp(13); topMargin = dp(5)
        })

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom + dp(7))
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
        window.statusBarColor = cBackground
        window.navigationBarColor = cBackground
        dspPage = buildDspPage()
        showHome()
    }

    private fun setAppChromeVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (::topHeader.isInitialized) topHeader.visibility = target
        if (::bottomNavigation.isInitialized) bottomNavigation.visibility = target
    }

    private fun displayContentPage(page: View) {
        if (page.parent !== contentFrame) {
            (page.parent as? ViewGroup)?.removeView(page)
            contentFrame.addView(page, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        for (index in 0 until contentFrame.childCount) {
            val child = contentFrame.getChildAt(index)
            child.visibility = if (child === page) View.VISIBLE else View.GONE
        }
    }

    private fun restoreLibraryPage(key: String, searchMode: Boolean): LibraryPageState {
        val existing = libraryPages[key]
        if (existing != null) return existing

        isSearchPage = searchMode
        val page = buildLibraryView(searchMode)
        return LibraryPageState(
            page,
            librarySearchField,
            libraryCountLabel ?: error("Library count label was not built"),
            libraryListContainer ?: error("Library collection container was not built"),
            libraryCollectionScroll ?: error("Library collection scroll was not built"),
            libraryListView ?: error("Library track list was not built")
        ).also { libraryPages[key] = it }
    }

    private fun activateLibraryPage(state: LibraryPageState, searchMode: Boolean) {
        isSearchPage = searchMode
        librarySearchField = state.searchField
        libraryCountLabel = state.countLabel
        libraryListContainer = state.collectionContainer
        libraryCollectionScroll = state.collectionScroll
        libraryListView = state.trackList
        libraryTrackAdapter = state.trackList.adapter as? TrackListAdapter
    }

    private fun showHome(refreshContent: Boolean = false) {
        setAppChromeVisible(true)
        activeDestination = DEST_HOME
        updateNavigationState()
        updateMiniPlayer()
        if (refreshContent) {
            destinationPages.remove(DEST_HOME)?.let(contentFrame::removeView)
        }
        val page = destinationPages.getOrPut(DEST_HOME) { buildHomeView() }
        displayContentPage(page)
    }

    private fun buildHomeView(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBackground) }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(38))
        }
        content.addView(uiText("YOUR MUSIC", 11f, cAccent, true).apply { letterSpacing = 0.18f })
        content.addView(uiText("Find your next\nfavourite.", 32f, cText, true).apply {
            setPadding(0, dp(8), 0, dp(7)); setLineSpacing(dp(1).toFloat(), 1.0f)
        })
        content.addView(uiText("A personal space for the music you love.", 14f, cMuted).apply { setPadding(0, 0, 0, dp(17)) })

        val current = playback?.currentEntry()
        val activeTrack = current?.let { entry -> libraryTrackByUri[entry.uri] } ?: currentNowPlaying
        val featured = activeTrack ?: libraryTracks.firstOrNull()
        val featuredCard = roundedCard(Color.rgb(20, 43, 46), 24)
        val featuredRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)) }
        featuredRow.addView(artwork(featured, 120, 20), LinearLayout.LayoutParams(dp(120), dp(120)).apply { marginEnd = dp(18) })
        val featureText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        featureText.addView(uiText(if (activeTrack != null) "CONTINUE LISTENING" else "MADE FOR YOUR MUSIC", 10f, cAccent, true).apply { letterSpacing = 0.1f })
        featureText.addView(uiText(featured?.title ?: "Your library is waiting", 19f, cText, true).apply { maxLines = 2; setPadding(0, dp(7), 0, dp(3)) })
        featureText.addView(uiText(featured?.artist ?: "Add music from your device", 12.5f, cMuted).apply { maxLines = 1 })
        featureText.addView(materialAction(if (activeTrack != null) "Continue listening" else if (libraryTracks.isEmpty()) "Scan music" else "Start listening", true, true).apply {
            setOnClickListener {
                when {
                    activeTrack != null && playback?.currentEntry() != null -> showNowPlaying()
                    featured != null -> {
                        val index = libraryIndexById[featured.id]
                        if (index != null) playTracks(librarySnapshot, index) else playTracks(listOf(featured), 0)
                    }
                    else -> requestLibraryPermissionOrLoad()
                }
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(14) }
        })
        featuredRow.addView(featureText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        featuredCard.addView(featuredRow)
        content.addView(featuredCard, matchWrap())

        val albumGroups = homeAlbumTracks
        if (albumGroups.isNotEmpty()) {
            sectionHeading(content, "Albums", "See all", { currentLibraryTab = LIBRARY_ALBUMS; currentAlbum = null; showLibrary(refreshContent = true) })
            val horizontal = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            albumGroups.take(7).forEach { track ->
                val albumCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { currentLibraryTab = LIBRARY_ALBUMS; currentAlbum = track.album; showLibrary(refreshContent = true) }
                }
                albumCard.addView(artwork(track, 152, 19), LinearLayout.LayoutParams(dp(152), dp(152)))
                albumCard.addView(uiText(track.album, 14f, cText, true).apply { maxLines = 1; setPadding(0, dp(8), 0, dp(3)) })
                albumCard.addView(uiText(track.artist, 12f, cMuted).apply { maxLines = 1 })
                horizontal.addView(albumCard, LinearLayout.LayoutParams(dp(152), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
            }
            content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(horizontal) }, matchWrap())
        }

        sectionHeading(content, "Recently added", "View library", { showLibrary() })
        if (libraryTracks.isEmpty()) {
            val empty = roundedCard(cSurface, 22)
            val emptyContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(28), dp(24), dp(28)) }
            val emptyIcon = ImageView(this).apply { setImageResource(R.drawable.ic_headphones); imageTintList = ColorStateList.valueOf(cAccent); scaleType = ImageView.ScaleType.CENTER_INSIDE }
            emptyContent.addView(emptyIcon, LinearLayout.LayoutParams(dp(46), dp(46)))
            emptyContent.addView(uiText("Nothing in your library yet", 19f, cText, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(6)) })
            emptyContent.addView(uiText("Scan your device for music or open an audio file to start listening.", 13.5f, cMuted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(13)) })
            emptyContent.addView(materialAction("Scan music library", true).apply { setOnClickListener { requestLibraryPermissionOrLoad() } }, matchWrap())
            empty.addView(emptyContent)
            content.addView(empty, matchWrap())
        } else {
            val latest = homeRecentTracks.take(4)
            latest.forEachIndexed { index, track -> addTrackRow(content, track, latest, index) }
        }

        sectionHeading(content, "Your listening space", "Sound settings", { showDsp() })
        val soundCard = roundedCard(cSurface, 20)
        val soundRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }
        soundRow.addView(iconControl(R.drawable.ic_equalizer, "Open sound settings", 48, cAccentDeep, cAccent) { showDsp() },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        val soundText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        soundText.addView(uiText("Shape your sound", 16f, cText, true))
        soundText.addView(uiText("StereoWide, EQ, preamp and more", 12.5f, cMuted).apply { setPadding(0, dp(4), 0, 0) })
        soundRow.addView(soundText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        soundRow.addView(uiText("›", 24f, cAccent, true))
        soundRow.setOnClickListener { showDsp() }
        soundCard.addView(soundRow)
        content.addView(soundCard)

        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return page
    }

    private fun showSearch(refreshContent: Boolean = false) {
        setAppChromeVisible(true)
        activeDestination = DEST_SEARCH
        updateNavigationState()
        updateMiniPlayer()
        if (refreshContent) {
            libraryPages.remove(DEST_SEARCH)?.let { contentFrame.removeView(it.page) }
        }
        val state = restoreLibraryPage(DEST_SEARCH, searchMode = true)
        activateLibraryPage(state, searchMode = true)
        displayContentPage(state.page)
        renderLibraryItems(state.countLabel)
        librarySearchField?.requestFocus()
    }

    private fun dspCard(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        initiallyExpanded: Boolean = false,
        contentBuilder: (LinearLayout) -> Unit
    ): LinearLayout {
        val card = roundedCard(cSurface, 24).apply {
            strokeColor = cBorder
            strokeWidth = dp(1)
            cardElevation = 0f
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(18), dp(17), dp(16), dp(17))
        }

        val iconTile = FrameLayout(this).apply {
            background = roundedBackground(cAccentDeep, 15)
        }
        val icon = ImageView(this).apply {
            setImageResource(when (title) {
                "Output level" -> R.drawable.ic_tune
                "StereoWide", "Stereo placement" -> R.drawable.ic_equalizer
                "Equalizer" -> R.drawable.ic_library
                "ReplayGain" -> R.drawable.ic_album
                "Reverb" -> R.drawable.ic_headphones
                "Dynamics" -> R.drawable.ic_music_note
                else -> R.drawable.ic_tune
            })
            imageTintList = ColorStateList.valueOf(cAccent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
        }
        iconTile.addView(icon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        header.addView(iconTile, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            marginEnd = dp(14)
        })

        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(uiText(title, 16f, cText, true))
        copy.addView(uiText(subtitle, 12f, cMuted).apply {
            maxLines = 2
            setPadding(0, dp(5), 0, 0)
        })
        header.addView(copy, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        var expanded = initiallyExpanded
        val affordance = uiText(if (expanded) "−" else "+", 22f, cAccent, true).apply {
            gravity = Gravity.CENTER
        }
        header.addView(affordance, LinearLayout.LayoutParams(dp(32), dp(36)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            setPadding(dp(18), 0, dp(18), dp(19))
        }
        addDspDivider(body)
        contentBuilder(body)

        column.addView(header)
        column.addView(body)
        card.addView(column)

        header.setOnClickListener {
            expanded = !expanded
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            affordance.text = if (expanded) "−" else "+"
        }

        parent.addView(card, matchWrap().apply { bottomMargin = dp(15) })
        return body
    }

        private fun dspSwitch(
        parent: LinearLayout,
        title: String,
        detail: String,
        onChange: (Boolean) -> Unit
    ): MaterialSwitch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(0, dp(9), 0, dp(9))
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(uiText(title, 15f, cText, true))
        copy.addView(uiText(detail, 12f, cMuted).apply {
            setPadding(0, dp(5), 0, 0)
            maxLines = 2
        })
        row.addView(copy, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        val toggle = MaterialSwitch(this).apply {
            showText = false
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(toggle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
        ).apply { marginStart = dp(12) })
        parent.addView(row, matchWrap())
        return toggle
    }

        private fun dspSlider(
        parent: LinearLayout,
        title: String,
        initial: Float,
        max: Float = 100f,
        step: Float = 1f,
        format: (Float) -> String,
        onChange: (Float) -> Unit
    ): Pair<Slider, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(uiText(title, 12f, cMuted, true).apply {
            letterSpacing = 0.05f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = uiText(format(initial), 13f, cAccent, true)
        row.addView(value)
        parent.addView(row, matchWrap().apply { topMargin = dp(13) })

        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = max
            stepSize = step
            this.value = initial.coerceIn(valueFrom, valueTo)
            addOnChangeListener { _, next, _ ->
                value.text = format(next)
                onChange(next)
            }
        }
        parent.addView(slider, matchWrap().apply {
            topMargin = dp(5)
            bottomMargin = dp(11)
        })
        return slider to value
    }

        private fun buildDspPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBackground)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(23), dp(24), dp(38))
        }

        content.addView(uiText("SOUND STUDIO", 11f, cAccent, true).apply {
            letterSpacing = 0.18f
        })
        content.addView(uiText("Make it sound right.", 29f, cText, true).apply {
            setPadding(0, dp(7), 0, dp(7))
        })
        content.addView(uiText(
            "The essentials are here. Open a section when you want more control.",
            13f, cMuted
        ).apply {
            setPadding(0, 0, 0, dp(23))
            maxLines = 2
        })
        content.addView(uiText("EVERYDAY CONTROLS", 10f, cAccent, true).apply {
            letterSpacing = 0.13f
            setPadding(dp(2), 0, 0, dp(11))
        })

        dspCard(content, "Output level", "Set the overall level of your music.", initiallyExpanded = true) { body ->
            val saved = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getInt(KEY_MASTER_PREAMP, 150).coerceIn(0, 300)
            val (slider, _) = dspSlider(
                body, "MASTER PREAMP", saved.toFloat(), 300f, 1f,
                { replayDbText(it.toInt()) }
            ) { _ -> applyReplayGain() }
            replayPreampSlider = slider

            val reset = MaterialButton(this).apply {
                text = "Reset to 0 dB"
                isAllCaps = false
                textSize = 12f
                cornerRadius = dp(14)
                insetTop = 0
                insetBottom = 0
                minHeight = dp(42)
                backgroundTintList = ColorStateList.valueOf(cSurfaceSoft)
                setTextColor(cText)
                setOnClickListener {
                    replayPreampSlider.value = 150f
                    applyReplayGain()
                }
            }
            body.addView(reset, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)
            ).apply { topMargin = dp(5) })
        }

        dspCard(
            content,
            "StereoWide",
            "Give your music a wider, more spacious stereo image.",
            initiallyExpanded = true
        ) { body ->
            stereoToggle = dspSwitch(
                body,
                "Enable StereoWide",
                "Keep it off for the original stereo presentation."
            ) { checked ->
                playback?.setStereoWide(checked, stereoSlider.value.toInt())
            }

            val (width, _) = dspSlider(
                body, "WIDTH", 0f, 100f, 1f,
                { value -> "${value.toInt()}%" }
            ) { value ->
                stereoValue.text = if (value <= 0f) {
                    "Natural stereo width"
                } else {
                    "Stereo image widened"
                }
                playback?.setStereoWide(stereoToggle.isChecked, value.toInt())
            }
            stereoSlider = width
            stereoValue = uiText("Natural stereo width", 12f, cMuted).apply {
                setPadding(0, dp(1), 0, dp(4))
            }
            body.addView(stereoValue, matchWrap())
            body.addView(uiText(
                "A little width goes a long way. Adjust while music is playing.",
                12f, cMuted
            ).apply { setPadding(0, dp(10), 0, 0) }, matchWrap())
        }

        content.addView(uiText("MORE SOUND OPTIONS", 10f, cAccent, true).apply {
            letterSpacing = 0.13f
            setPadding(dp(2), dp(12), 0, dp(11))
        })

        dspCard(content, "Stereo placement", "Balance left and right, or listen in mono.") { body ->
            balanceToggle = dspSwitch(
                body, "Balance", "Move the sound towards the left or right."
            ) { checked ->
                playback?.setBalance(checked, (balanceSlider.value - 100f) / 100.0)
            }
            val (balance, _) = dspSlider(
                body, "LEFT  ·  CENTRE  ·  RIGHT", 100f, 200f, 1f,
                { value ->
                    when {
                        value < 100f -> "Left ${(100f - value).toInt()}%"
                        value > 100f -> "Right ${(value - 100f).toInt()}%"
                        else -> "Centre"
                    }
                }
            ) { value ->
                val normalized = (value - 100f) / 100.0
                val db = 48.0 * kotlin.math.abs(normalized)
                balanceValue.text = when {
                    normalized >= 1.0 -> "Right channel only"
                    normalized <= -1.0 -> "Left channel only"
                    normalized > 0.0 -> "Right bias · left −${"%.1f".format(db)} dB"
                    normalized < 0.0 -> "Left bias · right −${"%.1f".format(db)} dB"
                    else -> "Centred · both channels equal"
                }
                playback?.setBalance(balanceToggle.isChecked, normalized)
            }
            balanceSlider = balance
            balanceValue = uiText("Centred · both channels equal", 12f, cMuted)
            body.addView(balanceValue, matchWrap().apply { topMargin = dp(2) })
            addDspDivider(body)
            monoToggle = dspSwitch(
                body, "Mono audio", "Combine left and right channels into one."
            ) { checked ->
                playback?.setMono(checked)
            }
        }

        dspCard(content, "Equalizer", "Shape the tone with bass and treble controls.") { body ->
            val eqHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            eqCount = uiText("2 bands available", 12f, cMuted)
            eqHeader.addView(eqCount, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            eqHeader.addView(materialAction("Add band", true, true).apply {
                setOnClickListener { showAddEqBandDialog() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            body.addView(eqHeader, matchWrap().apply { bottomMargin = dp(12) })
            eqContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(eqContainer, matchWrap())
            addEqBand(eqContainer, "Bass shelf · 90 Hz · Q 0.8", 0, 4, 90, 0.8)
            addEqBand(eqContainer, "Treble shelf · 10,000 Hz · Q 0.6", 1, 5, 10000, 0.6)
            updateEqCount()
        }

        dspCard(content, "ReplayGain", "Keep the volume more consistent between tracks.") { body ->
            replayModeButton = materialAction(replayModeText(), false, true).apply {
                textSize = 13f
                setOnClickListener {
                    replayMode = (replayMode + 1) % 3
                    text = replayModeText()
                    applyReplayGain()
                }
            }
            body.addView(uiText("GAIN MODE", 10f, cMuted, true), matchWrap())
            body.addView(replayModeButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(7); bottomMargin = dp(15) })

            replaySourceButton = materialAction(replaySourceText(), false, true).apply {
                textSize = 13f
                setOnClickListener {
                    replaySource = 1 - replaySource
                    text = replaySourceText()
                    applyReplayGain()
                }
            }
            body.addView(uiText("PREFERRED TAG SOURCE", 10f, cMuted, true), matchWrap())
            body.addView(replaySourceButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(7); bottomMargin = dp(13) })

            val (default, _) = dspSlider(
                body, "DEFAULT GAIN WITHOUT TAGS", 150f, 300f, 1f,
                { replayDbText(it.toInt()) }
            ) { _ -> applyReplayGain() }
            replayDefaultSlider = default
        }

        dspCard(content, "Reverb", "Add a sense of space and room to your music.") { body ->
            reverbToggle = dspSwitch(
                body, "Enable reverb", "Apply the room and ambience effect."
            ) { enabled ->
                playback?.setReverbEnabled(enabled)
            }
            addDspSubheading(body, "BASIC ROOM CONTROLS")

            fun addReverbControl(index: Int, title: String) {
                val (slider, valueLabel) = dspSlider(
                    body, title, reverbInitial[index].toFloat(), 100f, 1f,
                    { value ->
                        when (index) {
                            1 -> "${value.toInt()}% · ${100 + 185 * value.toInt()} Hz"
                            3 -> "${value.toInt()}% · ${value.toInt() * 2} ms"
                            6 -> "${value.toInt()}% · ${18600 - 185 * value.toInt()} Hz"
                            else -> "${value.toInt()}%"
                        }
                    }
                ) { value ->
                    playback?.setReverbParameter(index, value / 100.0)
                }
                reverbSliders[index] = slider
                reverbValueLabels[index] = valueLabel
            }

            addReverbControl(0, "Room size")
            addReverbControl(1, "Tone")
            addReverbControl(4, "Wet mix")
            dspDisclosure(body, "Advanced room controls", "Fade, pre-delay and damping") { advanced ->
                addReverbControl(2, "Fade")
                addReverbControl(3, "Pre-delay")
                addReverbControl(5, "Pre-delay mix")
                addReverbControl(6, "Damping")
            }
        }

        dspCard(content, "Dynamics", "Keep peaks controlled and playback clean.") { body ->
            limiterToggle = dspSwitch(
                body, "Peak limiter", "Help prevent sudden peaks from clipping."
            ) { enabled ->
                playback?.setLimiter(enabled, limiterPath)
            }
            addDspDivider(body)
            limiterPathButton = materialAction("Path: Hard ceiling (1.0)", false, true).apply {
                textSize = 13f
                setOnClickListener {
                    limiterPath = if (limiterPath == 0) 1 else 0
                    text = if (limiterPath == 0) "Path: Hard ceiling (1.0)"
                    else "Path: Pre-gain protection"
                    playback?.setLimiter(limiterToggle.isChecked, limiterPath)
                }
            }
            body.addView(limiterPathButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(8) })
        }

        content.addView(uiText(
            "Your adjustments are applied live while music is playing.",
            12f, cMuted
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(14), dp(10), dp(6))
        })

        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return page
    }

        private fun addDspDivider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(cBorder) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            })
    }

    private fun addDspSubheading(parent: LinearLayout, title: String) {
        parent.addView(uiText(title, 10f, cAccent, true).apply {
            letterSpacing = 0.12f
            setPadding(dp(1), dp(12), 0, dp(2))
        }, matchWrap())
    }

    private fun dspDisclosure(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        contentBuilder: (LinearLayout) -> Unit
    ) {
        val section = roundedCard(cSurfaceSoft, 18).apply {
            strokeColor = cBorder
            strokeWidth = dp(1)
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(15), dp(13), dp(15), dp(13))
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(uiText(title, 14f, cText, true))
        copy.addView(uiText(subtitle, 11.5f, cMuted).apply {
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(copy, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        val affordance = uiText("+", 22f, cAccent, true).apply {
            gravity = Gravity.CENTER
        }
        header.addView(affordance, LinearLayout.LayoutParams(dp(32), dp(32)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(2), dp(16), dp(16))
        }
        contentBuilder(details)
        column.addView(header)
        column.addView(details)
        section.addView(column)
        header.setOnClickListener {
            val expanded = details.visibility != View.VISIBLE
            details.visibility = if (expanded) View.VISIBLE else View.GONE
            affordance.text = if (expanded) "−" else "+"
        }
        parent.addView(section, matchWrap().apply { topMargin = dp(13) })
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
            val byUri = loaded.associateBy { it.uri }
            val indexById = loaded.withIndex().associate { it.value.id to it.index }
            val searchIndex = loaded.associate { track ->
                track.id to "${track.title} ${track.artist} ${track.album}".lowercase()
            }
            val recent = loaded.sortedByDescending { it.dateAddedSeconds }
            val albums = loaded.groupBy { it.album to it.artist }.values
                .mapNotNull { group -> group.maxByOrNull { it.dateAddedSeconds } }
                .sortedByDescending { it.dateAddedSeconds }
            runOnUiThread {
                libraryTracks.clear()
                libraryTracks.addAll(loaded)
                librarySnapshot = loaded
                libraryTrackByUri = byUri
                libraryIndexById = indexById
                librarySearchIndex = searchIndex
                homeRecentTracks = recent
                homeAlbumTracks = albums
                if (::contentFrame.isInitialized) {
                    when (activeDestination) {
                        DEST_HOME -> showHome(refreshContent = true)
                        DEST_SEARCH -> showSearch(refreshContent = true)
                        DEST_LIBRARY -> showLibrary(refreshContent = true)
                        DEST_NOW_PLAYING -> showNowPlaying()
                        else -> Unit
                    }
                }
            }
        }, "Auralis-library-scan").start()
    }

    private fun showDsp() {
        setAppChromeVisible(true)
        if (activeDestination != DEST_SOUND) soundReturnDestination = activeDestination
        activeDestination = DEST_SOUND
        updateNavigationState()
        updateMiniPlayer()
        displayContentPage(dspPage)
    }

    private fun showLibrary(refreshContent: Boolean = false) {
        setAppChromeVisible(true)
        activeDestination = DEST_LIBRARY
        updateNavigationState()
        updateMiniPlayer()
        if (refreshContent) {
            libraryPages.remove(DEST_LIBRARY)?.let { contentFrame.removeView(it.page) }
        }
        val state = restoreLibraryPage(DEST_LIBRARY, searchMode = false)
        activateLibraryPage(state, searchMode = false)
        displayContentPage(state.page)
        renderLibraryItems(state.countLabel)
    }

    private fun buildLibraryView(searchMode: Boolean = false): View {
        isSearchPage = searchMode
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(16))
            setBackgroundColor(cBackground)
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val headingCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headingCopy.addView(uiText(if (searchMode) "Search" else "Your library", 28f, cText, true))
        headingCopy.addView(uiText(if (searchMode) "Find a track, album or artist." else "Your music, organised in one place.", 13f, cMuted).apply { setPadding(0, dp(5), 0, 0) })
        heading.addView(headingCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!searchMode) {
            heading.addView(iconControl(R.drawable.ic_refresh, "Rescan music library", 42, cSurface) { requestLibraryPermissionOrLoad() },
                LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        page.addView(heading, matchWrap().apply { bottomMargin = dp(22) })

        // Search is a dedicated destination. The Library remains focused on browsing.
        if (searchMode) {
            val searchWrap = roundedCard(cSurface, 20).apply { strokeColor = cBorder; strokeWidth = dp(1) }
            val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
            val searchIcon = ImageView(this).apply { setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(cMuted) }
            searchRow.addView(searchIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            val search = EditText(this).apply {
                hint = "Search your music"
                setSingleLine(true)
                textSize = 15.5f
                setTextColor(cText)
                setHintTextColor(cMuted)
                background = null
                setPadding(0, dp(12), 0, dp(12))
                setText(currentSearch)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            }
            searchRow.addView(search, LinearLayout.LayoutParams(0, dp(56), 1f))
            if (currentSearch.isNotBlank()) {
                searchRow.addView(iconControl(R.drawable.ic_back, "Clear search", 30, Color.TRANSPARENT, cMuted) {
                    search.setText("")
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
            }
            searchWrap.addView(searchRow)
            page.addView(searchWrap, matchWrap().apply { bottomMargin = dp(18) })
            librarySearchField = search
    
        } else {
            librarySearchField = null
        }

        val categories = ChipGroup(this).apply {
            isSingleSelection = true
            isSingleLine = true
            chipSpacingHorizontal = dp(10)
            chipSpacingVertical = 0
        }
        listOf(LIBRARY_SONGS, LIBRARY_ALBUMS, LIBRARY_ARTISTS, LIBRARY_FAVOURITES, LIBRARY_PLAYLISTS).forEach { tab ->
            val chip = Chip(this).apply {
                text = when (tab) { LIBRARY_FAVOURITES -> "Liked"; LIBRARY_PLAYLISTS -> "Playlists"; else -> tab }
                isCheckable = true
                isChecked = currentLibraryTab == tab
                isClickable = true
                chipCornerRadius = dp(18).toFloat()
                chipStrokeWidth = dp(1).toFloat()
                chipStrokeColor = ColorStateList.valueOf(if (isChecked) cAccent else cBorder)
                chipBackgroundColor = ColorStateList.valueOf(if (isChecked) cAccentDeep else cSurface)
                setTextColor(if (isChecked) cAccent else cMuted)
                textSize = 12f
                chipMinHeight = dp(38).toFloat()
                setEnsureMinTouchTargetSize(true)
                setOnClickListener {
                    if (currentLibraryTab != tab) {
                        currentLibraryTab = tab
                        currentAlbum = null; currentArtist = null; currentPlaylist = null
                        if (searchMode) showSearch(refreshContent = true) else showLibrary(refreshContent = true)
                    }
                }
            }
            categories.addView(chip)
        }
        page.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(categories)
        }, matchWrap().apply { bottomMargin = dp(14) })

        val detail = currentAlbum ?: currentArtist ?: currentPlaylist
        if (detail != null) {
            val back = MaterialButton(this).apply {
                text = "‹   ${currentLibraryTab.uppercase()}"
                isAllCaps = false
                textSize = 11.5f
                cornerRadius = dp(14)
                insetTop = 0; insetBottom = 0
                minHeight = dp(40)
                backgroundTintList = ColorStateList.valueOf(cSurface)
                setTextColor(cAccent)
                setOnClickListener { currentAlbum = null; currentArtist = null; currentPlaylist = null; if (searchMode) showSearch(refreshContent = true) else showLibrary(refreshContent = true) }
            }
            page.addView(back, matchWrap().apply { bottomMargin = dp(5) })
        }

        val sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val count = uiText("", 12f, cMuted)
        libraryCountLabel = count
        sortRow.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sortButton = MaterialButton(this).apply {
            text = "Sort  ·  $currentSort"
            isAllCaps = false
            textSize = 12f
            cornerRadius = dp(14)
            insetTop = 0; insetBottom = 0
            minHeight = dp(40)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(cMuted)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                val menu = android.widget.PopupMenu(this@MainActivity, this)
                arrayOf(SORT_TITLE, SORT_ARTIST, SORT_ALBUM, SORT_RECENT).forEach { menu.menu.add(it) }
                menu.setOnMenuItemClickListener { item ->
                    currentSort = item.title.toString()
                    text = "Sort  ·  $currentSort"
                    renderLibraryItems(libraryCountLabel)
                    true
                }
                menu.show()
            }
        }
        sortRow.addView(sortButton)
        page.addView(sortRow, matchWrap().apply { topMargin = dp(8); bottomMargin = dp(12) })

        libraryListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        val listScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(libraryListContainer)
        }
        libraryCollectionScroll = listScroll

        val listView = ListView(this).apply {
            divider = GradientDrawable().apply { setColor(cBorder); setSize(1, dp(1)) }
            dividerHeight = dp(1)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isFastScrollEnabled = true
            isSmoothScrollbarEnabled = true
            cacheColorHint = Color.TRANSPARENT
            setBackgroundColor(cBackground)
            setPadding(0, dp(8), 0, dp(22))
            clipToPadding = false
        }
        libraryTrackAdapter = TrackListAdapter()
        listView.adapter = libraryTrackAdapter
        listView.visibility = View.GONE
        libraryListView = listView

        val results = FrameLayout(this)
        results.addView(listScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        results.addView(listView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        page.addView(results, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearch = s?.toString().orEmpty()
                libraryQueryGeneration++
                pendingLibraryQuery?.cancel(true)
                pendingSearchRender?.let(uiHandler::removeCallbacks)
                val render = Runnable { renderLibraryItems(libraryCountLabel) }
                pendingSearchRender = render
                uiHandler.post(render)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        return page
    }

    private var libraryListContainer: LinearLayout? = null
    private var libraryCollectionScroll: ScrollView? = null
    private var libraryListView: ListView? = null
    private var libraryTrackAdapter: TrackListAdapter? = null

    private data class LibraryPageState(
        val page: View,
        val searchField: EditText?,
        val countLabel: TextView,
        val collectionContainer: LinearLayout,
        val collectionScroll: ScrollView,
        val trackList: ListView
    )

    private inner class TrackListAdapter : BaseAdapter() {
        private var tracks: List<MusicTrack> = emptyList()

        fun submit(items: List<MusicTrack>) {
            tracks = items
            notifyDataSetChanged()
        }

        override fun getCount(): Int = tracks.size
        override fun getItem(position: Int): MusicTrack = tracks[position]
        override fun getItemId(position: Int): Long = tracks[position].id
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row: LinearLayout
            val holder: TrackRowHolder
            if (convertView == null) {
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(9), dp(4), dp(9))
                }
                val art = artworkThumbnail(null, 60, 12)
                row.addView(art, LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(14) })
                val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val title = uiText("", 15f, cText, true).apply { maxLines = 1 }
                val artist = uiText("", 12f, cMuted).apply { maxLines = 1; setPadding(0, dp(4), 0, 0) }
                info.addView(title)
                info.addView(artist)
                row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val duration = uiText("", 11.5f, cMuted).apply { setPadding(dp(4), 0, dp(2), 0) }
                row.addView(duration)
                val more = ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_more)
                    imageTintList = ColorStateList.valueOf(cMuted)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    contentDescription = "More track actions"
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                row.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
                holder = TrackRowHolder(art, title, artist, duration, more)
                row.tag = holder
            } else {
                row = convertView as LinearLayout
                holder = row.tag as TrackRowHolder
            }

            val track = tracks[position]
            holder.track = track
            holder.title.text = track.title
            holder.artist.text = track.artist
            holder.duration.text = track.durationLabel
            bindArtwork(holder.artwork, track.albumArtUri, 60)
            holder.position = position
            holder.more.setOnClickListener { holder.track?.let { showTrackActions(it) } }
            row.setOnClickListener {
                val index = holder.position
                if (index in tracks.indices) playTracks(tracks, index)
            }
            row.setOnLongClickListener {
                holder.track?.let { showTrackActions(it) }
                true
            }
            row.layoutParams = AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return row
        }
    }

    private data class TrackRowHolder(
        val artwork: ImageView,
        val title: TextView,
        val artist: TextView,
        val duration: TextView,
        val more: ImageView,
        var track: MusicTrack? = null,
        var position: Int = -1
    )

    private fun appendLibraryPage(container: LinearLayout) {
        val start = libraryRenderedCount
        val end = (start + LIBRARY_PAGE_SIZE).coerceAtMost(libraryPageTracks.size)
        if (start >= end) return

        for (index in start until end) {
            addTrackRow(container, libraryPageTracks[index], libraryPageTracks, index)
        }
        libraryRenderedCount = end

        if (end < libraryPageTracks.size) {
            val remaining = libraryPageTracks.size - end
            val nextCount = minOf(LIBRARY_PAGE_SIZE, remaining)
            val more = materialAction("Load next $nextCount songs  ·  $remaining remaining", false, true)
            more.setOnClickListener {
                container.removeView(more)
                appendLibraryPage(container)
            }
            container.addView(more, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(12); bottomMargin = dp(12) })
        }
    }

    private fun appendLibraryCollectionPage(container: LinearLayout, tab: String) {
        val start = libraryCollectionRenderedCount
        val end = (start + LIBRARY_COLLECTION_PAGE_SIZE).coerceAtMost(libraryCollectionItems.size)
        if (start >= end) return

        for (index in start until end) {
            val item = libraryCollectionItems[index]
            addLibraryCollectionRow(container, item.title, item.subtitle, item.trailing, item.track) {
                when (tab) {
                    LIBRARY_ALBUMS -> { currentAlbum = item.title; currentArtist = null }
                    LIBRARY_ARTISTS -> { currentArtist = item.title; currentAlbum = null }
                    LIBRARY_PLAYLISTS -> currentPlaylist = item.title
                }
                showLibrary(refreshContent = true)
            }
        }
        libraryCollectionRenderedCount = end

        if (tab == LIBRARY_PLAYLISTS && start == 0) {
            val create = materialAction("+ Create playlist", false, true).apply {
                setOnClickListener { showCreatePlaylistDialog() }
            }
            container.addView(create, matchWrap().apply { topMargin = dp(10) })
        }

        if (end < libraryCollectionItems.size) {
            val remaining = libraryCollectionItems.size - end
            val nextCount = minOf(LIBRARY_COLLECTION_PAGE_SIZE, remaining)
            val more = materialAction("Load next $nextCount collections  ·  $remaining remaining", false, true)
            more.setOnClickListener {
                container.removeView(more)
                appendLibraryCollectionPage(container, tab)
            }
            container.addView(more, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(12); bottomMargin = dp(12) })
        }
    }

    private fun renderLibraryItems(countLabel: TextView? = null) {
        val container = libraryListContainer ?: return
        val collectionScroll = libraryCollectionScroll ?: return
        val trackList = libraryListView ?: return
        val adapter = libraryTrackAdapter ?: return

        libraryRenderedCount = 0
        libraryPageTracks = emptyList()
        libraryCollectionItems = emptyList()
        libraryCollectionRenderedCount = 0

        val query = if (searchMode) currentSearch.trim() else ""
        val queryKey = query.lowercase()
        val tab = currentLibraryTab
        val sort = currentSort
        val album = currentAlbum
        val artist = currentArtist
        val playlist = currentPlaylist
        val searchMode = isSearchPage
        val sourceTracks = librarySnapshot
        val generation = ++libraryQueryGeneration

        // A blank Search page stays lightweight and does not construct song rows.
        if (searchMode && query.isBlank()) {
            pendingLibraryQuery?.cancel(true)
            countLabel?.text = "Search your music"
            container.removeAllViews()
            collectionScroll.visibility = View.VISIBLE
            trackList.visibility = View.GONE
            val prompt = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(34), dp(22), dp(34))
            }
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(cAccent)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            prompt.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
            prompt.addView(uiText("Find something you love", 16f, cText, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(6))
            })
            prompt.addView(uiText("Search by song, album or artist. Results appear as you type.", 12f, cMuted).apply {
                gravity = Gravity.CENTER
            })
            container.addView(prompt, matchWrap())
            displayedTracks = emptyList()
            return
        }

        // Keep the previous visible rows until the new query is ready. Search never clears
        // and reconstructs the list for each keystroke.
        pendingLibraryQuery?.cancel(true)
        pendingLibraryQuery = libraryQueryExecutor.submit {
            fun interrupted() = Thread.currentThread().isInterrupted
            fun matches(value: String): Boolean = queryKey.isBlank() || value.contains(queryKey)

            val favourites = if (tab == LIBRARY_FAVOURITES || tab == LIBRARY_PLAYLISTS ||
                playlist == PlaylistStore.FAVOURITES_NAME) {
                playlistStore.favourites()
            } else emptySet()

            val playlistIds = if (tab == LIBRARY_PLAYLISTS && playlist != null) {
                playlistStore.trackIds(playlist).toHashSet()
            } else emptySet()

            val playlistNames = if (tab == LIBRARY_PLAYLISTS && playlist == null) {
                playlistStore.playlists()
            } else emptyList()

            val collections = when {
                tab == LIBRARY_ALBUMS && album == null -> sourceTracks
                    .groupBy { it.album to it.artist }
                    .mapNotNull { (key, tracks) ->
                        if (interrupted() || (!matches(key.first.lowercase()) && !matches(key.second.lowercase()))) null
                        else LibraryCollectionEntry(
                            key.first, key.second, "${tracks.size} tracks",
                            tracks.maxByOrNull { it.dateAddedSeconds }
                        )
                    }
                    .sortedBy { it.title.lowercase() }

                tab == LIBRARY_ARTISTS && artist == null -> sourceTracks
                    .groupBy { it.artist }
                    .mapNotNull { (name, tracks) ->
                        if (interrupted() || !matches(name.lowercase())) null
                        else LibraryCollectionEntry(
                            name, "Artist", "${tracks.size} tracks",
                            tracks.maxByOrNull { it.dateAddedSeconds }
                        )
                    }
                    .sortedBy { it.title.lowercase() }

                tab == LIBRARY_PLAYLISTS && playlist == null -> buildList {
                    add(LibraryCollectionEntry(
                        PlaylistStore.FAVOURITES_NAME, "Your saved tracks",
                        "${favourites.size} tracks", sourceTracks.firstOrNull { it.id in favourites }
                    ))
                    playlistNames.forEach { name ->
                        if (interrupted()) return@buildList
                        val ids = playlistStore.trackIds(name).toHashSet()
                        add(LibraryCollectionEntry(
                            name, "Playlist", "${ids.size} tracks",
                            sourceTracks.firstOrNull { it.id in ids }
                        ))
                    }
                }

                else -> emptyList()
            }

            if (interrupted()) return@submit

            val isCollectionPage = (tab == LIBRARY_ALBUMS && album == null) ||
                (tab == LIBRARY_ARTISTS && artist == null) ||
                (tab == LIBRARY_PLAYLISTS && playlist == null)

            val filtered = if (isCollectionPage) {
                emptyList()
            } else {
                val selectedIds = when (tab) {
                    LIBRARY_FAVOURITES -> favourites
                    LIBRARY_PLAYLISTS -> playlistIds
                    else -> null
                }
                val result = ArrayList<MusicTrack>()
                for (track in sourceTracks) {
                    if (interrupted()) return@submit
                    if (selectedIds != null && track.id !in selectedIds) continue
                    if (album != null && track.album != album) continue
                    if (artist != null && track.artist != artist) continue
                    if (queryKey.isNotBlank()) {
                        val searchable = librarySearchIndex[track.id]
                        if (searchable == null || !searchable.contains(queryKey)) continue
                    }
                    result.add(track)
                }
                when (sort) {
                    SORT_ARTIST -> result.sortWith(compareBy<MusicTrack> { it.artist.lowercase() }.thenBy { it.title.lowercase() })
                    SORT_ALBUM -> result.sortWith(compareBy<MusicTrack> { it.album.lowercase() }.thenBy { it.title.lowercase() })
                    SORT_RECENT -> result.sortByDescending { it.dateAddedSeconds }
                    // The library snapshot is already in title order for the default path.
                }
                result
            }

            if (interrupted()) return@submit

            runOnUiThread {
                if (generation != libraryQueryGeneration || isFinishing || isDestroyed ||
                    libraryListContainer !== container || libraryListView !== trackList) return@runOnUiThread

                displayedTracks = filtered
                libraryPageTracks = filtered
                libraryCollectionItems = collections

                countLabel?.text = when {
                    isCollectionPage -> "${collections.size} ${if (tab == LIBRARY_ARTISTS) "artists" else if (tab == LIBRARY_PLAYLISTS) "playlists" else "albums"}"
                    tab == LIBRARY_PLAYLISTS && playlist != null -> "${filtered.size} tracks"
                    else -> "${filtered.size} songs"
                }

                container.removeAllViews()
                when {
                    isCollectionPage -> {
                        collectionScroll.visibility = View.VISIBLE
                        trackList.visibility = View.GONE
                        if (collections.isEmpty()) {
                            container.addView(uiText("No matching collections found.", 13f, cMuted).apply {
                                gravity = Gravity.CENTER
                                setPadding(dp(20), dp(34), dp(20), dp(34))
                            }, matchWrap())
                        } else {
                            appendLibraryCollectionPage(container, tab)
                        }
                    }

                    filtered.isEmpty() -> {
                        collectionScroll.visibility = View.VISIBLE
                        trackList.visibility = View.GONE
                        container.addView(uiText(
                            if (sourceTracks.isEmpty()) "No music found yet. Add audio to your device or use OPEN FILE."
                            else "No matching tracks. Try another search or category.",
                            13f, cMuted
                        ).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(22), dp(40), dp(22), dp(40))
                        }, matchWrap())
                    }

                    else -> {
                        collectionScroll.visibility = View.GONE
                        trackList.visibility = View.VISIBLE
                        adapter.submit(filtered)
                        trackList.setSelection(0)
                    }
                }
            }
        }
    }

    private fun addLibraryCollectionRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        trailing: String,
        artTrack: MusicTrack? = null,
        click: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(11), dp(4), dp(11))
            setOnClickListener { click() }
        }
        row.addView(artworkThumbnail(artTrack, 64, 13),
            LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(16) })
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(uiText(title, 15.5f, cText, true).apply { maxLines = 1 })
        text.addView(uiText(subtitle, 12.5f, cMuted).apply { maxLines = 1; setPadding(0, dp(5), 0, 0) })
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(uiText(trailing, 12f, cMuted).apply { setPadding(dp(8), 0, dp(4), 0) })
        row.addView(uiText("›", 24f, cAccent).apply { setPadding(dp(5), 0, 0, 0) })
        parent.addView(row, matchWrap())

        val divider = View(this).apply { setBackgroundColor(cBorder) }
        parent.addView(divider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { leftMargin = dp(80) })
    }

    private fun addTrackRow(parent: LinearLayout, track: MusicTrack, queueTracks: List<MusicTrack>, index: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(9), dp(4), dp(9))
            setOnClickListener { playTracks(queueTracks, index) }
        }
        row.addView(artworkThumbnail(track, 60, 12),
            LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(14) })

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(uiText(track.title, 15f, cText, true).apply { maxLines = 1 })
        info.addView(uiText(track.artist, 12f, cMuted).apply {
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(uiText(track.durationLabel, 11.5f, cMuted).apply {
            setPadding(dp(4), 0, dp(2), 0)
        })

        val more = ImageView(this).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = ColorStateList.valueOf(cMuted)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "More actions for ${track.title}"
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showTrackActions(track) }
        }
        row.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        row.setOnLongClickListener { showTrackActions(track); true }
        parent.addView(row, matchWrap().apply { topMargin = dp(2) })

        val divider = View(this).apply { setBackgroundColor(cBorder) }
        parent.addView(divider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { leftMargin = dp(78) })
    }

    private fun playTracks(tracks: List<MusicTrack>, index: Int) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        if (activeDestination != DEST_NOW_PLAYING) returnDestination = activeDestination
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
                    val visibleIndex = displayedTracks.indexOfFirst { it.id == track.id }
                    if (visibleIndex >= 0) {
                        playTracks(displayedTracks, visibleIndex)
                    } else {
                        val libraryIndex = libraryIndexById[track.id]
                        if (libraryIndex != null) playTracks(librarySnapshot, libraryIndex)
                        else playTracks(listOf(track), 0)
                    }
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
                    showLibrary(refreshContent = true)
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
        setAppChromeVisible(false)
        if (activeDestination != DEST_NOW_PLAYING) returnDestination = activeDestination
        activeDestination = DEST_NOW_PLAYING
        updateNavigationState()
        contentFrame.removeAllViews()

        val entry = playback?.currentEntry()
        val track = entry?.let { active -> libraryTrackByUri[active.uri] } ?: currentNowPlaying
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackground(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(27, 53, 58), Color.rgb(16, 27, 35), cBackground)))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(20), dp(26), dp(34))
        }
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topBar.addView(iconControl(R.drawable.ic_back, "Back", 42, Color.rgb(33, 52, 58)) {
            when (returnDestination) {
                DEST_LIBRARY -> showLibrary()
                DEST_SEARCH -> showSearch()
                DEST_SOUND -> showDsp()
                else -> showHome()
            }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val topTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        topTitle.addView(uiText("NOW PLAYING", 10f, cText, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.16f })
        topTitle.addView(uiText("AURALIS PLAYER", 8f, cMuted).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
        topBar.addView(topTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(iconControl(R.drawable.ic_queue, "Playback queue", 42, Color.rgb(33, 52, 58)) { showQueueDialog() },
            LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(topBar, matchWrap().apply { bottomMargin = dp(26) })

        val artworkSize = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt().minus(64).coerceIn(228, 340)
        content.addView(artwork(track, artworkSize, 25), LinearLayout.LayoutParams(dp(artworkSize), dp(artworkSize)).apply {
            bottomMargin = dp(32)
        })

        val infoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val songInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        songInfo.addView(uiText(if (entry != null || track != null) "NOW PLAYING" else "READY TO PLAY", 9f, cAccent, true).apply { letterSpacing = 0.13f })
        songInfo.addView(uiText(entry?.title ?: track?.title ?: "Choose your music", 25f, cText, true).apply {
            maxLines = 2; setPadding(0, dp(7), 0, dp(4))
        })
        songInfo.addView(uiText(entry?.artist ?: track?.artist ?: "Your library is waiting", 14f, cMuted).apply { maxLines = 1 })
        infoRow.addView(songInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (track != null) {
            val saved = playlistStore.isFavourite(track.id)
            infoRow.addView(iconControl(R.drawable.ic_favorite, if (saved) "Remove from favourites" else "Add to favourites", 44,
                if (saved) cAccentDeep else cSurfaceSoft, if (saved) cAccent else cMuted) {
                playlistStore.toggleFavourite(track.id)
                showNowPlaying()
            })
        }
        content.addView(infoRow, matchWrap().apply { bottomMargin = dp(16) })

        nowPlayingSeekBar = Slider(this).apply {
            valueFrom = 0f
            valueTo = 1000f
            stepSize = 1f
            value = 0f
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { userSeeking = true }
                override fun onStopTrackingTouch(slider: Slider) {
                    userSeeking = false
                    val duration = playback?.playbackDurationMs() ?: track?.durationMs ?: 0L
                    if (duration > 0L) playback?.seekTo((duration * slider.value / 1000.0).toLong())
                }
            })
        }
        content.addView(nowPlayingSeekBar, matchWrap())
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nowPositionLabel = uiText("0:00", 12f, cMuted)
        nowDurationLabel = uiText(formatTime(playback?.playbackDurationMs() ?: track?.durationMs ?: 0L), 12f, cMuted)
        timeRow.addView(nowPositionLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(nowDurationLabel)
        content.addView(timeRow, matchWrap().apply { bottomMargin = dp(17) })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(iconControl(R.drawable.ic_shuffle, "Toggle shuffle", 42, Color.TRANSPARENT,
            if (playback?.isShuffleEnabled() == true) cAccent else cMuted) {
            playback?.setShuffle(playback?.isShuffleEnabled() != true); showNowPlaying()
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        controls.addView(iconControl(R.drawable.ic_skip_previous, "Previous track", 48, Color.TRANSPARENT, cText) {
            playback?.skipToPrevious(); updateMiniPlayer(); showNowPlaying()
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        val play = MaterialButton(this).apply {
            text = ""
            setIconResource(if (playback?.isPlaying() == true) R.drawable.ic_pause else R.drawable.ic_play)
            iconTint = ColorStateList.valueOf(Color.rgb(6, 29, 23))
            iconSize = dp(32)
            iconPadding = 0
            cornerRadius = dp(34)
            insetTop = 0; insetBottom = 0
            minWidth = dp(70); minHeight = dp(70)
            backgroundTintList = ColorStateList.valueOf(cAccent)
            elevation = dp(3).toFloat()
            setOnClickListener { togglePlayback(); showNowPlaying() }
        }
        controls.addView(play, LinearLayout.LayoutParams(dp(70), dp(70)).apply { marginStart = dp(8); marginEnd = dp(8) })
        controls.addView(iconControl(R.drawable.ic_skip_next, "Next track", 48, Color.TRANSPARENT, cText) {
            playback?.skipToNext(); updateMiniPlayer(); showNowPlaying()
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        controls.addView(iconControl(R.drawable.ic_repeat, "Cycle repeat mode", 42, Color.TRANSPARENT,
            if ((playback?.getRepeatMode() ?: 0) != PlaybackService.REPEAT_OFF) cAccent else cMuted) {
            playback?.setRepeatMode(((playback?.getRepeatMode() ?: 0) + 1) % 3); showNowPlaying()
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(controls, matchWrap().apply { bottomMargin = dp(18) })

        val repeatLabel = when (playback?.getRepeatMode() ?: 0) {
            PlaybackService.REPEAT_ALL -> "Repeat all"
            PlaybackService.REPEAT_ONE -> "Repeat one"
            else -> "Repeat off"
        }
        content.addView(uiText("${if (playback?.isShuffleEnabled() == true) "Shuffle on" else "Shuffle off"}   ·   $repeatLabel", 12f, cMuted).apply { gravity = Gravity.CENTER })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(materialAction("Playback queue", false, true).apply {
            setOnClickListener { showQueueDialog() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        actions.addView(materialAction("Sound studio", false, true).apply {
            setOnClickListener { showDsp() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        content.addView(actions, matchWrap().apply { topMargin = dp(30) })

        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        contentFrame.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateMiniPlayer()
    }

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
            currentNowPlaying = libraryTrackByUri[entries[which].uri] ?: currentNowPlaying
            playback?.skipToQueueIndex(which)
            showNowPlaying()
        }.setNeutralButton("Clear queue") { _, _ -> playback?.clearQueue(); currentNowPlaying = null; updateMiniPlayer() }.show()
    }

    private fun togglePlayback() {
        if (playback?.isPlaying() == true) playback?.pausePlayback()
        else if (playback?.currentEntry() != null) playback?.resumePlayback()
        else currentNowPlaying?.let { playTracks(listOf(it), 0) }
        updateMiniPlayer()
    }

    private fun updateMiniPlayer() {
        if (!::miniTitle.isInitialized || !::miniPlayerCard.isInitialized) return
        val entry = playback?.currentEntry()
        val track = entry?.let { current -> libraryTrackByUri[current.uri] } ?: currentNowPlaying
        val title = entry?.title ?: track?.title ?: "Nothing playing"
        val subtitle = entry?.artist ?: track?.artist ?: "Choose a track from your library"
        if (miniTitle.text != title) miniTitle.text = title
        if (miniSubtitle.text != subtitle) miniSubtitle.text = subtitle
        bindArtwork(miniArtwork, track?.albumArtUri, 48)

        val targetVisibility = if (entry != null || track != null) {
            if (activeDestination == DEST_NOW_PLAYING) View.GONE else View.VISIBLE
        } else View.GONE
        if (miniPlayerCard.visibility != targetVisibility) miniPlayerCard.visibility = targetVisibility

        if (::miniPlayButton.isInitialized) {
            val targetIcon = if (playback?.isPlaying() == true) R.drawable.ic_pause else R.drawable.ic_play
            if (miniPlayButton.tag != targetIcon) {
                miniPlayButton.setIconResource(targetIcon)
                miniPlayButton.tag = targetIcon
            }
        }
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            val service = playback
            if (service != null) {
                val duration = service.playbackDurationMs()
                val position = service.playbackPositionMs()
                if (::nowPlayingSeekBar.isInitialized && !userSeeking && duration > 0L) {
                    nowPlayingSeekBar.value = ((position.coerceAtMost(duration) * 1000L) / duration).toFloat()
                    if (::nowPositionLabel.isInitialized) nowPositionLabel.text = formatTime(position)
                    if (::nowDurationLabel.isInitialized) nowDurationLabel.text = formatTime(duration)
                }
                updateMiniPlayer()
            }
            if (activityStarted) uiHandler.postDelayed(this, 500L)
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
        val card = roundedCard(cSurfaceSoft, 18).apply {
            strokeColor = cBorder
            strokeWidth = dp(1)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val toggle = MaterialSwitch(this).apply {
            text = title
            textSize = 14f
            setTextColor(cText)
            showText = false
        }
        heading.addView(toggle, LinearLayout.LayoutParams(0, dp(48), 1f))
        box.addView(heading)

        val gainLabel = uiText(
            "Gain · ${"%.1f".format(initialGainDb)} dB",
            12f, cMuted
        ).apply { setPadding(0, dp(9), 0, dp(2)) }
        val gain = Slider(this).apply {
            valueFrom = 0f
            valueTo = 300f
            stepSize = 1f
            this.value = ((initialGainDb.coerceIn(-15.0, 15.0) * 10.0) + 150.0).toFloat()
        }
        val control = EqUiControl(index, type, frequency, q, channel, toggle, gain, gainLabel)
        eqControls.add(control)

        fun update() {
            val db = (gain.value.toInt() - 150) / 10.0
            gainLabel.text = "Gain · ${"%.1f".format(db)} dB"
            playback?.setEqBand(index, toggle.isChecked, type, frequency, q, db, channel)
        }

        toggle.setOnCheckedChangeListener { _, _ -> update() }
        gain.addOnChangeListener { _, _, _ -> update() }
        box.addView(gainLabel)
        box.addView(gain, matchWrap())
        card.addView(box)
        parent.addView(card, matchWrap().apply { bottomMargin = dp(11) })
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
        if (::eqCount.isInitialized) {
            eqCount.text = if (eqControls.size == 1) "1 band available"
            else "${eqControls.size} bands available"
        }
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
            (replayDefaultSlider.value.toInt() - 150) * 10)
        playback?.setMasterGainDb((replayPreampSlider.value.toInt() - 150) / 10.0)
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putInt(KEY_MASTER_PREAMP, replayPreampSlider.value.toInt()).apply()
    }

    private fun syncControls() {
        playback?.setReverbEnabled(reverbToggle.isChecked)
        reverbSliders.forEachIndexed { index, slider ->
            slider?.let { playback?.setReverbParameter(index, it.value / 100.0) }
        }
        playback?.setStereoWide(stereoToggle.isChecked, stereoSlider.value.toInt())
        playback?.setBalance(balanceToggle.isChecked, (balanceSlider.value - 100f) / 100.0)
        playback?.setMono(monoToggle.isChecked)
        playback?.setLimiter(limiterToggle.isChecked, limiterPath)
        applyReplayGain()
        eqControls.forEach { c ->
            val db = (c.gain.value.toInt() - 150) / 10.0
            playback?.setEqBand(c.index, c.enabled.isChecked, c.type, c.frequency, c.q, db, c.channel)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    @Deprecated("Use platform back navigation")
    override fun onBackPressed() {
        val target = when (activeDestination) {
            DEST_NOW_PLAYING -> returnDestination
            DEST_SOUND -> soundReturnDestination
            else -> null
        }
        when (target) {
            DEST_LIBRARY -> showLibrary()
            DEST_SEARCH -> showSearch()
            DEST_NOW_PLAYING -> showNowPlaying()
            DEST_SOUND -> showDsp()
            DEST_HOME -> showHome()
            else -> super.onBackPressed()
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        uiHandler.removeCallbacks(progressTicker)
        uiHandler.post(progressTicker)
    }

    override fun onStop() {
        activityStarted = false
        uiHandler.removeCallbacks(progressTicker)
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(progressTicker)
        pendingSearchRender?.let(uiHandler::removeCallbacks)
        pendingLibraryQuery?.cancel(true)
        libraryQueryExecutor.shutdownNow()
        artworkExecutor.shutdownNow()
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
        val enabled: CompoundButton,
        val gain: Slider,
        val value: TextView
    )

    private data class LibraryCollectionEntry(
        val title: String,
        val subtitle: String,
        val trailing: String,
        val track: MusicTrack?
    )

    companion object {
        private const val DEST_HOME = "home"
        private const val DEST_SEARCH = "search"
        private const val DEST_LIBRARY = "library"
        private const val DEST_SOUND = "sound"
        private const val DEST_NOW_PLAYING = "now_playing"
        private const val REQUEST_AUDIO = 8201
        private const val REQUEST_LIBRARY_PERMISSION = 8202
        private const val PREFERENCES = "auralis.player"
        private const val KEY_MASTER_PREAMP = "master_preamp_progress"
        private const val LIBRARY_SONGS = "Songs"
        private const val LIBRARY_ALBUMS = "Albums"
        private const val LIBRARY_ARTISTS = "Artists"
        private const val LIBRARY_FAVOURITES = "Favourites"
        private const val LIBRARY_PLAYLISTS = "Playlists"
        private const val SORT_TITLE = "Title"
        private const val SORT_ARTIST = "Artist"
        private const val SORT_ALBUM = "Album"
        private const val SORT_RECENT = "Recently added"
        private const val MAX_EQ_BANDS = 64
        private const val LIBRARY_PAGE_SIZE = 12
        private const val LIBRARY_COLLECTION_PAGE_SIZE = 16
        private const val NO_ARTWORK_TAG = "auralis:no-artwork"
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
