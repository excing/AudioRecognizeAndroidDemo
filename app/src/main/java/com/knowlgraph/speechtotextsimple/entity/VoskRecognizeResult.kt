package com.knowlgraph.speechtotextsimple.entity

data class VoskRecognizeResult(
    val text: String?,
    val partial: String?,
    val result: List<VoskRecognizeWord>?,
)
