package com.shehab360.videoplayerpro

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shehab360.videoplayerpro.databinding.ActivityHistoryBinding

/** "Continue watching": দেখা ভিডিওর তালিকা (সর্বশেষ আগে) */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val adapter = HistoryAdapter(
        onClick = { entry ->
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_VIDEO_URI, entry.uri)
                    .putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, entry.title.ifEmpty { null })
            )
        },
        onLongClick = { entry, anchor ->
            PopupMenu(this, anchor).apply {
                menu.add(R.string.remove_from_history)
                setOnMenuItemClickListener {
                    PlaybackStore.removeHistory(entry.uri)
                    reload()
                    true
                }
            }.show()
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.historyRoot.applySystemBarsPadding()
        ThemeManager.styleSystemBars(this)

        binding.historyToolbar.setNavigationOnClickListener { finish() }
        binding.historyToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_history) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.clear_history)
                    .setMessage(R.string.clear_history_confirm)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        PlaybackStore.clearHistory()
                        reload()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            } else false
        }

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val list = PlaybackStore.getHistory()
        adapter.submit(list)
        binding.tvHistoryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}

private class HistoryAdapter(
    private val onClick: (HistoryEntry) -> Unit,
    private val onLongClick: (HistoryEntry, View) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private var items: List<HistoryEntry> = emptyList()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvHistoryTitle)
        val meta: TextView = view.findViewById(R.id.tvHistoryMeta)
    }

    fun submit(list: List<HistoryEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.title.text = e.title.ifEmpty { e.uri.substringAfterLast('/') }
        val whenText = DateUtils.getRelativeTimeSpanString(
            e.lastPlayed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        val pos = PlaybackStore.getPosition(e.uri)
        h.meta.text = if (pos > 0L && e.duration > 0L) {
            "$whenText • ${Fmt.duration(pos)} / ${Fmt.duration(e.duration)}"
        } else whenText.toString()
        h.itemView.setOnClickListener { onClick(e) }
        h.itemView.setOnLongClickListener {
            onLongClick(e, it)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}
