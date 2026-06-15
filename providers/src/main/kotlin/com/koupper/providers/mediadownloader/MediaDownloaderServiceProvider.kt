package com.koupper.providers.mediadownloader

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class MediaDownloaderServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(MediaDownloaderProvider::class, {
            YtDlpMediaDownloader(
                ytDlpCommand = env("YTDLP_COMMAND", required = false, default = "yt-dlp"),
                ffmpegCommand = env("FFMPEG_COMMAND", required = false, default = "ffmpeg")
            )
        })
    }
}
