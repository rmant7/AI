package ai.localstudio.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.databinding.ItemMessageBinding

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
        holder.binding.roleText.text = message.role
        holder.binding.bodyText.text = message.body
        holder.binding.detailsText.text = message.details.orEmpty()
        holder.binding.detailsText.visibility =
            if (message.details.isNullOrBlank()) View.GONE else View.VISIBLE
    }
}
