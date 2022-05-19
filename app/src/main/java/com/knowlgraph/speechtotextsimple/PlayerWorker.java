package com.knowlgraph.speechtotextsimple;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;

public class PlayerWorker extends Worker {

    private static final String TAG = "PlayerWorker";
    private MediaPlayer mediaPlayer;

    public PlayerWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Data inputData = getInputData();

        String url = inputData.getString("url");
        int startMsec = inputData.getInt("start_msec", -1);
        int endMsec = inputData.getInt("end_msec", -1);

        Log.d(TAG, "doWork: 0000" + startMsec + ": " + endMsec + "> " + url);

        mediaPlayer = new MediaPlayer();

        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setOnCompletionListener(l -> {
            if (isStopped()) {
                return;
            }
            stopMediaPlayer();
        });
        if (startMsec > 0) {
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.seekTo(startMsec);
            });
        }
        if (endMsec > startMsec) {
            mediaPlayer.setOnBufferingUpdateListener((mp, percent) -> {
                Log.i(TAG, "doWork: onBufferingUpdate " + percent);
            });
            mediaPlayer.setOnTimedTextListener((mp, text) -> {
                Log.i(TAG, "doWork: onTimedText " + text);
            });
        }
        if (isStopped()) {
            return Result.success();
        }
        try {
            mediaPlayer.setDataSource(url);
            mediaPlayer.prepare();
        } catch (IOException e) {
            stopMediaPlayer();
            return Result.failure();
        }

        mediaPlayer.start();

        return Result.success();
    }

    @Override
    public void onStopped() {
        super.onStopped();
        stopMediaPlayer();
    }

    private void stopMediaPlayer() {
        if (mediaPlayer == null) return;

        if (mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
            mediaPlayer.seekTo(0);
        }
        mediaPlayer.release();
        mediaPlayer = null;
    }
}
