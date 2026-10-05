package com.shehab360.videoplayerpro

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int {
        return 2 // Videos এবং Music দুইটি ট্যাব
    }

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> VideosFragment()
            1 -> MusicFragment()
            else -> VideosFragment()
        }
    }
}