package ai.localstudio.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.databinding.ItemMessageBinding
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import io.noties.markwon.Markwon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Message(
    val role: String,
    val body: String,
    val details: String? = null,
    val isError: Boolean = false,
    /** Wall-clock time the message appeared. 0 for conversations saved before this existed. */
    val timestamp: Long = System.currentTimeMillis(),
    /** The same `data:<mime>;base64,...` URI sent to the model, when this turn attached an image — null for every message before this existed and every one without an attachment. */
    val imageDataUri: String? = null,
) {
    companion object {
        // Stored as-is in every saved conversation on disk — changing these
        // values would silently break the user/assistant bubble styling (and
        // history's own "last message from the user" check) for every chat
        // saved before the change. The strings shown on screen are resolved
        // separately, from these same values, via displayRole() below.
        const val ROLE_USER = "Вы"
        const val ROLE_ASSISTANT = "Модель"
        const val ROLE_ERROR = "Ошибка"

        fun user(text: String, imageDataUri: String? = null) = Message(ROLE_USER, text, imageDataUri = imageDataUri)
        fun assistant(body: String, details: String?) = Message(ROLE_ASSISTANT, body, details)
        fun error(body: String, details: String?) = Message(ROLE_ERROR, body, details, isError = true)

        /**
         * Moscow time, always — pinned rather than device-local because the
         * user reads these against their own clock, and a phone that travels
         * or sits on a different timezone would otherwise silently relabel
         * every message in the history.
         */
        private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Europe/Moscow")
        }

        fun formatTime(timestamp: Long): String =
            if (timestamp <= 0L) "" else synchronized(clockFormat) { clockFormat.format(Date(timestamp)) }
    }
}

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {

    private val messages = mutableListOf<Message>()

    // Built once, lazily, against a real context — an LLM's output is full of
    // markdown syntax (**bold**, lists, code) that looks like a rendering bug
    // rather than formatting when shown as raw text. User messages are left
    // as plain text: typed input rarely contains markdown on purpose, and
    // rendering it would be surprising rather than helpful.
    private var markwon: Markwon? = null

    /** Read-only view of what is on screen — used by the instrumented smoke test. */
    fun messages(): List<Message> = messages.toList()

    class Holder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    fun add(message: Message) {
        messages += message
        notifyItemInserted(messages.size - 1)
    }

    /** Replaces the message at [index] in place — how a streamed answer's bubble grows without becoming a new bubble each chunk. */
    fun update(index: Int, message: Message) {
        messages[index] = message
        notifyItemChanged(index)
    }

    /** Index of the last message, for the placeholder bubble a streamed answer fills in — -1 when there is none yet. */
    fun lastIndex(): Int = messages.size - 1

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        if (markwon == null) markwon = Markwon.create(parent.context)
        return Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount(): Int = messages.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = messages[position]
        val binding = holder.binding
        binding.roleText.text = displayRole(binding.root.context, message.role)
        val isAssistantReply = !message.isError && message.role != Message.ROLE_USER
        if (isAssistantReply) {
            // A generated response is untrusted input as far as a markdown
            // parser is concerned: a small model that degenerates into
            // repeating "**" or an unbalanced code fence for the length of a
            // long reply is exactly the pathological input that crashes a
            // naive parser (stack overflow included, hence catching
            // Throwable, not just Exception) — and that risk scales with how
            // long a reply is allowed to run, not with context window size,
            // which is why lowering the context didn't help this crash.
            val engine = markwon
            val rendered = engine != null && runCatching { engine.setMarkdown(binding.bodyText, message.body) }.isSuccess
            if (!rendered) binding.bodyText.text = message.body
        } else {
            binding.bodyText.text = message.body
        }
        binding.detailsText.text = message.details.orEmpty()
        binding.detailsText.visibility = if (message.details.isNullOrBlank()) View.GONE else View.VISIBLE

        // Decoded fresh on every bind rather than cached: images are rare
        // (one per turn at most, already downscaled before ever reaching a
        // Message — see ChatActivity.attachImage) and a bitmap cache here
        // would outlive the handful of rows it was ever useful for.
        val bitmap = message.imageDataUri?.let { decodeDataUri(it) }
        if (bitmap != null) {
            binding.messageImage.setImageBitmap(bitmap)
            binding.messageImage.visibility = View.VISIBLE
        } else {
            binding.messageImage.setImageDrawable(null)
            binding.messageImage.visibility = View.GONE
        }

        val time = Message.formatTime(message.timestamp)
        binding.timeText.text = time
        binding.timeText.visibility = if (time.isBlank()) View.GONE else View.VISIBLE

        val isUser = message.role == Message.ROLE_USER
        val (backgroundRes, textColorAttr) = when {
            message.isError -> R.drawable.bg_bubble_error to MaterialR.attr.colorOnErrorContainer
            isUser -> R.drawable.bg_bubble_user to MaterialR.attr.colorOnPrimaryContainer
            else -> R.drawable.bg_bubble_assistant to MaterialR.attr.colorOnSurfaceVariant
        }
        binding.bubble.background = ContextCompat.getDrawable(binding.root.context, backgroundRes)
        // Reassigned, not mutated in place: this view is recycled across
        // messages of different roles, and a plain field mutation on the
        // fetched LayoutParams is not guaranteed to be picked up on reuse.
        binding.bubble.layoutParams = (binding.bubble.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
        }

        val textColor = MaterialColors.getColor(binding.root, textColorAttr)
        binding.roleText.setTextColor(textColor)
        binding.timeText.setTextColor(textColor)
        binding.bodyText.setTextColor(textColor)
        binding.detailsText.setTextColor(textColor)
        binding.copyButton.imageTintList = ColorStateList.valueOf(textColor)
        binding.copyButton.setOnClickListener { copyToClipboard(binding.root.context, message.body) }
    }

    private fun displayRole(context: Context, role: String): String = when (role) {
        Message.ROLE_USER -> context.getString(R.string.role_user)
        Message.ROLE_ASSISTANT -> context.getString(R.string.role_assistant)
        Message.ROLE_ERROR -> context.getString(R.string.role_error)
        else -> role
    }

    private fun decodeDataUri(dataUri: String): Bitmap? = runCatching {
        val base64 = dataUri.substringAfter(",", "")
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clip_label_model_answer), text))
        // Android 13+ (API 33) already shows its own system "Copied" toast for
        // every clip — adding this one too would just duplicate it on top.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.message_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
