package com.shehab360.videoplayerpro

import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.shehab360.videoplayerpro.databinding.FragmentMusicBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicFragment : Fragment() {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!

    private var all: List<Music> = emptyList()
    private var query = ""
    private var adapter: MusicAdapter? = null
    private var isLoading = false
    private var askedOnce = false
    private var permanentlyDenied = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable { loadMusic() }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.removeCallbacks(reloadRunnable)
            mainHandler.postDelayed(reloadRunnable, 600)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (Perms.hasAudioAccess(requireContext())) {
            loadMusic()
        } else {
            permanentlyDenied = Perms.audio().none { shouldShowRequestPermissionRationale(it) }
            showPermissionState()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerViewMusic.layoutManager = LinearLayoutManager(requireContext())
        adapter = MusicAdapter(::play)
        binding.recyclerViewMusic.adapter = adapter

        parentFragmentManager.setFragmentResultListener(VideosFragment.REQ_QUERY, viewLifecycleOwner) { _, b ->
            query = b.getString(VideosFragment.KEY_QUERY, "") ?: ""
            render()
        }

        if (Perms.hasAudioAccess(requireContext())) {
            loadMusic()
        } else {
            askedOnce = true
            permissionLauncher.launch(Perms.audio())
        }
    }

    override fun onStart() {
        super.onStart()
        requireContext().contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer
        )
    }

    override fun onResume() {
        super.onResume()
        if (Perms.hasAudioAccess(requireContext())) {
            if (all.isEmpty() && !isLoading) loadMusic()
        } else if (askedOnce) {
            showPermissionState()
        }
    }

    override fun onStop() {
        requireContext().contentResolver.unregisterContentObserver(observer)
        mainHandler.removeCallbacks(reloadRunnable)
        super.onStop()
    }

    private fun loadMusic() {
        if (_binding == null || isLoading) return
        isLoading = true
        val appContext = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val result = mutableListOf<Music>()
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION
                )
                // IS_MUSIC != 0 অনেক গান (WhatsApp অডিও, রেকর্ডিং, ডাউনলোড) বাদ দিত;
                // এখন শুধু রিংটোন/নোটিফিকেশন/অ্যালার্ম বাদ
                val selection = "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
                    "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
                    "${MediaStore.Audio.Media.IS_ALARM} = 0 AND " +
                    "${MediaStore.Audio.Media.DURATION} > 0"
                try {
                    appContext.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection, selection, null,
                        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                    )?.use { c ->
                        val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val nameC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                        val artistC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val durC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        while (c.moveToNext()) {
                            val id = c.getLong(idC)
                            // getString null দিতে পারে: আগে এখানেই NPE হত
                            val title = c.getString(titleC)?.takeIf { it.isNotBlank() }
                                ?: c.getString(nameC)
                                ?: "Audio $id"
                            val artist = c.getString(artistC)
                                ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                                ?: appContext.getString(R.string.unknown_artist)
                            result.add(
                                Music(
                                    id = id,
                                    title = title,
                                    artist = artist,
                                    durationMs = c.getLong(durC),
                                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // পারমিশন হারালে / MediaStore ত্রুটিতে খালি তালিকা
                }
                result
            }
            isLoading = false
            if (_binding == null) return@launch
            all = list
            render()
        }
    }

    private fun render() {
        val b = _binding ?: return
        val shown = if (query.isBlank()) all
        else all.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
        adapter?.submit(shown)

        if (!Perms.hasAudioAccess(requireContext())) {
            showPermissionState()
            return
        }
        if (shown.isEmpty()) {
            b.emptyState.visibility = View.VISIBLE
            b.recyclerViewMusic.visibility = View.GONE
            b.tvEmpty.setText(if (query.isBlank()) R.string.no_music else R.string.no_results)
            b.btnEmptyAction.visibility = View.GONE
        } else {
            b.emptyState.visibility = View.GONE
            b.recyclerViewMusic.visibility = View.VISIBLE
        }
    }

    private fun showPermissionState() {
        val b = _binding ?: return
        b.emptyState.visibility = View.VISIBLE
        b.recyclerViewMusic.visibility = View.GONE
        b.tvEmpty.setText(R.string.permission_needed_message)
        b.btnEmptyAction.visibility = View.VISIBLE
        if (permanentlyDenied) {
            b.btnEmptyAction.setText(R.string.open_settings)
            b.btnEmptyAction.setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null))
                )
            }
        } else {
            b.btnEmptyAction.setText(R.string.grant_permission)
            b.btnEmptyAction.setOnClickListener {
                askedOnce = true
                permissionLauncher.launch(Perms.audio())
            }
        }
    }

    private fun play(clicked: Music) {
        val shown = if (query.isBlank()) all
        else all.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
        val index = shown.indexOfFirst { it.id == clicked.id }.coerceAtLeast(0)
        val from = (index - 100).coerceAtLeast(0)
        val to = (index + 101).coerceAtMost(shown.size)
        val window = shown.subList(from, to)

        startActivity(
            Intent(requireContext(), PlayerActivity::class.java).apply {
                putStringArrayListExtra(PlayerActivity.EXTRA_VIDEO_LIST, ArrayList(window.map { it.uri.toString() }))
                putStringArrayListExtra(PlayerActivity.EXTRA_VIDEO_TITLES, ArrayList(window.map { it.title }))
                putExtra(PlayerActivity.EXTRA_CURRENT_INDEX, index - from)
                putExtra(PlayerActivity.EXTRA_AUDIO_ONLY, true)
            }
        )
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(reloadRunnable)
        adapter = null
        super.onDestroyView()
        _binding = null
    }
}
