package com.iykyk.assignment.domain.pipeline

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.iykyk.assignment.R
import com.iykyk.assignment.domain.model.PersonCluster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class CollageCanvasRenderer(private val context: Context) {

    private val washiYellowBmp by lazy {
        ContextCompat.getDrawable(context, R.drawable.asset_washi_tape_yellow)?.toBitmap(180, 56)
    }
    private val washiPinkBmp by lazy {
        ContextCompat.getDrawable(context, R.drawable.asset_washi_tape_pink)?.toBitmap(180, 56)
    }
    private val heartDoodleBmp by lazy {
        ContextCompat.getDrawable(context, R.drawable.asset_doodle_heart)?.toBitmap(80, 80)
    }
    private val sparkleBmp by lazy {
        ContextCompat.getDrawable(context, R.drawable.asset_doodle_sparkle)?.toBitmap(64, 64)
    }

    /**
     * Renders a 1080x1920 Instagram Story scrapbook poster.
     *
     * Every person detected in the video gets exactly one card, captioned with how many
     * separate appearances they made, so the layout has to work for any number of people
     * rather than for the five the template was drawn around.
     */
    suspend fun renderCollageBitmap(
        clusters: List<PersonCluster>,
        titleText: String = "UNIQUE PEOPLE"
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawPaperBackground(canvas, width, height)

        val colorBases = listOf(
            Color.parseColor("#9370DB"), // Purple watercolor
            Color.parseColor("#F472B6"), // Pink watercolor
            Color.parseColor("#FBBF24"), // Yellow gold
            Color.parseColor("#FB7185"), // Coral / peach
            Color.parseColor("#38BDF8"), // Sky blue
            Color.parseColor("#34D399")  // Mint
        )

        val headerBottom = drawHeader(canvas, width, titleText, clusters)
        val footerTop = drawFooter(canvas, width, height, clusters)

        CollageLayout.place(clusters.size, width, headerBottom, footerTop).forEachIndexed { i, slot ->
            drawScrapbookPhotoCard(
                canvas = canvas,
                cx = slot.cx,
                cy = slot.cy,
                w = slot.w,
                h = slot.h,
                rot = slot.rotation,
                bgColor = colorBases[i % colorBases.size],
                tapeIsPink = i % 2 != 0,
                cluster = clusters[i],
                compact = slot.w < CollageLayout.COMPACT_WIDTH
            )
        }

        bitmap
    }

    private fun drawPaperBackground(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Color.parseColor("#F3ECE0"))

        val gridLine = Paint().apply {
            color = Color.parseColor("#EAE0D0")
            strokeWidth = 1.5f
        }
        var gy = 40f
        while (gy < height) {
            canvas.drawLine(40f, gy, width - 40f, gy, gridLine)
            gy += 45f
        }

        val borderPaper = Paint().apply {
            color = Color.parseColor("#E4D9C8")
            style = Paint.Style.STROKE
            strokeWidth = 24f
        }
        canvas.drawRect(RectF(12f, 12f, width - 12f, height - 12f), borderPaper)

        drawBotanicalSprig(canvas, 90f, 1660f)
        drawBurstLines(canvas, 940f, 250f)
        drawScribbleLoops(canvas, 40f, 1120f)
        sparkleBmp?.let { canvas.drawBitmap(it, 962f, 1180f, null) }
        heartDoodleBmp?.let { canvas.drawBitmap(it, 916f, 1706f, null) }
    }

    /** Draws the title block and returns the y coordinate where cards may start. */
    private fun drawHeader(
        canvas: Canvas,
        width: Int,
        titleText: String,
        clusters: List<PersonCluster>
    ): Float {
        val people = clusters.size
        val appearances = clusters.sumOf { it.appearanceCount }

        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#18181B")
            textAlign = Paint.Align.CENTER
            textSize = 150f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        canvas.drawText(people.toString(), width / 2f, 190f, countPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#18181B")
            textAlign = Paint.Align.CENTER
            textSize = 54f
            letterSpacing = 0.22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(
            if (people == 1) "UNIQUE PERSON" else titleText,
            width / 2f,
            250f,
            titlePaint
        )

        val underline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C2410C")
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(width / 2f - 130f, 276f, width / 2f + 130f, 276f, underline)

        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7A6A55")
            textAlign = Paint.Align.CENTER
            textSize = 34f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        canvas.drawText(
            "$appearances ${if (appearances == 1) "appearance" else "appearances"} in this video",
            width / 2f,
            330f,
            subtitlePaint
        )

        return 370f
    }

    /** Draws the footer strip and returns the y coordinate where cards must stop. */
    private fun drawFooter(
        canvas: Canvas,
        width: Int,
        height: Int,
        clusters: List<PersonCluster>
    ): Float {
        val footerTop = height - 130f

        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8C7853")
            textAlign = Paint.Align.CENTER
            textSize = 30f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        val busiest = clusters.maxByOrNull { it.appearanceCount }
        val note = if (busiest != null && busiest.appearanceCount > 1) {
            "most seen: ${busiest.personLabel} - ${busiest.appearanceCount} appearances"
        } else {
            "everyone who appeared, once each"
        }
        canvas.drawText(note, width / 2f, height - 70f, notePaint)

        return footerTop
    }

    private fun drawScrapbookPhotoCard(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rot: Float,
        bgColor: Int,
        tapeIsPink: Boolean,
        cluster: PersonCluster,
        compact: Boolean
    ) {
        canvas.save()
        canvas.rotate(rot, cx, cy)

        val left = cx - w / 2f
        val top = cy - h / 2f

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 30, 30, 30)
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(left + 6f, top + 10f, left + w + 6f, top + h + 10f),
            8f, 8f, shadowPaint
        )

        val tornBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawPath(createTornRectPath(left, top, w, h, 6f), tornBgPaint)

        val margin = w * 0.05f
        val whiteLeft = left + margin
        val whiteTop = top + margin
        val whiteW = w - margin * 2f
        val whiteH = h - margin * 2f

        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val whiteBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2518181B")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val innerTorn = createTornRectPath(whiteLeft, whiteTop, whiteW, whiteH, 3f)
        canvas.drawPath(innerTorn, whitePaint)
        canvas.drawPath(innerTorn, whiteBorder)

        // Polaroid proportions: a wide caption skirt below the photo carries the name and
        // the appearance count, which the assignment asks to be shown per person.
        val photoMargin = whiteW * 0.05f
        val captionH = if (compact) whiteH * 0.20f else whiteH * 0.17f
        val photoRect = RectF(
            whiteLeft + photoMargin,
            whiteTop + photoMargin,
            whiteLeft + whiteW - photoMargin,
            whiteTop + whiteH - captionH
        )

        drawPhotoAspectFill(canvas, cluster, photoRect)

        val photoOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#20000000")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRect(photoRect, photoOutline)

        drawCaption(canvas, cluster, whiteLeft, whiteW, photoRect.bottom, captionH, bgColor, compact)

        val tapeBmp = if (tapeIsPink) washiPinkBmp else washiYellowBmp
        tapeBmp?.let { tape ->
            val tw = w * 0.42f
            val th = tw * 0.31f
            val tLeft = cx - tw / 2f
            val tTop = top - th / 2f + 4f
            canvas.drawBitmap(
                tape,
                Rect(0, 0, tape.width, tape.height),
                Rect(tLeft.toInt(), tTop.toInt(), (tLeft + tw).toInt(), (tTop + th).toInt()),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        }

        canvas.restore()
    }

    /**
     * Draws the person's photo filling [dest] without distorting it.
     *
     * The previous version mapped the whole crop onto the tile rectangle, so any crop whose
     * aspect ratio differed from the card - which is most of them - was stretched, and
     * faces came out subtly widened or squashed. This scales uniformly and centres the
     * overflowing axis, biased slightly upward because the subject sits above centre in a
     * portrait crop.
     */
    private fun drawPhotoAspectFill(canvas: Canvas, cluster: PersonCluster, dest: RectF) {
        val faceBmp = cluster.representativeShot.generousCropBitmap
            ?: cluster.representativeShot.alignedCropBitmap
            ?: return

        val destAspect = dest.width() / dest.height()
        val srcAspect = faceBmp.width.toFloat() / faceBmp.height

        val src = if (srcAspect > destAspect) {
            // Source is wider: trim the sides.
            val keepW = (faceBmp.height * destAspect).toInt().coerceAtLeast(1)
            val x = ((faceBmp.width - keepW) / 2).coerceAtLeast(0)
            Rect(x, 0, x + keepW, faceBmp.height)
        } else {
            // Source is taller: trim mostly from the bottom, keeping the head in view.
            val keepH = (faceBmp.width / destAspect).toInt().coerceAtLeast(1)
            val y = ((faceBmp.height - keepH) * 0.35f).toInt().coerceAtLeast(0)
            Rect(0, y, faceBmp.width, (y + keepH).coerceAtMost(faceBmp.height))
        }

        canvas.save()
        canvas.clipRect(dest)
        canvas.drawBitmap(
            faceBmp,
            src,
            Rect(
                dest.left.toInt(),
                dest.top.toInt(),
                dest.right.toInt(),
                dest.bottom.toInt()
            ),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        canvas.restore()
    }

    /** Handwritten-style name plus the appearance count for one person. */
    private fun drawCaption(
        canvas: Canvas,
        cluster: PersonCluster,
        whiteLeft: Float,
        whiteW: Float,
        captionTop: Float,
        captionH: Float,
        accent: Int,
        compact: Boolean
    ) {
        val centerX = whiteLeft + whiteW / 2f

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#18181B")
            textAlign = Paint.Align.CENTER
            textSize = if (compact) 26f else 34f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        canvas.drawText(cluster.personLabel, centerX, captionTop + captionH * 0.42f, namePaint)

        val count = cluster.appearanceCount
        val countLabel = if (compact) {
            "x$count"
        } else {
            "$count ${if (count == 1) "appearance" else "appearances"}"
        }

        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5A5148")
            textAlign = Paint.Align.CENTER
            textSize = if (compact) 21f else 26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val badgeY = captionTop + captionH * 0.76f
        val textWidth = countPaint.measureText(countLabel)
        val padX = if (compact) 10f else 16f
        val badgeRect = RectF(
            centerX - textWidth / 2f - padX,
            badgeY - countPaint.textSize * 0.86f,
            centerX + textWidth / 2f + padX,
            badgeY + countPaint.textSize * 0.34f
        )

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(accent, 48)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(badgeRect, badgeRect.height() / 2f, badgeRect.height() / 2f, badgePaint)
        canvas.drawText(countLabel, centerX, badgeY, countPaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun createTornRectPath(l: Float, t: Float, w: Float, h: Float, jitter: Float): Path {
        val path = Path()
        path.moveTo(l, t)
        
        // Top edge with subtle waviness
        val steps = 8
        for (i in 1..steps) {
            val px = l + (w / steps) * i
            val py = t + if (i % 2 == 0) jitter else -jitter
            path.lineTo(px, py)
        }

        // Right edge
        for (i in 1..steps) {
            val px = l + w + if (i % 2 == 0) jitter else -jitter
            val py = t + (h / steps) * i
            path.lineTo(px, py)
        }

        // Bottom edge
        for (i in 1..steps) {
            val px = l + w - (w / steps) * i
            val py = t + h + if (i % 2 == 0) -jitter else jitter
            path.lineTo(px, py)
        }

        // Left edge
        for (i in 1..steps) {
            val px = l + if (i % 2 == 0) -jitter else jitter
            val py = t + h - (h / steps) * i
            path.lineTo(px, py)
        }

        path.close()
        return path
    }

    private fun drawGridNoteScrap(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, rot: Float) {
        canvas.save()
        canvas.rotate(rot, cx, cy)
        val l = cx - w / 2f
        val t = cy - h / 2f
        val p = Paint().apply {
            color = Color.parseColor("#FFFDF5")
            style = Paint.Style.FILL
        }
        val border = Paint().apply {
            color = Color.parseColor("#D4D4D8")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRect(RectF(l, t, l + w, t + h), p)
        canvas.drawRect(RectF(l, t, l + w, t + h), border)

        // Grid lines inside scrap
        val gl = Paint().apply {
            color = Color.parseColor("#E4E4E7")
            strokeWidth = 1f
        }
        var y = t + 20f
        while (y < t + h) {
            canvas.drawLine(l, y, l + w, y, gl)
            y += 20f
        }
        var x = l + 20f
        while (x < l + w) {
            canvas.drawLine(x, t, x, t + h, gl)
            x += 20f
        }
        canvas.restore()
    }

    private fun drawBurstLines(canvas: Canvas, cx: Float, cy: Float) {
        val paint = Paint().apply {
            color = Color.parseColor("#18181B")
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        canvas.drawLine(cx - 30f, cy + 20f, cx - 45f, cy - 25f, paint)
        canvas.drawLine(cx, cy + 15f, cx + 5f, cy - 35f, paint)
        canvas.drawLine(cx + 30f, cy + 20f, cx + 50f, cy - 20f, paint)
    }

    private fun drawScribbleLoops(canvas: Canvas, cx: Float, cy: Float) {
        val paint = Paint().apply {
            color = Color.parseColor("#18181B")
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val path = Path().apply {
            moveTo(cx - 20f, cy)
            cubicTo(cx + 10f, cy - 30f, cx + 30f, cy + 20f, cx + 10f, cy + 30f)
            cubicTo(cx - 15f, cy + 40f, cx + 25f, cy + 50f, cx + 35f, cy + 20f)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawBotanicalSprig(canvas: Canvas, cx: Float, cy: Float) {
        val paint = Paint().apply {
            color = Color.parseColor("#8C7853")
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        // Main stem
        val stem = Path().apply {
            moveTo(cx, cy + 160f)
            cubicTo(cx - 15f, cy + 80f, cx + 10f, cy + 20f, cx - 20f, cy - 60f)
        }
        canvas.drawPath(stem, paint)

        // Little yellow flowers on stem
        val flowerPaint = Paint().apply {
            color = Color.parseColor("#E6C280")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(cx - 20f, cy - 60f, 6f, flowerPaint)
        canvas.drawCircle(cx - 30f, cy - 40f, 5f, flowerPaint)
        canvas.drawCircle(cx + 5f, cy, 5f, flowerPaint)
        canvas.drawCircle(cx - 10f, cy + 40f, 5f, flowerPaint)
    }

    /**
     * Saves the collage into the device gallery, returning its MediaStore uri or null.
     *
     * The pending-item dance is only available from API 29; before that the row is written
     * directly, which is also why the manifest still asks for WRITE_EXTERNAL_STORAGE up to
     * API 28. Failures are reported as null rather than thrown so the UI can tell the user
     * instead of crashing on a save.
     */
    suspend fun saveToGallery(bitmap: Bitmap, title: String = "collage"): Uri? = withContext(Dispatchers.IO) {
        val filename = "${title}_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/UniqueCollages")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IllegalStateException("Bitmap could not be encoded")
                }
            } ?: throw IllegalStateException("MediaStore returned no output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            // Do not leave a half-written or permanently pending row behind.
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * Builds an ACTION_SEND chooser for the collage, wrapped so the standard Android share
     * sheet is always shown rather than silently launching a default handler.
     *
     * The uri is also set as clipData: the read grant flag alone is unreliable for
     * EXTRA_STREAM on several receivers, which show up as a blank or failed share.
     */
    suspend fun createShareIntent(bitmap: Bitmap): Intent? = withContext(Dispatchers.IO) {
        val contentUri = try {
            val cachePath = File(context.cacheDir, "images").apply { mkdirs() }
            val file = File(cachePath, "collage_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            return@withContext null
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            clipData = ClipData.newRawUri("collage", contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        Intent.createChooser(sendIntent, "Share collage").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
