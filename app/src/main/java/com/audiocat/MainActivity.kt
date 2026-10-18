package com.audiocat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        // The pager draws behind the system bars; keep content clear of them.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setUpPager()
        setUpBottomNav()
        requestStoragePermission()
    }

    private fun setUpPager() {
        binding.pager.adapter = PagerAdapter()
        // Keeps every page warm so switching tabs is a no-op. ViewPager2 must
        // never be given a limit >= the item count: it layout-loops and the
        // pages stop rendering.
        binding.pager.offscreenPageLimit = PAGE_COUNT - 1
        binding.pager.isUserInputEnabled = false
    }

    private fun setUpBottomNav() {
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
    }
}
