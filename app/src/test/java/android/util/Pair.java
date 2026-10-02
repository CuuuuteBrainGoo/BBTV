package android.util;

/**
 * JVM 单测专用的 `android.util.Pair` 替身。
 *
 * ## 为什么必须有它（比 Log 那个更关键）
 * AGP 给的 `android.jar` 是空壳，**静态工厂 `Pair.create()` 直接返回 `null`**。
 * 而 media3 的 MP4 解析**整个建立在 `Pair` 上**：
 *
 * ```
 * AtomParsers.parseMdhd()  -> Pair<Long, Long>
 * AtomParsers.parseTrak()  -> 直接读 mdhdData.second
 * ```
 *
 * 于是每次解析走到 `AtomParsers.parseTrak` 都必然
 * `NullPointerException: Cannot read field "second" because "mdhdData" is null`
 * —— **解析器一步都进不去**，7 个样本全崩在同一个地方，跟流本身毫无关系。
 *
 * 这个替身按 Android 官方实现照抄（`first`/`second` 是 public final 字段，
 * media3 是**直接读字段**而不是调 getter，所以字段名和可见性都不能改）。
 */
public class Pair<F, S> {

    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }

    public static <A, B> Pair<A, B> create(A a, B b) {
        return new Pair<A, B>(a, b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pair)) {
            return false;
        }
        Pair<?, ?> other = (Pair<?, ?>) o;
        return eq(first, other.first) && eq(second, other.second);
    }

    @Override
    public int hashCode() {
        return (first == null ? 0 : first.hashCode()) ^ (second == null ? 0 : second.hashCode());
    }

    @Override
    public String toString() {
        return "Pair{" + first + " " + second + "}";
    }

    private static boolean eq(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }
}
