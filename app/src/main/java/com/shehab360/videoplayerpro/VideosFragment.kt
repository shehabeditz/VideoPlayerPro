package com.shehab360.videoplayerpro

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shehab360.videoplayerpro.databinding.FragmentVideosBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class VideosFragment : Fragment() {

    companion object {
        const val REQ_QUERY = "media_query"
        const val REQ_REFRESH = "media_refresh"
        const val KEY_QUERY = "q"
    }

    private var _binding: FragmentVideosBinding? = null
    private val binding get() = _binding!!

    private var allVideos: List<Video> = emptyList()
    private var query: String = ""
    private var currentFolder: FolderItem? = null

    private var videoAdapter: VideoAdapter? = null
    private var folderAdapter: FolderAdapter? = null
    private var showingGrid = false

    private var isLoading = false
    private var askedOnce = false
    private var permanentlyDenied = false

    // পেন্ডিং ফাইল অপারেশন (Android 10/11+ এর সিস্টেম অনুমতির পর শেষ করতে)
    private var pendingRename: Pair<Uri, String>? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable { loadVideos() }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.removeCallbacks(reloadRunnable)
            mainHandler.postDelayed(reloadRunnable, 600)
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            currentFolder = null
            render()
            updateBackCallback()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (Perms.hasVideoAccess(requireContext())) {
            loadVideos()
        } else {
            // "আর জিজ্ঞাসা করো না" চাপা থাকলে Settings এ পাঠাতে হবে
            permanentlyDenied = Perms.video().none { shouldShowRequestPermissionRationale(it) }
            showEmptyState()
        }
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
            loadVideos()
        }
    }

    private val writeLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingRename
        pendingRename = null
        if (result.resultCode == android.app.Activity.RESULT_OK && pending != null) {
            performRename(pending.first, pending.second)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        parentFragmentManager.setFragmentResultListener(REQ_QUERY, viewLifecycleOwner) { _, b ->
            query = b.getString(KEY_QUERY, "") ?: ""
            render()
        }
        parentFragmentManager.setFragmentResultListener(REQ_REFRESH, viewLifecycleOwner) { _, _ ->
            render()
        }

        if (Perms.hasVideoAccess(requireContext())) {
            loadVideos()
        } else {
            askedOnce = true
            permissionLauncher.launch(Perms.video())
        }
    }

    override fun onStart() {
        super.onStart()
        requireContext().contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )
    }

    override fun onResume() {
        super.onResume()
        updateBackCallback()
        // সেটিংস থেকে পারমিশন দিয়ে ফিরলে / প্লেয়ার থেকে ফিরলে (প্রগ্রেস বার) আপডেট
        if (Perms.hasVideoAccess(requireContext())) {
            if (allVideos.isEmpty() && !isLoading) loadVideos() else videoAdapter?.refreshProgress()
        } else if (askedOnce) {
            showEmptyState()
        }
    }

    override fun onPause() {
        backCallback.isEnabled = false
        super.onPause()
    }

    override fun onStop() {
        requireContext().contentResolver.unregisterContentObserver(observer)
        mainHandler.removeCallbacks(reloadRunnable)
        super.onStop()
    }

    private fun updateBackCallback() {
        backCallback.isEnabled = currentFolder != null
    }

    // ------------------------------------------------------------------
    // লোড
    // ------------------------------------------------------------------
    private fun loadVideos() {
        if (_binding == null || isLoading) return
        isLoading = true
        val appContext = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val result = mutableListOf<Video>()
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.BUCKET_ID,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT
                )
                try {
                    appContext.contentResolver.query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        projection, null, null,
                        "${MediaStore.Video.Media.DATE_ADDED} DESC"
                    )?.use { c ->
                        val idC = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                        val nameC = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                        val durC = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                        val sizeC = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                        val dateC = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                        val bidC = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                        val bnameC = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                        val wC = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                        val hC = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                        while (c.moveToNext()) {
                            val id = c.getLong(idC)
                            result.add(
                                Video(
                                    id = id,
                                    title = c.getString(nameC) ?: "Video $id",
                                    durationMs = c.getLong(durC),
                                    sizeBytes = c.getLong(sizeC),
                                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                                    dateAddedSec = c.getLong(dateC),
                                    folderId = c.getLong(bidC),
                                    folderName = c.getString(bnameC) ?: "",
                                    width = c.getInt(wC),
                                    height = c.getInt(hC)
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
            // Fragment এর view ইতিমধ্যে নষ্ট হলে আর UI ছোঁয়া যাবে না
            if (_binding == null) return@launch
            allVideos = list
            // বর্তমান ফোল্ডার মুছে গেলে রুটে ফেরা
            if (currentFolder != null && list.none { it.folderId == currentFolder?.id }) currentFolder = null
            render()
            updateBackCallback()
        }
    }

    // ------------------------------------------------------------------
    // দেখানো (ফোল্ডার / ভিডিও, সার্চ, সর্ট, List/Grid)
    // ------------------------------------------------------------------
    private fun render() {
        val b = _binding ?: return
        val ctx = context ?: return

        val wantGrid = AppPrefs.gridView
        val folderMode = AppPrefs.folderView && currentFolder == null && query.isBlank()

        if (folderMode) {
            b.tvFolderTitle.visibility = View.GONE
            b.recyclerViewVideos.layoutManager = LinearLayoutManager(ctx)
            val folders = allVideos.groupBy { it.folderId }
                .map { (id, vids) -> FolderItem(id, vids.first().folderName.ifBlank { "/" }, vids.size) }
                .sortedBy { it.name.lowercase() }
            val fa = folderAdapter ?: FolderAdapter { folder ->
                currentFolder = folder
                render()
                updateBackCallback()
            }.also { folderAdapter = it }
            if (b.recyclerViewVideos.adapter !== fa) b.recyclerViewVideos.adapter = fa
            fa.submit(folders)
            updateEmpty(folders.isEmpty())
            return
        }

        // ভিডিও তালিকা
        val folder = currentFolder
        if (folder != null && query.isBlank()) {
            b.tvFolderTitle.visibility = View.VISIBLE
            b.tvFolderTitle.text = folder.name
        } else {
            b.tvFolderTitle.visibility = View.GONE
        }

        var videos = if (folder != null && query.isBlank()) allVideos.filter { it.folderId == folder.id } else allVideos
        if (query.isNotBlank()) videos = videos.filter { it.title.contains(query, ignoreCase = true) }
        videos = sortVideos(videos)

        if (videoAdapter == null || showingGrid != wantGrid) {
            showingGrid = wantGrid
            videoAdapter = VideoAdapter(wantGrid, ::openVideo, ::showContextMenu)
        }
        b.recyclerViewVideos.layoutManager = if (wantGrid) {
            val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            GridLayoutManager(ctx, (dp / 170).toInt().coerceAtLeast(2))
        } else LinearLayoutManager(ctx)
        if (b.recyclerViewVideos.adapter !== videoAdapter) b.recyclerViewVideos.adapter = videoAdapter
        videoAdapter?.submit(videos)
        updateEmpty(videos.isEmpty())
    }

    private fun sortVideos(list: List<Video>): List<Video> = when (AppPrefs.sortIndex) {
        1 -> list.sortedBy { it.dateAddedSec }
        2 -> list.sortedBy { it.title.lowercase() }
        3 -> list.sortedByDescending { it.title.lowercase() }
        4 -> list.sortedByDescending { it.sizeBytes }
        5 -> list.sortedBy { it.sizeBytes }
        6 -> list.sortedByDescending { it.durationMs }
        7 -> list.sortedBy { it.durationMs }
        else -> list.sortedByDescending { it.dateAddedSec }
    }

    private fun updateEmpty(isEmpty: Boolean) {
        val b = _binding ?: return
        if (!Perms.hasVideoAccess(requireContext())) {
            showEmptyState()
            return
        }
        if (isEmpty) {
            val partial = Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_MEDIA_VIDEO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            b.emptyState.visibility = View.VISIBLE
            b.recyclerViewVideos.visibility = View.GONE
            if (partial && query.isBlank()) {
                b.tvEmpty.setText(R.string.limited_access_message)
                b.btnEmptyAction.visibility = View.VISIBLE
                b.btnEmptyAction.setText(R.string.open_settings)
                b.btnEmptyAction.setOnClickListener { openAppSettings() }
            } else {
                b.tvEmpty.setText(if (query.isBlank()) R.string.no_videos else R.string.no_results)
                b.btnEmptyAction.visibility = View.GONE
            }
        } else {
            b.emptyState.visibility = View.GONE
            b.recyclerViewVideos.visibility = View.VISIBLE
        }
    }

    private fun showEmptyState() {
        val b = _binding ?: return
        b.emptyState.visibility = View.VISIBLE
        b.recyclerViewVideos.visibility = View.GONE
        b.tvEmpty.setText(R.string.permission_needed_message)
        b.btnEmptyAction.visibility = View.VISIBLE
        if (permanentlyDenied) {
            b.btnEmptyAction.setText(R.string.open_settings)
            b.btnEmptyAction.setOnClickListener { openAppSettings() }
        } else {
            b.btnEmptyAction.setText(R.string.grant_permission)
            b.btnEmptyAction.setOnClickListener {
                askedOnce = true
                permissionLauncher.launch(Perms.video())
            }
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null))
        )
    }

    // ------------------------------------------------------------------
    // প্লে
    // ------------------------------------------------------------------
    private fun openVideo(clicked: Video) {
        // বর্তমানে দেখানো তালিকার ক্রমেই প্লেলিস্ট। Intent এর সাইজ লিমিট এড়াতে আগে-পরে সর্বোচ্চ ১০০টি
        val current = videoAdapterItemsOrder()
        val index = current.indexOfFirst { it.id == clicked.id }.coerceAtLeast(0)
        val from = (index - 100).coerceAtLeast(0)
        val to = (index + 101).coerceAtMost(current.size)
        val window = current.subList(from, to)

        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putStringArrayListExtra(PlayerActivity.EXTRA_VIDEO_LIST, ArrayList(window.map { it.uri.toString() }))
            putStringArrayListExtra(PlayerActivity.EXTRA_VIDEO_TITLES, ArrayList(window.map { it.title }))
            putExtra(PlayerActivity.EXTRA_CURRENT_INDEX, index - from)
        }
        startActivity(intent)
    }

    /** এখন স্ক্রিনে যে ক্রমে ভিডিও দেখানো হচ্ছে সেই ক্রম */
    private fun videoAdapterItemsOrder(): List<Video> {
        val folder = currentFolder
        var videos = if (folder != null && query.isBlank()) allVideos.filter { it.folderId == folder.id } else allVideos
        if (query.isNotBlank()) videos = videos.filter { it.title.contains(query, ignoreCase = true) }
        return sortVideos(videos)
    }

    // ------------------------------------------------------------------
    // লং-প্রেস মেনু: Share / Rename / Delete / Info
    // ------------------------------------------------------------------
    private fun showContextMenu(video: Video, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.video_context_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.ctx_share -> shareVideo(video)
                R.id.ctx_rename -> promptRename(video)
                R.id.ctx_delete -> deleteVideo(video)
                R.id.ctx_info -> showInfo(video)
            }
            true
        }
        popup.show()
    }

    private fun shareVideo(video: Video) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, video.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun showInfo(video: Video) {
        val date = DateFormat.getMediumDateFormat(requireContext()).format(Date(video.dateAddedSec * 1000L))
        val res = if (video.width > 0) "${video.width} × ${video.height}" else getString(R.string.unknown)
        val message = getString(
            R.string.file_info_format,
            video.title, video.folderName, Fmt.size(video.sizeBytes), Fmt.duration(video.durationMs), res, date
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.info)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun promptRename(video: Video) {
        val ext = video.title.substringAfterLast('.', "")
        val baseName = if (ext.isEmpty()) video.title else video.title.removeSuffix(".$ext")
        val input = EditText(requireContext()).apply {
            setText(baseName)
            setSelection(baseName.length)
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newBase = input.text.toString().trim().replace("/", "")
                if (newBase.isNotEmpty() && newBase != baseName) {
                    val newName = if (ext.isEmpty()) newBase else "$newBase.$ext"
                    requestRename(video.uri, newName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestRename(uri: Uri, newName: String) {
        val cr = requireContext().contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingRename = uri to newName
            val pi = MediaStore.createWriteRequest(cr, listOf(uri))
            writeLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            performRename(uri, newName)
        }
    }

    private fun performRename(uri: Uri, newName: String) {
        val cr = requireContext().contentResolver
        try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
            val rows = cr.update(uri, values, null, null)
            if (rows > 0) {
                Toast.makeText(requireContext(), R.string.renamed, Toast.LENGTH_SHORT).show()
                loadVideos()
            } else {
                Toast.makeText(requireContext(), R.string.rename_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                pendingRename = uri to newName
                writeLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } else {
                Toast.makeText(requireContext(), R.string.rename_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.rename_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteVideo(video: Video) {
        val cr = requireContext().contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // সিস্টেম নিজেই নিশ্চিতকরণ ডায়ালগ দেখায়
            val pi = MediaStore.createDeleteRequest(cr, listOf(video.uri))
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_confirm, video.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    cr.delete(video.uri, null, null)
                    Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
                    loadVideos()
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                        deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                    } else {
                        Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(reloadRunnable)
        videoAdapter = null
        folderAdapter = null
        super.onDestroyView()
        _binding = null
    }
}
