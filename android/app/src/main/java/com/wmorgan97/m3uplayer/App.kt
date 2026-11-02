package com.wmorgan97.m3uplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** Sizes Coil for a TV screen rather than a phone.
 *
 *  Nothing in this app ever set an `ImageLoader`, so every picture went through Coil's own default one: a memory
 *  cache at roughly 20% of the app's heap. A 4K home screen keeps far more pictures in play at once than a phone
 *  does - a settled screen's own rows, everything the Lazy lists prefetch just off top and bottom, and the spotlight's
 *  backdrop at native width - so that budget was gone almost immediately. A picture scrolled past minutes ago, and
 *  still sitting decoded in memory on a phone, was being evicted and decoded again here: 1,300+ `decodeBitmap` calls
 *  (Shield trace, D8) for the fifteen or so pictures actually on screen during one Home scroll, each a few
 *  milliseconds the main thread then waited on. A third of the heap for pictures, and a real disk cache so a picture
 *  already shrunk by `/img` (server/img.js) is never re-fetched after a cold start either, costs nothing else this
 *  app does and fixes both at once. */
class App : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.33).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizePercent(0.03).build() }
        .build()
}
