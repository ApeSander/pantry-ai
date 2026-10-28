package com.pantryai.ai

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** On-device text recognition (ML Kit, bundled model: works offline). */
object ReceiptOcr {
    private data class Piece(val text: String, val left: Int, val top: Int, val bottom: Int) {
        val cy get() = (top + bottom) / 2.0
        val h get() = (bottom - top).coerceAtLeast(1)
    }

    /**
     * Returns receipt rows. ML Kit reads the product name and the price as separate blocks;
     * we glue pieces that sit on the same horizontal line back together.
     */
    suspend fun readRows(context: Context, uri: Uri): List<String> {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        recognizer.close()
        val pieces = text.textBlocks.flatMap { b -> b.lines }.mapNotNull { l ->
            val r = l.boundingBox ?: return@mapNotNull null
            Piece(l.text, r.left, r.top, r.bottom)
        }.sortedBy { it.cy }
        if (pieces.isEmpty()) return text.text.lines()
        val medianH = pieces.map { it.h }.sorted()[pieces.size / 2]
        val rows = mutableListOf<MutableList<Piece>>()
        for (p in pieces) {
            val row = rows.lastOrNull()
            if (row != null && abs(row.map { it.cy }.average() - p.cy) < medianH * 0.55) row += p else rows += mutableListOf(p)
        }
        return rows.map { r -> r.sortedBy { it.left }.joinToString("  ") { it.text } }
    }
}
