# mpvRx Playback Startup — Architecture Audit & Cold-Open Optimisation Plan

Audit of the complete playback path in mpvRx, from tapping a video row to the first
rendered frame, with a phase-by-phase walkthrough and a concrete plan to make the
player open instantly.

- **Scope:** tap → `PlayerActivity.onCreate` → libmpv init → Surface bind → `loadfile`
  → `MPV_EVENT_FILE_LOADED` → UI catch-up
- **Focus:** cold-start latency, main-thread blocking work, and offline-vs-online gating
- **All line references verified against the working tree at commit `1dfed29a`**

---

## Table of contents

1. [The cast of characters](#1-the-cast-of-characters)
2. [Architecture at a glance](#2-architecture-at-a-glance)
3. [Phase 0 — App launch (background)](#3-phase-0--app-launch-background)
4. [Phase 1 — The tap](#4-phase-1--the-tap)
5. [Phase 2 — `PlayerActivity.onCreate` (UI thread)](#5-phase-2--playeractivityoncreate-ui-thread)
6. [Phase 3 — Surface arrives, `vo` flips on](#6-phase-3--surface-arrives-vo-flips-on)
7. [Phase 4 — `startMediaLoad` prep lane](#7-phase-4--startmediaload-prep-lane)
8. [Phase 5 — `issuePlaybackLoad` parallel join](#8-phase-5--issueplaybackload-parallel-join)
9. [Phase 6 — `PlaybackSession.load` → `loadfile`](#9-phase-6--playbacksessionload--loadfile)
10. [Phase 7 — libmpv native work](#10-phase-7--libmpv-native-work)
11. [Phase 8 — `FILE_LOADED` UI catch-up](#11-phase-8--file_loaded-ui-catch-up)
12. [Cost ranking](#12-cost-ranking)
13. [Offline vs online: what to remove and gate](#13-offline-vs-online-what-to-remove-and-gate)
14. [Concrete fixes](#14-concrete-fixes)
15. [Priority order](#15-priority-order)
16. [How to measure](#16-how-to-measure)

---

## 1. The cast of characters

| Layer | File | Size | Role |
|---|---|---|---|
| **App process** | `app/src/main/java/app/gyrolet/mpvrx/App.kt` | 647 L | Prewarms libmpv core on process launch; runs the idle core reaper |
| **Native core** (process-wide singleton) | `MPVLib` — external AAR, `libs.mpvlib.*` (`app/build.gradle.kts:300-302`) | — | libmpv 30–40 MB `.so`, one `mpv_handle` per process |
| **Core wrapper** | `ui/player/PlaybackSession.kt` | 2145 L | The **only** code that talks to `MPVLib`. Owns state machine, locking, load lifecycle |
| **Surface view** | `ui/player/MPVView.kt` | 768 L | `SurfaceView`; supplies `initOptions()` / `observeProperties()` / `postInitOptions()` |
| **Activity** | `ui/player/PlayerActivity.kt` | **8600 L** | Orchestration, lifecycle, intent handling, UI wiring |
| **ViewModel** | `ui/player/PlayerViewModel.kt` | **7986 L** | UI state, filters, custom buttons, shaders, collectors |
| **Service** | `ui/player/MediaPlaybackService.kt` | 2042 L | `MediaSession` + foreground notification. Started **after** `FILE_LOADED` |
| **Controls** | `ui/player/controls/PlayerControls.kt` | 2186 L | Compose control surface, ~60 `collectAsState()` |
| **Layout** | `res/layout/player_layout.xml` | 56 L | `MPVView` first, then two `ComposeView`s |

### Critical architectural facts

1. **There is exactly one libmpv core per process.** `MPVLib.create` exits the process
   if `g_mpv` is already non-null; `MPVLib.init` exits if it is null. There is no handle
   to stash. See `PlaybackSession.kt:259-277`.
2. **`PlayerActivity` is `singleTask`.** Re-opening a video goes through
   `onNewIntent` (`PlayerActivity.kt:5452`), not `onCreate`. Only a cold open pays the
   full Phase 2 cost.
3. **The launch URI is already known when `setupMPV()` runs.** `MediaUtils.playFile`
   does `Intent(ACTION_VIEW, playbackUri)` + `startActivity` at `MediaUtils.kt:306-372`,
   long before `PlayerActivity.kt:703`. This is the linchpin for offline/online gating.

---

## 2. Architecture at a glance

```
┌─ App.onCreate ────────────────────────────────────────────────────────────┐
│  prewarmPlaybackStartup()          [BG] dlopen libmpv + create mpv_handle │
│  startIdleMpvCoreReaper()         [BG] ⚠ kills core after 3 min idle      │
└───────────────────────────────────────────────────────────────────────────┘
                                    │
┌─ User taps row ──────────────────▼─────────────────────────────────────────┐
│  MediaUtils.playFile()                                                    │
│    ├─ audio?          → MiniPlayer, return                               │
│    ├─ torrent, no idx → TorrentSelectionActivity                         │
│    └─ else            → Intent(ACTION_VIEW, uri) → PlayerActivity        │
│  PlaybackPerformanceTrace.mark("OPEN_REQUEST")                            │
└───────────────────────────────────────────────────────────────────────────┘
                                    │
┌─ PlayerActivity.onCreate ────────▼────────── UI THREAD ───────────────────┐
│  setContentView (SurfaceView FIRST)                                        │
│  setupMPV()                                                               │
│    ├ syncBundledAssetsIfNeeded()          ~6.7 MB, version-gated          │
│    ├ prepareUserMpvAssetsForStartup()     SAF tree walk — JOINED          │
│    └ initializePlayerWithRendererFallback()                               │
│         └ MPVView.initializeSession()                                     │
│              ├ mpvConfigCache.configurationKey()  sync disk read + SHA256  │
│              └ PlaybackSession.initialize()                               │
│                   ├ initOptions()      ~70 setOptionString  ← OFFLINE+ON  │
│                   │    └ YtdlpManager.setupMpvOptions()  ← ONLINE ONLY    │
│                   ├ MPVLib.init()      mpv.conf + Lua scripts            │
│                   ├ vo = null          (no GPU yet, no Surface)           │
│                   └ observeProperties() 26 observers                      │
│  setupPlayerControls() + setupVideoAmbientBackground()   2 Compose trees  │
│  generatePlaylistFromFolder()             ⚠ sync folder walk              │
│  getPlayableUri → startMediaLoad()                                        │
└───────────────────────────────────────────────────────────────────────────┘
                                    │
┌─ Surface arrives ─────────────────▼──────────── next frame ───────────────┐
│  MPVView.surfaceCreated → PlaybackSession.bindSurface()                   │
│    └ MPVLib.attachSurface + vo = desiredVideoOutput   ⭐ FIRST VIDEO       │
└───────────────────────────────────────────────────────────────────────────┘
                                    │
┌─ startMediaLoad ─────────────────▼──────────── mediaLoadDispatcher ──────┐
│  stopStream / torrent / m3u · build PlaybackItem                           │
│  async artwork fetch   (music only)     async cookie export (http only)    │
│  issuePlaybackLoad()                                                        │
│    async ytdlp prep  ‖  awaitStopCompletion  ‖  saved-position DB read     │
└───────────────────────────────────────────────────────────────────────────┘
                                    │
┌─ PlaybackSession.load ────────────▼───────────────────────────────────────┐
│  resolvePlayableUri()  → 5 branches, all early-return for plain local file │
│  smbPath (runBlocking, null for local)                                    │
│  user-agent / http-header-fields / vid=no                                 │
│  vo=null if !surfaceAttached           ⚠ vo thrash                       │
│  ⭐ MPVLib.command("loadfile", uri, "replace", -1, opts)                   │
└───────────────────────────────────────────────────────────────────────────┘
                                    │
┌─ libmpv native threads ────────▼──────────────────────────────────────────┐
│  demuxer open → probe → MediaCodec → vo_gpu init → first frame            │
│  MPV_EVENT_START_FILE → MPV_EVENT_FILE_LOADED → MPV_EVENT_PLAYBACK_RESTART│
└───────────────────────────────────────────────────────────────────────────┘
                                    │
┌─ UI catch-up ──────────────────▼──────────── UI THREAD ───────────────────┐
│  PlayerObserver.event → PlayerActivity.event(FILE_LOADED)                 │
│    ├ viewModel.onVideoLoadCompleted()      release poster overlay          │
│    ├ handleFileLoaded()                    position, hash, Jellyfin         │
│    ├ scheduleDeferredUserMpvAssetRefresh()                                 │
│    └ startBackgroundPlayback()             ⭐ MediaPlaybackService HERE    │
│  PlayerArtworkTransition 320 ms tween + 1 s anchor timeout                │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Phase 0 — App launch (background)

`App.kt:169` fires `prewarmPlaybackStartup()` the moment the process starts.

```kotlin
// App.kt:447-454
private fun prewarmPlaybackStartup() {
  applicationScope.launch {                              // Dispatchers.Default
    runCatching { PlaybackSession.prewarmNativeCore(this@App) }
      .onFailure { error -> Log.e(TAG, "Failed to prewarm the libmpv core on launch", error) }
    runCatching { VideoCodecSupportInspector.hardwareDecoderCodecIds() }
      .onFailure { error -> Log.e(TAG, "Failed to prewarm the hardware decoder capabilities", error) }
  }
}
```

**What it means:** libmpv is a 30–40 MB `.so`. The first time any code touches the
`MPVLib` class, Android runs `dlopen` on it — reads the file off disk, maps it into
memory, relocates every symbol. That must not happen on the UI thread. So
`PlaybackSession.kt:278-285` does exactly one step in the background and stops:

```kotlin
internal fun prewarmNativeCore(context: Context) {
  nativeLock.withLock {
    if (nativeCoreCreated) return
    runCatching { MPVLib.create(context.applicationContext) }
      .onSuccess { nativeCoreCreated = true }
      .onFailure { error -> Log.w(TAG, "Failed to prewarm the libmpv core", error) }
  }
}
```

`MPVLib.create` allocates libmpv's one process-wide handle. After this the file is in
memory and the handle exists — but the engine is still **asleep**: no options written,
no `mpv.conf` parsed, no scripts loaded.

### ⚠️ The reaper that undoes the prewarm

```kotlin
// App.kt:101
private const val IDLE_MPV_CORE_GRACE_MS = 3L * 60L * 1000L   // 3 minutes

// App.kt:168 → 462
private fun startIdleMpvCoreReaper() {
  applicationScope.launch {
    PlaybackSession.state.collectLatest { state ->
      val isFullyIdle = state.phase == PlaybackPhase.IDLE &&
                        state.currentItem == null &&
                        !state.surfaceAttached &&
                        PlaybackSession.isInitialized
      if (!isFullyIdle) return@collectLatest
      delay(IDLE_MPV_CORE_GRACE_MS)
      val latest = PlaybackSession.state.value
      val stillFullyIdle = /* …same re-check… */
      // … shuts the core down
```

**Consequence:** watch one video → press back → browse the library for 3 minutes → the
core is shut down. The next tap pays the **full `dlopen` + `create` again**. Browsing a
folder for 3 minutes is completely normal behaviour, so this is the single biggest
cause of "why is it sometimes slow".

---

## 4. Phase 1 — The tap

`MediaUtils.playFile()` — `MediaUtils.kt:200-373`.

```kotlin
fun playFile(
  source: Any,
  context: Context,
  launchSource: String? = null,
  title: String? = null,
  headers: Map<String, String>? = null,
  subtitles: List<Uri> = emptyList(),
  /* …20 more params… */
) {
  val videoSource = source as? Video
  val localPath = when {
    videoSource != null -> localPlaybackPath(videoSource)
    source is String && source.startsWith("file://", ignoreCase = true) -> source.removePrefix("file://")
    source is String && source.startsWith("/") -> source
    source is Uri && source.scheme.equals("file", ignoreCase = true) -> source.path
    else -> null
  }?.takeIf(String::isNotBlank)

  val playbackUri: Uri = when { /* …resolve final Uri… */ }

  val isAudioMedia = isAudio ||
    videoSource?.isAudio == true ||
    (localPath?.let { File(it).extension.lowercase() in FileTypeUtils.AUDIO_EXTENSIONS } ?: false)

  if (shouldPlayInMiniPlayerOnly(isAudioMedia)) {
    val queueItems = /* …build PlaybackItem list… */
    playInMiniPlayer(context, queueItems, playlistIndex)
    return
  }

  val intent = Intent(Intent.ACTION_VIEW, playbackUri)
  val torrentSource = /* …detect magnet/torrent… */
  intent.setClass(
    context,
    if (torrentSource != null && torrentFileIndex == null && torrentPreparationId == null) {
      TorrentSelectionActivity::class.java
    } else {
      PlayerActivity::class.java
    },
  )
  intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
  intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  intent.putExtra("internal_launch", true)
  localPath?.let { intent.putExtra("local_media_path", it) }
  applyPlaybackExtras(intent, launchSource, title, headers, /* … */)

  PlaybackPerformanceTrace.mark(
    "OPEN_REQUEST",
    "source=${launchSource ?: (if (videoSource != null) "library" else "direct")} " +
      "kind=${if (videoSource != null) "video" else (playbackUri.scheme ?: "path")}",
  )
  context.startActivity(intent)                                    // MediaUtils.kt:372
}
```

Three exits before `PlayerActivity`: audio → mini player; unselected torrent →
`TorrentSelectionActivity`; else → `PlayerActivity`.

**`SINGLE_TOP` + `REORDER_TO_FRONT` matters.** With `singleTask`, a second tap on an
already-running player goes to `onNewIntent`, so Phase 2 is skipped entirely.

### Faster path: `playFromMediaLibrary`

`MediaLibraryContent.kt:328-377` stages the whole queue up front so the Activity can skip
intent parsing:

```kotlin
val launchToken = PreparedPlaybackLaunchStore.stage(
  items = queueItems, currentIndex = index, isExplicitQueue = true,
)
val intent = Intent(Intent.ACTION_VIEW, video.uri).apply {
  setClass(context, PlayerActivity::class.java)
  putExtra("internal_launch", true)
  putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true)
  putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, launchToken)
  putExtra("playlist_id", ALL_VIDEOS_PLAYLIST_ID)
  putExtra("playlist_index", index)
  putExtra("launch_source", "media_library")
  …
}
```

---

## 5. Phase 2 — `PlayerActivity.onCreate` (UI thread)

`PlayerActivity.kt:662-898`. Read as a numbered list.

### 5.1 Orientation + open animation (`:663-674`)

```kotlin
applyInitialVideoOrientation(intent)
enableEdgeToEdge()
super.onCreate(savedInstanceState)
if (intent.action == MediaPlaybackService.ACTION_OPEN_PLAYER) {
  val animateArtwork = PlayerArtworkTransitions.motion?.destination == PlayerArtworkDestination.FULL
  overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, if (animateArtwork) 0 else R.anim.slide_in_up, 0)
} else {
  overridePendingTransition(android.R.anim.fade_in, 0)
}
```

Android 14+ path. Duration `0` when a poster→player artwork transition is pending, so
there is no double animation.

### 5.2 Lua-script-reload redirect (`:675-680`)

```kotlin
if (intent.action == MediaPlaybackService.ACTION_OPEN_PLAYER && player.userScriptsNeedReload()) {
  currentPlaybackIntentForScriptReload()?.let { playbackIntent ->
    setIntent(playbackIntent)
    applyInitialVideoOrientation(playbackIntent)
  }
}
```

### 5.3 Claim ownership (`:681-693`)

```kotlin
if (redirectUnselectedTorrentToPicker(intent, finishCurrent = true)) return
if (!acceptPreparedPlaybackLaunch(intent)) { finish(); return }
playbackOwnerToken = PlaybackActivityOwner.claim()      // ReentrantReadWriteLock — one owner
pendingSavedPlaylistSelection = savedInstanceState?.takeUnless { intent.getBooleanExtra(EXTRA_SCRIPT_RUNTIME_RESTART, false) }
  ?.toSavedPlaylistSelection()
if (!beginMediaRequest()) { finish(); return }
isSecureFolderLaunch = intent.getStringExtra("launch_source") == "secure_folder"
```

`PlaybackActivityOwner` (`PlayerLifecyclePolicy.kt:17-47`) is an `AtomicLong` token plus
a `ReentrantReadWriteLock`, so exactly one Activity owns the process-wide core.

### 5.4 Layout inflation (`:696-698`)

`player_layout.xml` order is deliberate and correct:

```xml
<app.gyrolet.mpvrx.ui.player.StretchAwarePlayerLayout>
    <app.gyrolet.mpvrx.ui.player.MPVView
        android:id="@+id/player" …/>          ← SurfaceView FIRST
    …
    <androidx.compose.ui.platform.ComposeView android:id="@+id/ambient_background" …/>
    <androidx.compose.ui.platform.ComposeView android:id="@+id/controls" …/>
</app.gyrolet.mpvrx.ui.player.StretchAwarePlayerLayout>
```

The SurfaceView exists as early as possible so Android can hand over a Surface fast.

### 5.5 `setupMPV()` — the expensive one (`:703` → `:2612-2653`)

```kotlin
private fun setupMPV(): String? {
  // Prepare config and user MPV assets before initializing MPV. These are multi-MB APK asset
  // copies, preference reads and a SAF tree walk, so they run on IO but are still joined here:
  // MPV must not initialize, and onCreate must not continue, before they have completed.
  runCatching {
    val preparationStartedAt = android.os.SystemClock.elapsedRealtime()
    syncBundledAssetsIfNeeded()                    // ①
    prepareUserMpvAssetsForStartup()               // ②
    val elapsed = android.os.SystemClock.elapsedRealtime() - preparationStartedAt
    Log.d(TAG, "MPV startup assets ready in $elapsed ms")
  }.onFailure { e -> Log.e(TAG, "Error copying MPV config and assets", e) }

  player.onSurfaceReady = {                        // ③ runs later, when Surface exists
    if (!isDeviceScreenOffOrLocked() && (isInBackgroundPlayback || lastVid > 0)) enableVideoAfterBackground()
    viewModel.restartPostProcessingIfActive()
    viewModel.restartAmbientIfActive()
    binding.root.post(::updateVideoAmbientPlayerBounds)
  }

  // NOW initialize MPV - it will find and load the scripts we just copied
  val initError = synchronized(USER_MPV_ASSET_LOCK) {
    val cleanupFailure = runCatching { removeDisabledCachedScripts() }.exceptionOrNull()  // ④
    if (cleanupFailure != null) {
      Log.e(TAG, "Could not remove disabled cached scripts", cleanupFailure)
      cleanupFailure.message ?: getString(R.string.toast_playback_load_failed)
    } else initializePlayerWithRendererFallback()                                            // ⑤
  }
  if (initError != null) return initError

  runCatching { PlaybackSession.setThumbnailJavaVM(applicationContext) }
  mpvInitialized = true
  Log.d(TAG, "MPV initialized")
  PlaybackSession.addObserver(playerObserver)
  scheduleDeferredSubtitleFontsSync()
  return null
}
```

The design is stated honestly in the KDoc at `:2614-2617`: the asset prep is IO-bound but
**joined**, because `MPVLib.init()` must not run before the scripts are on disk.

#### ① `syncBundledAssetsIfNeeded()` — `:3010-3025`

```kotlin
private fun syncBundledAssetsIfNeeded() {
  val syncPrefs = assetSyncPreferences
  val currentVersion = longVersionCode()
  val assetsAlreadyPrepared = File(filesDir, "mpv.conf").exists() &&
                              File(filesDir, "input.conf").exists() &&
                              File(filesDir, "scripts").exists()
  if (assetsAlreadyPrepared && syncPrefs.getLong("bundled_assets_version", -1L) == currentVersion) return
  Utils.copyAssets(this@PlayerActivity)
  syncPrefs.edit().putLong("bundled_assets_version", currentVersion).apply()
}
```

Bundled asset sizes (`app/src/main/assets/`):

| Asset dir | Size |
|---|---|
| `ytdl` | 3.4 MB |
| `shaders` | 2.2 MB |
| `guessit` | 756 KB |
| `textmate` | 360 KB |
| **total** | **~6.7 MB** |

Guarded by app version → only runs after an upgrade. **Acceptable as-is.**

#### ② `prepareUserMpvAssetsForStartup()` — `:2655-2676`

```kotlin
private fun prepareUserMpvAssetsForStartup() {
  ensureConfigCacheForStartup()
  val syncPreferences = assetSyncPreferences
  val currentSelection = currentUserMpvAssetSelection()
  val storedSelection = syncPreferences.getString(USER_MPV_ASSET_SELECTION, null)
  val cacheReady = hasLaunchReadyUserMpvAssetCache()
  val canAdoptExistingCache = storedSelection == null && cacheReady &&
                              cachedConfigsMatchPreferences() && cachedScriptsMatchSelection()

  if (cacheReady && cachedScriptsMatchSelection() &&
      (storedSelection == currentSelection || canAdoptExistingCache)) {
    if (canAdoptExistingCache) rememberUserMpvAssetSelection(syncPreferences)
    Log.d(TAG, "Using cached MPV user assets for startup")
    return
  }

  syncFromUserMpvDirectory()                       // ⚠ SAF tree walk, JOINED
  rememberUserMpvAssetSelection(syncPreferences)
  deferredUserMpvAssetRefreshStarted.set(true)
}
```

A deferred re-do exists (`scheduleDeferredUserMpvAssetRefresh()` at `:3047-3070`, 750 ms
delay), but the **first** pass is still joined.

#### ⑤ `initializePlayerWithRendererFallback()` — `:2743-2759`

```kotlin
private fun initializePlayerWithRendererFallback(): String? {
  player.forceOpenGlFallback = false
  val firstAttempt = player.initializeSession(filesDir.path, cacheDir.path)
  if (firstAttempt.isSuccess) return null

  val firstError = firstAttempt.exceptionOrNull()
  if (!decoderPreferences.useVulkan.get() || !VulkanCapabilities.isAvailable(this)) {
    Log.e(TAG, "Failed to initialize MPV", firstError)
    return firstError?.message ?: firstError?.toString() ?: "Unknown error"
  }

  Log.w(TAG, "MPV Vulkan init failed, retrying with OpenGL fallback for this session", firstError)
  player.forceOpenGlFallback = true                            // ⚠ retry everything
  val fallbackAttempt = player.initializeSession(filesDir.path, cacheDir.path)
  fallbackAttempt.exceptionOrNull()?.let { error -> Log.e(TAG, "Failed to initialize MPV", error) }
  return if (fallbackAttempt.isSuccess) null
    else fallbackAttempt.exceptionOrNull()?.message ?: fallbackAttempt.exceptionOrNull()?.toString() ?: "Unknown fallback error"
}
```

⚠️ On a Vulkan device where Vulkan init fails, **everything in 5.6–5.9 runs twice**.

### 5.6 `MPVView.initializeSession()` — `:80-110`

```kotlin
fun initializeSession(configDir: String, cacheDir: String): Result<Boolean> {
  // The libmpv core is process-wide, so returning to the player can reuse a core created with
  // older renderer preferences. Keep fallbacks stable for the lifetime of that preference
  // selection, but recreate the core when gpu-next/Vulkan selection actually changes.
  MpvConfigOverridePolicy.configure(advancedPreferences.mpvConfOverrides.get())
  val requestedBackend = selectRenderBackend(ignoreForcedOpenGlFallback = true)
  val scriptsKey = advancedPreferences.userScriptsConfigurationKey()
  val coreConfigurationKey = "${requestedBackend.configurationKey}|conf=${MpvConfigOverridePolicy.configurationKey()}" +
                            "|mpv=${mpvConfigCache.configurationKey()}|scripts=$scriptsKey"
  val result = PlaybackSession.initialize(
    context = context.applicationContext,
    configDir = configDir,
    cacheDir = cacheDir,
    coreConfigurationKey = coreConfigurationKey,
    initOptions = ::initOptions,
    postInitOptions = ::postInitOptions,
    observeProperties = ::observeProperties,
    userScriptsKey = scriptsKey,
  )
  if (result.isSuccess) {
    holder.removeCallback(this)
    holder.addCallback(this)
    if (holder.surface.isValid && !isSurfaceReady) surfaceCreated(holder)
  }
  return result
}
```

⚠️ **Two traps here.**

**Trap 1 — synchronous disk read + SHA-256 on the UI thread, every open.**
`mpvConfigCache.configurationKey()` (`MpvConfigCache.kt:69-79`):

```kotlin
fun configurationKey(): String {
  ensureCurrent()                                // → readCachedContent() → atomicConfigFile.readFully()
  return synchronized(lock) {
    val bytes = cachedBytes ?: atomicConfigFile.readFully().also { cachedBytes = it }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE)
  }
}
```

`ensureCurrent()` → `updateLocked()` (`:107-117`) reads the whole file, compares to the
preference, and **may rewrite it** with an `AtomicFile` write + `finishWrite` fsync.

**Trap 2 — `coreConfigurationKey` is a fingerprint.** If *any* component changes,
`PlaybackSession.initialize` **destroys and rebuilds the entire core** (see 5.7).

### 5.7 `PlaybackSession.initialize()` — the engine wakes up (`:312-415`)

```kotlin
fun initialize(
  context: Context,
  configDir: String,
  cacheDir: String,
  coreConfigurationKey: String,
  initOptions: () -> Unit,
  postInitOptions: () -> Unit,
  observeProperties: () -> Unit,
  userScriptsKey: String? = null,
): Result<Boolean> =
  runCatching {
    nativeLock.withLock {
      if (initialized && activeCoreConfigurationKey == coreConfigurationKey) return@withLock false  // warm reuse
      if (initialized) {
        Log.i(TAG, "Playback core configuration changed; recreating the libmpv core")
        destroyLocked()                                        // ⚠ full recreation
      }

      applicationContext = context.applicationContext
      nativeCoreReady = false
      observedProperties.clear()
      suspendedVideoTrack = null
      deferredVideoSelectionGeneration = null
      pendingStopClearQueue = false
      supersededStopGeneration = 0L
      desiredPaused = true
      loadedGeneration = 0L
      defaultUserAgent = null
      pendingPositionRestoreGeneration = 0L
      pendingPositionRestoreOverride = null
      initialPositionGeneration = 0L
      clearSeekAudioGuardLocked(restoreMute = false)
      clearPlaybackTransitionAudioGuardLocked(restoreMute = false)
      resetAmbientShaderTrackingLocked()
      updateState { it.copy(phase = PlaybackPhase.INITIALIZING, error = null) }

      try {
        // Adopts the core [prewarmNativeCore] already created instead of calling
        // MPVLib.create a second time, which libmpv treats as a fatal double-create.
        if (!nativeCoreCreated) {
          MPVLib.create(context.applicationContext)
          nativeCoreCreated = true
        }
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        MPVLib.setOptionString("gpu-shader-cache-dir", cacheDir)
        MPVLib.setOptionString("icc-cache-dir", cacheDir)
        // Keep app defaults before initialization. libmpv then parses the native mpv.conf once
        // during init, preserving its profiles, includes and quoting without runtime replay.
        initOptions()                                          // ⭐ ~70 JNI writes
        MPVLib.init()                                           // ⭐ mpv.conf + Lua scripts
        // Runtime properties do not exist between MPVLib.create() and MPVLib.init(). Keep option
        // writes available in that window, but permit property reads only from this point on.
        nativeCoreReady = true
        // Preserve the effective default after mpv.conf has been parsed. Per-media request
        // headers may temporarily override it, but must not leak into the next item.
        defaultUserAgent = MPVLib.getPropertyString("user-agent")
        postInitOptions()
        MPVLib.getPropertyString("vo")?.takeIf { it.isNotBlank() && it != "null" }
          ?.let { desiredVideoOutput = it }
        MPVLib.setPropertyString("vo", "null")                 // ⭐ deliberately no renderer yet
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "yes")
        MPVLib.addObserver(this)
        reobserveTrackedProperties()
        observeProperties()                                    // 26 observeProperty calls
        initialized = true
        activeCoreConfigurationKey = coreConfigurationKey
        activeUserScriptsKey = userScriptsKey
        updateState { it.copy(phase = PlaybackPhase.IDLE, paused = true, error = null) }
        true
      } catch (error: Throwable) {
        runCatching { MPVLib.removeObserver(this) }
        runCatching { MPVLib.destroy() }
        initialized = false; nativeCoreReady = false; nativeCoreCreated = false
        activeCoreConfigurationKey = null; activeUserScriptsKey = null
        /* …full state reset… */
        updateState { it.copy(phase = PlaybackPhase.ERROR, error = error.message ?: error.javaClass.simpleName) }
        throw error
      }
    }
  }
```

**The `vo = null` at `:374` is a deliberate, good optimisation.** Video output needs no
GPU pipeline until a Surface exists, so libmpv doesn't build the renderer yet. This
directly delays MediaCodec startup — as the KDoc at `PlaybackSession.kt:263-265` admits:

> *"Both used to happen on the main thread inside `initialize()`, before the SurfaceView
> surface is created — delaying the `vo` flip and therefore MediaCodec startup."*

### 5.8 `MPVView.initOptions()` — line by line (`:205-329`)

Every line is: take `nativeLock` → JNI into libmpv → parse the option. **~70 of them,
serialized, on the UI thread.**

```kotlin
override fun initOptions() {
  // ── Quality preset ──
  val profile = decoderPreferences.profile.get()
  PlaybackSession.setOptionString("profile", profile)

  // ── Renderer selection ──
  val backend = selectRenderBackend()
  val useVulkan = backend.gpuApi == "vulkan"
  val hwdecMode = preferredHwdecMode(useVulkan)
  PlaybackSession.setVideoOutput(backend.vo)
  PlaybackSession.setOptionString("gpu-api", backend.gpuApi)
  PlaybackSession.setOptionString("gpu-context", backend.gpuContext)

  // ── HDR screen output ──
  val hdrScreenOutputEnabled = decoderPreferences.hdrScreenOutput.get()
  val isLinearAvailable = useVulkan && backend.vo == "gpu-next"
  val hdrScreenMode = if (!hdrScreenOutputEnabled) HdrScreenMode.OFF else {
    val mode = decoderPreferences.hdrScreenMode.get()
    if (mode == HdrScreenMode.LINEAR && !isLinearAvailable) HdrScreenMode.defaultEnabledMode else mode
  }
  val hdrPipelineReady = hdrScreenMode != HdrScreenMode.LINEAR || isLinearAvailable
  if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) {
    applyHdrScreenOutputOptions(mode = hdrScreenMode, pipelineReady = hdrPipelineReady,
                                boostSdrToHdr = decoderPreferences.boostSdrToHdr.get())
  }

  // ── Hardware decode ──
  // Fongmi can map direct MediaCodec frames into Vulkan; other Vulkan builds start with copy mode.
  if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HARDWARE_DECODER)) {
    val hardwareDecoderCodecs = VideoCodecSupportInspector.hardwareDecoderCodecIds()
    PlaybackSession.setOptionString("hwdec", if (hardwareDecoderCodecs.isEmpty()) "no" else hwdecMode)
    if (hardwareDecoderCodecs.isNotEmpty()) {
      PlaybackSession.setOptionString("hwdec-codecs", hardwareDecoderCodecs.joinToString(","))
    }
  }

  // These were forced on between the last known-good build (e3b1de8) and the first build
  // reproducing the HEVC/Main10 frame-drop regression (84f21fc). Keep mpv's normal direct-
  // rendering heuristic, matching mpv's defaults.
  PlaybackSession.setOptionString("vd-lavc-dr", "auto")

  if (decoderPreferences.useYUV420P.get()) PlaybackSession.setOptionString("vf", "format=yuv420p")
  val logLevel = if (advancedPreferences.verboseLogging.get()) "v" else "warn"
  PlaybackSession.setOptionString("msg-level", "all=$logLevel")

  PlaybackSession.setOptionString("keep-open", "yes")
  PlaybackSession.setOptionString("input-default-bindings", "yes")

  // ── TLS ──
  PlaybackSession.setOptionString("tls-verify", "yes")
  PlaybackSession.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")

  // ── Screenshots ──
  val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
  screenshotDir.mkdirs()                                                    // ⚠ sync FS on UI thread
  PlaybackSession.setOptionString("screenshot-directory", screenshotDir.path)

  // ── 6 colour filters ──
  VideoFilters.entries.forEach {
    PlaybackSession.setOptionString(it.mpvProperty, it.preference(decoderPreferences).get().toString())
  }

  PlaybackSession.setOptionString("speed", playerPreferences.defaultSpeed.get().toString())
  // Avoid forcing CPU-side film-grain synthesis globally; this can spike thermals on mobile SoCs.
  PlaybackSession.setOptionString("vd-lavc-film-grain", "auto")

  // ── Streaming ──
  PlaybackSession.setOptionString("hls-bitrate", "no")
  PlaybackSession.setOptionString("cookies", "yes")
  PlaybackSession.setOptionString("cookies-file", AndroidCookieJar.playbackCookieFile(context).absolutePath)
  PlaybackSession.setOptionString("cache", "auto")
  PlaybackSession.setOptionString("cache-pause", "yes")
  PlaybackSession.setOptionString("cache-pause-wait", "2")
  PlaybackSession.setOptionString("demuxer-max-bytes", "64MiB")
  // Recover boundedly from transient HTTP/TLS disconnects, including non-seekable live inputs.
  // Do not use reconnect_at_eof globally: a legitimate VOD EOF must still finish normally.
  PlaybackSession.setOptionString("demuxer-lavf-o",
    "http_persistent=0,reconnect=1,reconnect_on_network_error=1,reconnect_streamed=1," +
    "reconnect_delay_max=5,reconnect_max_retries=5,reconnect_delay_total_max=20")
  // demuxer-lavf-o only reaches demuxer-internal opens (HLS/DASH segments). The primary http(s)
  // URL is opened by stream_lavf, which reads stream-lavf-o; without it a dropped connection or
  // one failed seek-reopen permanently stalls network playback (endless buffering).
  PlaybackSession.setOptionString("stream-lavf-o",
    "reconnect=1,reconnect_on_network_error=1,reconnect_on_http_error=5xx,reconnect_streamed=1," +
    "reconnect_delay_max=5,reconnect_max_retries=5,reconnect_delay_total_max=20")
  // Drop only video-output-bound late frames when rendering cannot keep up.
  PlaybackSession.setOptionString("framedrop", "vo")

  // ── Seeking ──
  val preciseSeek = playerPreferences.usePreciseSeeking.get()
  PlaybackSession.setOptionString("hr-seek", if (preciseSeek) "yes" else "no")
  PlaybackSession.setOptionString("hr-seek-framedrop", if (preciseSeek) "no" else "yes")

  // Use audio-based video sync for better frame pacing with 4K HDR content.
  PlaybackSession.setOptionString("video-sync", "audio")

  // Anime4K shader initialization (MUST be in initOptions, not after file load!)
  if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.ANIME4K)) {
    applyAnime4KShaders(backend.vo, backend.gpuApi)
  }
  // HDR Toys shaders (loaded after Anime4K so they append in the correct order)
  if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) {
    applyHdrToysMode(hdrScreenMode, hdrPipelineReady)
  }

  setupSubtitlesOptions()                                                 // ⭐ ~45 writes (:490)
  setupAudioOptions()                                                     // 6 writes (:477)
  YtdlpManager.setupMpvOptions(context, ytdlPreferences, subtitlesPreferences)   // ⭐⭐ ONLINE ONLY
}
```

#### Renderer selection — `selectRenderBackend()` (`:703-767`)

```kotlin
private fun selectRenderBackend(ignoreForcedOpenGlFallback: Boolean = false): RenderBackendSelection {
  val anime4kEnabled = decoderPreferences.enableAnime4K.get() &&
                       (decoderPreferences.anime4kMode.get() != "OFF")
  val gpuNextEnabled = decoderPreferences.gpuNext.get()
  val vulkanEnabled = shouldUseVulkan(ignoreForcedOpenGlFallback)

  if (anime4kEnabled && gpuNextEnabled && !vulkanEnabled)
    return RenderBackendSelection("gpu", "opengl", "android", "Anime4K with gpu-next but without Vulkan…")
  if (gpuNextEnabled && vulkanEnabled)
    return RenderBackendSelection("gpu-next", "vulkan", "androidvk", "…")
  if (gpuNextEnabled)
    return RenderBackendSelection("gpu-next", "opengl", "android", "gpu-next without Vulkan…")
  if (vulkanEnabled)
    return RenderBackendSelection("gpu", "vulkan", "androidvk", "…")
  return RenderBackendSelection("gpu", "opengl", "android", "…")
}
```

`shouldUseVulkan` (`:682-694`) → `VulkanCapabilities.isDeviceSupported`. That is
**memoized** at `VulkanCapabilities.kt:28-41`:

```kotlin
@Volatile private var cachedDeviceSupport: Boolean? = null

fun isDeviceSupported(context: Context): Boolean {
  cachedDeviceSupport?.let { return it }
  return resolveDeviceSupport(context).also { cachedDeviceSupport = it }
}
```

The KDoc notes the probe costs two binder IPCs and "used to run uncached twice per player
open". ✅ Already fixed.

#### ⭐⭐ `YtdlpManager.setupMpvOptions()` — the online-only block (`:446-573`)

This runs **on every player open, for every local video**:

```kotlin
fun setupMpvOptions(context: Context, ytdlPreferences: YtdlpPreferences,
                    subtitlesPreferences: SubtitlesPreferences) {
  val nativeLibDir = context.applicationInfo.nativeLibraryDir
  val ytdlBinaryPath = File(nativeLibDir, "libytdl.so").absolutePath
  val ytdlDir = getYtdlDir(context).absolutePath
  val ytDlpScriptPath = File(ytdlDir, "yt-dlp").absolutePath
  val pythonPath = File(nativeLibDir, "libpython.so").absolutePath
  val quickJsPath = File(nativeLibDir, "libqjs.so").absolutePath

  // Set environment variables for the subprocesses started by libmpv
  try {
    Os.setenv("YTDL_PYTHON", pythonPath, true)
    Os.setenv("YTDL_SCRIPT", ytDlpScriptPath, true)
    Os.setenv("PYTHONHOME", ytdlDir, true)
    Os.setenv("PYTHONPATH", "$ytdlDir/python313.zip:$ytdlDir:$nativeLibDir", true)
    Os.setenv("SSL_CERT_FILE", File(context.filesDir, "cacert.pem").absolutePath, true)

    val currentPath = runCatching { Os.getenv("PATH") }.getOrNull()
    val newPath = if (currentPath.isNullOrBlank()) nativeLibDir else "$nativeLibDir:$currentPath"
    Os.setenv("PATH", newPath, true)

    val currentLd = runCatching { Os.getenv("LD_LIBRARY_PATH") }.getOrNull()
    val newLd = if (currentLd.isNullOrBlank()) nativeLibDir else "$nativeLibDir:$currentLd"
    Os.setenv("LD_LIBRARY_PATH", newLd, true)

    Log.d(TAG, "Environment variables set for ytdl bridge")
  } catch (e: Exception) {
    Log.e(TAG, "Failed to set environment variables", e)
  }

  val ytdlFile = File(ytdlDir, "yt-dlp")
  if (!ytdlFile.exists()) Log.w(TAG, "yt-dlp not found in ${ytdlFile.absolutePath}. Subprocess will fail until installed.")
  if (!File(quickJsPath).exists()) Log.w(TAG, "QuickJS runtime not found at $quickJsPath. …")

  val storedSettings = YtdlpOptionSettings.fromPreferences(ytdlPreferences, subtitlesPreferences)
  val settings = storedSettings.copy(
    cookiesFile = storedSettings.cookiesFile.ifBlank { AndroidCookieJar.playbackCookieFile(context).absolutePath },
    javascriptRuntime = "quickjs:$quickJsPath",
  )
  val resolvedOptions = YtdlpOptionsBuilder.build(settings)
  val ua = ytdlPreferences.customUserAgent.get().ifBlank { YtdlpOptionsBuilder.DEFAULT_USER_AGENT }
  val allFormats = "yes"

  // Keep a generated fallback for ytdl_hook. This file is app-owned compatibility state…
  try {
    val scriptOptsDir = File(context.filesDir, "script-opts")
    if (!scriptOptsDir.exists()) scriptOptsDir.mkdirs()
    val ytdlConf = File(scriptOptsDir, "ytdl_hook.conf")
    val existingContent = ytdlConf.takeIf(File::isFile)?.readText().orEmpty()      // ⚠ sync read
    val generatedConfig = existingContent.startsWith(GENERATED_HOOK_CONFIG_MARKER) ||
                          isLegacyGeneratedHookConfig(existingContent)

    when {
      existingContent.isNotBlank() && !generatedConfig ->
        Log.d(TAG, "Preserving user-supplied ytdl_hook.conf")
      else -> {
        val confLines = buildList {
          add(GENERATED_HOOK_CONFIG_MARKER)
          add("ytdl_path=$ytdlBinaryPath")
          add("all_formats=$allFormats")
          add("force_all_formats=yes")
          add("try_ytdl_first=yes")
          add("exclude=$DIRECT_MEDIA_EXCLUDE")
        }
        ytdlConf.writeText(confLines.joinToString("\n", postfix = "\n"))          // ⚠ sync write
        Log.d(TAG, "Created generated ytdl_hook.conf at ${ytdlConf.absolutePath}")
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "Failed to create ytdl_hook.conf", e)
  }

  // Apply options to MPV core
  PlaybackSession.setIntegrationOptionString("ytdl", "yes")

  // These values are part of mpvRx's bundled bridge contract. They intentionally bypass
  // preference ownership so a broad script-opts override cannot remove half of the integration.
  PlaybackSession.setIntegrationOptionString("script-opts-append", "ytdl_hook-path=$ytdlBinaryPath")
  PlaybackSession.setIntegrationOptionString("script-opts-append", "ytdl_hook-ytdl_path=$ytdlBinaryPath")
  PlaybackSession.setIntegrationOptionString("script-opts-append", "ytdl_hook-all_formats=$allFormats")
  PlaybackSession.setIntegrationOptionString("script-opts-append", "ytdl_hook-force_all_formats=yes")
  PlaybackSession.setIntegrationOptionString("script-opts-append", "ytdl_hook-try_ytdl_first=yes")
  // Skip yt-dlp for direct media/manifest URLs (.m3u8/.mpd/.mp4/.ts/…). Without this,
  // ytdl_hook intercepts every http(s) URL and routes it through yt-dlp's generic
  // extractor, which chokes on tokenized HLS/CDN links — so mpv never falls back to
  // ffmpeg's native HLS demuxer and playback fails (while MX Player/VLC play it fine).
  PlaybackSession.setIntegrationOptionString("script-opts-append", "ytdl_hook-exclude=$DIRECT_MEDIA_EXCLUDE")

  val ytdlFormat = resolvedOptions.format
  if (ytdlFormat.isNotBlank()) PlaybackSession.setOptionString("ytdl-format", ytdlFormat)

  // Global User-Agent to avoid blocks at the network level
  PlaybackSession.setOptionString("user-agent", ua)                             // ⚠ GLOBAL

  Log.d(TAG, "Setting ytdl-format to: $ytdlFormat")
  Log.d(TAG, "Setting ytdl-raw-options to: ${resolvedOptions.rawOptions}")
  PlaybackSession.setOptionString("ytdl-raw-options", resolvedOptions.rawOptions)
  PlaybackSession.setIntegrationOptionString("script-opts-append", "ytdl_hook-user_agent=\"$ua\"")
  Log.d(TAG, "MPV ytdl options set. Binary: $ytdlBinaryPath")
}
```

**Plain meaning:** it builds the bridge that lets mpv shell out to a Python-based yt-dlp
binary (plus a bundled QuickJS runtime) to resolve YouTube-style *page* URLs into direct
stream URLs. **A local `/storage/…/movie.mkv` will never use any of it.**

⚠️ **It cannot simply be deleted.** Two hard constraints:

1. `ytdl=yes` + `script-opts-append` must be set **before `MPVLib.init()`**
   (`PlaybackSession.kt:363`), because that is when libmpv loads its Lua scripts.
2. The core is **process-wide**. A local file now → YouTube 30 seconds later must work.

`PlaybackSession.setIntegrationOptionString` uses
`withCore(-1, allowInitializing = true)` (`PlaybackSession.kt:1137-1140` → `:2121-2131`):

```kotlin
private inline fun <T> withCore(default: T, allowInitializing: Boolean = true, block: () -> T): T =
  nativeLock.withLock {
    if (!initialized && !(allowInitializing && _state.value.phase == PlaybackPhase.INITIALIZING)) {
      return@withLock default
    }
    block()
  }
```

So these writes succeed in the `INITIALIZING` window **or** once `initialized = true` —
but `script-opts-append` set *after* init will not retro-wire an already-loaded script.
**Therefore the correct shape is: apply once per core, lazily, on the first item that
needs it — before that item's `loadfile`.**

#### `setupAudioOptions()` — `:477-487`

```kotlin
private fun setupAudioOptions() {
  // Let mpv resolve the common case during demuxer initialization. TrackSelector still applies
  // title-based commentary/description filtering after load when mpv's choice needs correction.
  PlaybackSession.setOptionString("alang", audioPreferences.preferredLanguages.get().toMpvLanguageList())
  PlaybackSession.setOptionString("audio-display", "embedded-first")
  PlaybackSession.setOptionString("audio-delay", (audioPreferences.defaultAudioDelay.get() / 1000.0).toString())
  PlaybackSession.setOptionString("audio-pitch-correction", audioPreferences.audioPitchCorrection.get().toString())
  PlaybackSession.setOptionString("volume-max", (audioPreferences.volumeBoostCap.get() + 100).toString())
  // Prevent automatic volume normalization when downmixing multi-channel audio
  PlaybackSession.setOptionString("audio-normalize-downmix", "no")
}
```

#### `setupSubtitlesOptions()` — `:490-584` (~45 writes)

All preference reads → option writes. Notable: `slang`, `sub-auto`, `sub-file-paths`,
`subs-fallback`, `sub-fonts-dir`, `sub-codepage`, `embeddedfonts`, `sub-font-provider`,
`sub-vsfilter-bidi-compat`, `sub-delay`/`sub-speed`/`secondary-sub-delay`,
`sub-ass-override` ×2, `blend-subtitles`, then a full typography block (`sub-font-size`,
`sub-bold`, `sub-italic`, `sub-justify`, `sub-color`, `sub-back-color`, `sub-border-color`,
`sub-shadow-color`, `sub-border-size`, `sub-border-style`, `sub-shadow-offset`,
`sub-scale`, `sub-pos`, `sub-scale-by-window`, `sub-use-margins`,
`secondary-sub-scale`, `secondary-sub-pos`).

**This is the single largest block of serial JNI in `initOptions`, and a local file needs
all of it** (local files absolutely can carry subtitles). Not network-gatable — but
cacheable.

#### `postInitOptions()` — `:335-350`

```kotlin
override fun postInitOptions() {
  applyOsdSafeAreaMargins()
  when (decoderPreferences.debanding.get()) {
    Debanding.None -> {}
    Debanding.CPU -> PlaybackSession.command("vf", "add", "@deband:gradfun=radius=12")
    Debanding.GPU -> PlaybackSession.setOptionString("deband", "yes")
  }
  advancedPreferences.enabledStatisticsPage.get().let {
    if (it in 1..5) {
      PlaybackSession.command("script-binding", "stats/display-stats-toggle")
      PlaybackSession.command("script-binding", "stats/display-page-$it")
    }
  }
}
```

### 5.9 `observeProperties()` — 26 observers (`:443-475`)

```kotlin
private val observedProps = mapOf(
  "pause" to MPV_FORMAT_FLAG,
  "paused-for-cache" to MPV_FORMAT_FLAG,
  "cache-buffering-state" to MPV_FORMAT_INT64,
  "demuxer-cache-duration" to MPV_FORMAT_DOUBLE,
  "demuxer-cache-time" to MPV_FORMAT_DOUBLE,
  "network" to MPV_FORMAT_FLAG,
  "video-params/aspect" to MPV_FORMAT_DOUBLE,
  "video-params/w" to MPV_FORMAT_INT64,
  "video-params/h" to MPV_FORMAT_INT64,
  "container-fps" to MPV_FORMAT_DOUBLE,
  "eof-reached" to MPV_FORMAT_FLAG,
  "user-data/mpvrx/show_text" to MPV_FORMAT_STRING,
  "user-data/mpvrx/toggle_ui" to MPV_FORMAT_STRING,
  "user-data/mpvrx/show_panel" to MPV_FORMAT_STRING,
  "user-data/mpvrx/set_button_title" to MPV_FORMAT_STRING,
  "user-data/mpvrx/reset_button_title" to MPV_FORMAT_STRING,
  "user-data/mpvrx/toggle_button" to MPV_FORMAT_STRING,
  "user-data/mpvrx/seek_by" to MPV_FORMAT_STRING,
  "user-data/mpvrx/seek_to" to MPV_FORMAT_STRING,
  "user-data/mpvrx/seek_by_with_text" to MPV_FORMAT_STRING,
  "user-data/mpvrx/seek_to_with_text" to MPV_FORMAT_STRING,
  "user-data/mpvrx/software_keyboard" to MPV_FORMAT_STRING,
  // Curl bridge: scripts write a JSON request here; response is written to curl_response
  "user-data/mpvrx/curl_request" to MPV_FORMAT_STRING,
  "user-data/mpvrx/curl_response" to MPV_FORMAT_STRING,
  "user-data/mpv/console/open" to MPV_FORMAT_FLAG,
  "sub-text" to MPV_FORMAT_STRING,
  "sub-scale" to MPV_FORMAT_DOUBLE,
)

override fun observeProperties() {
  for ((name, format) in observedProps) PlaybackSession.observeProperty(name, format)
}
```

### 5.10 Post-init Activity wiring (`:710-758`)

```kotlin
externalDisplayManager = ExternalDisplayManager(this) { active -> onExternalDisplayStateChanged(active) }
  .also { it.enabled = playerPreferences.externalDisplayProjection.get(); it.start() }

// Construct the Activity-scoped adapter only after the process-wide native core exists;
// its StateFlow declarations register native properties during ViewModel initialization.
viewModel.attachHost(this)
viewModelHostAttached = true
viewModel.onMpvCoreInitialized()
MediaPlaybackService.createNotificationChannel(this)
setupAudio()
setupBackPressHandler()
setupVideoAmbientBackground()      // :1153
setupPlayerControls()              // :1132
setupVideoTransformObserver()
setupAudioPlayerViewObserver()
setupMediaSession()                // :6779
observePlaybackSessionQueue()
observeTorrentStreamingState()
```

`PlayerViewModel.kt:2592-2599`:

```kotlin
fun onMpvCoreInitialized() {
  _isMpvCoreReady.value = true
  scheduleAmbientUpdate(0)
  startMpvStateCollectors()
  isMpvReadyForCustomButtons = true
  reloadCustomButtonsScript("mpv_core_initialized")
  startAndroidSystemInfoBridge()
}
```

`setupPlayerControls()` — `:1132-1151`:

```kotlin
private fun setupPlayerControls() {
  binding.controls.setContent {
    MpvrxTheme {
      Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().graphicsLayer {
          alpha = PlayerArtworkTransitions.contentAlpha(PlayerArtworkDestination.FULL)
        }) {
          PlayerControls(viewModel = viewModel, onBackPress = ::handleBackPress, modifier = Modifier)
        }
        PlayerArtworkTransitionOverlay(PlayerArtworkDestination.FULL)
      }
    }
  }
}
```

`PlayerControls.kt:191` declares ~60 `collectAsState()` / `collectAsStateWithLifecycle()`
hooks. `setupVideoAmbientBackground()` (`:1153-1225`) is a **second** `ComposeView` with
~12 more.

### 5.11 Playlist resolution (`:740-812`)

```kotlin
playlistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
playlistIndex = intent.getIntExtra("playlist_index", -1).takeIf { it >= 0 } ?: intent.getIntExtra("playlistIndex", 0)
loadNetworkPlaylistMetadata(intent)

playlist = if (SDK_INT >= TIRAMISU) intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
           else intent.getParcelableArrayListExtra("playlist") ?: emptyList()

val preparedPlaybackQueue = playlist.isEmpty() && restorePreparedPlaybackQueue(intent)
val hasReusableSavedPlaybackSession = hasValidSavedPlaybackSession()

if (playlist.isNotEmpty()) {
  playlistIndex = playlistIndex.coerceIn(0, playlist.lastIndex)
  restoredSavedPlaylistItem = applyPendingSavedSelection(playlist)
  playlistWindowOffset = 0; playlistTotalCount = playlist.size
  viewModel.refreshPlaylistItems()
}

// ✅ async
if (playlist.isEmpty() && playlistId != null && !hasReusableSavedPlaybackSession) {
  lifecycleScope.launch(Dispatchers.IO) {
    val pid = playlistId ?: return@launch
    try { loadPlaylistById(pid = pid, sourceIntent = intent, logPrefix = "Loaded") }
    catch (e: Exception) { Log.e(TAG, "Failed to load playlist from database", e) }
  }
}

// ⚠ SYNCHRONOUS folder walk
if (playlist.isEmpty() && playlistId == null &&
    !intent.hasExtra(AudiobookPlayback.EXTRA_BOOK_ID) &&
    playerPreferences.playlistMode.get() && !hasReusableSavedPlaybackSession) {
  val path = parsePathFromIntent(intent)
  if (path != null) generatePlaylistFromFolder(path)
}

fileName = getFileName(intent)
if (fileName.isBlank()) fileName = intent.data?.lastPathSegment ?: "Unknown Video"
legacyMediaIdentifier = getLegacyMediaIdentifier(intent, fileName)
mediaIdentifier = getMediaIdentifier(intent, fileName)

// A validated process-local session still owns its queue. Do not clear or republish it before
// the saved-state attachment below has a chance to claim that exact current item.
if (intent.action != MediaPlaybackService.ACTION_OPEN_PLAYER &&
    !preparedPlaybackQueue && !hasReusableSavedPlaybackSession) {
  if (playlist.isEmpty()) PlaybackSession.clearQueue() else publishPlaylistToSession()
}

// Set HTTP headers (including referer) BEFORE playing the file
setHttpHeadersFromExtras(intent.extras)
```

### 5.12 Hand-off to the loader (`:817-848`)

```kotlin
val attachedToCurrentSession = attachToCurrentPlaybackSessionIfRequested() || attachToSavedPlaybackSessionIfValid()
if (!attachedToCurrentSession && !restoredSavedPlaylistItem && playlist.isNotEmpty()) pendingSavedPlaylistSelection = null
val awaitingRoomPlaylistRestore = !attachedToCurrentSession && pendingSavedPlaylistSelection != null &&
                                 playlist.isEmpty() && playlistId != null
if (!attachedToCurrentSession && restoredSavedPlaylistItem) {
  pendingSavedPlaylistSelection = null
  loadPlaylistItemInternal(playlistIndex, saveCurrentPlaybackState = false)
} else if (!attachedToCurrentSession && !awaitingRoomPlaylistRestore) {
  getPlayableUri(intent)?.let { playableUri ->
    currentPlayableUri = playableUri
    isReady = false
    viewModel.onVideoLoadStarted()                     // artwork overlay starts covering the video
    val originalUri = extractUriFromIntent(intent)
    val shouldExpandM3u = M3uPlaybackPolicy.shouldExpandInApp(
      playableUri = playableUri, originalUri = originalUri?.toString(), fileName = fileName,
      mimeType = intent.type, hasExistingPlaylist = playlist.isNotEmpty(), hasPlaylistId = playlistId != null,
    )
    if (shouldExpandM3u) startMediaLoad(playableUri, originalUri?.toString(), expandM3u = true)
    else                 startMediaLoad(playableUri, originalUri?.toString())
  }
}
setupCastPlayback()
if (isKnownAudioLaunch(intent) || playerPreferences.orientation.get() != PlayerOrientation.Video) setOrientation()
viewModel.applyPersistedShuffleState()
```

`getPlayableUri()` — `:4019-4037`:

```kotlin
private fun getPlayableUri(intent: Intent): String? {
  extractUriFromIntent(intent)?.toString()
    ?.takeIf { source -> isTorrentSource(source, intent.type) }
    ?.let { return it }

  val uri = parsePathFromIntent(intent)
  if (uri == null) {
    Log.e(TAG, "Unable to resolve playable media URI: ${extractUriFromIntent(intent)}")
    viewModel.onVideoLoadCompleted()
    viewModel.showToast(getString(R.string.toast_playback_load_failed))
    return null
  }
  // PlaybackSession.resolvePlayableUri performs exactly this content:// resolution on its worker
  // thread for every load, so it is deliberately NOT repeated here on the main thread…
  return uri
}
```

`onCreate` then launches three `lifecycleScope` collectors (`:860-895`) and calls
`setLayoutInDisplayCutoutModeIfSupported(shortEdges = true)` (`:897`).

---

## 6. Phase 3 — Surface arrives, `vo` flips on

Android delivers `surfaceCreated` on the next frame traversal — i.e. **after `onCreate`
returns**. This is exactly why `MPVLib.init()` blocking in `onCreate` delays MediaCodec
startup.

`MPVView.kt:400-441`:

```kotlin
override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
  PlaybackSession.resizeSurface(width, height, owner = this)
  applyFrameRate()
}

override fun surfaceCreated(holder: SurfaceHolder) {
  if (!surfaceBindingEnabled) return
  isSurfaceReady = PlaybackSession.bindSurface(holder.surface, width, height, this,
    ownerIsActive = { surfaceBindingEnabled })
  applyFrameRate()
  post { if (isSurfaceReady && holder.surface.isValid) onSurfaceReady?.invoke() }
}

override fun surfaceDestroyed(holder: SurfaceHolder) {
  isSurfaceReady = false
  PlaybackSession.unbindSurface(this)
}

private fun applyFrameRate() {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    val fps = PlaybackSession.getPropertyDouble("container-fps") ?: 0.0
    if (fps > 0.0 && holder?.surface?.isValid == true) {
      try {
        holder.surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
      } catch (e: Exception) { Log.e(TAG, "Failed to set frame rate on surface", e) }
    }
  }
}
```

`PlaybackSession.bindSurface()` — `:417-449`:

```kotlin
fun bindSurface(surface: Surface, width: Int? = null, height: Int? = null,
                owner: Any, ownerIsActive: () -> Boolean = { true }): Boolean =
  withCore(default = false) {
    if (!ownerIsActive() || !surface.isValid) return@withCore false

    // Surface ownership is a renderer concern only. Full player, mini player, PiP and Activity
    // recreation all hand the same live media session between Android Surfaces. Never change
    // `vid` during that handoff or mpv can discard cached packets and refetch normal HTTP data.
    if (_state.value.surfaceAttached && attachedSurfaceOwner !== owner) detachRendererSurfaceLocked()

    MPVLib.attachSurface(surface)                                    // native ANativeWindow
    width?.takeIf { it > 0 }?.let { w -> height?.takeIf { it > 0 }?.let { h ->
      MPVLib.setPropertyString("android-surface-size", "${w}x$h") } }

    MPVLib.setOptionString("force-window", "yes")
    MPVLib.setPropertyString("vo", desiredVideoOutput)               // ⭐ null → gpu-next / gpu
    attachedSurfaceOwner = owner
    updateState { it.copy(surfaceAttached = true) }
    restoreSuspendedVideoTrackLocked()
    if (deferredVideoSelectionGeneration == _state.value.generation) {
      MPVLib.setPropertyString("vid", "auto")
      deferredVideoSelectionGeneration = null
    }
    true
  }
```

Related surface helpers:

```kotlin
// :451-462
fun resizeSurface(width: Int, height: Int, owner: Any): Boolean = withCore(default = false) {
  if (width <= 0 || height <= 0 || attachedSurfaceOwner !== owner || !_state.value.surfaceAttached)
    return@withCore false
  MPVLib.setPropertyString("android-surface-size", "${width}x$height")
  true
}

// :464-474
fun unbindSurface(owner: Any): Boolean = withCore(default = false) {
  if (attachedSurfaceOwner !== owner || !_state.value.surfaceAttached) return@withCore false
  // Losing the Surface (home button, app switch, Activity recreation) is a renderer event, not a
  // media event. Deselecting `vid` here makes mpv's demuxer drop every cached video packet…
  detachRendererSurfaceLocked()
  true
}

// :480-486
private fun detachRendererSurfaceLocked() {
  runCatching { MPVLib.setPropertyString("vo", "null") }
  runCatching { MPVLib.setOptionString("force-window", "no") }
  runCatching { MPVLib.detachSurface() }
  attachedSurfaceOwner = null
  updateState { it.copy(surfaceAttached = false) }
}

// :488-495
fun setVideoOutput(videoOutput: String) {
  desiredVideoOutput = videoOutput
  withCore(Unit, allowInitializing = true) {
    // Track selection does not require an Android Surface, but a GPU video output does.
    // bindSurface() performs the real null -> configured output transition later.
    MPVLib.setOptionString("vo", if (_state.value.surfaceAttached) videoOutput else "null")
  }
}
```

---

## 7. Phase 4 — `startMediaLoad` prep lane

`PlayerActivity.kt:5703-5982`. **Already off the main thread** (`mediaLoadDispatcher`).

```kotlin
private fun startMediaLoad(playableUri: String, originalUri: String? = null, expandM3u: Boolean = false) {
  if (!ownsPlaybackSession()) return
  mediaLoadJob?.cancel()
  cancelPlaybackLoadRecovery()
  playWhenFileLoaded = true

  val sourceIntent = Intent(intent)
  val requestedFileName = fileName
  val requestedMediaIdentifier = mediaIdentifier
  val requestedLegacyMediaIdentifier = legacyMediaIdentifier
  val requestedPlaylistIndex = playlistIndex
  val requestedQueueItem = PlaybackSession.queue.value.items.getOrNull(requestedPlaylistIndex)
  val requestGeneration = mediaRequestGeneration
  val requestedSource = originalUri ?: extractUriFromIntent(sourceIntent)?.toString() ?: playableUri
  val requestedHeaders = buildPlaybackHeaders(
    Uri.parse(requestedSource),
    PlaybackHttpHeaders.fromFlatPairs(sourceIntent.extras?.getStringArray("headers")),
    requestedQueueItem?.headers.orEmpty(),
  )
  val requestedTorrentFileIndex = sourceIntent.getIntExtra("torrent_file_index", -1).takeIf { it >= 0 }
  val isTorrentRequest = isTorrentSource(requestedSource, sourceIntent.type) ||
                         isTorrentSource(playableUri, sourceIntent.type)

  mediaLoadJob = lifecycleScope.launch(mediaLoadDispatcher) {
    try {
      // ① Audiobook recovery
      val bookId = sourceIntent.getLongExtra(AudiobookPlayback.EXTRA_BOOK_ID, -1L)
      if (sourceIntent.getBooleanExtra("internal_launch", false) && bookId > 0 &&
          requestedQueueItem?.audiobook?.bookId != bookId) {
        val recovered = AudiobookPlayback.prepareQueue(bookId,
          sourceIntent.getLongExtra(AudiobookPlayback.EXTRA_TRACK_ID, -1L).takeIf { it > 0 })
        /* …withContext(Main) { restore queue, update intent… } */
        issuePlaybackLoad(bookItem, attempt = 0, requestGeneration = requestGeneration)
        return@launch
      }

      // ② Torrent
      if (!isTorrentRequest) torrentStreamingEngine.stopStream()          // ⚠ runs for local too
      if (isTorrentRequest && !advancedPreferences.enableP2pStreaming.get()) {
        torrentStreamingEngine.stopStream()
        playWhenFileLoaded = false
        withContext(Dispatchers.Main) {
          ensureCurrentMediaRequest(requestGeneration)
          viewModel.onVideoLoadCompleted()
          viewModel.showToast(getString(R.string.toast_torrent_streaming_disabled))
        }
        return@launch
      }
      if (isTorrentRequest) {
        val result = torrentStreamingEngine.startStream(TorrentStreamRequest(
          source = requestedSource, fileIndex = requestedTorrentFileIndex,
          preparationId = sourceIntent.getStringExtra("torrent_preparation_id")))
        /* resolvedPlayableUri = result.localUrl  ← local HTTP loopback */
        /* …persist torrent file catalog, update intent on Main… */
      }

      // ③ M3U expansion (local .m3u files SHOULD expand)
      if (expandM3u && loadDynamicM3uPlaylist(uriString = originalUri ?: playableUri,
            sourceIntent = sourceIntent, requestGeneration = requestGeneration)) {
        withContext(Dispatchers.Main) {
          ensureCurrentMediaRequest(requestGeneration)
          if (playlist.isNotEmpty()) loadPlaylistItem(playlistIndex.coerceIn(0, playlist.lastIndex))
        }
        return@launch
      }

      // ④ Build the PlaybackItem
      val networkPath = sourceIntent.getStringExtra("network_file_path")
      val networkConnectionId = sourceIntent.getLongExtra("network_connection_id", -1L)
      val networkSource = if (!networkPath.isNullOrBlank() && networkConnectionId != -1L)
        NetworkPlaybackSource(networkConnectionId, networkPath) else null

      val item = if (!isTorrentRequest) requestedQueueItem?.copy(
                      playableUri = resolvedPlayableUri, headers = requestedHeaders)
                 else null
        ?: PlaybackItem(
          stableId = resolvedMediaIdentifier.ifBlank { PlaybackIdentity.forUri(resolvedOriginalUri) },
          originalUri = resolvedOriginalUri,
          playableUri = resolvedPlayableUri,
          title = resolvedFileName,
          mimeType = resolvedMimeType,
          headers = requestedHeaders,
          networkSource = networkSource,
          torrentFileIndex = torrentResult?.selectedFile?.index,
        )

      // ⑤ ⭐ Two pre-load fetches started together — ALREADY CORRECTLY GATED
      // Fetch artwork for music streaming URLs (YouTube / YouTube Music via oEmbed) and stage
      // libmpv's cookie file. Both are needed before loadfile…, but neither depends on the other,
      // so they are started together and joined at their point of use instead of one after the other.
      val artworkDeferred =
        if (item.artworkUri.isNullOrBlank() && HttpUtils.isMusicStreamingUrl(resolvedOriginalUri))
          async(Dispatchers.IO) { HttpUtils.fetchMusicStreamingArtwork(resolvedOriginalUri) }
        else null

      val cookieExportDeferred =
        sequenceOf(resolvedPlayableUri, resolvedOriginalUri)
          .firstOrNull { value -> value.startsWith("http://", true) || value.startsWith("https://", true) }
          ?.let { cookieSource ->
            // libmpv needs this file before it opens the stream, so the export cannot be deferred
            // past loadfile. A file already written during this session is current: cookies are
            // only written by OkHttp/WebView outside this Activity, so re-running the
            // CookieManager read plus the AtomicFile fsync on every load is pure overhead.
            if (AndroidCookieJar.playbackCookieFile(this@PlayerActivity).lastModified() >= sessionStartedAtMillis) null
            else async(Dispatchers.IO) {
              androidCookieJar.exportForPlayback(cookieSource, AndroidCookieJar.playbackCookieFile(this@PlayerActivity))
                .onFailure { error -> Log.w(TAG, "Failed to prepare playback cookies", error) }
            }
          }

      ensureCurrentMediaRequest(requestGeneration)
      val itemWithArtwork = artworkDeferred?.await()?.takeIf { it.isNotBlank() }
        ?.let { artwork -> item.copy(artworkUri = artwork) } ?: item

      // ⑥ Publish the queue
      if (requestedQueueItem == null || isTorrentRequest) {
        val torrentSeries = torrentResult?.takeIf { it.playableFiles.size > 1 }
        if (torrentSeries != null) {
          /* …one PlaybackItem per episode, PlaybackSession.replaceQueue(seriesItems, selectedPosition, isExplicitQueue = true)… */
        } else {
          commitMediaRequest(requestGeneration) { PlaybackSession.replaceQueue(listOf(itemWithArtwork), 0) }
        }
      }

      // libmpv reads the cookie file as it opens the stream, so this is the last join before loadfile.
      cookieExportDeferred?.await()

      // ⑦ Load
      issuePlaybackLoad(item = itemWithArtwork, attempt = 0, requestGeneration = requestGeneration,
        legacyMediaIdentifier = requestedLegacyMediaIdentifier.takeUnless { isTorrentRequest })
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      cancelPlaybackLoadRecovery()
      playWhenFileLoaded = false
      isAdvancingAtEof = false
      Log.e(TAG, "Failed to load media URL", error)
      withContext(Dispatchers.Main) {
        if (!isCurrentMediaRequest(requestGeneration)) return@withContext
        viewModel.onVideoLoadCompleted()
        viewModel.showToast(/* torrent vs generic failure message */)
      }
    }
  }
}
```

---

## 8. Phase 5 — `issuePlaybackLoad` parallel join

`PlayerActivity.kt:5984-6094`. **This part is already well designed.**

```kotlin
private suspend fun issuePlaybackLoad(
  item: PlaybackItem,
  attempt: Int,
  requestGeneration: Long,
  legacyMediaIdentifier: String? = null,
  ytdlFormat: String? = null,
  positionRestoreOverride: PlaybackPositionRestoreOverride? = null,
) {
  ensureCurrentMediaRequest(requestGeneration)

  val scriptRestore = if (intent.getStringExtra(EXTRA_SCRIPT_RESTORE_MEDIA_ID) == item.stableId)
    PlaybackPositionRestoreOverride(
      positionSeconds = intent.getDoubleExtra(EXTRA_SCRIPT_RESTORE_POSITION, Double.NaN)
        .takeIf { it.isFinite() && it >= 0 },
      paused = intent.getBooleanExtra(EXTRA_SCRIPT_RESTORE_PAUSED, false)) else null

  // A snapshot jump names an exact second to land on. Consumed once, so a later track change in
  // the same Activity does not drag every subsequent load back to the snapshot's timestamp.
  val snapshotJump = intent.getDoubleExtra(EXTRA_START_POSITION_SECONDS, Double.NaN)
    .takeIf { it.isFinite() && it >= 0 }
    ?.also { intent.removeExtra(EXTRA_START_POSITION_SECONDS) }
    ?.let { PlaybackPositionRestoreOverride(positionSeconds = it, paused = false) }

  val effectivePositionOverride = positionRestoreOverride ?: scriptRestore ?: snapshotJump
    ?: AudiobookPlayback.positionForLoad(item, intent)
  val restoreSavedPosition = playerPreferences.savePositionOnQuit.get()
  val resumeMode = playerPreferences.resumePlaybackMode.get()
  // Only Always Resume may use the fast load-local start option. Never starts at zero.
  val needsSavedPositionLookup = effectivePositionOverride == null && restoreSavedPosition &&
                                 resumeMode == ResumePlaybackMode.Always && !item.isDefinitelyAudioOnly()

  ensureCurrentMediaRequest(requestGeneration)
  val requiresYtdlp = sequenceOf(item.originalUri, item.playableUri).any(YtdlpManager::requiresYtdlp)

  // The yt-dlp runtime prep (multi-MB runtime copy plus a Python subprocess for web sources),
  // the previous-session stop wait and the resume-position database read have no ordering
  // dependency on each other, so they are started together and joined only where their result
  // is needed: pre-load latency becomes max(...) instead of sum(...).
  val generation = coroutineScope {
    val ytdlpReadyDeferred = async {
      YtdlpManager.prepareForPlayback(this@PlayerActivity, item.playableUri) { line ->
        line.trim().takeIf { it.isNotEmpty() }?.let { message -> Log.d(TAG, message) }
      }
    }
    val stopCompletedDeferred = async { PlaybackSession.awaitStopCompletion() }
    val savedPositionDeferred = if (needsSavedPositionLookup) async {
      resolvePlaybackState(item.stableId, legacyMediaIdentifier)?.lastPosition?.takeIf { it > 3 }?.toDouble()
    } else null

    val initialPositionSeconds = effectivePositionOverride?.positionSeconds
      ?.takeIf { it.isFinite() && it > 0.0 } ?: savedPositionDeferred?.await()

    if (!ytdlpReadyDeferred.await()) throw IllegalStateException("yt-dlp could not be prepared for web playback")
    ensureCurrentMediaRequest(requestGeneration)
    if (!stopCompletedDeferred.await()) throw IllegalStateException("Timed out waiting for previous playback to stop")
    ensureCurrentMediaRequest(requestGeneration)

    PlaybackSession.load(
      item = item,
      restoreSavedPosition = restoreSavedPosition,
      positionRestoreOverride = effectivePositionOverride,
      initialPositionSeconds = initialPositionSeconds,
      flattenEditions = requiresYtdlp && !MpvConfigOverridePolicy.isOwnedByMpvConf("flatten-editions"),
      commit = { nativeLoad ->
        PlaybackActivityOwner.runIfOwner(playbackOwnerToken, -1L) {
          if (requestGeneration != mediaRequestGeneration) -1L
          else {
            if (requiresYtdlp) PlaybackSession.setPropertyString("ytdl-format", ytdlFormat.orEmpty())
            nativeLoad()
          }
        }
      },
    )
  }
  if (generation < 0L) {
    ensureCurrentMediaRequest(requestGeneration)
    throw IllegalStateException("libmpv core is unavailable")
  }
  if (item.audiobook != null) intent.removeExtra(AudiobookPlayback.EXTRA_POSITION_MS)
  if (scriptRestore != null) {
    intent.removeExtra(EXTRA_SCRIPT_RESTORE_MEDIA_ID)
    intent.removeExtra(EXTRA_SCRIPT_RESTORE_POSITION)
    intent.removeExtra(EXTRA_SCRIPT_RESTORE_PAUSED)
  }

  val request = PendingMediaLoadRecovery(item, generation, attempt, requestGeneration,
    legacyMediaIdentifier, ytdlFormat, positionRestoreOverride)
  withContext(Dispatchers.Main) { armPlaybackLoadRecovery(request) }
}
```

`YtdlpManager.prepareForPlayback` (`:348-366`) **already early-returns for local** ✅:

```kotlin
suspend fun prepareForPlayback(context: Context, source: String, onLog: (String) -> Unit = {}): Boolean {
  val uri = Uri.parse(source)
  val isWebSource = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
  if (!isWebSource) return true                        // ✅ local: instant true
  return withContext(Dispatchers.IO) {
    installMutex.withLock {
      if (!prepareRuntimeAssets(context, onLog)) return@withLock false
      if (!requiresYtdlp(source) || isPlaybackRuntimeReady(context)) return@withLock true
      onLog("Preparing the current yt-dlp web playback runtime.\n")
      installYtdlp(context, onLog)
    }
  }
}
```

`YtdlpManager.requiresYtdlp` (`:114-121`) ✅:

```kotlin
fun requiresYtdlp(source: String): Boolean {
  val uri = Uri.parse(source)
  if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) return false
  return !HttpUtils.isDirectMediaUrl(uri)
}
```

Watchdog — `armPlaybackLoadRecovery` (`:6096-6141`):

```kotlin
private fun armPlaybackLoadRecovery(request: PendingMediaLoadRecovery) {
  if (!isCurrentMediaRequest(request.requestGeneration) ||
      !PlaybackSession.isCurrentGeneration(request.generation)) return
  playbackLoadWatchdogJob?.cancel()
  pendingMediaLoadRecovery = request
  when (PlaybackSession.state.value.phase) {
    PlaybackPhase.READY, PlaybackPhase.BACKGROUND -> { cancelPlaybackLoadRecovery(); return }
    PlaybackPhase.ERROR -> { retryOrFinishPlaybackLoad(request, PlaybackSession.state.value.error); return }
    PlaybackPhase.LOADING -> Unit
    else -> { cancelPlaybackLoadRecovery(); return }
  }
  playbackLoadWatchdogJob = lifecycleScope.launch {
    delay(playbackLoadTimeoutMs(request.item))
    /* …if still LOADING/ERROR for this generation → retryOrFinishPlaybackLoad(request, "Timed out while opening media")… */
  }
}
```

---

## 9. Phase 6 — `PlaybackSession.load` → `loadfile`

### 9.1 Outer `load` — `PlaybackSession.kt:845-903`

```kotlin
fun load(
  item: PlaybackItem,
  restoreSavedPosition: Boolean = false,
  positionRestoreOverride: PlaybackPositionRestoreOverride? = null,
  initialPositionSeconds: Double? = null,
  flattenEditions: Boolean = false,
  commit: ((() -> Long) -> Long)? = null,
): Long {
  val preparationStartedAt = android.os.SystemClock.elapsedRealtime()
  PlaybackPerformanceTrace.mark("MEDIA_PREPARATION_START")
  val resolved = try {
    resolvePlayableUri(item)
  } finally {
    PlaybackPerformanceTrace.mark("MEDIA_PREPARATION_END",
      "durationMs=${android.os.SystemClock.elapsedRealtime() - preparationStartedAt}")
  }
  return try {
    var previous: NetworkStreamRegistration? = null
    var previousAuxiliary = emptyList<NetworkStreamRegistration>()
    val performCommit = {
      nativeLock.withLock {
        val generation = load(playableUri = resolved.uri, item = item,
          restoreSavedPosition = restoreSavedPosition,
          positionRestoreOverride = positionRestoreOverride,
          initialPositionSeconds = initialPositionSeconds, flattenEditions = flattenEditions)
        if (generation >= 0L) {
          previous = activeNetworkStream
          activeNetworkStream = resolved.registration
          previousAuxiliary = auxiliaryNetworkStreams.values.toList()
          auxiliaryNetworkStreams.clear()
        }
        generation
      }
    }
    val generation = commit?.invoke(performCommit) ?: performCommit()
    if (generation < 0L) {
      resolved.registration?.let(::releaseNetworkStream)
      generation
    } else {
      val previousRegistration = previous
      if (previousRegistration != null && previousRegistration != resolved.registration) {
        releaseNetworkStream(previousRegistration)
      }
      previousAuxiliary.forEach(::releaseNetworkStream)
      generation
    }
  } catch (error: Throwable) {
    resolved.registration?.let(::releaseNetworkStream)
    throw error
  }
}
```

### 9.2 `resolvePlayableUri()` — five branches, all early-return for local (`:2001-2072`)

```kotlin
private fun resolvePlayableUri(item: PlaybackItem): ResolvedPlayable {
  // ① ZIP archive
  if (ZipArchiveMedia.isPlaybackUri(item.originalUri)) {
    val archive = ZipArchiveMedia.openPlayback(context, item.originalUri)
    return ResolvedPlayable(archive.uri,
      archive.descriptor?.let { NetworkStreamRegistration("archive-${streamSequence.incrementAndGet()}",
        archiveDescriptor = it) })
  }

  // ② Xtream / IPTV
  val xtreamReference = XtreamPlaybackUri.parse(item.playableUri)
  if (xtreamReference != null) {
    val proxy = XtreamStreamingProxy.getInstance()
    val streamId = "xtream-${streamSequence.incrementAndGet()}"
    return ResolvedPlayable(
      proxy.registerStream(streamId, xtreamReference, item.headers, item.mimeType ?: "application/octet-stream"),
      NetworkStreamRegistration(xtreamProxy = proxy, streamId = streamId))
  }

  // ③ SMB / NFS / FTP via NetworkStreamingProxy
  val reference = NetworkPlaybackUri.parse(item.playableUri)
    ?: item.networkSource?.let { NetworkPlaybackUri.parse(NetworkPlaybackUri.create(it.connectionId, it.relativePath)) }
  if (reference != null) {
    val proxy = NetworkStreamingProxy.getInstance()
    val streamId = "playback-${streamSequence.incrementAndGet()}"
    return ResolvedPlayable(
      proxy.registerStream(streamId, reference.connectionId, reference.path.value,
        item.mimeType ?: "application/octet-stream"),
      NetworkStreamRegistration(proxy = proxy, streamId = streamId))
  }

  // ④ HLS via HlsStreamingProxy
  if (M3uPlaybackPolicy.shouldProxyHls(item.playableUri, item.mimeType)) {
    val hlsProxy = HlsStreamingProxy.getInstance()
    val streamId = "hls-${streamSequence.incrementAndGet()}"
    return ResolvedPlayable(
      hlsProxy.registerStream(streamId, item.playableUri, item.headers, PlaybackHttpHeaders.userAgent(item.headers)),
      NetworkStreamRegistration(hlsProxy = hlsProxy, streamId = streamId))
  }

  // ⑤ fd:// descriptors are single-use
  if (item.playableUri.startsWith("fd://") && item.originalUri.startsWith("content://")) {
    val refreshedUri = Uri.parse(item.originalUri).openContentFd(context)
      ?: error("Unable to reopen content URI for playback")
    return ResolvedPlayable(refreshedUri)
  }

  // ⑥ ⭐ plain local file / http(s) — nothing to set up
  if (!item.playableUri.startsWith("content://")) return ResolvedPlayable(item.playableUri)

  // ⑦ content:// (SAF / MediaStore)
  return ResolvedPlayable(Uri.parse(item.playableUri).openContentFd(context) ?: item.playableUri)
}
```

✅ A local `/storage/x.mkv` hits line 2069 and returns immediately.

### 9.3 Inner `load` — every mpv call (`:905-1010`)

```kotlin
private fun load(
  playableUri: String,
  item: PlaybackItem? = null,
  restoreSavedPosition: Boolean = false,
  positionRestoreOverride: PlaybackPositionRestoreOverride? = null,
  initialPositionSeconds: Double? = null,
  flattenEditions: Boolean = false,
): Long {
  // ── SMB path resolution: runBlocking DB read, null for local ✅
  val smbPath = item?.networkSource?.let { source ->
    try {
      val conn = kotlinx.coroutines.runBlocking {
        KoinJavaComponent.get<NetworkRepository>(NetworkRepository::class.java)
          .getConnectionById(source.connectionId)
      }
      if (conn != null) "smb://${conn.host}/${conn.path.trim('/')}/${source.relativePath.removePrefix("/")}" else null
    } catch (_: Exception) { null }
  }

  return withCore(default = -1L) {
    if (_state.value.phase == PlaybackPhase.STOPPING) return@withCore -1L
    AudiobookPlayback.capture()
    val resolvedItem = item ?: PlaybackItem.fromUri(playableUri)
    loadedPlaybackItem = null
    if (resolvedItem.audiobook != null) AudiobookPlayback.ensureStarted()
    if (resolvedItem.audiobook == null && speedBeforeAudiobook != null) {
      if (!MpvConfigOverridePolicy.isOwnedByMpvConf("speed")) MPVLib.setPropertyDouble("speed", speedBeforeAudiobook!!.toDouble())
      speedBeforeAudiobook = null
    }

    // Select the track during demuxer initialization. Video output remains `vo=null` until a
    // Surface is attached, so cold starts do not need a post-load track reselect.
    val videoSelection = resolvedItem.videoSelection()
    val selectVideoForNewFile = videoSelection == PlaybackVideoSelection.IMMEDIATE

    // An OUTPUT Ambient shader bakes the previous video's aspect ratio into its GLSL. Because the
    // libmpv core outlives PlayerActivity, a late/cancelled Ambient job can otherwise poison the
    // next file even when the UI preference is OFF. Start every replacement load from a clean,
    // identity-scaled shader state; an enabled Ambient mode will re-append after FILE_LOADED.
    clearAmbientShadersLocked(resetDesired = true)

    // A saved video-track id belongs to the outgoing file only. Never carry it into a new load.
    suspendedVideoTrack = null
    desiredPaused = positionRestoreOverride?.paused ?: false
    clearSeekAudioGuardLocked(restoreMute = true)

    // Keep replacement/startup audio muted until mpv has restarted cleanly. FILE_LOADED can be
    // followed by saved-position and audio-track restoration; without this guard tiny fragments
    // from the pre-restore timeline can reach AudioTrack and sound like a glitch/warble.
    beginPlaybackTransitionAudioGuardLocked(canRestore = true)

    val generation = _state.value.generation + 1L
    val initialPosition = initialPositionSeconds?.takeIf { it.isFinite() && it > 0.0 }
    pendingPositionRestoreGeneration = generation.takeIf {
      positionRestoreOverride != null || (restoreSavedPosition && !resolvedItem.isDefinitelyAudioOnly())
    } ?: 0L
    pendingPositionRestoreOverride = positionRestoreOverride?.let { generation to it }
    initialPositionGeneration = generation.takeIf { initialPosition != null } ?: 0L
    val holdForPositionRestore = pendingPositionRestoreGeneration == generation
    deferredVideoSelectionGeneration = null

    updateState {
      it.copy(phase = PlaybackPhase.LOADING, generation = generation,
              paused = holdForPositionRestore || desiredPaused,
              currentItem = resolvedItem, error = null)
    }
    clearTimelinePropertiesLocked()

    // URL-specific headers are request metadata, not a global mpv preference. Always apply the
    // media UA, then restore the post-mpv.conf default for a headerless item.
    val userAgent = PlaybackHttpHeaders.userAgent(resolvedItem.headers)
    val headerFields = PlaybackHttpHeaders.toMpvHeaderFields(resolvedItem.headers)
    MPVLib.setPropertyString("user-agent", userAgent ?: defaultUserAgent.orEmpty())
    MPVLib.setPropertyString("http-header-fields", headerFields)
    MPVLib.setPropertyString("force-media-title", "")
    MPVLib.setPropertyString("user-data/mpvrx/original-path", smbPath ?: resolvedItem.originalUri)

    if (!_state.value.surfaceAttached) {                    // ⚠ Surface not up yet (usual cold start)
      MPVLib.setPropertyString("vo", "null")                //    forces vo back OFF
      MPVLib.setOptionString("force-window", "no")
    }

    // Disable the outgoing track only once this replacement request owns the native lock. Doing
    // it during asynchronous URI preparation can blank playback even when that work is cancelled.
    MPVLib.setPropertyString("vid", "no")

    val loadOptions = buildList {
      add("pause=yes")
      add(if (selectVideoForNewFile) "vid=auto" else "vid=no")
      initialPosition?.let { add("start=$it") }
      if (flattenEditions && !MpvConfigOverridePolicy.isOwnedByMpvConf("flatten-editions")) add("flatten-editions=yes")
    }.joinToString(",")

    PlaybackPerformanceTrace.mark("LOADFILE_SENT", "generation=$generation")
    MPVLib.command("loadfile", playableUri, "replace", "-1", loadOptions)      // ⭐ THE OPEN
    propBoolean.emit("pause", holdForPositionRestore || desiredPaused)
    generation
  }
}
```

⚠️ The `vo = null` at `:987-990` is a genuine race: if the Surface hasn't arrived, `vo` is
forced back to `null` **even if `bindSurface` already flipped it on**.
`deferredVideoSelectionGeneration` exists purely to patch the consequences.

---

## 10. Phase 7 — libmpv native work

Native libmpv timeline (async, off the Kotlin main thread):

```
loadfile → ffmpeg demuxer opens the file → probe streams → MediaCodec HW decoder opens
         → vo_gpu / vo_gpu_next initialises (Vulkan or GL) → first frame renders
         → MPV_EVENT_START_FILE → MPV_EVENT_FILE_LOADED → MPV_EVENT_PLAYBACK_RESTART
```

`MPV_EVENT_START_FILE` — `:1557-1571`:

```kotlin
MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
  // loadfile 'replace' commands can coalesce inside one mpv dispatch batch, in which case
  // mpv only ever starts the newest target and emits a single START_FILE for it. Any
  // per-load FIFO desyncs permanently on that skip, so the started file is always
  // attributed to the latest requested generation.
  if (_state.value.phase != PlaybackPhase.STOPPING) {
    updateState { it.copy(phase = PlaybackPhase.LOADING, activeGeneration = it.generation) }
  }
  if (supersededStopGeneration != 0L && _state.value.generation > supersededStopGeneration) {
    supersededStopGeneration = 0L
  }
  true
}
```

`MPV_EVENT_FILE_LOADED` — `:1572-1622`:

```kotlin
MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
  val current = _state.value
  if (current.phase == PlaybackPhase.STOPPING) {
    runCatching { MPVLib.command("stop") }
    return@withLock true
  }
  supersededStopGeneration = 0L
  loadedGeneration = current.generation
  pendingEofSeekGeneration = null
  loadedPlaybackItem = current.currentItem
  loadedAudiobookEnded = false
  loadedAudiobookDurationMs = ((MPVLib.getPropertyDouble("duration") ?: 0.0) * 1000).toLong().coerceAtLeast(0)
  current.currentItem?.let { AudiobookPlayback.onFileLoaded(it, current.generation) }
  val restoringPosition = pendingPositionRestoreGeneration == current.generation
  val appliedPaused = restoringPosition || desiredPaused
  // Track/decoder replacement is now complete. Apply the latest user/service intent
  // once instead of allowing pause writes to race the load operation.
  MPVLib.setPropertyBoolean("pause", appliedPaused)
  // Surface ownership is the source of truth at this boundary. Activity observers can
  // detach during recreation, and deferred-generation bookkeeping only covers loads that
  // started without video. Repair a disabled selection before exposing READY so an
  // attached player cannot remain on a black frame with audio.
  if (current.surfaceAttached && current.currentItem?.isDefinitelyAudioOnly() != true) {
    val selectedVideoTrack = MPVLib.getPropertyInt("vid")
    if (selectedVideoTrack == null || selectedVideoTrack <= 0) MPVLib.setPropertyString("vid", "auto")
    if (deferredVideoSelectionGeneration == current.generation) deferredVideoSelectionGeneration = null
  }
  updateState {
    it.copy(phase = when {
      restoringPosition -> PlaybackPhase.LOADING
      it.phase == PlaybackPhase.BACKGROUND -> PlaybackPhase.BACKGROUND
      else -> PlaybackPhase.READY
    }, activeGeneration = it.generation, paused = appliedPaused, error = null)
  }
  propBoolean.emit("pause", appliedPaused)
  restoreSuspendedVideoTrackLocked()
  true
}
```

`MPV_EVENT_PLAYBACK_RESTART` — `:1623-1638`; `MPV_EVENT_END_FILE` — `:1639-1701`
(incl. redirect handling, EOF-before-ready failure detection); `MPV_EVENT_SHUTDOWN` —
`:1702-1726` (full state teardown, `initialized = false`).

Forwarding — `:1731-1733`:

```kotlin
if (shouldForward) {
  observerSnapshot().forEach { observer -> runCatching { observer.event(eventId, data) } }
}
```

`PlaybackPerformanceTrace.kt:105-115` marks all four events:

```kotlin
override fun event(eventId: Int, data: MPVNode) {
  when (eventId) {
    MPVLib.MpvEvent.MPV_EVENT_START_FILE -> mark("MPV_START_FILE")
    MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> mark("MPV_FILE_LOADED")
    MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> mark("MPV_PLAYBACK_RESTART")
    MPVLib.MpvEvent.MPV_EVENT_END_FILE -> mark("MPV_END_FILE", endFileReason(data))
  }
}
```

---

## 11. Phase 8 — `FILE_LOADED` UI catch-up

`PlayerObserver.kt:143-159`:

```kotlin
override fun event(eventId: Int, data: MPVNode) {
  if (shouldIgnoreCallback()) return
  val naturalEnd = eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE && PlaybackSession.isNaturalEndFile(data)
  activity.runOnUiThread {
    if (shouldIgnoreCallback()) return@runOnUiThread
    activity.event(eventId)
    if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) requestStretchVideoOrientationUpdate()
    if (naturalEnd) activity.onObserverEvent("eof-reached", true)
  }
}
```

`shouldIgnoreCallback()` = `activity.player.isExiting || !activity.isActivePlaybackOwner()`.
Geometry properties (`video-params/*`, `container-fps`) bypass the UI-thread hop via
`runIfActivePlaybackOwner` (`:79-92`, `:117-130`).

`PlayerActivity.event` — `:4383-4441`:

```kotlin
internal fun event(eventId: Int) {
  when (eventId) {
    MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
      val loadGeneration = PlaybackSession.state.value.activeGeneration
      if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return
      val recovery = pendingMediaLoadRecovery
      if (recovery?.generation == loadGeneration) cancelPlaybackLoadRecovery()
      eofAdvanceJob?.cancel(); eofAdvanceJob = null
      isAdvancingAtEof = false
      isReady = true
      if (playWhenFileLoaded) playWhenFileLoaded = false
      viewModel.onVideoLoadCompleted()                 // ⭐ releases the poster overlay
      handleFileLoaded(loadGeneration)
      scheduleDeferredUserMpvAssetRefresh()
      if (isBackgroundPlaybackEnabled()) startBackgroundPlayback(allowUserPrompt = false)  // ⭐ SERVICE HERE
    }

    MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
      if (PlaybackSession.state.value.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) return
      isAdvancingAtEof = false
      player.isExiting = false
      if (!isReady) isReady = true
      viewModel.onVideoLoadCompleted()
    }

    MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
      val recovery = pendingMediaLoadRecovery ?: return
      val session = PlaybackSession.state.value
      if (session.generation == recovery.generation && session.phase == PlaybackPhase.ERROR) {
        playbackLoadWatchdogJob?.cancel()
        playbackLoadWatchdogJob = lifecycleScope.launch {
          delay(PLAYBACK_LOAD_ERROR_SETTLE_MS)
          /* …retryOrFinishPlaybackLoad / cancelPlaybackLoadRecovery / re-arm… */
        }
      }
    }
  }
}
```

**The `MediaPlaybackService` starts at `FILE_LOADED`, not before** ✅ — correctly off the
critical path. Chain: `startBackgroundPlayback` (`:7007-7039`) → notification-permission
check → `startBackgroundPlaybackInternal` (`:7041-7096`) → `buildStartupNotification()`
(`MediaPlaybackService.kt:1302-1312`, `setSilent(true)`, `PRIORITY_LOW`,
`setOnlyAlertOnce(true)`) → `startPlaybackObservers()` (`:389-458`) which registers the
audio-becoming-noisy receiver, background-playback policy collector, queue sync, and 9 MPV
property observers.

`PlayerViewModel.kt:2780-2792`:

```kotlin
fun onVideoLoadCompleted() {
  _videoOpenAnimationState.update { current ->
    if (current.isWaitingForVideo) current.copy(isWaitingForVideo = false) else current
  }
  syncplayManager.updateFileInfo(currentSyncplayFileInfo())
  applyEqualizerMpvFilters()
  if (isAudioOnly.value) loadLyricsForCurrentTrack()
  scheduleAutoCropAnalysis()
}
```

`handleFileLoaded` — `:4448-4672`:

```kotlin
private fun handleFileLoaded(loadGeneration: Long) {
  if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return
  val positionRestoreOverride = PlaybackSession.positionRestoreOverride(loadGeneration)
  val initialPositionApplied = PlaybackSession.wasInitialPositionApplied(loadGeneration)
  if (!initialPositionApplied) {
    positionRestoreOverride?.positionSeconds?.let { PlaybackSession.setPropertyDouble("time-pos", it.coerceAtLeast(0.0)) }
  }
  if (fileName.isBlank()) {
    fileName = getFileName(intent)
    if (fileName.isBlank()) fileName = intent.data?.lastPathSegment ?: "Unknown Video"
    legacyMediaIdentifier = getLegacyMediaIdentifier(intent, fileName)
    mediaIdentifier = getMediaIdentifier(intent, fileName)
  } else if (mediaIdentifier.isBlank()) {
    legacyMediaIdentifier = getLegacyMediaIdentifier(intent, fileName)
    mediaIdentifier = getMediaIdentifier(intent, fileName)
  }
  if (serviceBound || mediaPlaybackService != null) syncBackgroundPlaybackService(updateThumbnail = true)

  val currentUri = if (playlist.isNotEmpty() && playlistIndex in playlist.indices) playlist[playlistIndex]
                   else extractUriFromIntent(intent)
  /* …snapshot of fileName/identifiers/intent/playlist… */
  if (loadedMediaIdentifier.isNotBlank()) activeSaveMediaIdentifier = loadedMediaIdentifier
  currentUri?.let { viewModel.calculateVideoHash(it) }

  reportJellyfinStop()
  currentUri?.toString()?.let { url ->
    val tokenFromHeader = networkPlaylistHeaders.getOrNull(playlistIndex)?.get("X-Emby-Token")
      ?: intent.getStringArrayExtra("headers")?.let { PlaybackHttpHeaders.fromFlatPairs(it)["X-Emby-Token"] }
    jellyfinSessionReporter = JellyfinSessionReporter.create(url = url, httpClient = networkHttpClient,
      fallbackToken = tokenFromHeader)
    /* …start session reporting… */
  }
  /* …resume position, audio track, subtitle tracks, chapters, playback-state persistence… */
}
```

`loadVideoPlaybackState` (`:5095-5145`) then does the Room read → `applyPlaybackState` →
`applyDefaultSettings` → `completePositionRestore`.

### Poster → frame cross-fade

`PlayerArtworkTransition.kt:180-234`:

```kotlin
LaunchedEffect(motion.id, session.currentItem?.stableId, lifecycle) {
  if (session.currentItem?.stableId != motion.source.mediaId) {
    PlayerArtworkTransitions.finish(motion.id); return@LaunchedEffect
  }
  lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
    try {
      val target = withTimeoutOrNull(1_000) {                          // ⚠ up to 1 s wait
        snapshotFlow { PlayerArtworkTransitions.anchor(destination)?.takeIf { it.mediaId == motion.source.mediaId } }
          .filterNotNull().first()
      }
      if (target != null) {
        if (reducedMotion) motion.progress.snapTo(1f)
        else motion.progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))  // ⚠ 320 ms
      }
    } finally {
      PlayerArtworkTransitions.finish(motion.id)
    }
  }
}
```

**320 ms of animation plus up to 1 s of anchor waiting sits directly on perceived open
time.**

---

## 12. Cost ranking

| # | Cost | Where | Main thread? | Already mitigated? |
|---|---|---|---|---|
| 1 | `dlopen` libmpv 30–40 MB + `mpv_handle` | `App.kt:449` → `PlaybackSession.kt:281` | ❌ background | ✅ prewarmed — **but reaped after 3 min** |
| 2 | `MPVLib.init()` — mpv.conf parse + Lua script load | `PlaybackSession.kt:363` | ✅ **YES** | ❌ **no** |
| 3 | ~70 `setOptionString` JNI calls | `MPVView.kt:205-329` | ✅ YES | ❌ no |
| 3a | ↳ ~45 of those are subtitles | `MPVView.kt:490-584` | ✅ YES | ❌ no |
| 3b | ↳ ~19 of those are yt-dlp | `MPVView.kt:328` | ✅ YES | ❌ **no — done even for local files** |
| 4 | `ytdl_hook.conf` `mkdirs` + `readText` + `writeText` | `YtdlpManager.kt:511-539` | ✅ YES | ❌ no |
| 5 | 7 × `Os.setenv` | `YtdlpManager.kt:459-475` | ✅ YES | ❌ no |
| 6 | Sync `mpv.conf` read + SHA-256 | `MpvConfigCache.kt:69-79` | ✅ YES | ❌ no |
| 7 | Full core destroy+recreate on key change | `PlaybackSession.kt:327-330` | ✅ YES | ❌ no |
| 8 | Vulkan→OpenGL retry doubles #2+#3 | `PlayerActivity.kt:2755-2759` | ✅ YES | ❌ no |
| 9 | SAF tree walk `syncFromUserMpvDirectory` | `PlayerActivity.kt:2766` | ✅ **YES (joined)** | ⚠ deferred refresh exists |
| 10 | ~6.7 MB asset copy | `PlayerActivity.kt:3023` | ✅ YES | ✅ version-gated |
| 11 | Compose inflate: `PlayerControls` (~60 collectors) + ambient (~12) | `PlayerActivity.kt:1132`, `:1153` | ✅ YES | ❌ no |
| 12 | `generatePlaylistFromFolder` | `PlayerActivity.kt:793` | ✅ **YES (sync)** | ❌ no |
| 13 | Surface delivery blocked behind #2/#3 | `MPVView.kt:410` | — | ✅ `vo=null` is smart |
| 14 | `vo` null↔real thrash | `PlaybackSession.kt:987-990` | load thread | ❌ no |
| 15 | `screenshotDir.mkdirs()` | `MPVView.kt:267` | ✅ YES | ❌ no |
| 16 | `runBlocking` NetworkRepository | `PlaybackSession.kt:915` | load thread | ⚠ null for local |
| 17 | 320 ms cross-fade + 1 s anchor timeout | `PlayerArtworkTransition.kt:196-203` | UI | ❌ no |
| 18 | `MediaPlaybackService` start | `PlayerActivity.kt:4400` | post-`FILE_LOADED` | ✅ yes |
| 19 | Jellyfin report + `calculateVideoHash` | `handleFileLoaded:4494-4507` | ❌ mostly | ✅ yes |
| 20 | yt-dlp runtime copy + Python subprocess | `PlayerActivity.kt:6026` | ❌ parallel | ✅ yes |
| 21 | Cookie export + artwork fetch | `PlayerActivity.kt:5875-5898` | ❌ parallel + gated | ✅ yes |

---

## 13. Offline vs online: what to remove and gate

### The key insight

**The launch URI is already known when `setupMPV()` runs.** `MediaUtils.playFile` did
`Intent(ACTION_VIEW, playbackUri)` + `startActivity` at `MediaUtils.kt:372`, long before
`PlayerActivity.kt:703`. So "is this local or network?" can be computed **once**, at the
top of `onCreate`, and threaded down into `MPVView` and `PlaybackSession.initialize`.
Nothing needs to be guessed later.

```kotlin
// The real cases, derived from resolvePlayableUri + startMediaLoad
enum class PlaybackSourceKind {
  LOCAL_FILE,     // file://, bare path, fd://            — no network machinery at all
  LOCAL_CONTENT,  // content:// (SAF / MediaStore)        — needs openContentFd, still no network
  LOCAL_ARCHIVE,  // archive:// (zip)                     — ParcelFileDescriptor, no network
  NETWORK,        // http(s)://, smb://, magnet, xtream://, network_file_path
}
// magnet/torrent also counts as NETWORK: it resolves to http://127.0.0.1:PORT
//   (TorrentStreamingEngine.kt:268 → startedProxy.serverUrl)
```

Helper predicates already in the codebase:

- `HttpUtils.isNetworkStream(uri)` — `HttpUtils.kt:319-324`, matches
  `http, https, rtmp, rtmps, rtsp, rtsps, mms, mmsh, ftp, ftps, gopher, sctp`
- `HttpUtils.isDirectMediaUrl(uri)` — `HttpUtils.kt:341-344`
- `YtdlpManager.requiresYtdlp(source)` — `YtdlpManager.kt:114-121`

### ❌ Pure online-only — remove/gate for local video

| # | What | Where | Why safe to gate |
|---|---|---|---|
| 1 | `YtdlpManager.setupMpvOptions` — 7 `Os.setenv`, `mkdirs`, `readText`/`writeText`, 12 JNI writes | `MPVView.kt:328` → `YtdlpManager.kt:446-573` | A local file never hits the yt-dlp hook; `requiresYtdlp` already returns false for non-http. **Must become once-per-core-lazy, not deleted** |
| 2 | `cookies` / `cookies-file` | `MPVView.kt:283-284` | libmpv only reads the cookie jar opening an HTTP stream |
| 3 | `demuxer-lavf-o` reconnect | `MPVView.kt:291-295` | Configures libavformat HTTP/TLS reconnect — meaningless for a local file |
| 4 | `stream-lavf-o` reconnect | `MPVView.kt:299-303` | `stream_lavf` is only used for `http(s)://` |
| 5 | `tls-verify` / `tls-ca-file` | `MPVView.kt:263-264` | TLS only |
| 6 | `cache`, `cache-pause`, `cache-pause-wait`, `demuxer-max-bytes`, `hls-bitrate` | `MPVView.kt:282-288` | The 64 MiB demuxer cache is pure network-resilience config |
| 7 | `runBlocking { NetworkRepository.getConnectionById(...) }` | `PlaybackSession.kt:913-926` | ✅ Already null for local — but the `runBlocking` should become a suspend param |
| 8 | `setPropertyString("http-header-fields", …)` + per-item UA | `PlaybackSession.kt:982-983` | Local files have no headers; skip the JNI writes when `headers.isEmpty()` |
| 9 | `reportJellyfinStop()` + `JellyfinSessionReporter.create` | `PlayerActivity.kt:4496-4507` | Only meaningful for a Jellyfin server URL |
| 10 | `torrentStreamingEngine.stopStream()` for non-torrents | `PlayerActivity.kt:5753` | Already no-ops internally, but a needless call |
| 11 | `ytdl_hook.conf` disk work (`mkdirs`/`readText`/`writeText`) | `YtdlpManager.kt:511-539` | Only needed before libmpv loads `ytdl_hook` |

