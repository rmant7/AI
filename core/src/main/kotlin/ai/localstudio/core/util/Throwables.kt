package ai.localstudio.core.util

/**
 * [Throwable.message], falling back to [Throwable.toString] not just when
 * it's null but also when it's blank. `message ?: toString()` only catches
 * the null case — some exceptions (a `MediaCodec` failure observed
 * on-device, among others) carry a genuinely empty, non-null message
 * string, which the elvis operator lets straight through: the user sees an
 * empty "Error: " with nothing after it instead of anything to go on.
 * [toString] always carries at least the exception's class name, which is
 * strictly more useful than nothing.
 */
fun Throwable.describeForUser(): String = message?.takeIf { it.isNotBlank() } ?: toString()
