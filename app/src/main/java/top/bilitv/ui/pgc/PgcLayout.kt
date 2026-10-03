package top.bilitv.ui.pgc

internal fun portraitBannerWidth(height: Float): Float = (height * 2f / 3f).coerceAtMost(260f)

/** Dimensions are the measured content area in dp, after navigation and padding. */
internal fun useSideBanner(width: Float, height: Float, wasSide: Boolean, fontScale: Float = 1f): Boolean {
    if (!width.isFinite() || !height.isFinite() || !fontScale.isFinite() || fontScale <= 0f) return false
    if (height < 160f * fontScale.coerceAtLeast(1f)) return false
    if (width - portraitBannerWidth(height) - 24f < 240f * fontScale.coerceAtLeast(1f)) return false
    return width / height > if (wasSide) 2.05f else 2.15f
}
