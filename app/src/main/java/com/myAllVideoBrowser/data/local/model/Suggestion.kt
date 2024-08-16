package com.myAllVideoBrowser.data.local.model

data class Suggestion(
    var content: String = "",

    var icon: Int = 0


) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Suggestion

        if (content != other.content) return false
        if (icon != other.icon) return false

        return true
    }

    override fun hashCode(): Int {
        var result = content.hashCode()
        result = 31 * result + icon
        return result
    }
}