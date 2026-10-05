package com.shehab360.videoplayerpro

import android.net.Uri

data class Video(
    val id: Long,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val uri: Uri,
    val dateAddedSec: Long,
    val folderId: Long,
    val folderName: String,
    val width: Int,
    val height: Int
)

data class Music(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri
)

data class FolderItem(
    val id: Long,
    val name: String,
    val count: Int
)

/** প্লেলিস্টের একটি আইটেম */
data class VideoItem(
    val uri: String,
    val title: String
)
