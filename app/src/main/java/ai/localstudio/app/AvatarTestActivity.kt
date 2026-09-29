package ai.localstudio.app

import ai.localstudio.app.avatar.AvatarGesture
import ai.localstudio.app.avatar.AvatarSpeechController
import ai.localstudio.app.databinding.ActivityAvatarTestBinding
import android.os.Bundle
import android.speech.tts.Voice
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity

/**
 * A standalone harness for the avatar pipeline (`AvatarView` +
 * [AvatarSpeechController] + `AvatarTtsEngine`) — independent of
 * [Settings.avatarEnabled] and of [ChatActivity]'s own wiring, so a voice or
 * a gesture can be auditioned without running an actual chat turn through a
 * model first. Owns its own [AvatarSpeechController] the same way
 * [ChatActivity] owns its avatar-enabled one: created in `onCreate`, torn
 * down in `onDestroy`.
 */
class AvatarTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAvatarTestBinding
    private lateinit var controller: AvatarSpeechController
    private lateinit var settings: Settings

    // Index-aligned with the spinner's own items; index 0 is always the
    // null "Auto" entry, everything after is a real device voice — see
    // populateVoicesWhenReady.
    private var voices: List<Voice?> = listOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAvatarTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets(applyImeInset = true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.menu_avatar_test)

        settings = AppContainer.get(this).settings
        controller = AvatarSpeechController(this, binding.avatarView, settings.avatarVoiceName)

        binding.avatarTestPlayButton.setOnClickListener { play() }
        binding.avatarTestStopButton.setOnClickListener { controller.onInterrupted() }

        // Each gesture button toggles a held expression; checked = currently held.
        val gestureButtons = mapOf(
            AvatarGesture.WINK_LEFT to binding.avatarTestWinkLeftButton,
            AvatarGesture.WINK_RIGHT to binding.avatarTestWinkRightButton,
            AvatarGesture.SURPRISE to binding.avatarTestSurpriseButton,
            AvatarGesture.BLINK to binding.avatarTestBlinkButton,
            AvatarGesture.SMILE to binding.avatarTestSmileButton,
            AvatarGesture.ANGER to binding.avatarTestAngerButton,
        )
        gestureButtons.forEach { (gesture, button) ->
            button.isCheckable = true
            button.setOnClickListener {
                binding.avatarView.toggleGesture(gesture)
                // Replacing a held gesture in the same group un-holds its sibling too.
                gestureButtons.forEach { (g, b) -> b.isChecked = binding.avatarView.isHeld(g) }
            }
        }

        populateVoicesWhenReady()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.shutdown()
    }

    private fun play() {
        val text = binding.avatarTestTextInput.text?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        // Treated as one complete answer, not a growing stream — onNewTurn
        // resets the chunker so a second Play doesn't try to diff this
        // text against whatever was typed (and already spoken) last time.
        controller.onNewTurn()
        controller.onGenerationDone(text)
    }

    // TextToSpeech's own init is asynchronous (see AvatarTtsEngine) — its
    // voices() list is empty until the engine reports ready, so this polls
    // briefly instead of populating the spinner with nothing on a slow
    // device.
    private fun populateVoicesWhenReady() {
        if (!controller.isReady) {
            binding.root.postDelayed(::populateVoicesWhenReady, VOICE_POLL_INTERVAL_MS)
            return
        }
        voices = listOf(null) + controller.availableVoices()
            .sortedWith(compareBy({ it.locale.toString() }, { it.name }))
        val labels = voices.map { voice ->
            if (voice == null) {
                getString(R.string.avatar_test_voice_auto)
            } else {
                val networkSuffix = if (voice.isNetworkConnectionRequired) " (network)" else ""
                "${voice.locale} — ${voice.name}$networkSuffix"
            }
        }
        binding.avatarTestVoiceSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        // Restore the remembered voice before the listener exists, so the
        // spinner's initial selection callback confirms it instead of
        // overwriting it with "Auto".
        val remembered = settings.avatarVoiceName
        val savedIndex = voices.indexOfFirst { it != null && it.name == remembered }
        if (savedIndex > 0) binding.avatarTestVoiceSpinner.setSelection(savedIndex, false)
        binding.avatarTestVoiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val voice = voices.getOrNull(position)
                controller.setVoice(voice)
                settings.avatarVoiceName = voice?.name
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        UtilityMenu.inflate(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        UtilityMenu.handle(this, item.itemId) || super.onOptionsItemSelected(item)

    private companion object {
        const val VOICE_POLL_INTERVAL_MS = 200L
    }
}