### ✅ Already correctly gated — leave alone

| What | Where | Why it's fine |
|---|---|---|
| Artwork fetch for music streams | `PlayerActivity.kt:5876` | `HttpUtils.isMusicStreamingUrl` |
| Cookie export | `PlayerActivity.kt:5881-5898` | `http(s)` prefix + already-written-this-session |
| yt-dlp runtime prep | `YtdlpManager.kt:354-355` | `if (!isWebSource) return true` |
| `requiresYtdlp` | `YtdlpManager.kt:114-121` | `if (!http/https) return false` |
| Saved-position DB read | `PlayerActivity.kt:6012-6016` | Only on `ResumePlaybackMode.Always` |
| `resolvePlayableUri` proxy branches | `PlaybackSession.kt:2001-2072` | Every branch early-returns for a plain local file |
| `smbPath` resolution | `PlaybackSession.kt:913` | Null when `networkSource == null` |
| `loadDynamicM3uPlaylist` | `PlayerActivity.kt:5765` | Local `.m3u` **should** expand — keep for `LOCAL_FILE` |
| Vulkan capability probe | `VulkanCapabilities.kt:39` | `@Volatile` memoized |
| `VideoCodecSupportInspector.hardwareDecoderCodecIds()` | `App.kt:451` | Prewarmed in background |
| Torrent stream resolution | `PlayerActivity.kt:5788-5843` | Gated on `isTorrentRequest` |

