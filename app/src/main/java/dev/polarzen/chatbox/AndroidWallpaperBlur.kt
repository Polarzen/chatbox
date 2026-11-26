package dev.polarzen.chatbox

/** Moving sums make each separable pass linear in pixel count even at maximum softness. */
internal fun blurWallpaperPixels(input: IntArray, width: Int, height: Int, radius: Int): IntArray {
    require(width > 0 && height > 0 && input.size == width * height && radius >= 0)
    if (radius == 0) return input
    val scratch = IntArray(input.size)
    val output = IntArray(input.size)
    fun pass(source: IntArray, target: IntArray, horizontal: Boolean) {
        val length = if (horizontal) width else height
        val rows = if (horizontal) height else width
        val stride = if (horizontal) 1 else width
        val count = radius * 2 + 1
        for (line in 0 until rows) {
            val start = if (horizontal) line * width else line
            fun index(offset: Int) = start + offset.coerceIn(0, length - 1) * stride
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            fun add(color: Int, direction: Int) {
                val weight = (color ushr 24) * direction
                alpha += weight
                red += (color ushr 16 and 255) * weight
                green += (color ushr 8 and 255) * weight
                blue += (color and 255) * weight
            }
            for (n in -radius..radius) add(source[index(n)], 1)
            for (n in 0 until length) {
                target[start + n * stride] = if (alpha == 0) 0 else ((alpha / count) shl 24) or
                    ((red / alpha) shl 16) or ((green / alpha) shl 8) or (blue / alpha)
                add(source[index(n - radius)], -1)
                add(source[index(n + radius + 1)], 1)
            }
        }
    }
    pass(input, scratch, true)
    pass(scratch, output, false)
    pass(output, scratch, true)
    pass(scratch, output, false)
    return output
}
