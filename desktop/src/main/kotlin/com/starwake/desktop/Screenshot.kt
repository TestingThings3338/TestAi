package com.starwake.desktop

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO

/** Rough content statistics of a screenshot, printed so black/blank frames stand out in the log. */
class ImageStats(val meanLuma: Double, val litFraction: Double, val distinctColors: Int) {
    override fun toString(): String =
        "mean luma %.3f, %.1f%% of pixels lit, ~%d colours".format(meanLuma, litFraction * 100, distinctColors)

    val looksBlank get() = litFraction < 0.002 || distinctColors < 4
}

object Screenshot {
    /**
     * Converts GL's bottom-up RGBA rows into a top-down image. [cutout] (if > 0) paints a fake
     * punch-hole camera at the left edge so inset handling can be judged visually.
     */
    fun toImage(rgba: ByteBuffer, w: Int, h: Int, cutout: Float = 0f): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val row = IntArray(w)
        for (y in 0 until h) {
            val src = (h - 1 - y) * w * 4
            for (x in 0 until w) {
                val i = src + x * 4
                val r = rgba.get(i).toInt() and 0xFF
                val g = rgba.get(i + 1).toInt() and 0xFF
                val b = rgba.get(i + 2).toInt() and 0xFF
                row[x] = (r shl 16) or (g shl 8) or b
            }
            img.setRGB(0, y, w, 1, row, 0, w)
        }
        if (cutout > 0f) {
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // the unsafe band the game was told about
            g.color = Color(255, 0, 255, 40)
            g.fillRect(0, 0, cutout.toInt(), h)
            // the camera hole itself, centred in the band
            val d = (cutout * 0.55f).toInt()
            g.color = Color.BLACK
            g.fillOval(((cutout - d) / 2).toInt(), h / 2 - d / 2, d, d)
            g.dispose()
        }
        return img
    }

    fun stats(img: BufferedImage): ImageStats {
        var sum = 0.0
        var lit = 0L
        val colours = HashSet<Int>()
        val w = img.width
        val h = img.height
        val step = maxOf(1, (w * h / 200_000))
        var n = 0L
        var i = 0
        while (i < w * h) {
            val p = img.getRGB(i % w, i / w)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            sum += l
            if (l > 0.03) lit++
            if (colours.size < 5000) colours.add(p and 0xF0F0F0)
            n++
            i += step
        }
        return ImageStats(sum / n, lit.toDouble() / n, colours.size)
    }

    fun write(img: BufferedImage, file: File) {
        file.parentFile?.mkdirs()
        if (!ImageIO.write(img, "png", file)) throw IllegalStateException("no PNG writer")
    }
}
