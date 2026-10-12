package com.vaulthome.app.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders [markdown] (no frontmatter) with Material 3 typography/colors (ported from QiMap).
 * Links go through [onLink] (caller decides safety). Obsidian wikilinks/embeds are styled; when
 * [onWikiLink] is non-null a tap passes the target note name to it. #tags are styled, not clickable.
 */
@Composable
fun MarkdownText(
    markdown: String,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    onWikiLink: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { MarkdownBlocks.parse(markdown) }
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    fun text(spans: List<MdSpan>, style: TextStyle): AnnotatedString = buildAnnotatedString {
        spans.forEach { s ->
            val span = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                fontFamily = if (s.code) FontFamily.Monospace else null,
                background = if (s.code) colors.surfaceVariant else Color.Unspecified,
            )
            if (s.wikiTarget != null) {
                val wikiStyle = SpanStyle(
                    color = colors.primary,
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontStyle = if (s.embed || s.italic) FontStyle.Italic else null,
                    textDecoration = TextDecoration.Underline,
                )
                val label = if (s.embed) "\u29C9 ${s.text}" else s.text
                if (onWikiLink != null) {
                    withLink(LinkAnnotation.Clickable(tag = "wiki:" + s.wikiTarget, styles = TextLinkStyles(wikiStyle)) { onWikiLink(s.wikiTarget) }) { append(label) }
                } else {
                    withStyle(wikiStyle) { append(label) }
                }
            } else if (s.tag) {
                withStyle(span.merge(SpanStyle(color = colors.onSurfaceVariant, background = colors.surfaceVariant))) { append(s.text) }
            } else if (s.url != null) {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = s.url,
                        styles = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)),
                    ) { onLink(s.url) },
                ) { withStyle(span) { append(s.text) } }
            } else {
                withStyle(span) { append(s.text) }
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> {
                    val st = when (b.level) { 1 -> type.headlineSmall; 2 -> type.titleLarge; 3 -> type.titleMedium; else -> type.titleSmall }
                    Text(text(b.text, st), style = st, color = colors.onSurface, modifier = Modifier.padding(top = 4.dp))
                }
                is MdBlock.Paragraph -> Text(text(b.text, type.bodyMedium), style = type.bodyMedium, color = colors.onSurface)
                is MdBlock.ListItem -> Row(Modifier.padding(start = (8 + 16 * b.depth).dp)) {
                    Text(b.marker, style = type.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(20.dp))
                    Text(text(b.text, type.bodyMedium), style = type.bodyMedium, color = colors.onSurface)
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(3.dp).padding(vertical = 2.dp).background(colors.error).padding(vertical = 10.dp))
                    Text(text(b.text, type.bodyMedium), style = type.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
                }
                is MdBlock.Code -> Text(
                    b.text, style = type.bodySmall.copy(fontFamily = FontFamily.Monospace), color = colors.onSurface,
                    modifier = Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(8.dp),
                )
                MdBlock.Rule -> HorizontalDivider()
                is MdBlock.Table -> {
                    val border = colors.outlineVariant
                    val cols = b.header.size
                    Column(
                        Modifier.horizontalScroll(rememberScrollState()).border(1.dp, border)
                            .width(IntrinsicSize.Max),
                    ) {
                        (listOf(b.header) + b.rows).forEachIndexed { r, row ->
                            Row(
                                Modifier.height(IntrinsicSize.Min).fillMaxWidth()
                                    .background(if (r == 0) colors.surfaceVariant else Color.Unspecified),
                            ) {
                                for (c in 0 until cols) {
                                    val spans = row.getOrElse(c) { emptyList() }
                                    val cellSpans = if (r == 0) spans.map { it.copy(bold = true) } else spans
                                    val st = type.bodySmall
                                    Text(
                                        text(cellSpans, st), style = st, color = colors.onSurface,
                                        textAlign = when (b.align.getOrElse(c) { -1 }) { 0 -> TextAlign.Center; 1 -> TextAlign.End; else -> TextAlign.Start },
                                        modifier = Modifier.fillMaxHeight().widthIn(min = 48.dp, max = 260.dp)
                                            .border(0.5.dp, border).padding(horizontal = 8.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
