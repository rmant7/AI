package ai.localstudio.app

import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the app actually runs, rather than merely compiling.
 *
 * With no endpoint configured the stub runtime answers, so this exercises the
 * real path — router, pipeline builder, validator, model selection, context
 * assembly, memory — on a real Android runtime, with no network involved.
 *
 * The chat is driven by setting the field and clicking the button directly
 * rather than through Espresso's typing: Espresso synchronises on an idle main
 * looper, and this screen deliberately keeps an indeterminate progress bar
 * animating while the pipeline runs. Driving the widgets on the main thread
 * tests the same behaviour without fighting that.
 */
@RunWith(AndroidJUnit4::class)
class ChatSmokeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(ChatActivity::class.java)

    @Test
    fun the_chat_screen_renders() {
        onView(withId(R.id.input)).check(matches(isDisplayed()))
        onView(withId(R.id.sendButton)).check(matches(isDisplayed()))
        onView(withId(R.id.statusText)).check(matches(isDisplayed()))
    }

    @Test
    fun sending_a_message_produces_an_answer() {
        activityRule.scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.input).setText("что ты умеешь?")
            activity.findViewById<android.view.View>(R.id.sendButton).performClick()
        }

        val messages = awaitMessages(timeoutMs = 60_000)
        assertTrue("expected the question and an answer, got $messages message(s)", messages >= 2)

        var last: Message? = null
        activityRule.scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.messages).adapter as MessageAdapter
            last = adapter.messages().lastOrNull()
        }

        val answer = last
        assertTrue("no answer was rendered", answer != null)
        assertTrue("the pipeline reported an error: ${answer?.body}", answer?.isError == false)
        assertTrue("the answer is empty", !answer?.body.isNullOrBlank())
        assertTrue("the answer carries no provenance", !answer?.details.isNullOrBlank())
    }

    /**
     * The assistant's bubble now appears the instant a turn starts (see
     * ChatActivity.send's streaming placeholder) and fills in as chunks
     * arrive, so `itemCount >= 2` is true almost immediately — long before
     * there's an actual answer to assert on. `details` is only ever set on
     * the final update (placeholder and streamed partials both pass `null`),
     * so waiting on it is what actually means "the turn finished," the same
     * thing `itemCount >= 2` used to mean back when the whole answer arrived
     * in one shot.
     */
    private fun awaitMessages(timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var count = 0
        while (System.currentTimeMillis() < deadline) {
            var finished = false
            activityRule.scenario.onActivity { activity ->
                val adapter = activity.findViewById<RecyclerView>(R.id.messages).adapter as? MessageAdapter
                count = adapter?.itemCount ?: 0
                val last = adapter?.messages()?.lastOrNull()
                finished = count >= 2 && last != null && (last.isError || !last.details.isNullOrBlank())
            }
            if (finished) return count
            Thread.sleep(250)
        }
        return count
    }
}

/**
 * The Models screen parses the shipped catalog on the device and ranks it with
 * the real scorer against the real [android.app.ActivityManager] memory
 * figures — the part of the architecture that cannot be verified anywhere but
 * on a device.
 */
@RunWith(AndroidJUnit4::class)
class ModelsScreenTest {

    @Test
    fun the_catalog_is_ranked_for_this_device() {
        ActivityScenario.launch(ModelsActivity::class.java).use { scenario ->
            var rows = 0
            scenario.onActivity { rows = it.findViewById<RecyclerView>(R.id.models).adapter?.itemCount ?: 0 }
            assertTrue("the shipped catalog should produce rows, got $rows", rows > 0)
        }
    }
}

/**
 * The native library is the one part of this app that cannot be verified
 * anywhere but on a device: it either loads for this ABI or it does not.
 * Running a model here would mean downloading gigabytes on every CI run, so
 * the test checks what is cheap and decisive — that llama.cpp linked, loaded
 * and reports the CPU features it was actually built with.
 */
@RunWith(AndroidJUnit4::class)
class LlamaNativeTest {

    @Test
    fun the_native_library_loads_and_reports_its_cpu_features() {
        assertTrue("llama_jni did not load for this ABI", ai.localstudio.app.llama.LlamaBridge.isAvailable)

        val info = ai.localstudio.app.llama.LlamaBridge().nativeSystemInfo()
        assertTrue("empty system info", info.isNotBlank())
        android.util.Log.i("LlamaNativeTest", "llama.cpp: $info")
    }
}
