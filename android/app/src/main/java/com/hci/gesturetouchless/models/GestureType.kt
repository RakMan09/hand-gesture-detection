package com.hci.gesturetouchless.models

enum class HandGestureType {
    CALL,
    LIKE,
    DISLIKE,
    FIST,
    PALM,
    PEACE,
    ROCK,
    NONE;

    companion object {
        fun fromLabel(label: String): HandGestureType {
            return when (label.trim().lowercase()) {
                "call" -> CALL
                "like" -> LIKE
                "dislike" -> DISLIKE
                "fist" -> FIST
                "palm" -> PALM
                "peace" -> PEACE
                "rock" -> ROCK
                else -> NONE
            }
        }
    }
}