### ⚠️ Not network — but expensive, worth attacking anyway

| # | What | Where | Cost |
|---|---|---|---|
| 12 | Sync `mpv.conf` read + SHA-256 **every open** | `MpvConfigCache.kt:69-79` via `MPVView.kt:90` | Disk read + digest on UI thread |
| 13 | `setupSubtitlesOptions` — ~45 writes + ~18 pref reads | `MPVView.kt:490-584` | Biggest single serial-JNI block |
| 14 | Full core destroy+recreate on key change | `PlaybackSession.kt:327-330` | Everything above, twice |
| 15 | Vulkan→OpenGL retry | `PlayerActivity.kt:2755-2759` | Everything above, twice |
| 16 | `generatePlaylistFromFolder` sync walk | `PlayerActivity.kt:793` | UI freeze while scanning a folder |
| 17 | Two Compose trees inflate | `PlayerActivity.kt:1132`, `:1153` | Compose is not cheap |
| 18 | `screenshotDir.mkdirs()` | `MPVView.kt:267` | Sync FS on UI thread |
| 19 | `vo` null↔real thrash | `PlaybackSession.kt:987-990` | Extra renderer init/destroy |
| 20 | 320 ms cross-fade + 1 s anchor timeout | `PlayerArtworkTransition.kt:196-203` | Directly on perceived open |
| 21 | 3-minute core reaper | `App.kt:101`, `:472` | Full `dlopen` again |

