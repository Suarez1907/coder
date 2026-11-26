package com.suarez.coder.orchestrator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs

/**
 * Cheap visual regression check: both screenshots are reduced to a 32x32 grayscale grid and compared cell by cell.
 * It does not judge whether a change is good; it tells the reviewer how much of the screen moved.
 */
object VisualDiff {
    const val SIZE = 32
    /** Above this share of changed cells the evaluator mentions it to the reviewer. */
    const val NOTICE = 0.25
    /** A cell counts as changed above this gray-level difference (0..255): ignores anti-aliasing noise. */
    const val CELL_THRESHOLD = 24

    /** Share of cells (0..1) whose gray level differs noticeably. Inputs are SIZE*SIZE gray values. */
    fun ratio(a: IntArray, b: IntArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        return a.indices.count { abs(a[it] - b[it]) > CELL_THRESHOLD }.toDouble() / a.size
    }

    fun gray(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        return IntArray(px.size) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
    }

    fun compare(a: File, b: File): Double? = runCatching {
        val x = BitmapFactory.decodeFile(a.path) ?: return null
        val y = BitmapFactory.decodeFile(b.path) ?: return null
        ratio(gray(x), gray(y))
    }.getOrNull()
}
