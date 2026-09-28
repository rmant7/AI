package ai.localstudio.app.avatar

/**
 * What [AvatarView] actually renders, one frame at a time — deliberately
 * separate from whatever drives it (TTS callbacks, idle animators): the
 * view only ever reads this, never reaches back into
 * [AvatarSpeechController] or `android.speech.tts.TextToSpeech` itself.
 *
 * [mouthOpen]/[blink] are `0f..1f` fractions, not pixels — [AvatarView]
 * decides what "fully open" actually measures against its own drawn size.
 */
data class AvatarState(
    val mouthOpen: Float = 0f,
    val mouthShape: MouthShape = MouthShape.CLOSED,
    val blink: Float = 0f,
    val headYaw: Float = 0f,
    val headPitch: Float = 0f,
)