---

## 14. Concrete fixes

### Fix 1 — Split yt-dlp setup into "core init" vs "first network load"

**Step A.** Move `YtdlpManager.setupMpvOptions(context, ytdlPreferences, subtitlesPreferences)`
out of `MPVView.kt:328`. Keep only the cheap option writes in the init window; move the 7
`Os.setenv` and the `ytdl_hook.conf` disk work behind a process flag.

**Step B.** Add to `YtdlpManager`:

```kotlin
private val optionsApplied = AtomicBoolean(false)

fun applyOptionsOnce(
  context: Context,
  ytdlPreferences: YtdlpPreferences,
  subtitlesPreferences: SubtitlesPreferences,
) {
  if (!optionsApplied.compareAndSet(false, true)) return
  setupMpvOptions(context, ytdlPreferences, subtitlesPreferences)
}

fun invalidateAppliedOptions() { optionsApplied.set(false) }   // call from destroyLocked()
```

**Step C.** Call it from `PlaybackSession.load` (`:905`) — already off the main thread and
already knows the URI:

```kotlin
return withCore(default = -1L) {
  if (item.playableUri.isHttpScheme() || item.originalUri.isHttpScheme()) {
    applicationContext?.let { YtdlpManager.applyOptionsOnce(it, ytdlPreferences, subtitlesPreferences) }
  }
  …
}
```

