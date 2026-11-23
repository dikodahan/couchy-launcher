package com.conreo.couchytv.data

import android.app.ActivityManager
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** Played bytes only; cache failures always fall back to ordinary streaming. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AerialVideoCache {
    @Volatile private var instance: SimpleCache? = null

    /** Call on Dispatchers.IO before producing the first remote playlist. */
    @Synchronized
    fun warm(context: Context) {
        if (instance != null) return
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val budget = AerialCachePolicy.budgetBytes(manager.isLowRamDevice || manager.memoryClass <= 256,
            app.cacheDir.usableSpace)
        if (budget == 0L) return
        // Includes a possibly locked/corrupt cache or a full disk: playback must still work.
        instance = runCatching {
            val cache = SimpleCache(File(app.cacheDir, "aerial-video"), LeastRecentlyUsedCacheEvictor(budget),
                StandaloneDatabaseProvider(app))
            try { cache.checkInitialization(); cache }
            catch (error: Exception) { cache.release(); throw error }
        }.getOrNull()
    }

    /** Never initializes the disk cache on the player/UI thread. */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory {
        val cache = instance ?: return upstream
        return CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun bytesUsed(): Long = instance?.cacheSpace ?: 0L
}
