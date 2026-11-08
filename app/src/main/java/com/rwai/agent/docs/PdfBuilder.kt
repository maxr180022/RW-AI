package com.rwai.agent.docs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import com.rwai.agent.logic.DocBlock
import com.rwai.agent.logic.LineBox
import java.io.File
import java.io.FileOutputStream

/**
 * Печатает документ по правилам российского делового письма: A4, поля 3 / 1,5 / 2 / 2 см, шрифт с засечками 14 пт,
 * полуторный интервал, красная строка 1,25 см, выравнивание по ширине, «шапка» в правой половине листа,
 * номера страниц со второй страницы. Использует только встроенный [PdfDocument] — без внешних библиотек.
 */
object PdfBuilder {
    private const val PW = 595
    private const val PH = 842
    private const val ML = 85f      // 3 см
    private const val MR = 43f      // 1,5 см
    private const val MT = 57f      // 2 см
    private const val MB = 57f      // 2 см
    private const val TW = PW - ML - MR
    private const val INDENT = 35.4f // 1,25 см
    private const val BODY = 14f
    const val MAX_PAGES = 40

    class Result(val pages: Int, val index: List<LineBox>)

    private fun paint(bold: Boolean, size: Float = BODY) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(text: CharSequence, p: TextPaint, width: Float, align: Layout.Alignment, justify: Boolean = false): StaticLayout {
        val b = StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt().coerceAtLeast(20))
            .setAlignment(align)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
        if (justify) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    private class State(val doc: PdfDocument) {
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var pageNo = 0
        var y = MT
        val index = ArrayList<LineBox>()

        fun newPage() {
            closePage()
            if (pageNo >= MAX_PAGES) throw IllegalStateException("Документ длиннее $MAX_PAGES страниц")
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create())
            canvas = page!!.canvas
            y = MT
        }

        fun closePage() {
            val pg = page ?: return
            if (pageNo >= 2) {
                val p = paint(false, 11f)
                p.textAlign = Paint.Align.CENTER
                canvas!!.drawText(pageNo.toString(), PW / 2f, PH - 28f, p)
            }
            doc.finishPage(pg)
            page = null
            canvas = null
        }