or better, from `issuePlaybackLoad`'s existing `ytdlpReadyDeferred`
(`PlayerActivity.kt:6026`), which already runs on a background dispatcher and already
computes `requiresYtdlp`.

**Step D.** Reset the flag in `PlaybackSession.destroyLocked()` (`:668`) so a core rebuild
re-applies.

**Local-video saving:** 7 `Os.setenv` JNI calls + `mkdirs` + `readText` + 12 JNI option
writes removed from the UI thread on every cold open.

### Fix 2 — Apply streaming options lazily per network item

Do **not** add `net=` to `coreConfigurationKey` — that would destroy the core when
switching local↔network. Instead apply them on first network load, the same shape as
Fix 1. mpv accepts `cache`, `demuxer-max-bytes`, `demuxer-lavf-o`, `stream-lavf-o`,
`tls-*` as runtime properties:

```kotlin
// PlaybackSession — add alongside the existing network-stream guards
private var streamingOptionsApplied = false

private fun applyStreamingOptionsIfNeededLocked() {
  if (streamingOptionsApplied) return
  PlaybackSession.setOptionString("cookies", "yes")
  PlaybackSession.setOptionString("cookies-file", AndroidCookieJar.playbackCookieFile(applicationContext!!).absolutePath)
  PlaybackSession.setOptionString("cache", "auto")
  PlaybackSession.setOptionString("demuxer-max-bytes", "64MiB")
  PlaybackSession.setOptionString("demuxer-lavf-o", "…")
  PlaybackSession.setOptionString("stream-lavf-o", "…")
  streamingOptionsApplied = true
}
```

