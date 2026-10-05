package com.shehab360.videoplayerpro

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shehab360.videoplayerpro.databinding.ItemMusicBinding

class MusicAdapter(
    private val onClick: (Music) -> Unit
) : RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    private var items: List<Music> = emptyList()

    class MusicViewHolder(val binding: ItemMusicBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<Music>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder =
        MusicViewHolder(ItemMusicBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        val music = items[position]
        holder.binding.tvMusicTitle.text = music.title
        holder.binding.tvMusicMeta.text = "${music.artist} • ${Fmt.duration(music.durationMs)}"
        holder.itemView.setOnClickListener { onClick(music) }
    }

    override fun getItemCount(): Int = items.size
}
