package com.hci.gesturetouchless.models

import com.hci.gesturetouchless.utils.LandmarkUtils

object GestureMapping {

    private val handGestures: Map<String, GestureAction> = mapOf(
        "call" to GestureAction.VOICE_ASSISTANT,
        "like" to GestureAction.INCREASE_VOLUME,
        "dislike" to GestureAction.DECREASE_VOLUME,
        "fist" to GestureAction.PAUSE,
        "palm" to GestureAction.PLAY,
        "peace" to GestureAction.OPEN_CAMERA,
        "rock" to GestureAction.TAKE_SCREENSHOT
    )

    fun getAction(gestureName: String): GestureAction {
        val key = LandmarkUtils.normalizeLabel(gestureName)
        return handGestures[key] ?: GestureAction.NONE
    }
}
