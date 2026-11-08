package com.dathaze.pocketpad.ui

/**
 * On-disk form of a customised controller layout.
 *
 * Each entry is `key:x:y:size:width:height`, where `x` and `y` are the
 * control's centre as a fraction of the safe area, `size` is a multiplier on
 * the radius the built-in layout gave that control, and `width`/`height` are
 * the separate stretches on top of that radius. Entries are joined with `;`.
 *
 * Older builds wrote fewer fields and all of them still load: `key:x:y` (before
 * resizing existed) reads as size 1 with a round shape, and `key:x:y:size`
 * reads as size with a round shape. A custom layout therefore keeps everything
 * it already had instead of being thrown away when the format grows.
 *
 * Deliberately free of `android.*`: the format and its compatibility rules are
 * the kind of thing that breaks silently, so they are unit-tested directly
 * instead of being inferred from a view that happens to render.
 */
object LayoutStorage {

    /**
     * One control's saved centre (0..1 of the safe area), uniform size and the
     * two stretches that make it wider or taller than a circle.
     */
    data class Entry(
        val key: String,
        val x: Float,
        val y: Float,
        val size: Float,
        val width: Float = 1f,
        val height: Float = 1f
    )

    private const val OLDEST_FIELDS = 3 // key:x:y
    private const val RESIZED_FIELDS = 4 // key:x:y:size
    private const val FIELDS = 6 // key:x:y:size:width:height
    private const val SEPARATOR = ';'
    private const val DELIMITER = ':'

    fun encode(entries: List<Entry>): String = entries.joinToString(SEPARATOR.toString()) { e ->
        "${e.key}$DELIMITER${e.x}$DELIMITER${e.y}$DELIMITER${e.size}" +
            "$DELIMITER${e.width}$DELIMITER${e.height}"
    }

    /**
     * Reads a stored layout, dropping anything malformed rather than failing
     * the whole screen. A null or blank string is an empty layout, which is
     * what "no custom layout saved" looks like.
     */
    fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        val entries = ArrayList<Entry>()
        for (chunk in raw.split(SEPARATOR)) {
            val parts = chunk.split(DELIMITER)
            if (parts.size != OLDEST_FIELDS &&
                parts.size != RESIZED_FIELDS &&
                parts.size != FIELDS
            ) {
                continue
            }
            if (parts[0].isEmpty()) continue
            val x = parts[1].toFloatOrNull() ?: continue
            val y = parts[2].toFloatOrNull() ?: continue
            val size = if (parts.size >= RESIZED_FIELDS) {
                parts[3].toFloatOrNull() ?: continue
            } else {
                1f
            }
            // Missing axes are a circle, which is what every older build drew.
            val width = if (parts.size == FIELDS) parts[4].toFloatOrNull() ?: continue else 1f
            val height = if (parts.size == FIELDS) parts[5].toFloatOrNull() ?: continue else 1f
            if (!size.isFinite() || size <= 0f) continue
            if (!width.isFinite() || width <= 0f) continue
            if (!height.isFinite() || height <= 0f) continue
            entries += Entry(parts[0], x, y, size, width, height)
        }
        return entries
    }
}
