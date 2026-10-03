package top.bilitv.data.settings

enum class RecommendSource(val label: String) {
    WEB("网页"), APP("App / HTTP");
    companion object { fun of(id: String?) = entries.firstOrNull { it.name == id } ?: WEB }
}
