package com.suarez.coder.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

/** Shrinks picked images on the device before they are sent to a vision model. */
object ImagePrep {
    const val MAX_EDGE = 1568
    const val JPEG_QUALITY = 80
    const val MAX_IMAGES = 3

    /** Target size keeping the aspect ratio, long edge at most [max] (never upscales). */
    fun targetSize(w: Int, h: Int, max: Int = MAX_EDGE): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= max) return w to h
        val scale = max.toDouble() / long
        return maxOf(1, Math.round(w * scale).toInt()) to maxOf(1, Math.round(h * scale).toInt())
    }

    /** Largest power-of-two subsampling that still decodes at least [max] on the long edge (keeps memory low). */
    fun sampleSize(w: Int, h: Int, max: Int = MAX_EDGE): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= max) s *= 2
        return s
    }

    /** Decodes, resizes and saves the image as JPEG under filesDir/images; returns the file path. */
    fun prepare(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Görsel okunamadı")
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("Görsel okunamadı")
        val (tw, th) = targetSize(decoded.width, decoded.height)
        val scaled = if (tw != decoded.width || th != decoded.height) Bitmap.createScaledBitmap(decoded, tw, th, true) else decoded
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + ".jpg")
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return out.absolutePath
    }
}
