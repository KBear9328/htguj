package com.example.mangareader.model

import android.graphics.Bitmap
import android.graphics.RectF

data class Panel(
    val rect: RectF,
    val bitmap: Bitmap,
    var ocrText: String = "",
    val readingOrder: Int = 0
)
