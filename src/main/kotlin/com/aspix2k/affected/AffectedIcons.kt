package com.aspix2k.affected

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

object AffectedIcons {

    val Action: Icon = IconLoader.getIcon("/icons/affected.svg", AffectedIcons::class.java)

    val Module: Icon = IconLoader.getIcon("/icons/module.svg", AffectedIcons::class.java)

    val Check: Icon = IconLoader.getIcon("/icons/check.svg", AffectedIcons::class.java)

    private val base: Icon = IconLoader.getIcon("/icons/affected_base.svg", AffectedIcons::class.java)
    private val cache = ConcurrentHashMap<String, Icon>()

    private const val MAX_COUNT = 99

    fun withCount(count: Int): Icon {
        if (count <= 0) return Action
        val label = if (count > MAX_COUNT) "$MAX_COUNT+" else count.toString()
        return cache.getOrPut(label) { CountIcon(base, label) }
    }

    private class CountIcon(private val base: Icon, private val label: String) : Icon {

        override fun getIconWidth(): Int = base.iconWidth

        override fun getIconHeight(): Int = base.iconHeight

        override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
            base.paintIcon(component, graphics, x, y)

            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                // Число рисуется внутри той же рамки, что и галочка в базовой
                // иконке, поэтому её размер не зависит от количества цифр.
                val frameHeight = iconHeight * FRAME_HEIGHT_RATIO
                var size = frameHeight * FONT_RATIO
                g.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, size)
                var metrics = g.fontMetrics

                val maxWidth = iconWidth * FRAME_WIDTH_RATIO
                while (metrics.stringWidth(label) > maxWidth && size > MIN_FONT) {
                    size -= 0.5f
                    g.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, size)
                    metrics = g.fontMetrics
                }

                g.color = TEXT_COLOR
                val textX = x + (iconWidth - metrics.stringWidth(label)) / 2f
                val textY = y + (frameHeight - metrics.height) / 2f + metrics.ascent
                g.drawString(label, textX, textY)
            } finally {
                g.dispose()
            }
        }

        private companion object {
            const val FRAME_HEIGHT_RATIO = 0.6f
            const val FRAME_WIDTH_RATIO = 0.72f
            const val FONT_RATIO = 0.8f
            const val MIN_FONT = 6f
            val TEXT_COLOR = JBColor(0x59A869, 0x5FAD65)
        }
    }
}
