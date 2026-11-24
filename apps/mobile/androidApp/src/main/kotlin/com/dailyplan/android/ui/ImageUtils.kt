package com.dailyplan.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal fun decodeSampled(path: String, maxDimension: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    var sample = 1
    while (options.outWidth / sample > maxDimension * 2 ||
        options.outHeight / sample > maxDimension * 2
    ) {
        sample *= 2
    }
    val decoded = BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(decoded.width, decoded.height))
    if (scale >= 1f) return decoded
    val scaled = Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== decoded) decoded.recycle()
    return scaled
}
