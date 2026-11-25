package com.chethan616.clearpdf.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.notes.CheckItem
import com.chethan616.clearpdf.notes.Note
import com.chethan616.clearpdf.ui.theme.CardTone
import com.chethan616.clearpdf.ui.theme.DarkClear
import com.chethan616.clearpdf.ui.theme.LightClear
import com.chethan616.clearpdf.ui.theme.Tone
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The desk's cards drawn for the home screen: the same gradients, glow, rim, type, sizes and
 * spacing as `ListCard` and `NoteCard` (ui/home/Cards.kt), laid out in dp at the widget's own size.
 * A widget can't host the app's live glass (it refracts only what is behind it inside the app), so
 * the list's pills are painted as the glass looks over their card: a lighter capsule with a rim.
 */
internal class WidgetCards(private val ctx: Context, private val w: Int, private val h: Int) {
    private val dm = ctx.resources.displayMetrics
    private val fontScale = ctx.resources.configuration.fontScale
    private fun dp(v: Float) = v * dm.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)
    val dark: Boolean = isDark(ctx)
    private val colors = if (dark) DarkClear else LightClear

    private fun face(res: Int): Typeface? = ResourcesCompat.getFont(ctx, res)

    private fun paint(res: Int, sizeSp: Float, color: Int, variation: String, trackingSp: Float = 0f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face(res)
        textSize = sp(sizeSp)
        this.color = color
        letterSpacing = if (sizeSp > 0f) trackingSp / sizeSp else 0f
        fontVariationSettings = variation
    }

    private fun layout(text: CharSequence, p: TextPaint, width: Int, lineSp: Float, maxLines: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout {
        val natural = p.fontMetrics.let { it.descent - it.ascent }
        val extra = (sp(lineSp) - natural).coerceAtLeast(0f)
        return StaticLayout.Builder.obtain(text, 0, text.length, p, width.coerceAtLeast(1))
            .setAlignment(align)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .setLineSpacing(extra, 1f)
            .build()
    }

    /** `WordFitText`: the size shrinks when the longest word would not fit the width. */
    private fun fitScale(text: String, p: TextPaint, room: Int): Float {
        val widest = text.split(' ', '\n').filter { it.isNotBlank() }.maxOfOrNull { p.measureText(it) } ?: 0f
        return if (widest <= room || widest == 0f) 1f else (room / widest * 0.98f).coerceAtLeast(0.5f)
    }

    // ---- the card itself: cardPaint ----------------------------------------------------------------

    private fun base(tone: CardTone, glowX: Float, glowY: Float): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val radius = dp(30f)
        val shape = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW) }
        c.save()
        c.clipPath(shape)
        // what the desk shows behind a translucent card: the wallpaper's middle
        c.drawColor(colors.wall[colors.wall.size / 2].toArgb())
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), tone.top.toArgb(), tone.bottom.toArgb(), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        val reach = maxOf(w, h) * 0.75f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                glowX * w, glowY * h, reach,
                Color.White.copy(alpha = if (dark) 0.10f else 0.32f).toArgb(), android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(glowX * w, glowY * h, reach, glow)
        c.restore()
        // the 1 dp rim: bright at the top, nearly gone at the bottom
        val inset = dp(0.5f)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.White.copy(alpha = if (dark) 0.18f else 0.6f).toArgb(), Color.White.copy(alpha = 0.05f).toArgb(), Shader.TileMode.CLAMP,
            )
        }
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), radius - inset, radius - inset, rim)
        return bmp to c
    }

    private fun Color.argb(alpha: Float) = copy(alpha = alpha).toArgb()

    // ---- the list: ListCard ---------------------------------------------------------------------------

    /** Where each ticked-able pill landed, in pixels, for the widget's tap targets. */
    class Spot(val item: CheckItem, val top: Int, val height: Int)

    class Drawn(val bitmap: Bitmap, val spots: List<Spot>)

    fun list(note: Note, untitled: String, empty: String, moreText: (Int) -> String): Drawn {
        val tone = colors.tone(Tone.Sage)
        val (bmp, c) = base(tone, 0.15f, 0f)
        val items = note.items.filter { it.text.isNotBlank() }
        val padX = dp(12f)
        val inner = (w - 2 * padX).toInt()
        var y = dp(20f)

        // the title in dot-matrix type, centred, its longest word kept whole
        val titleText = note.title.ifBlank { untitled }
        val titleBase = paint(R.font.doto, 30f, tone.ink.argb(0.92f), "'wght' 800, 'ROND' 60")
        val titleRoom = inner - dp(4f).toInt()
        val k = fitScale(titleText, titleBase, titleRoom)
        val titlePaint = paint(R.font.doto, 30f * k, tone.ink.argb(0.92f), "'wght' 800, 'ROND' 60")
        val title = layout(titleText, titlePaint, titleRoom, 32f * k, 3, Layout.Alignment.ALIGN_CENTER)
        c.save()
        c.translate(padX + dp(2f), y)
        title.draw(c)
        c.restore()
        y += title.height + dp(18f)

        // as many pills as fit (the desk shows four), 40 dp tall with 8 dp between
        val pillH = dp(40f)
        val gap = dp(8f)
        val moreRoom = dp(8f) + sp(15f) + dp(14f)
        var fit = 0
        while (fit < items.size && y + (fit + 1) * pillH + fit * gap + dp(14f) <= h) fit++
        if (fit < items.size && y + fit * (pillH + gap) - gap + moreRoom > h && fit > 1) fit-- // room for "+N more"
        fit = fit.coerceAtLeast(if (items.isEmpty()) 0 else 1).coerceAtMost(8)
        val spots = ArrayList<Spot>()
        for (i in 0 until fit.coerceAtMost(items.size)) {
            pill(c, items[i], tone, padX, y, w - padX, y + pillH)
            spots.add(Spot(items[i], y.toInt(), pillH.toInt()))
            y += pillH + gap
        }
        val more = items.size - spots.size
        if (more > 0) {
            val p = paint(R.font.outfit, 11.5f, tone.ink.argb(0.6f), "'wght' 400")
            c.save()
            c.translate(padX + dp(10f), y)
            layout(moreText(more), p, inner, 15f, 1).draw(c)
            c.restore()
        }
        if (items.isEmpty()) {
            val p = paint(R.font.outfit, 13f, tone.ink.argb(0.6f), "'wght' 500")
            c.save()
            c.translate(padX, y)
            layout(empty, p, inner, 18f, 2, Layout.Alignment.ALIGN_CENTER).draw(c)
            c.restore()
        }
        return Drawn(bmp, spots)
    }

    /** `CheckPill`: a glass capsule with a round tick; ticked, it darkens and the tick fills. */
    private fun pill(c: Canvas, item: CheckItem, tone: CardTone, left: Float, top: Float, right: Float, bottom: Float) {
        val r = (bottom - top) / 2f
        val rect = RectF(left, top, right, bottom)
        // the glass over the card: a lift of light, a rim bright at the top
        val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, top, 0f, bottom,
                Color.White.copy(alpha = if (dark) 0.16f else 0.34f).toArgb(), Color.White.copy(alpha = if (dark) 0.07f else 0.2f).toArgb(), Shader.TileMode.CLAMP,
            )
        }
        c.drawRoundRect(rect, r, r, glass)
        if (item.done) {
            val wash = if (dark) Color.Black.copy(alpha = 0.34f) else tone.ink.copy(alpha = 0.58f)
            c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = wash.toArgb() })
        }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            shader = LinearGradient(
                0f, top, 0f, bottom,
                Color.White.copy(alpha = if (dark) 0.35f else 0.8f).toArgb(), Color.White.copy(alpha = if (dark) 0.08f else 0.22f).toArgb(), Shader.TileMode.CLAMP,
            )
        }
        val half = dp(0.5f)
        c.drawRoundRect(RectF(left + half, top + half, right - half, bottom - half), r - half, r - half, rim)

        val ink = if (item.done) Color.White else tone.ink
        val cx = left + dp(8f) + dp(12f)
        val cy = (top + bottom) / 2f
        if (item.done) {
            c.drawCircle(cx, cy, dp(12f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(2f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = (if (dark) tone.bottom else tone.ink).toArgb()
            }
            val s = dp(14f) / 24f
            val path = Path().apply {
                moveTo(cx + (5f - 12f) * s * 1.0f, cy + (12.5f - 12f) * s)
                lineTo(cx + (10f - 12f) * s, cy + (17f - 12f) * s)
                lineTo(cx + (19f - 12f) * s, cy + (7f - 12f) * s)
            }
            c.drawPath(path, tick)
        } else {
            c.drawCircle(cx, cy, dp(12f) - dp(0.8f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(1.6f)
                color = ink.argb(0.75f)
            })
        }
        val textX = left + dp(8f) + dp(24f) + dp(10f)
        val tp = paint(R.font.outfit, 14.5f, ink.toArgb(), "'wght' 400")
        val room = (right - dp(12f) - textX).toInt()
        val line = layout(item.text, tp, room, 22f, 1)
        c.save()
        c.translate(textX, cy - line.height / 2f)
        line.draw(c)
        c.restore()
    }

    // ---- the note: NoteCard -----------------------------------------------------------------------------

    fun note(note: Note, untitled: String, mediaFile: (String) -> File): Bitmap {
        val tone = colors.tone(Tone.Paper)
        val (bmp, c) = base(tone, 0.1f, 0f)
        val pad = dp(18f)
        val inner = (w - 2 * pad).toInt()
        val title = note.title.ifBlank { note.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("# ").orEmpty() }
        val body = if (note.title.isBlank()) "" else note.body.lineSequence().filter { it.isNotBlank() }.joinToString(" ") { it.trim().removePrefix("# ").replace("**", "") }
        var y = pad

        val titleText = title.ifBlank { untitled }
        val variation = "'wght' 800"
        val base = paint(R.font.outfit, 27f, tone.accent.toArgb(), variation, -0.6f)
        val k = fitScale(titleText, base, inner)
        val tp = paint(R.font.outfit, 27f * k, tone.accent.toArgb(), variation, -0.6f * k)
        val tl = layout(titleText, tp, inner, 29f * k, 4)
        c.save(); c.translate(pad, y); tl.draw(c); c.restore()
        y += tl.height

        val date = shortDate(note.updated)
        val dp0 = paint(R.font.outfit, 10.5f, tone.ink.argb(0.55f), "'wght' 400")
        val dateH = dp0.fontMetrics.let { it.descent - it.ascent }
        val dateTop = h - pad - dateH
        if (body.isNotBlank() && title.length < 40) {
            // up to three lines, as many as fit above the date (the desk card grows instead)
            val lines = ((dateTop - dp(12f) - (y + dp(8f))) / sp(18f)).toInt().coerceAtMost(3)
            if (lines >= 1) {
                y += dp(8f)
                val bp = paint(R.font.outfit, 13f, tone.ink.argb(0.62f), "'wght' 500")
                val bl = layout(body, bp, inner, 18f, lines)
                c.save(); c.translate(pad, y); bl.draw(c); c.restore()
                y += bl.height
            }
        }
        note.images.firstOrNull()?.let { name ->
            val room = dateTop - dp(12f) - (y + dp(12f))
            val imgH = minOf(dp(86f), room)
            if (imgH > dp(24f)) {
                decode(mediaFile(name), inner, imgH.toInt())?.let { pic ->
                    val clip = Path().apply { addRoundRect(RectF(pad, y + dp(12f), pad + inner, y + dp(12f) + imgH), dp(16f), dp(16f), Path.Direction.CW) }
                    c.save(); c.clipPath(clip)
                    // ContentScale.Crop
                    val s = maxOf(inner / pic.width.toFloat(), imgH / pic.height.toFloat())
                    val dw = pic.width * s
                    val dh = pic.height * s
                    c.drawBitmap(pic, null, RectF(pad + (inner - dw) / 2f, y + dp(12f) + (imgH - dh) / 2f, pad + (inner + dw) / 2f, y + dp(12f) + (imgH + dh) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
                    c.restore()
                    pic.recycle()
                }
            }
        }
        // the date, at the bottom right
        val dl = layout(date, dp0, inner, 15f, 1, Layout.Alignment.ALIGN_OPPOSITE)
        c.save(); c.translate(pad, h - pad - dl.height); dl.draw(c); c.restore()
        return bmp
    }

    private fun decode(file: File, maxW: Int, maxH: Int): Bitmap? = runCatching {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun shortDate(t: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))

    companion object {
        /** The app's theme choice (System, Light, Dark), as the desk reads it. */
        fun isDark(ctx: Context): Boolean {
            val choice = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getString("theme", "System")
            return when (choice) {
                "Light" -> false
                "Dark" -> true
                else -> ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}
