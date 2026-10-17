package com.hugo.player.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/** Cria ExoPlayers configurados para IPTV (HLS, TS, MP4/MKV). */
object HugoPlayerFactory {
    @OptIn(UnstableApi::class)
    fun create(context: Context, userAgent: String, lowLatencyPreview: Boolean = false): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val dataSource = DefaultDataSource.Factory(context, http)
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val loadControl = if (lowLatencyPreview) {
            DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 1_000, 2_000).build()
        } else {
            // buffers pensados para IPTV: começa rápido, segura oscilações
            DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_000, 4_000).build()
        }
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setLoadControl(loadControl)
            .build()
    }

    fun mediaItem(url: String, forceHls: Boolean = false): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        when {
            forceHls || StreamTypes.detect(url) == StreamTypes.Kind.HLS -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            StreamTypes.detect(url) == StreamTypes.Kind.TS -> builder.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        return builder.build()
    }
}
