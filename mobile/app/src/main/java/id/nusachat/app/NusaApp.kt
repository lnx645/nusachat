package id.nusachat.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

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

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .respectCacheHeaders(false)
            .build()
}
