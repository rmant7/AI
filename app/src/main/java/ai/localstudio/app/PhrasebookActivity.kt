package ai.localstudio.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.databinding.ActivityPhrasebookBinding
import ai.localstudio.app.databinding.ItemPhrasebookAnswerBinding
import ai.localstudio.app.databinding.ItemPhrasebookHeaderBinding
import ai.localstudio.app.databinding.ItemPhrasebookPhraseBinding
import ai.localstudio.app.phrasebook.Phrase
import ai.localstudio.app.phrasebook.Phrasebook

/**
 * Offline RU↔CRS tourist phrasebook (see the pilot data in
 * app/src/main/assets/phrasebook_crs.json) — a fixed, pre-checked set of
 * situational phrases with canned replies, not a free-text translator
 * ([TranslationActivity] is that). Deliberately a separate screen and a
 * separate data path: a phrase here is exactly what its source says it is,
 * with nothing generated at request time, so it stays reliable even with no
 * model downloaded and no network.
 *
 * Roughly 40% of the pilot data is `verified` (checked against more than one
 * documented source); the rest is a documented best-effort guess. Both are
 * shown — cutting the unverified half would cut most of the taxi/food/
 * shopping content — but [Phrase.verified] is surfaced on every card rather
 * than hidden, since repeating an unverified phrase to a local is the actual
 * risk, not the phrase being present at all.
 */
class PhrasebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhrasebookBinding
    private val adapter = PhrasebookAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhrasebookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.menu_phrasebook)

        binding.phrasebookList.layoutManager = LinearLayoutManager(this)
        binding.phrasebookList.adapter = adapter

        // The asset is bundled at build time and never touched by the user,
        // so a parse failure here would mean the JSON and this screen's data
        // classes drifted out of sync in a build that still compiled — the
        // one way that can happen silently, worth a log line to catch in
        // review rather than a crash on every launch of this screen.
        val data = runCatching { Phrasebook.load(this) }
            .onFailure { AppContainer.get(this).appLog.record("PHRASEBOOK_LOAD", "FAILED: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

        if (data == null) {
            binding.phrasebookEmpty.visibility = View.VISIBLE
            binding.phrasebookList.visibility = View.GONE
            return
        }

        adapter.submit(
            buildList {
                data.situations.forEach { situation ->
                    add(Row.Header(situationTitle(situation)))
                    data.phrases.filter { it.situation == situation }.forEach { add(Row.PhraseRow(it)) }
                }
            },
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        UtilityMenu.inflate(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        UtilityMenu.handle(this, item.itemId) || super.onOptionsItemSelected(item)

    private fun situationTitle(situation: String): String = getString(
        when (situation) {
            "basics" -> R.string.phrasebook_situation_basics
            "taxi" -> R.string.phrasebook_situation_taxi
            "travel" -> R.string.phrasebook_situation_travel
            "food" -> R.string.phrasebook_situation_food
            "shopping" -> R.string.phrasebook_situation_shopping
            else -> R.string.phrasebook_situation_basics
        },
    )

    private sealed interface Row {
        data class Header(val title: String) : Row
        data class PhraseRow(val phrase: Phrase) : Row
    }

    private inner class PhrasebookAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()
        private val expanded = mutableSetOf<String>()

        fun submit(next: List<Row>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.PhraseRow -> TYPE_PHRASE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(ItemPhrasebookHeaderBinding.inflate(inflater, parent, false))
            } else {
                PhraseHolder(ItemPhrasebookPhraseBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row.title)
                is Row.PhraseRow -> (holder as PhraseHolder).bind(
                    row.phrase,
                    expanded = row.phrase.id in expanded,
                    onToggle = {
                        if (!expanded.add(row.phrase.id)) expanded.remove(row.phrase.id)
                        notifyItemChanged(position)
                    },
                )
            }
        }
    }

    private class HeaderHolder(val binding: ItemPhrasebookHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            binding.root.text = title
        }
    }

    private class PhraseHolder(val binding: ItemPhrasebookPhraseBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(phrase: Phrase, expanded: Boolean, onToggle: () -> Unit) {
            val context = binding.root.context
            binding.phraseRu.text = phrase.ru
            binding.phraseCrs.text = phrase.crs
            binding.phraseEn.text = phrase.en
            binding.phraseBadge.text = context.getString(
                if (phrase.verified) R.string.phrasebook_badge_verified else R.string.phrasebook_badge_unverified,
            )

            binding.phraseAnswers.removeAllViews()
            if (phrase.answers.isNotEmpty()) {
                val inflater = LayoutInflater.from(context)
                phrase.answers.forEach { answer ->
                    val answerBinding = ItemPhrasebookAnswerBinding.inflate(inflater, binding.phraseAnswers, true)
                    answerBinding.answerCrs.text = answer.crs
                    answerBinding.answerRu.text = answer.ru
                }
                binding.phraseAnswers.visibility = if (expanded) View.VISIBLE else View.GONE
                binding.root.setOnClickListener { onToggle() }
            } else {
                binding.phraseAnswers.visibility = View.GONE
                binding.root.setOnClickListener(null)
                binding.root.isClickable = false
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_PHRASE = 1
    }
}
