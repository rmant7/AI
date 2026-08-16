package ai.localstudio.app

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.typeText
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
 */
@RunWith(AndroidJUnit4::class)
class ChatSmokeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(ChatActivity::class.java)

    @Test
    fun sending_a_message_produces_an_answer() {
        onView(withId(R.id.input)).perform(typeText("что ты умеешь?"), closeSoftKeyboard())
        onView(withId(R.id.sendButton)).perform(click())

        // Espresso does not know about the coroutine that runs the pipeline, so
        // the adapter is polled rather than assumed to be up to date.
        val messages = await(timeoutMs = 30_000) { activity ->
            activity.findViewById<RecyclerView>(R.id.messages).adapter?.itemCount ?: 0
        }

        assertTrue("expected the question and an answer, got $messages message(s)", messages >= 2)
    }

    private fun await(timeoutMs: Long, read: (ChatActivity) -> Int): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var value = 0
        while (System.currentTimeMillis() < deadline) {
            activityRule.scenario.onActivity { value = read(it) }
            if (value >= 2) return value
            Thread.sleep(250)
        }
        return value
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