Called from `load` when the playable/original URI is `http(s)`/`smb`. Reset in
`destroyLocked()`.

If you prefer the simpler version and accept a core rebuild on local↔network transitions:

```kotlin
val coreConfigurationKey = "${requestedBackend.configurationKey}|conf=…|mpv=…|scripts=$scriptsKey|net=$sourceKind.isNetwork"
```

— but this trades a ~10-JNI saving for a full core teardown. **Not recommended.**

### Fix 3 — Kill the 3-minute reaper, or replace it with a re-prewarm

`App.kt:101`, `:462`. Either delete `startIdleMpvCoreReaper()` entirely, or replace the cold
shutdown with a cheap re-prewarm:

```kotlin
// MainActivity.onResume, or a ProcessLifecycleOwner ON_START observer
if (!PlaybackSession.isInitialized) {
  applicationScope.launch { PlaybackSession.prewarmNativeCore(this@App) }
}
```

`prewarmNativeCore` is idempotent (`if (nativeCoreCreated) return` at
`PlaybackSession.kt:280`) and costs ~nothing when already warm.

### Fix 4 — Cache the `mpv.conf` configuration key off the main thread

`MpvConfigCache.kt` already collects `preferences.mpvConf.changes()` on IO in its `init`
block (`:43-48`), so `cachedBytes` is already kept fresh there:

