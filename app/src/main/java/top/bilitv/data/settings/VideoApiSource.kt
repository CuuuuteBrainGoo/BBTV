package top.bilitv.data.settings

enum class VideoApiSource(val label: String) {
    WEB("Web / HTTP"), APP("App / gRPC");
    companion object { fun of(id: String?) = entries.firstOrNull { it.name == id } ?: WEB }
}
