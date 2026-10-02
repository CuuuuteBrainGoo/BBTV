-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Media3 的必要反射入口由依赖的 consumer rules 保留，不整库禁用收缩。
-dontwarn okhttp3.**