```kotlin
@Volatile private var cachedConfigurationKey: String? = null

fun configurationKey(): String =
  cachedConfigurationKey ?: synchronized(lock) { computeKeyLocked() }.also { cachedConfigurationKey = it }

// and null it inside updateLocked() when the content changes
```

### Fix 5 — Stop joining the SAF walk in `onCreate`

`PlayerActivity.kt:2616-2625`. Kick both off at the top of `onCreate` and join only right
before `MPVLib.init()`:

```kotlin
private var mpvAssetsJob: Job? = null

// top of onCreate, before setContentView
mpvAssetsJob = lifecycleScope.launch(Dispatchers.IO) {
  runCatching { syncBundledAssetsIfNeeded(); prepareUserMpvAssetsForStartup() }
}

// in setupMPV(), immediately before initializePlayerWithRendererFallback()
// runBlocking { mpvAssetsJob?.join() }   ← narrow join; everything above overlaps
```

This lets Compose inflation, `setupAudio`, `ExternalDisplayManager.start()` and the SAF
tree walk all run concurrently.

### Fix 6 — Move `generatePlaylistFromFolder` off the UI thread

`PlayerActivity.kt:793` — the `loadPlaylistById` branch directly above it (`:770`) is
already on `Dispatchers.IO`. Make it match:

