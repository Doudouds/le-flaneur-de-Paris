package fr.parisfogofwar

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import okio.Path.Companion.toOkioPath

@HiltAndroidApp
class ParisFogApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .addInterceptor { chain ->
                                    chain.proceed(
                                        chain.request().newBuilder()
                                            .header("User-Agent", WIKIMEDIA_USER_AGENT)
                                            .header("Referer", "https://commons.wikimedia.org/")
                                            .build(),
                                    )
                                }
                                .build()
                        },
                    ),
                )
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("monument-image-cache").toOkioPath())
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .build()

    companion object {
        private const val WIKIMEDIA_USER_AGENT =
            "LeFlaneurDeParis/0.1 (https://www.mediawiki.org/wiki/API:Etiquette)"
    }
}
