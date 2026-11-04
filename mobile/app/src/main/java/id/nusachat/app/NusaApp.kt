package id.nusachat.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.memory.MemoryCache

/** Batas bitmap Coil di RAM (default Coil persen dari memori app). */
private const val IMAGE_MEMORY_CACHE_BYTES = 32 * 1024 * 1024

/**
 * ImageLoader global Coil dengan decoder GIF animasi
 * (ImageDecoder di API 28+, GifDecoder di bawahnya).
 */
class NusaApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        id.nusachat.app.ui.i18n.applyAppLocale(
            id.nusachat.app.data.SettingsStore.savedLanguage(this)
        )
    }

    override fun newImageLoader(): ImageLoader {
        val app = this
        return ImageLoader.Builder(app)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder(app)
                    .maxSizeBytes(IMAGE_MEMORY_CACHE_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }
}