```kotlin
lifecycleScope.launch(Dispatchers.IO) { generatePlaylistFromFolder(path) }
```

### Fix 7 — Remove the `vo` thrash

`PlaybackSession.kt:987-990`. Track "a Surface is expected imminently":

```kotlin
private val surfaceExpected = AtomicBoolean(false)

// set true just before loadfile; cleared 500 ms later by a handler
if (!_state.value.surfaceAttached && !surfaceExpected.get()) {
  MPVLib.setPropertyString("vo", "null")
  MPVLib.setOptionString("force-window", "no")
}
```

Then `bindSurface` (`:440`) becomes the single authoritative `vo` flip, and
`deferredVideoSelectionGeneration` can be deleted.

### Fix 8 — Skip the Jellyfin reporter for local files

`PlayerActivity.kt:4496-4507`:

```kotlin
if (HttpUtils.isNetworkStream(Uri.parse(currentUri))) {
  reportJellyfinStop()
  jellyfinSessionReporter = JellyfinSessionReporter.create(url, networkHttpClient, fallbackToken)
}
```

### Fix 9 — Snap instead of animate when the first frame is already up

`PlayerArtworkTransition.kt:196-203`. If the session was already `READY` when the overlay
started (warm `onNewIntent` re-entry), `snapTo(1f)` immediately instead of the 320 ms
`tween`, and shorten the `withTimeoutOrNull(1_000)`.

### Fix 10 — Skip per-item header writes for local files

`PlaybackSession.kt:978-983`:

```kotlin
val userAgent = PlaybackHttpHeaders.userAgent(resolvedItem.headers)
val headerFields = PlaybackHttpHeaders.toMpvHeaderFields(resolvedItem.headers)
if (resolvedItem.headers.isNotEmpty()) {
  MPVLib.setPropertyString("user-agent", userAgent ?: defaultUserAgent.orEmpty())
  MPVLib.setPropertyString("http-header-fields", headerFields)
}
```

### Fix 11 — Cache the subtitle option block

`MPVView.kt:490-584` writes ~45 options from ~18 preference reads on **every** core init,
local or not. The values only change when the user edits subtitle settings. Snapshot the
resolved `(name, value)` pairs into a list keyed by a hash of those 18 preferences, and
replay the list on warm inits. `MpvConfigCache.kt` is the existing pattern to follow.

---

## 15. Priority order

| Rank | Fix | Effort | Payoff |
|---|---|---|---|
| 1 | **Fix 3** — reaper / re-prewarm | tiny | removes full `dlopen` from most opens |
| 2 | **Fix 1** — lazy yt-dlp options | small | ~19 JNI + 3 FS ops off the UI thread, every local open |
| 3 | **Fix 5** — unjoin the SAF walk | small | overlaps asset prep with Compose inflation |
| 4 | **Fix 4** — cache mpv.conf key | small | one disk read + SHA-256 off the UI thread |
| 5 | **Fix 2** — lazy streaming options | medium | ~10 JNI writes off the UI thread for local |
| 6 | **Fix 7** — `vo` thrash | medium | removes a renderer re-init |
| 7 | **Fix 6** — folder walk async | tiny | no UI freeze in playlist mode |
| 8 | **Fix 8** — Jellyfin gate | tiny | no dead network call |
| 9 | **Fix 9** — snap the cross-fade | tiny | −320 ms perceived |
| 10 | **Fix 11** — subtitle option cache | medium | −45 JNI writes on warm opens |

---

## 16. How to measure

### Already-instrumented logcat (debug builds)

`PlaybackPerformanceTrace.kt:39-51`:

```kotlin
fun mark(name: String, detail: String? = null) {
  if (Trace.isEnabled()) {
    val traceName = buildTraceName(name, detail)
    Trace.beginSection(traceName); Trace.endSection()
  }
  if (BuildConfig.DEBUG) {
    Log.d(TAG, "${SystemClock.elapsedRealtimeNanos()} $name${detail?.let { " [$it]" }.orEmpty()}")
  }
}
```

Available marks, in expected order:

| Mark | Emitted at |
|---|---|
| `OPEN_REQUEST` | `MediaUtils.kt:368` — the tap |
| `MEDIA_PREPARATION_START` / `MEDIA_PREPARATION_END durationMs=` | `PlaybackSession.kt:854` / `:859` |
| `LOADFILE_SENT generation=N` | `PlaybackSession.kt:1005` |
| `MPV_START_FILE` | `PlaybackPerformanceTrace.kt:110` |
| `MPV_FILE_LOADED` | `:111` |
| `MPV_PLAYBACK_RESTART` | `:112` |
| `MPV_END_FILE reason=…` | `:113` |
| `SUPERSEDED_STOP_END_FILE` | `PlaybackSession.kt:1648` |

Plus two plain `Log.d` milestones:
- `MPV startup assets ready in N ms` — `PlayerActivity.kt:2622`
- `Using cached MPV user assets for startup` — `PlayerActivity.kt:2672`

```bash
adb logcat -s PlaybackPerf PlayerActivity MPVView PlaybackSession
```

The delta `OPEN_REQUEST → MPV_FILE_LOADED` **is** your cold-open time. The delta
`MPV_FILE_LOADED → MPV_PLAYBACK_RESTART` isolates decode+render.

### Perfetto / atrace

`Trace.isEnabled()` (`PlaybackPerformanceTrace.kt:43`) makes the object emit real atrace
slices under the `mpvRx:` prefix at **zero cost** in release (no JNI, no name allocation
when no capture is attached). `begin()`/`end()` (`:53-76`) emit named spans.

Attaching a capture makes these appear in full:

```
mpvRx:OPEN_REQUEST
mpvRx:MEDIA_PREPARATION_START
mpvRx:MEDIA_PREPARATION_END:durationMs=…
mpvRx:LOADFILE_SENT:generation=N
mpvRx:MPV_START_FILE
mpvRx:MPV_FILE_LOADED
mpvRx:MPV_PLAYBACK_RESTART
```

`PlaybackPerformanceTrace` is registered as an observer at `App.kt:166`, before
`startPlaybackPerformanceTracing()` at `:167`, so marks exist for the whole process
lifetime.

---

## Appendix — Reference: helper predicates and constants

| Symbol | Location | Note |
|---|---|---|
| `PlaybackPhase` enum | `PlaybackModels.kt:25-34` | `UNINITIALIZED, INITIALIZING, IDLE, LOADING, READY, BACKGROUND, STOPPING, ERROR` |
| `PlaybackSessionState` | `PlaybackSession.kt:47-55` | `phase, generation, activeGeneration, surfaceAttached, paused, currentItem, error` |
| `PlaybackItem` | `PlaybackModels.kt:46-102` | `stableId, originalUri, playableUri, title, artist, mimeType, headers, networkSource, playlistItemId, artworkUri, durationSeconds, videoWidth, videoHeight, torrentFileIndex, audiobook` |
| `HttpUtils.isNetworkStream` | `HttpUtils.kt:319-324` | 12 schemes incl. `http, https, rtmp, rtsp, ftp` |
| `HttpUtils.isDirectMediaUrl` | `HttpUtils.kt:341-344` | `directMediaExtensions` at `HttpUtils.kt:25` |
| `YtdlpManager.requiresYtdlp` | `YtdlpManager.kt:114-121` | non-http → `false` |
| `YtdlpManager.prepareForPlayback` | `YtdlpManager.kt:348-366` | non-http → `true` immediately |
| `MpvConfigOverridePolicy.isOwnedByMpvConf` | `MpvConfigOverride.kt:280` | `optionName in overriddenOptionNames` — O(1) Set |
| `PlaybackActivityOwner` | `PlayerLifecyclePolicy.kt:17-47` | `AtomicLong` token + `ReentrantReadWriteLock` |
| `VulkanCapabilities.isDeviceSupported` | `VulkanCapabilities.kt:38-41` | `@Volatile` memoized |
| `nativeLock` / `withCore` | `PlaybackSession.kt:2121-2131` | serialises all `MPVLib` access |
| `withReadyCore` | `PlaybackSession.kt:2133-2140` | requires `nativeCoreReady` |
| `IDLE_MPV_CORE_GRACE_MS` | `App.kt:101` | `3L * 60L * 1000L` |
| `SEEK_AUDIO_RESTORE_DELAY_MS` | `PlaybackSession.kt` | seek-guard audio restore |
| `PLAYBACK_TRANSITION_AUDIO_RESTORE_DELAY_MS` | `PlaybackSession.kt` | transition-guard audio restore |
| `PLAYBACK_LOAD_ERROR_SETTLE_MS` | `PlayerActivity.kt:4425` | END_FILE-before-START_FILE settle window |
| `DEFERRED_MPV_ASSET_SYNC_DELAY_MS` | `PlayerActivity.kt:3055` | deferred user-asset refresh |
| `PLAYER_ASSET_SIZE` | `app/src/main/assets/` | ytdl 3.4 MB, shaders 2.2 MB, guessit 756 KB, textmate 360 KB |