package com.hci.gesturetouchless.models

enum class GestureAction(val displayName: String) {
    VOICE_ASSISTANT("Voice assistant"),
    INCREASE_VOLUME("Increase volume"),
    DECREASE_VOLUME("Decrease volume"),
    PLAY("Play"),
    PAUSE("Pause"),
    OPEN_CAMERA("Open camera"),
    TAKE_SCREENSHOT("Screenshot"),
    NONE("None")
}