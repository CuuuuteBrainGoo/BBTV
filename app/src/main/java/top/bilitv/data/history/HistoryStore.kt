package top.bilitv.data.history

import android.content.Context
import android.content.SharedPreferences
import top.bilitv.util.AppLog

/**
 * 本机观看记录（含续播位置）。
 *
 * ## 存储选择
 *
 * 用 SharedPreferences 存一段 JSON 字符串，**不上 SQLite / Room**：
 * 记录上限 200 条、单条 10 个字段，整段 JSON 大约 60~120 KB，
 * 一次读写几毫秒。为一个"最新 200 条"的列表引 Room（多一个编译器插件 +
 * 一套 DAO + migration 机制），成本和收益完全不成比例。
 *
 * 什么时候该换：需要**按关键字搜历史**、或者记录量级上到几千条的时候。
 * 那时 `all()` 这个"全量读 + 全量写"的模型才会开始疼。
 *
 * ## 为什么读写都走 [History] 的纯函数
 *
 * 去重 / 排序 / 截断 / 合并 / 编解码全在 [History] 里，这里只负责"拿字符串、存字符串"。
 * 那样这些规则能在 JVM 单测里被直接驱动 —— 见 `HistoryTest`。
 */
class HistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 全部记录，**按最后观看时间倒序**（最新在前） */
    fun all(): List<HistoryEntry> = History.decode(prefs.getString(KEY_ENTRIES, null))

    /** 取一条。续播时用 —— 只关心某一个 key 的进度 */
    fun get(key: String): HistoryEntry? {
        if (key.isBlank()) return null
        return all().firstOrNull { it.key == key }
    }

    /**
     * 写入一条记录。
     *
     * 元信息（标题/封面）取非空的合并，进度取新的 —— 见 [History.mergeFields] 的说明。
     * 这一条很关键：进度是高频写的，标题只在开头拿得到一次，
     * 不区分的话每 5 秒一次的进度写入会把标题抹掉。
     */
    fun upsert(entry: HistoryEntry) {
        if (entry.key.isBlank()) return
        val old = get(entry.key)
        val merged = History.mergeFields(old, entry)
        save(History.merge(all(), merged))
    }

    fun remove(key: String) {
        if (key.isBlank()) return
        val next = all().filterNot { it.key == key }
        if (next.isEmpty()) {
            prefs.edit().remove(KEY_ENTRIES).apply()
        } else {
            save(next)
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
        AppLog.i(TAG, "观看记录已清空")
    }

    /** 元数据修正不改变进度、时间或原列表顺序。 */
    fun updateTitle(key: String, title: String) {
        if (title.isBlank()) return
        save(all().map { if (it.key == key) it.copy(title = title) else it })
    }

    /** 写入顺序：先落盘再返回。写入失败只记日志，不让界面崩 */
    private fun save(list: List<HistoryEntry>) {
        runCatching { prefs.edit().putString(KEY_ENTRIES, History.encode(list)).apply() }
            .onFailure { AppLog.e(TAG, "观看记录写入失败", it) }
    }

    private companion object {
        const val PREFS_NAME = "bilitv_history"
        const val KEY_ENTRIES = "entries"
        const val TAG = "History"
    }
}
