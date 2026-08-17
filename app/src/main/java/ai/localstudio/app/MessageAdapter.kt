package ai.localstudio.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.databinding.ItemMessageBinding
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

data class Message(
    val role: String,
    val body: String,
    val details: String? = null,
    val isError: Boolean = false,
) {
    companion object {
        fun user(text: String) = Message("Вы", text)
        fun assistant(body: String, details: String?) = Message("Модель", body, details)
        fun error(body: String, details: String?) = Message("Ошибка", body, details, isError = true)
    }
}

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {

    private val messages = mutableListOf<Message>()

    /** Read-only view of what is on screen — used by the instrumented smoke test. */
    fun messages(): List<Message> = messages.toList()

    class Holder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    fun add(message: Message) {
        messages += message
        notifyItemInserted(messages.size - 1)
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = messages.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = messages[position]
        val binding = holder.binding
        binding.roleText.text = message.role
        binding.bodyText.text = message.body
        binding.detailsText.text = message.details.orEmpty()
        binding.detailsText.visibility = if (message.details.isNullOrBlank()) View.GONE else View.VISIBLE

        val isUser = message.role == "Вы"
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
        binding.bodyText.setTextColor(textColor)
        binding.detailsText.setTextColor(textColor)
    }
}
