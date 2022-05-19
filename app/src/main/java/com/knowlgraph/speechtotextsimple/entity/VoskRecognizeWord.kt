package com.knowlgraph.speechtotextsimple.entity

import com.google.gson.annotations.SerializedName

data class VoskRecognizeWord(
    @SerializedName("word") val word: String,
    @SerializedName("start") val startTime: Float,
    @SerializedName("end") val entTime: Float,
    @SerializedName("conf") val accuracy: Float,
)
