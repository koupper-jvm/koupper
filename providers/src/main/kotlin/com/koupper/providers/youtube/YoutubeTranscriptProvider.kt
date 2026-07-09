package com.koupper.providers.youtube

interface YoutubeTranscriptProvider {
    fun getTranscript(youtubeUrl: String): String
}
