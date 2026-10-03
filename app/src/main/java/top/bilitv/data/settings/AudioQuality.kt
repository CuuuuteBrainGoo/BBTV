package top.bilitv.data.settings

enum class AudioQuality(val id: Int, val label: String) {
    AUTO(0, "自动（优先常规音轨）"),
    AAC_192(30280, "192K"), AAC_132(30232, "132K"), AAC_64(30216, "64K"),
    DOLBY(30250, "杜比音轨"), HI_RES(30251, "Hi-Res 无损");

    companion object {
        val NORMAL_IDS = listOf(30216, 30232, 30280)
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: AUTO
    }
}
