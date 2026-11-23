package com.conreo.couchytv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser
import java.io.BufferedReader
import java.util.Locale

internal class LibraryIcon(
    val name: String,
    aliases: String,
    private val width: Int,
    private val height: Int,
    private val pathData: String,
) {
    val label = name.replace('-', ' ')
    internal val searchText = "$label ${aliases.replace('-', ' ')}"

    fun render(size: Int): Bitmap {
        val path = PathParser().parsePathString(pathData).toPath().asAndroidPath()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = size.toFloat() / maxOf(width, height)
        val x = (size - width * scale) / 2f
        val y = (size - height * scale) / 2f
        Canvas(bitmap).apply {
            translate(x, y)
            scale(scale, scale)
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        return bitmap
    }
}

internal class IconLibrary private constructor(private val icons: List<LibraryIcon>) {
    fun search(query: String): List<LibraryIcon> {
        val words = query.trim().lowercase(Locale.ROOT)
            .split(SEARCH_SEPARATOR).filter { it.isNotEmpty() }
        if (words.isEmpty()) return icons

        val phrase = words.joinToString(" ")
        val matches = icons.filter { icon -> words.all { word -> word in icon.searchText } }
        return matches.sortedBy { icon ->
            when {
                icon.label == phrase -> 0
                icon.label.startsWith(phrase) -> 1
                phrase in icon.label -> 2
                else -> 3
            }
        }
    }

    companion object {
        private val SEARCH_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
        private var catalog: IconLibrary? = null

        @Synchronized
        fun load(context: Context): IconLibrary =
            catalog ?: context.assets.open("material-symbols.tsv").bufferedReader()
                .use(::read).also { catalog = it }

        internal fun read(reader: BufferedReader): IconLibrary = IconLibrary(reader.lineSequence().map { line ->
            val (name, aliases, width, height, path) = line.split('\t', limit = 5)
            LibraryIcon(name, aliases, width.toInt(), height.toInt(), path)
        }.toList())
    }
}
