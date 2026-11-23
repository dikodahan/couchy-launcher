package com.conreo.couchytv.data

/** Bound both axes before decoding, including very wide panoramas. */
internal object ImageSizing {
    fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        require(width > 0 && height > 0 && maxDimension > 0)
        var sample = 1
        while (maxOf(width, height) / sample > maxDimension.toLong() * 2) sample *= 2
        return sample
    }

    fun fit(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxDimension > 0)
        val scale = minOf(maxDimension.toDouble() / width, maxDimension.toDouble() / height, 1.0)
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }
}
