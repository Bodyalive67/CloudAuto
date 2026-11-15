package it.cloudauto

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Servizio multimediale: espone le cartelle di Yandex Disk come albero
 * sfogliabile (Android Auto, Bluetooth, Assistant) e riproduce i file audio.
 */
@OptIn(UnstableApi::class)
class MusicLibraryService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var api: YandexDiskApi
    private val executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4))

    override fun onCreate() {
        super.onCreate()
        api = YandexDiskApi { CredentialStore.get(applicationContext) }

        // Il link di download viene risolto al momento della lettura,
        // così non scade mentre il brano è in coda.
        val resolver = ResolvingDataSource.Resolver { spec ->
            if (spec.uri.scheme == SCHEME) {
                val path = spec.uri.getQueryParameter("path")
                    ?: throw IOException("Percorso mancante")
                val source = api.resolve(path)
                val resolved = spec.withUri(Uri.parse(source.url))
                if (source.headers.isEmpty()) resolved else resolved.withAdditionalHeaders(source.headers)
            } else {
                spec
            }
        }
        val dataSourceFactory = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory(), resolver)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        executor.shutdown()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = if (mediaId == ROOT_ID) {
                rootItem()
            } else {
                MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(playUri(mediaId))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(mediaId.substringAfterLast('/').substringBeforeLast('.'))
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .build(),
                    )
                    .build()
            }
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            executor.submit(
                Callable<LibraryResult<ImmutableList<MediaItem>>> {
                    try {
                        val path = if (parentId == ROOT_ID) "/" else parentId
                        val items = api.list(path).map { it.toMediaItem() }
                        val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
                        val to = (from.toLong() + pageSize).coerceAtMost(items.size.toLong()).toInt()
                        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
                    } catch (e: YandexDiskApi.AuthException) {
                        LibraryResult.ofError<ImmutableList<MediaItem>>(
                            SessionResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED,
                        )
                    } catch (e: IOException) {
                        LibraryResult.ofError<ImmutableList<MediaItem>>(SessionResult.RESULT_ERROR_IO)
                    }
                },
            )

        /** Aggiunge l'URI riproducibile agli item che arrivano dai controller (hanno solo mediaId). */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.withPlayableUri() }.toMutableList())

        /**
         * Quando si tocca un brano, mette in coda tutti i brani della stessa cartella
         * e parte da quello scelto.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            executor.submit(
                Callable<MediaSession.MediaItemsWithStartPosition> {
                    val single = mediaItems.singleOrNull()
                    if (single != null && single.mediaId != ROOT_ID) {
                        try {
                            val siblings = api.list(parentOf(single.mediaId))
                                .filter { !it.isDir }
                                .map { it.toMediaItem() }
                            val index = siblings.indexOfFirst { it.mediaId == single.mediaId }
                            if (index >= 0) {
                                return@Callable MediaSession.MediaItemsWithStartPosition(
                                    siblings,
                                    index,
                                    startPositionMs,
                                )
                            }
                        } catch (_: IOException) {
                            // ripiega sul singolo brano
                        }
                    }
                    MediaSession.MediaItemsWithStartPosition(
                        mediaItems.map { it.withPlayableUri() },
                        startIndex,
                        startPositionMs,
                    )
                },
            )
    }

    // ---- helper ----

    private fun rootItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Yandex Disk")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    private fun DiskEntry.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(if (isDir) name else name.substringBeforeLast('.'))
            .setIsBrowsable(isDir)
            .setIsPlayable(!isDir)
            .setMediaType(
                if (isDir) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_MUSIC,
            )
            .build()
        val builder = MediaItem.Builder().setMediaId(path).setMediaMetadata(metadata)
        if (!isDir) {
            builder.setUri(playUri(path))
            if (mimeType?.startsWith("audio/") == true) builder.setMimeType(mimeType)
        }
        return builder.build()
    }

    private fun MediaItem.withPlayableUri(): MediaItem =
        if (localConfiguration != null) this else buildUpon().setUri(playUri(mediaId)).build()

    private fun playUri(path: String): Uri =
        Uri.Builder().scheme(SCHEME).authority("file").appendQueryParameter("path", path).build()

    private fun parentOf(path: String): String {
        return path.substringBeforeLast('/').ifEmpty { "/" }
    }

    companion object {
        private const val ROOT_ID = "root"
        private const val SCHEME = "yadisk"
    }
}
