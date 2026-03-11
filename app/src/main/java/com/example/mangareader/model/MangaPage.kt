package com.example.mangareader.model

import android.graphics.Bitmap

data class MangaPage(
    val bitmap: Bitmap,
    val panels: List<Panel>,
    val pageNumber: Int,
    val fileName: String
)
