package com.rwai.agent.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.rwai.agent.logic.Segment
import com.rwai.agent.logic.PhraseFinder
import com.rwai.agent.logic.Shape
import java.io.File
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Рисует фигуры из [com.rwai.agent.logic.ShapeMarkup] на Canvas. Координаты в процентах холста. */
object Drawing {
    private fun fillPaint(color: Int, alpha: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.FILL; this.alpha = (alpha * 255).toInt().coerceIn(0, 255)
    }

    fun drawShape(c: Canvas, w: Float, h: Float, s: Shape) {
        when (s) {
            is Shape.Background -> {
                val p = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h, s.top, s.bottom, Shader.TileMode.CLAMP) }
                c.drawRect(0f, 0f, w, h, p)
            }
            is Shape.Rect -> {
                val r = RectF(s.x * w / 100f, s.y * h / 100f, (s.x + s.w) * w / 100f, (s.y + s.h) * h / 100f)
                val p = fillPaint(s.color, s.alpha)
                if (!s.fill) { p.style = Paint.Style.STROKE; p.strokeWidth = max(2f, w * 0.004f) }
                val rad = s.round * w / 100f
                if (rad > 0f) c.drawRoundRect(r, rad, rad, p) else c.drawRect(r, p)
            }
            is Shape.Circle -> {
                val p = fillPaint(s.color)
                if (!s.fill) { p.style = Paint.Style.STROKE; p.strokeWidth = max(2f, w * 0.004f) }
                c.drawCircle(s.x * w / 100f, s.y * h / 100f, s.r * w / 100f, p)
            }
            is Shape.Line -> {
                val p = fillPaint(s.color).apply { style = Paint.Style.STROKE; strokeWidth = max(1.5f, s.width * w / 100f); strokeCap = Paint.Cap.ROUND }
                val x1 = s.x1 * w / 100f; val y1 = s.y1 * h / 100f; val x2 = s.x2 * w / 100f; val y2 = s.y2 * h / 100f
                c.drawLine(x1, y1, x2, y2, p)
                if (s.arrow) {
                    val a = atan2(y2 - y1, x2 - x1); val len = max(14f, p.strokeWidth * 4f)
                    for (d in floatArrayOf(2.6f, -2.6f)) c.drawLine(x2, y2, x2 + len * cos(a + d), y2 + len * sin(a + d), p)
                }
            }
            is Shape.Text -> {
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = s.color; textSize = s.size * w / 100f
                    typeface = Typeface.create(Typeface.SANS_SERIF, if (s.bold) Typeface.BOLD else Typeface.NORMAL)
                }
                val xpx = s.x * w / 100f
                val maxW = max(60f, when (s.align) { "center" -> 2f * min(xpx, w - xpx); "right" -> xpx; else -> w - xpx })
                val lay = StaticLayout.Builder.obtain(s.text, 0, s.text.length, tp, maxW.toInt())
                    .setAlignment(when (s.align) { "center" -> Layout.Alignment.ALIGN_CENTER; "right" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL })
                    .setIncludePad(false).build()
                val left = when (s.align) { "center" -> xpx - maxW / 2f; "right" -> xpx - maxW; else -> xpx }
                c.save(); c.translate(left, s.y * h / 100f); lay.draw(c); c.restore()
            }
            else -> {}
        }
    }

    /** Пометка по найденному отрезку строки PDF (координаты в pt → пиксели). */
    fun drawSegment(c: Canvas, w: Float, h: Float, seg: Segment, style: String, color: Int) {
        val sx = w / seg.pageW; val sy = h / seg.pageH
        val x1 = seg.x1 * sx - 1f; val x2 = seg.x2 * sx + 1f
        val top = seg.top * sy; val bot = seg.bottom * sy
        val lw = max(2f, w * 0.0025f)
        when (style) {
            "highlight" -> c.drawRect(x1, top, x2, bot, fillPaint(color, 0.38f))
            "underline" -> c.drawRect(x1, bot - lw, x2, bot, fillPaint(color))
            "strike" -> c.drawRect(x1, (top + bot) / 2f - lw / 2f, x2, (top + bot) / 2f + lw / 2f, fillPaint(color))
            else -> c.drawRect(x1 - 2f, top, x2 + 2f, bot, fillPaint(color).apply { this.style = Paint.Style.STROKE; strokeWidth = lw })
        }
    }

    /** Новая картинка PNG по списку фигур. */
    fun renderImage(shapes: List<Shape>): Bitmap {
        val cv = shapes.filterIsInstance<Shape.Canvas>().firstOrNull() ?: Shape.Canvas(1080, 1080)
        val bmp = Bitmap.createBitmap(cv.w, cv.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        for (s in shapes) drawShape(c, cv.w.toFloat(), cv.h.toFloat(), s)
        return bmp
    }

    fun savePng(ctx: Context, bmp: Bitmap, name: String): GeneratedFile {
        val f = DocFiles.uniqueFile(ctx, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return GeneratedFile(f.name, f.absolutePath, "image/png")
    }

    class Annotated(val file: GeneratedFile, val marks: Int, val missing: List<String>)

    /**
     * Разметка существующего файла (PDF или картинка). PDF перерисовывается постранично как картинки с пометками
     * (текст в результате перестаёт быть выделяемым — это честная цена простого способа без внешних библиотек).
     */
    fun annotate(ctx: Context, src: GeneratedFile, shapes: List<Shape>, outBase: String): Annotated {
        val drawables = shapes.filter { it !is Shape.Page && it !is Shape.Phrase && it !is Shape.Canvas && it !is Shape.Background }
        val missing = mutableListOf<String>()
        var marks = 0
        // Распределяем по страницам: после Page(n) все фигуры относятся к n‑й странице.
        val byPage = HashMap<Int, MutableList<Any>>()
        var cur = 1
        for (s in shapes) {
            when (s) {
                is Shape.Page -> cur = s.n
                is Shape.Phrase -> {
                    val idx = GeneratedFiles.index(src.name)
                    val segs = if (idx == null) emptyList() else PhraseFinder.find(idx, s.phrase)
                    if (segs.isEmpty()) missing += s.phrase
                    else for (g in segs) { byPage.getOrPut(g.page) { mutableListOf() } += PhraseDraw(g, s.style, s.color); marks++ }
                }
                in drawables -> { byPage.getOrPut(cur) { mutableListOf() } += s; marks++ }
                else -> {}
            }
        }
        if (src.mime == "application/pdf") {
            val out = DocFiles.uniqueFile(ctx, "$outBase.pdf")
            val pfd = ParcelFileDescriptor.open(File(src.path), ParcelFileDescriptor.MODE_READ_ONLY)
            val doc = PdfDocument()
            try {
                PdfRenderer(pfd).use { r ->
                    if (r.pageCount > 25) throw IllegalArgumentException("Слишком много страниц для разметки (${r.pageCount}, максимум 25).")
                    for (i in 0 until r.pageCount) {
                        r.openPage(i).use { p ->
                            val scale = 1240f / p.width
                            val bw = (p.width * scale).toInt(); val bh = (p.height * scale).toInt()
                            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val cv = Canvas(bmp)
                            byPage[i + 1]?.forEach { drawAny(cv, bw.toFloat(), bh.toFloat(), it) }
                            val page = doc.startPage(PdfDocument.PageInfo.Builder(p.width, p.height, i + 1).create())
                            page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, p.width.toFloat(), p.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                            doc.finishPage(page)
                            bmp.recycle()
                        }
                    }
                }
                FileOutputStream(out).use { doc.writeTo(it) }
            } finally { doc.close(); pfd.close() }
            return Annotated(GeneratedFile(out.name, out.absolutePath, "application/pdf"), marks, missing)
        }
        // Картинка
        val opts = BitmapFactory.Options().apply { inMutable = true }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
        opts.inSampleSize = sample
        val bmp = BitmapFactory.decodeFile(src.path, opts) ?: throw IllegalArgumentException("Не удалось прочитать изображение.")
        val cv = Canvas(bmp)
        byPage[1]?.forEach { drawAny(cv, bmp.width.toFloat(), bmp.height.toFloat(), it) }
        val jpeg = src.mime == "image/jpeg"
        val out = DocFiles.uniqueFile(ctx, "$outBase." + if (jpeg) "jpg" else "png")
        FileOutputStream(out).use { bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, it) }
        bmp.recycle()
        return Annotated(GeneratedFile(out.name, out.absolutePath, if (jpeg) "image/jpeg" else "image/png"), marks, missing)
    }

    // Внутренний «тип» для отрезков фраз, чтобы хранить их в том же списке, что и фигуры.
    private class PhraseDraw(val seg: Segment, val style: String, val color: Int)
    private fun drawAny(c: Canvas, w: Float, h: Float, s: Any) {
        if (s is PhraseDraw) drawSegment(c, w, h, s.seg, s.style, s.color) else if (s is Shape) drawShape(c, w, h, s)
    }
}
