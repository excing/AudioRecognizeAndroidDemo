package com.knowlgraph.speechtotextsimple

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.os.HandlerCompat
import com.google.gson.Gson
import com.knowlgraph.speechtotextsimple.entity.VoskRecognizeResult
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

class RecognizeService
    (model: Model, cacheDir: File, listener: OnRecognizeResultListener) {

    //        val rec = Recognizer(
    //            voskModel, 16000f, "[\"one zero zero zero one\", " +
    //                    "\"oh zero one two three four five six seven eight nine\", \"[unk]\"]"
    //        )

    private var handler: Handler // background thread handler to run recognize
    private var recognizer: Recognizer = Recognizer(model, DEFAULT_SAMPLE_RAT)

    private var recognizeBuffer: ShortArray
    private var recognizeIndex = 0

    private var audioBuffer: ShortArray
    private var audioBufferLen = 0

    private var recognizing = false
    private var onRecognizeResultListener = listener

    private var audioCacheDir = cacheDir
    private var audioNumber = 0

    // 用于重新计算录音开始时间点
    private var audioDuration = 0f
    private var needAgainCalculateAudioDuration = false

    init {
        val handlerThread = HandlerThread("RecognizeService")
        handlerThread.start()
        handler = HandlerCompat.createAsync(handlerThread.looper)
        audioBuffer = ShortArray((DEFAULT_SAMPLE_RAT * 2).toInt())
        recognizeBuffer = ShortArray((DEFAULT_SAMPLE_RAT * 0.2f).toInt())
    }

    fun start() {
        recognizer.reset()
        recognizer.setWords(true)
        recognizeIndex = 0
        audioBufferLen = 0
        needAgainCalculateAudioDuration = true
        recognizing = true
        audioNumber++

        val run = Runnable {
            val gson = Gson()

            val wavFile = File(audioCacheDir, "test-$audioNumber.wav")
            var id = System.currentTimeMillis()

            while (recognizing) {
                if (recognizeIndex >= audioBufferLen) {
                    Thread.sleep(100)
                }
                val recognizeLen =
                    (audioBufferLen - recognizeIndex).coerceAtMost(recognizeBuffer.size)
                if (recognizeLen < recognizeBuffer.size) {
                    continue
                }
                System.arraycopy(audioBuffer, recognizeIndex, recognizeBuffer, 0, recognizeLen)
                recognizeIndex += recognizeLen

                if (!recognizing) break

                if (recognizer.acceptWaveForm(recognizeBuffer, recognizeLen)) {
                    postRecognizeResult(gson, id, wavFile.absolutePath, recognizer.result, true)

                    // 每句结束后，重新初始化一个
                    id = System.currentTimeMillis()
                } else {
                    postRecognizeResult(
                        gson,
                        id,
                        wavFile.absolutePath,
                        recognizer.partialResult,
                        false
                    )
                }
            }

            postRecognizeResult(gson, id, wavFile.absolutePath, recognizer.finalResult, true)
        }

        handler.post(run)
    }

    private fun postRecognizeResult(
        gson: Gson,
        id: Long,
        wavePath: String,
        text: String,
        finished: Boolean
    ) {
        Log.v(TAG, "postRecognizeResult 0000: $text, $finished")

        val data = gson.fromJson(text, VoskRecognizeResult::class.java)

        val resultText = (if (finished) data.text else data.partial) ?: return
        if ("" == resultText) return

        var startTime = 0.0f
        var endTime = 0.0f
        if (finished && data != null && data.result != null && data.result.isNotEmpty()) {
            val startWord = data.result[0]
            startTime = startWord.startTime

            endTime = if (data.result.size == 1) {
                startWord.entTime
            } else {
                val endWord = data.result[data.result.size - 1]
                endWord.entTime
            }

            if (needAgainCalculateAudioDuration) {
                audioDuration  = startTime
                needAgainCalculateAudioDuration = false
            }
        }

        // 只要录音一直工作，那么识别出来的起止时间就是录音时长，而非实际识别的语音时长。
        // 所以此处需要重新计算语音时长
        startTime -= audioDuration
        endTime -= audioDuration

        onRecognizeResultListener.onResult(
            id,
            wavePath,
            resultText,
            startTime,
            endTime,
            finished
        )
    }

    fun recognize(data: FloatArray, offset: Int, length: Int) {
        if (audioBufferLen + length >= audioBuffer.size) {
            val newAudioBuffer = ShortArray(audioBuffer.size * 2)
            System.arraycopy(
                audioBuffer,
                0,
                newAudioBuffer,
                0,
                audioBufferLen
            )
            audioBuffer = newAudioBuffer
        }
        for (i in offset until (length + offset)) {
            audioBuffer[audioBufferLen++] = (data[i] * 32768).toInt().toShort()
        }
    }

    fun stop() {
        recognizing = false
        makeAndSaveWave(audioBuffer, audioBufferLen, File(audioCacheDir, "test-$audioNumber.wav"))
//        recognizeIndex = 0
//        audioBufferLen = 0
    }

    fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
        recognizer.reset()
        recognizer.close()
        audioBuffer = ShortArray(0)
        recognizeBuffer = ShortArray(0)
        audioNumber = 0
    }

    fun copyArray(array: FloatArray, length: Int) {
        val offset = audioBufferLen - length
        for (i in 0 until length) {
            array[i] = (audioBuffer[i + offset] / 32768.0f)
        }
    }

    private fun makeAndSaveWave(data: ShortArray, size: Int, waveFile: File) {
        if (size == 0) return

        val dataSize = size * 2

        // 填入参数，比特率等等。这里用的是16位单声道 8000 hz

        // 填入参数，比特率等等。这里用的是16位单声道 8000 hz
        val header = WaveHeader()
        // 长度字段 = 内容的大小（TOTAL_SIZE) +
        // 头部字段的大小(不包括前面4字节的标识符RIFF以及fileLength本身的4字节)
        // 长度字段 = 内容的大小（TOTAL_SIZE) +
        // 头部字段的大小(不包括前面4字节的标识符RIFF以及fileLength本身的4字节)
        header.fileLength = dataSize + (44 - 8)
        header.FmtHdrLeth = 16
        header.BitsPerSample = 16
        header.Channels = 2
        header.FormatTag = 0x0001
        header.SamplesPerSec = 8000
        header.BlockAlign = (header.Channels * header.BitsPerSample / 8).toShort()
        header.AvgBytesPerSec = header.BlockAlign * header.SamplesPerSec
        header.DataHdrLeth = dataSize

        val wavHeaderBytes: ByteArray = header.header
        val pcmBytes = ByteArray(size * 2)
        for (i in 0 until size) {
            pcmBytes[i * 2] = data[i].toByte()
            pcmBytes[i * 2 + 1] = (data[i].toInt() shr 8).toByte()
        }

        waveFile.deleteOnExit()
        val ouStream = BufferedOutputStream(FileOutputStream(waveFile))
        ouStream.write(wavHeaderBytes)
        ouStream.write(pcmBytes)
        ouStream.close()

        Log.i(TAG, "makeAndSaveWave: size is $dataSize, path is " + waveFile.absoluteFile)
    }

    interface OnRecognizeResultListener {
        fun onResult(
            id: Long,
            wavePath: String,
            text: String,
            startTime: Float,
            endTime: Float,
            finished: Boolean
        )
    }

    companion object {
        private const val TAG = "RecognizeService"
        private const val DEFAULT_SAMPLE_RAT = 16000f
    }
}