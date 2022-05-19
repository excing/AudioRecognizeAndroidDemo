package com.knowlgraph.speechtotextsimple.entity

data class RecognizeResultViewData(
    val id: Long,
    var text: String,
    var audio: String,
    var startTime: Float,
    var entTime: Float,
    var finished: Boolean,
    var playing: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RecognizeResultViewData

        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
