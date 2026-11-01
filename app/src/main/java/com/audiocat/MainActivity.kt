package com.audiocat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.doOnPreDraw
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.audiocat.databinding.ActivityMainBinding
import com.audiocat.playback.PlayerConnection
import com.audiocat.ui.files.FilesFragment
import com.audiocat.ui.playing.PlayerFragment
import com.audiocat.ui.playing.PlaylistFragment
import com.audiocat.ui.settings.SettingsFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Single-activity shell: a four-page pager with bottom navigation.
 *
 * The pages are kept alive by [ViewPager2]'s offscreen limit so switching tabs
 * never re-runs a scan or re-queries the library, which is what makes tab
 * switching feel instant.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var initialInsetsApplied = false
    private var firstDrawAttempts = 0

    /** Shared with every page; a media controller connects to the service. */
    val player: PlayerConnection by lazy { PlayerConnection(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            // Newly granted, so pull the library in immediately.
            AudioCatApp.get().scanCoordinator.scan(force = false)
        } else {
            Snackbar.make(binding.root, R.string.permission_needed, Snackbar.LENGTH_LONG)
                .setAction(R.string.permission_grant) { requestStoragePermission() }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpWindowInsets()

        setUpPager()
        setUpBottomNav()
        binding.pager.setCurrentItem(initialPage(), false)
        requestStoragePermission()
        warmUpPages()
    }

    /**
     * Applies the final system-bar padding before the first frame is allowed to draw.
     *
     * On a cold start the first layout can happen before window insets reach the
     * root view, which makes the bottom navigation measure once in a temporary
     * position and then jump when the inset padding arrives. Holding the very
     * first draw until the first inset pass lands keeps the bar stable.
     */
    private fun setUpWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            applyRootPadding(bars.left, bars.top, bars.right, bars.bottom)
            initialInsetsApplied = true
            insets
        }
        binding.root.doOnAttach { ViewCompat.requestApplyInsets(it) }
        binding.root.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!initialInsetsApplied) {
                        val insets = ViewCompat.getRootWindowInsets(binding.root)
                        if (insets != null) {
                            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                            applyRootPadding(bars.left, bars.top, bars.right, bars.bottom)
                            initialInsetsApplied = true
                        } else {
                            firstDrawAttempts += 1
                            if (firstDrawAttempts < MAX_FIRST_DRAW_ATTEMPTS) {
                                ViewCompat.requestApplyInsets(binding.root)
                                return false
                            }
                            // Some Android 10 builds never dispatch the first insets pass.
                            // Falling back to the current zero-padding state is better than
                            // leaving the whole activity blank forever.
                            initialInsetsApplied = true
                        }
                    }
                    binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }

    private fun applyRootPadding(left: Int, top: Int, right: Int, bottom: Int) {
        val changed = binding.root.paddingLeft != left ||
            binding.root.paddingTop != top ||
            binding.root.paddingRight != right ||
            binding.root.paddingBottom != bottom
        if (changed) {
            binding.root.setPadding(left, top, right, bottom)
        }
    }

    /**
     * Builds the tab pages the cold start skipped, right after the first frame.
     *
     * The pager starts with a small offscreen limit so the first draw only lays
     * out the Player and Playlist pages; the Files and Settings pages are
     * created one frame later by raising the limit. After that point every page
     * stays warm, so switching tabs remains a no-op exactly like before.
     */
    private fun warmUpPages() {
        binding.root.doOnPreDraw {
            // Deferred past this frame: raising the limit instantiates the
            // remaining fragments, which must not happen inside the draw pass.
            binding.pager.post {
                binding.pager.offscreenPageLimit = PAGE_COUNT - 1
            }
        }
    }

    private fun setUpPager() {
        binding.pager.adapter = PagerAdapter()
        // The cold start only lays out the Player and Playlist pages; the rest
        // are warmed one frame after the first draw (see the pre-draw hook in
        // onCreate). This keeps the very first frame cheap without giving up
        // instant tab switching once the pages have been warmed. ViewPager2 must
        // never be given a limit >= the item count: it layout-loops and the
        // pages stop rendering.
        binding.pager.offscreenPageLimit = 1
        // Tabs can also be changed with a left/right swipe on open page space.
        binding.pager.isUserInputEnabled = true
    }

    private fun setUpBottomNav() {
        binding.bottomNav.isItemActiveIndicatorEnabled = false
        binding.bottomNav.setOnItemSelectedListener { item ->
            // MenuItem.getOrder() is 0 unless declared in XML, so map ids
            // explicitly to pager positions.
            val page = when (item.itemId) {
                R.id.tab_player -> PAGE_PLAYER
                R.id.tab_playlist -> PAGE_PLAYLIST
                R.id.tab_files -> PAGE_FILES
                else -> PAGE_SETTINGS
            }
            binding.pager.setCurrentItem(page, false)
            true
        }
        // Keep the navigation state in sync when the pager is changed in code.
        binding.pager.registerOnPageChangeCallback(
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    binding.bottomNav.menu.getItem(position).isChecked = true
                }
            },
        )
    }

    /**
     * Reveals the Player tab, closing any sheet first so the page is never shown
     * behind a half-open overlay.
     *
     * Only sheets are dismissed: the four pages are fragments of this same
     * activity, and the type filter is what keeps them - and the Files tab's
     * rename dialog, which is not a bottom sheet - out of reach.
     */
    fun showPlayer() {
        supportFragmentManager.fragments
            .filterIsInstance<BottomSheetDialogFragment>()
            .forEach { it.dismissAllowingStateLoss() }
        binding.pager.setCurrentItem(PAGE_PLAYER, false)
    }

    override fun onStop() {
        super.onStop()
        // Persist the queue so it survives the process being killed.
        player.saveState()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------

    /**
     * Requests the media read permissions appropriate for this API level.
     *
     * Android 13 replaced READ_EXTERNAL_STORAGE with per-type grants, and the
     * video grant is needed separately because the library includes video
     * containers.
     */
    private fun requestStoragePermission() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            AudioCatApp.get().scanCoordinator.onAppStart()
            return
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            // Cover art that sits next to the files, not inside them.
            add(Manifest.permission.READ_MEDIA_IMAGES)
            // Without this the playback notification is silently dropped on
            // stock Android 13+, which takes the notification, the lockscreen
            // transport and the media buttons with it. OEM builds (HyperOS)
            // let a media session post anyway, so this only shows up on Pixel.
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun initialPage(): Int =
        intent?.getIntExtra(EXTRA_INITIAL_PAGE, PAGE_PLAYER)
            ?.coerceIn(PAGE_PLAYER, PAGE_SETTINGS)
            ?: PAGE_PLAYER

    /** Page adapter for the four fixed tabs. */
    private inner class PagerAdapter : FragmentStateAdapter(this) {

        override fun getItemCount(): Int = PAGE_COUNT

        override fun createFragment(position: Int): androidx.fragment.app.Fragment = when (position) {
            0 -> PlayerFragment()
            1 -> PlaylistFragment()
            2 -> FilesFragment()
            else -> SettingsFragment()
        }
    }

    private companion object {
        const val PAGE_COUNT = 4
        const val PAGE_PLAYER = 0
        const val PAGE_PLAYLIST = 1
        const val PAGE_FILES = 2
        const val PAGE_SETTINGS = 3
        const val EXTRA_INITIAL_PAGE = "initial_page"
        const val MAX_FIRST_DRAW_ATTEMPTS = 4
    }
}
