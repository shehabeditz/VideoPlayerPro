package com.shehab360.videoplayerpro

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** ভিডিও তালিকা: List ও Grid দুই লেআউট, থাম্বনেইল, প্রগ্রেস বার, NEW / HD / 4K ব্যাজ */
class VideoAdapter(
    private val grid: Boolean,
    private val onClick: (Video) -> Unit,
    private val onLongClick: (Video, View) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private var items: List<Video> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ivThumb)
        val title: TextView = view.findViewById(R.id.tvVideoTitle)
        val meta: TextView = view.findViewById(R.id.tvVideoMeta)
        val duration: TextView = view.findViewById(R.id.tvDuration)
        val badge: TextView = view.findViewById(R.id.tvBadge)
        val newBadge: TextView = view.findViewById(R.id.tvNew)
        val progress: ProgressBar = view.findViewById(R.id.progressWatched)
        var job: Job? = null
    }

    fun submit(list: List<Video>) {
        items = list
        notifyDataSetChanged()
    }

    /** প্লেয়ার থেকে ফিরে এলে প্রগ্রেস বার নতুন করে আঁকার জন্য */
    fun refreshProgress() = notifyDataSetChanged()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (grid) R.layout.item_video_grid else R.layout.item_video
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = items[position]
        val ctx = h.itemView.context

        h.title.text = v.title
        h.meta.text = Fmt.size(v.sizeBytes)
        h.duration.text = Fmt.duration(v.durationMs)

        val longest = max(v.width, v.height)
        val label = when {
            longest >= 3840 -> "4K"
            longest >= 1920 -> "FHD"
            longest >= 1280 -> "HD"
            else -> ""
        }
        h.badge.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        h.badge.text = label

        val uriStr = v.uri.toString()
        val savedPos = PlaybackStore.getPosition(uriStr)
        val isNew = !PlaybackStore.isPlayed(uriStr) && savedPos == 0L &&
            v.dateAddedSec * 1000L > System.currentTimeMillis() - NEW_WINDOW_MS
        h.newBadge.visibility = if (isNew) View.VISIBLE else View.GONE

        if (savedPos > 0L && v.durationMs > 0L) {
            h.progress.visibility = View.VISIBLE
            h.progress.progress = ((savedPos.toDouble() / v.durationMs) * 1000).toInt().coerceIn(0, 1000)
        } else {
            h.progress.visibility = View.GONE
        }

        h.job?.cancel()
        h.thumb.setImageDrawable(null)
        h.job = scope.launch {
            val bmp = ThumbnailLoader.load(ctx, v.uri)
            if (isActive && bmp != null) h.thumb.setImageBitmap(bmp)
        }

        h.itemView.setOnClickListener { onClick(v) }
        h.itemView.setOnLongClickListener {
            onLongClick(v, it)
            true
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel()
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        scope.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun getItemCount(): Int = items.size

    companion object {
        private const val NEW_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
    }
}

class FolderAdapter(
    private val onClick: (FolderItem) -> Unit
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    private var items: List<FolderItem> = emptyList()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvFolderName)
        val count: TextView = view.findViewById(R.id.tvFolderCount)
    }

    fun submit(list: List<FolderItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = items[position]
        h.name.text = f.name
        h.count.text = h.itemView.context.resources.getQuantityString(R.plurals.video_count, f.count, f.count)
        h.itemView.setOnClickListener { onClick(f) }
    }

    override fun getItemCount(): Int = items.size
}
