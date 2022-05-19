package com.knowlgraph.speechtotextsimple

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.os.HandlerCompat
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class OncePlayerService(listener: OnPlayStateListener) {

    private var handler: Handler
    private var onPlayStateListener = listener

    private var mediaPlayer: MediaPlayer? = null

    init {
        val handlerThread = HandlerThread("OncePlayerService")
        handlerThread.start()
        handler = HandlerCompat.createAsync(handlerThread.looper)
    }

    fun play(url: String, startMsec: Int, endMsec: Int) {
        val player = MediaPlayer()
        val pause = object : Runnable {
            override fun run() {
                try {
                    if (player.isPlaying && player.currentPosition > endMsec) {
                        Log.d(TAG, "run: pause")
                        stop()
                    } else {
                        handler.postDelayed(this, 200)
                    }
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                }
            }
        }

        val executor: Executor = Executors.newSingleThreadExecutor()
        executor.execute {
            Log.d(TAG, "play: 0000 $startMsec: $endMsec> $url")

            player.setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setOnCompletionListener {
                Log.d(TAG, "play: completion")
                stop()
            }

            if (startMsec > 0) {
                player.setOnPreparedListener {
                    try {
                        player.seekTo(startMsec)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                    }
                }
            }

            try {
                player.setDataSource(url)
                player.prepare()
                player.start()
            } catch (e: IOException) {
                e.printStackTrace()
                stop()
            }

            onPlayStateListener.onPlayState(true)

            if (endMsec > startMsec) {
                handler.postDelayed(pause, 200)
            }
        }

        mediaPlayer = player
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)

        if (mediaPlayer != null) {
            stopMediaPlayer(mediaPlayer!!)
            mediaPlayer = null
        }

//        Exception("stop: media player").printStackTrace()
        handler.post { onPlayStateListener.onPlayState(false) }
    }

    private fun stopMediaPlayer(player: MediaPlayer) {
        if (player.isPlaying) {
            player.seekTo(0)
            player.pause()
            player.stop()
        }
        player.reset() // Fix: mediaplayer went away with unhandled events.
        player.release()
    }

    interface OnPlayStateListener {
        fun onPlayState(playing: Boolean)
    }

    companion object {
        const val TAG = "OncePlayerService"
    }
}