        val bottom get() = PH - MB
        val atTop get() = y <= MT + 0.5f
    }

    fun build(blocks: List<DocBlock>, out: File): Result {
        val doc = PdfDocument()
        val st = State(doc)
        try {
            st.newPage()
            val normal = paint(false)
            val bold = paint(true)
            val lineH = normal.fontSpacing * 1.15f
            var lastWasHead = false

            fun gap(h: Float) {
                if (st.atTop) return
                if (st.y + h > st.bottom) st.newPage() else st.y += h
            }

            fun ensure(h: Float) { if (st.y + h > st.bottom && !st.atTop) st.newPage() }

            /** Рисует макет, при необходимости переходя на новые страницы между строками. */
            fun place(lay: StaticLayout, x: Float, width: Float, justified: Boolean) {
                var start = 0
                val text = lay.text
                while (start < lay.lineCount) {
                    val avail = st.bottom - st.y
                    var end = start
                    while (end < lay.lineCount && lay.getLineBottom(end) - lay.getLineTop(start) <= avail) end++
                    if (end == start) {
                        if (st.atTop) end = start + 1 else { st.newPage(); continue }
                    }
                    val top = lay.getLineTop(start).toFloat()
                    val bot = lay.getLineBottom(end - 1).toFloat()
                    val c = st.canvas!!
                    c.save()
                    c.translate(x, st.y - top)
                    c.clipRect(0f, top, width + 4f, bot)
                    lay.draw(c)
                    c.restore()
                    for (i in start until end) {
                        val s = lay.getLineStart(i)
                        val e = lay.getLineEnd(i)
                        val raw = text.subSequence(s, e).toString()
                        val trimmed = raw.trimEnd(' ', '\n').length
                        val xs = FloatArray(raw.length + 1)
                        for (k in 0..raw.length) xs[k] = x + lay.getPrimaryHorizontal(s + minOf(k, trimmed))
                        if (justified && i < lay.lineCount - 1 && trimmed > 0) {
                            val spaces = raw.take(trimmed).count { it == ' ' }
                            val extra = (x + width) - xs[trimmed]
                            if (spaces > 0 && extra > 0f) {
                                val per = extra / spaces
                                var seen = 0
                                for (k in 0..raw.length) {
                                    xs[k] += per * seen
                                    if (k < trimmed && raw[k] == ' ') seen++
                                }
                            }
                        }
                        st.index += LineBox(
                            st.pageNo, raw, xs,
                            st.y + (lay.getLineTop(i) - top), st.y + (lay.getLineBottom(i) - top),
                            PW.toFloat(), PH.toFloat(),
                        )
                    }
                    st.y += bot - top
                    start = end
                    if (start < lay.lineCount) st.newPage()
                }
            }

            for (b in blocks) {
                if (b !is DocBlock.Head) lastWasHead = false
                when (b) {
                    is DocBlock.Head -> {
                        val w = TW * 0.55f
                        place(layout(b.text, normal, w, Layout.Alignment.ALIGN_NORMAL), ML + TW * 0.45f, w, false)
                        lastWasHead = true
                    }
                    is DocBlock.Title -> {
                        gap(lineH * 0.8f)
                        val t = paint(true, 16f)
                        place(layout(b.text, t, TW, Layout.Alignment.ALIGN_CENTER), ML, TW, false)
                        gap(lineH * 0.5f)
                    }
                    is DocBlock.Center -> place(layout(b.text, normal, TW, Layout.Alignment.ALIGN_CENTER), ML, TW, false)
                    is DocBlock.Sub -> {
                        gap(lineH * 0.3f)
                        place(layout(b.text, bold, TW, Layout.Alignment.ALIGN_NORMAL), ML, TW, false)
                    }
                    is DocBlock.Right -> place(layout(b.text, normal, TW, Layout.Alignment.ALIGN_OPPOSITE), ML, TW, false)
                    is DocBlock.Para -> {
                        val sp = SpannableString(b.text)
                        if (b.text.isNotEmpty()) sp.setSpan(LeadingMarginSpan.Standard(INDENT.toInt(), 0), 0, sp.length, Spanned.SPAN_PARAGRAPH)
                        place(layout(sp, if (b.bold) bold else normal, TW, Layout.Alignment.ALIGN_NORMAL, justify = true), ML, TW, true)
                    }
                    is DocBlock.Item -> {
                        val sp = SpannableString("– " + b.text)
                        sp.setSpan(LeadingMarginSpan.Standard(INDENT.toInt(), (INDENT + 11f).toInt()), 0, sp.length, Spanned.SPAN_PARAGRAPH)
                        place(layout(sp, normal, TW, Layout.Alignment.ALIGN_NORMAL, justify = true), ML, TW, true)
                    }
                    is DocBlock.Row -> {
                        val left = layout(b.left, normal, TW * 0.6f, Layout.Alignment.ALIGN_NORMAL)
                        val right = layout(b.right, normal, TW * 0.4f, Layout.Alignment.ALIGN_OPPOSITE)
                        val h = maxOf(left.height, right.height).toFloat()
                        ensure(h)
                        val c = st.canvas!!
                        c.save(); c.translate(ML, st.y); left.draw(c); c.restore()
                        c.save(); c.translate(ML + TW * 0.6f, st.y); right.draw(c); c.restore()
                        st.index += LineBox(st.pageNo, b.left + " " + b.right, FloatArray((b.left + " " + b.right).length + 1) { ML }, st.y, st.y + h, PW.toFloat(), PH.toFloat())
                        st.y += h
                    }
                    DocBlock.Rule -> {
                        ensure(10f)
                        val p = Paint().apply { color = Color.BLACK; strokeWidth = 0.6f }
                        st.canvas!!.drawLine(ML, st.y + 5f, PW - MR, st.y + 5f, p)
                        st.y += 10f
                    }
                    DocBlock.Gap -> gap(lineH)
                    DocBlock.HalfGap -> gap(if (lastWasHead) lineH * 0.2f else lineH * 0.5f)
                    DocBlock.PageBreak -> if (!st.atTop) st.newPage()
                }
            }
            st.closePage()
            FileOutputStream(out).use { doc.writeTo(it) }
            return Result(st.pageNo, st.index)
        } finally {
            try { st.closePage() } catch (_: Exception) { }
            doc.close()
        }
    }
}
