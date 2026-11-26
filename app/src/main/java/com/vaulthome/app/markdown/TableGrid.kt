package com.vaulthome.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Pure column-width math (unit-tested): per column max of cell widths, clamped to [min, max]. */
object TableWidths {
    fun columns(cellWidths: List<List<Int>>, cols: Int, min: Int, max: Int): IntArray =
        IntArray(cols) { c -> (cellWidths.maxOfOrNull { it.getOrElse(c) { 0 } } ?: 0).coerceIn(min, max) }
}

private class GridGeom { var xs = IntArray(0); var ys = IntArray(0) }

/**
 * Lays out rows*cols children (row-major) on a shared grid: every column gets one width
 * (max intrinsic width of its cells, 48..260dp, longer text wraps), every row its tallest cell.
 * Row 0 is the header (shaded). Borders are drawn between all cells.
 */
@Composable
fun TableGrid(rows: Int, cols: Int, border: Color, headerBg: Color, content: @Composable () -> Unit) {
    val geom = remember { GridGeom() }
    Layout(
        content = content,
        modifier = Modifier.drawBehind {
            if (geom.xs.isEmpty() || geom.ys.size < 2) return@drawBehind
            val w = geom.xs.last().toFloat(); val h = geom.ys.last().toFloat()
            drawRect(headerBg, Offset.Zero, Size(w, geom.ys[1].toFloat()))
            val sw = 1.dp.toPx()
            geom.xs.forEach { x -> val xf = x.toFloat().coerceAtMost(w - sw / 2).coerceAtLeast(sw / 2); drawLine(border, Offset(xf, 0f), Offset(xf, h), sw) }
            geom.ys.forEach { y -> val yf = y.toFloat().coerceAtMost(h - sw / 2).coerceAtLeast(sw / 2); drawLine(border, Offset(0f, yf), Offset(w, yf), sw) }
        },
    ) { measurables, _ ->
        val minW = 48.dp.roundToPx(); val maxW = 260.dp.roundToPx()
        val intrinsic = List(rows) { r -> List(cols) { c -> measurables.getOrNull(r * cols + c)?.maxIntrinsicWidth(Constraints.Infinity) ?: 0 } }
        val widths = TableWidths.columns(intrinsic, cols, minW, maxW)
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i % cols])) }
        val heights = IntArray(rows) { r -> (0 until cols).maxOf { c -> placeables.getOrNull(r * cols + c)?.height ?: 0 } }
        val xs = IntArray(cols + 1).also { for (c in 0 until cols) it[c + 1] = it[c] + widths[c] }
        val ys = IntArray(rows + 1).also { for (r in 0 until rows) it[r + 1] = it[r] + heights[r] }
        geom.xs = xs; geom.ys = ys
        layout(xs.last(), ys.last()) {
            placeables.forEachIndexed { i, p -> p.place(xs[i % cols], ys[i / cols]) }
        }
    }
}
