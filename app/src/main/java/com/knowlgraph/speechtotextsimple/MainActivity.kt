package com.knowlgraph.speechtotextsimple

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.HandlerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.knowlgraph.speechtotextsimple.entity.RecognizeResultViewData
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.SupportPreconditions
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.vosk.Model
import java.io.File
import java.lang.Runnable as Runnable1

// 前进、后退、快来、快走、上下、左右、播放、暂停、快进、停止、工作、下一个、上一个、继续。

class MainActivity : AppCompatActivity(), RecognizeService.OnRecognizeResultListener,
    OncePlayerService.OnPlayStateListener {

    private lateinit var handler: Handler // background thread handler to run classification

    private var audioClassifier: AudioClassifier? = null
    private var audioRecord: AudioRecord? = null
    private var classificationInterval = 500L // how often should classification run in milli-secs

    private var voskModel: Model? = null
    private var recognizeService: RecognizeService? = null

    private var oncePlayerService = OncePlayerService(this)

    private lateinit var resultRecyclerView: RecyclerView
    private val recognizeResults = ArrayList<RecognizeResultViewData>()

    private lateinit var audioRecognizeToggleButton: ToggleButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val getContent = registerForActivityResult(GetContent()) { uri: Uri? ->
            // Handle the returned Uri
            Log.i(TAG, "openVoskModel: $uri")
            if (uri == null) {
                Snackbar.make(findViewById(R.id.container), "请选择一个模型", Snackbar.LENGTH_LONG).show()
            } else {
                loadVoskModel(uri)
            }
        }

        resultRecyclerView = findViewById(R.id.resultRecyclerView)
        resultRecyclerView.layoutManager = LinearLayoutManager(this)
        resultRecyclerView.adapter = resultRecyclerAdapter

        findViewById<Button>(R.id.openVoskModel).setOnClickListener {
            getContent.launch("application/zip")
        }

        audioRecognizeToggleButton = findViewById(R.id.audioRecognize)
        audioRecognizeToggleButton.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) tryStartAudioClassification()
            else stopAudioClassification()
        }
        findViewById<Button>(R.id.playRecognizeAudio).setOnClickListener {
            downloadVoskModel()
        }

        // Create a handler to run classification in a background thread
        val handlerThread = HandlerThread("backgroundThread")
        handlerThread.start()
        handler = HandlerCompat.createAsync(handlerThread.looper)

        val cacheRecord = File(cacheDir, "record")
        deleteRecordCache(cacheRecord)
    }

    private val resultRecyclerAdapter = object : RecyclerView.Adapter<SimpleViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SimpleViewHolder {
            val itemView = LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.simple_layout_recognize_result, null)
            itemView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return SimpleViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: SimpleViewHolder, position: Int) {
            val textView: TextView = holder.get(R.id.recognizeResultTextView)
            val switch: Switch = holder.get(R.id.audioPlayStatusSwitch)

            val result = recognizeResults[position]
            textView.text = result.text

            switch.isEnabled = result.finished
            switch.isChecked = result.playing
            switch.text = if (switch.isChecked) "停止" else "播放"
            switch.setOnClickListener {
                if (switch.isChecked) {
                    playRecognizeAudio(
                        result.audio,
                        result.startTime,
                        result.entTime
                    )
                    result.playing = true
                    switch.text = "停止"
                } else {
                    switch.text = "播放"
                    oncePlayerService.stop()
                }
            }
        }

        override fun getItemCount(): Int {
            return recognizeResults.size
        }

    }

    private fun downloadVoskModel() {
        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        intent.data = Uri.parse("https://alphacephei.com/vosk/models")
        startActivity(intent)
    }

    private fun playRecognizeAudio(url: String, start: Float, end: Float) {
        if (audioRecognizeToggleButton.isChecked) {
            audioRecognizeToggleButton.performClick()
        }
        oncePlayerService.stop()
        oncePlayerService.play(
            url,
            (start * 1000).toInt(),
            (end * 1000).toInt()
        )
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        // Handles "top" resumed event on multi-window environment

        if (!audioRecognizeToggleButton.isChecked) {
            return
        }

        if (isTopResumedActivity) tryStartAudioClassification()
        else stopAudioClassification()
    }

    private fun tryStartAudioClassification() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startAudioClassification()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Audio permission granted :)")
                startAudioClassification()
            } else {
                Log.e(TAG, "Audio permission not granted :(")
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    @SuppressLint("MissingPermission")
    private fun startAudioClassification() {
        // 如果 Vosk 模型未加载，则什么都不做。
        if (null == voskModel) return

        // If the audio classifier is initialized and running, do nothing.
        if (audioClassifier != null) return

        recognizeResults.clear()
        resultRecyclerAdapter.notifyDataSetChanged()

        val cacheRecord = File(cacheDir, "record")
        deleteRecordCache(cacheRecord)
        cacheRecord.mkdirs()

        // Initialize the audio classifier
        val classifier = AudioClassifier.createFromFile(this, MODEL_FILE)
        val audioTensor = classifier.createInputTensorAudio()

        val recognizer = RecognizeService(voskModel!!, cacheRecord, this)

        // Initialize the audio recorder

        val channelConfig: Byte = when (audioTensor.format.channels) {
            1 -> 16
            2 -> 12
            else -> throw IllegalArgumentException(
                String.format(
                    "Number of channels required by the model is %d. getAudioRecord method only supports 1 or 2 audio channels.",
                    audioTensor.format.channels
                )
            )
        }

        var bufferSizeInBytes = AudioRecord.getMinBufferSize(
            audioTensor.format.sampleRate,
            channelConfig.toInt(),
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val record: AudioRecord

        if (bufferSizeInBytes != -1 && bufferSizeInBytes != -2) {
            val bufferSizeMultiplier = 2
            val modelRequiredBufferSize =
                classifier.requiredInputBufferSize.toInt() * DataType.FLOAT32.byteSize() * bufferSizeMultiplier
            if (bufferSizeInBytes < modelRequiredBufferSize) {
                bufferSizeInBytes = modelRequiredBufferSize
            }
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, audioTensor.format.sampleRate,
                channelConfig.toInt(), AudioFormat.ENCODING_PCM_FLOAT, bufferSizeInBytes
            )
            SupportPreconditions.checkState(
                record.state == AudioRecord.STATE_INITIALIZED,
                "AudioRecord failed to initialize"
            )
        } else {
            throw IllegalStateException(
                String.format(
                    "AudioRecord.getMinBufferSize failed. Returned: %d",
                    bufferSizeInBytes
                )
            )
        }

        record.startRecording()

        val audioClassifierBuffer = FloatArray(TF_AUDIO_CLASSIFIER_MINI_AUDIO_LENGTH)
        val newData = FloatArray(record.channelCount * record.bufferSizeInFrames)

        // Define the classification runnable
        val run = object : Runnable1 {
            override fun run() {
                val startTime = System.currentTimeMillis()

                // Load the latest audio sample
                val nidx = 0
                var nread = record.read(newData, nidx, newData.size, AudioRecord.READ_NON_BLOCKING)
                val startSpeech = isSpeech(newData, nread) > 0
                var speeching = startSpeech
                if (startSpeech) {
                    recognizer.start()
                }

                var lastTime = System.currentTimeMillis()
                while (speeching && nread >= 0 && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    if (nread > 0) {
                        Log.i(TAG, "startAudioClassification: $nread")

                        recognizer.recognize(newData, nidx, nread)
                    }

                    nread = record.read(newData, 0, newData.size, AudioRecord.READ_NON_BLOCKING)

                    if (System.currentTimeMillis() - lastTime > classificationInterval) {
                        recognizer.copyArray(
                            audioClassifierBuffer,
                            TF_AUDIO_CLASSIFIER_MINI_AUDIO_LENGTH - nread
                        )
                        System.arraycopy(
                            newData,
                            0,
                            audioClassifierBuffer,
                            TF_AUDIO_CLASSIFIER_MINI_AUDIO_LENGTH - nread,
                            nread
                        )
                        speeching = isSpeech(
                            audioClassifierBuffer,
                            TF_AUDIO_CLASSIFIER_MINI_AUDIO_LENGTH
                        ) > 0
                        if (speeching) {
                            lastTime = System.currentTimeMillis()
                        }
                    }
                }

                if (startSpeech) {
                    recognizer.stop()
                }

                val finishTime = System.currentTimeMillis()

                Log.d(TAG, "Latency = ${finishTime - startTime}ms")

                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    // Rerun the classification after a certain interval
                    handler.postDelayed(this, classificationInterval)
                }
            }

            fun isSpeech(data: FloatArray, bufferLength: Int): Int {
                if (bufferLength <= 0) return bufferLength

                audioTensor.load(data, 0, bufferLength)

                val output = classifier.classify(audioTensor)

                // Filter out results above a certain threshold, and sort them descendingly
                val filteredModelOutput = output[0].categories.filter {
                    it.score > MINIMUM_DISPLAY_THRESHOLD
                }.sortedBy {
                    -it.score
                }

                val isSpeeching: Boolean = if (filteredModelOutput.isNotEmpty()) {
                    "Speech" == filteredModelOutput[0].label
                } else {
                    false
                }

                Log.i(TAG, "isSpeech($bufferLength): $filteredModelOutput")

                return if (isSpeeching) bufferLength else -1
            }
        }

        // Start the classification process
        handler.post(run)

        // Save the instances we just created for use later
        audioClassifier = classifier
        audioRecord = record

        recognizeService = recognizer
    }

    private fun deleteRecordCache(cacheRecord: File) {
        if (!cacheRecord.delete()) {
            val files = cacheRecord.listFiles()
            if (files != null) {
                for (file in files) {
                    if (!file.delete()) {
                        Log.i(TAG, "startAudioClassification: delete ${file.name} failed")
                    }
                }
            }
        }
    }

    private fun stopAudioClassification() {
        handler.removeCallbacksAndMessages(null)
        audioRecord?.release()
        audioRecord = null
        audioClassifier?.close()
        audioClassifier = null

        recognizeService?.release()
        recognizeService = null
    }

    override fun onResult(
        id: Long,
        wavePath: String,
        text: String,
        startTime: Float,
        endTime: Float,
        finished: Boolean
    ) {
//        Log.d(TAG, "onResult: $id, $wavePath, $text, $startTime, $endTime, $finished")
        var ok = false
        for (item in recognizeResults) {
            if (item.id == id) {
                item.audio = wavePath
                item.text = text
                item.startTime = startTime
                item.entTime = endTime
                item.finished = finished

                ok = true

                break
            }
        }

        if (!ok) {
            recognizeResults.add(
                RecognizeResultViewData(
                    id,
                    text,
                    wavePath,
                    startTime,
                    endTime,
                    finished,
                    false
                )
            )
        }

        runOnUiThread {
            resultRecyclerAdapter.notifyDataSetChanged()
            resultRecyclerView.scrollToPosition(resultRecyclerAdapter.itemCount - 1)
        }
    }

    override fun onPlayState(playing: Boolean) {
        Log.d(TAG, "onPlayState: $playing")
        if (!playing) {
            for (result in recognizeResults) {
                if (result.playing) {
                    result.playing = false
                }
            }
            runOnUiThread {
                resultRecyclerAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun loadVoskModel(uri: Uri) {
        ModelService.unpack(this, uri, { _model ->
            voskModel = _model
            runOnUiThread {
                resultRecyclerAdapter.notifyDataSetChanged()
                audioRecognizeToggleButton.isEnabled = true
            }
        }, { s ->
            recognizeResults.add(
                RecognizeResultViewData(
                    System.currentTimeMillis(),
                    s,
                    "",
                    0f,
                    0f,
                    finished = false,
                    playing = false
                )
            )
            runOnUiThread { resultRecyclerAdapter.notifyDataSetChanged() }
        })
    }

    companion object {
        const val REQUEST_RECORD_AUDIO = 1337
        private const val TAG = "MainActivity"
        private const val MODEL_FILE = "yamnet.tflite"
        private const val MINIMUM_DISPLAY_THRESHOLD: Float = 0.3f
        private const val TF_AUDIO_CLASSIFIER_MINI_AUDIO_LENGTH = 9600
    }
}