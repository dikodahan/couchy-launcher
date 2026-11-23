package com.conreo.couchytv.data

internal object AerialCachePolicy {
    private const val MIB = 1024L * 1024L

    fun budgetBytes(lowRam: Boolean, availableBytes: Long): Long {
        // Leave breathing room for app updates and other apps on small TV flash storage.
        if (availableBytes < 512 * MIB) return 0L
        return minOf(availableBytes / 8, (if (lowRam) 128 else 256) * MIB)
    }
}
