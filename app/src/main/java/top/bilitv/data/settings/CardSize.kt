package top.bilitv.data.settings

import top.bilitv.R

enum class CardSize(val labelRes: Int, val scale: Float) {
    COMPACT(R.string.card_compact, .75f), STANDARD(R.string.card_standard, 1f),
    LARGE(R.string.card_large, 1.35f), EXTRA_LARGE(R.string.card_extra_large, 2f);

    companion object {
        fun of(name: String?): CardSize = entries.firstOrNull { it.name == name } ?: STANDARD
        val COLUMNS = listOf(0) + (2..9)
        fun columns(value: Int): Int = value.takeIf { it in COLUMNS } ?: 0
    }
}
