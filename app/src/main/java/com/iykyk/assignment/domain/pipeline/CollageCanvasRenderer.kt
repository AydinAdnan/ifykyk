package com.iykyk.assignment.domain.pipeline

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
     * Renders a 1080x1920 Instagram Story scrapbook poster matching the template design.
     */
    suspend fun renderCollageBitmap(
        clusters: List<PersonCluster>,
        titleText: String = "UNIQUE PEOPLE"
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Aged Vintage Parchment Background (#F2ECE1)
        canvas.drawColor(Color.parseColor("#F3ECE0"))

        // Subtle aged paper border & paper grain tone
        val borderPaper = Paint().apply {
            color = Color.parseColor("#E4D9C8")
            style = Paint.Style.STROKE
            strokeWidth = 24f
        }
        canvas.drawRect(RectF(12f, 12f, width - 12f, height - 12f), borderPaper)

        // Grid paper lines in center area
        val gridLine = Paint().apply {
            color = Color.parseColor("#EAE0D0")
            strokeWidth = 1.5f
        }
        var gy = 40f
        while (gy < height) {
            canvas.drawLine(40f, gy, width - 40f, gy, gridLine)
            gy += 45f
        }

        // Draw botanical doodle / dried flower sprig in bottom-left
        drawBotanicalSprig(canvas, 100f, 1720f)

        // Draw doodle burst lines in top-right
        drawBurstLines(canvas, 920f, 180f)

        // Draw scribble loops in mid-right
        drawScribbleLoops(canvas, 960f, 1240f)

        // Draw sparkle stars in mid-left
        sparkleBmp?.let { sp ->
            canvas.drawBitmap(sp, 80f, 850f, null)
        }

        // Draw heart doodle in bottom-right
        heartDoodleBmp?.let { hd ->
            canvas.drawBitmap(hd, 900f, 1740f, null)
        }

        // 2. Coordinated Card Layout (Matching 5-Photo Scrapbook Template)
        val colorBases = listOf(
            Color.parseColor("#9370DB"), // Purple watercolor
            Color.parseColor("#F472B6"), // Pink watercolor
            Color.parseColor("#FBBF24"), // Yellow gold
            Color.parseColor("#FB7185"), // Coral / peach
            Color.parseColor("#38BDF8")  // Sky blue
        )

        val n = clusters.size
        if (n == 5) {
            // Exact 5-Card Layout from user's template:
            // 1. Top-Left (Purple, -4.5 deg)
            drawScrapbookPhotoCard(
                canvas, cx = 290f, cy = 420f, w = 420f, h = 530f, rot = -4.5f,
                bgColor = colorBases[0], tapeIsPink = false,
                cluster = clusters[0]
            )

            // 2. Top-Right (Pink, +4.0 deg)
            drawScrapbookPhotoCard(
                canvas, cx = 790f, cy = 460f, w = 420f, h = 530f, rot = 4.0f,
                bgColor = colorBases[1], tapeIsPink = true,
                cluster = clusters[1]
            )

            // 3. Center Hero (Yellow, 0.0 deg with grid note scrap)
            drawGridNoteScrap(canvas, 700f, 980f, 140f, 260f, 8f)
            drawScrapbookPhotoCard(
                canvas, cx = 540f, cy = 970f, w = 450f, h = 560f, rot = 0.0f,
                bgColor = colorBases[2], tapeIsPink = false,
                cluster = clusters[2]
            )

            // 4. Bottom-Left (Coral, +3.5 deg)
            drawScrapbookPhotoCard(
                canvas, cx = 300f, cy = 1520f, w = 430f, h = 540f, rot = 3.5f,
                bgColor = colorBases[3], tapeIsPink = true,
                cluster = clusters[3]
            )

            // 5. Bottom-Right (Blue, -3.0 deg)
            drawScrapbookPhotoCard(
                canvas, cx = 780f, cy = 1550f, w = 430f, h = 540f, rot = -3.0f,
                bgColor = colorBases[4], tapeIsPink = false,
                cluster = clusters[4]
            )
        } else {
            // Adaptive Grid for other counts (1 to 6 people)
            val cols = if (n <= 4) 2 else 3
            val rows = (n + cols - 1) / cols
            val startY = 240f
            val availableHeight = 1500f
            val cellW = (width - 100f) / cols
            val cellH = availableHeight / rows

            clusters.forEachIndexed { i, cl ->
                val col = i % cols
                val row = i / cols
                val cx = 50f + col * cellW + cellW / 2f
                val cy = startY + row * cellH + cellH / 2f
                val rot = if (i % 2 == 0) -3f else 3f
                drawScrapbookPhotoCard(
                    canvas, cx = cx, cy = cy, w = cellW * 0.90f, h = cellH * 0.88f, rot = rot,
                    bgColor = colorBases[i % colorBases.size], tapeIsPink = (i % 2 != 0),
                    cluster = cl
                )
            }
        }

        bitmap
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
        cluster: PersonCluster
    ) {
        canvas.save()
        canvas.rotate(rot, cx, cy)

        val left = cx - w / 2f
        val top = cy - h / 2f

        // 1. Soft Shadow
        val shadowPaint = Paint().apply {
            color = Color.argb(45, 30, 30, 30)
            style = Paint.Style.FILL
            isAntiAlias = true
            maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(RectF(left + 6f, top + 10f, left + w + 6f, top + h + 10f), 8f, 8f, shadowPaint)

        // 2. Torn Colored Watercolor Backing Paper
        val tornBgPaint = Paint().apply {
            color = bgColor
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val tornPath = createTornRectPath(left, top, w, h, 6f)
        canvas.drawPath(tornPath, tornBgPaint)

        // 3. White Polaroid Frame with deckle edge
        val margin = 20f
        val whiteLeft = left + margin
        val whiteTop = top + margin
        val whiteW = w - (margin * 2f)
        val whiteH = h - (margin * 2f)

        val whitePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val whiteBorder = Paint().apply {
            color = Color.parseColor("#2518181B")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        val innerTorn = createTornRectPath(whiteLeft, whiteTop, whiteW, whiteH, 3f)
        canvas.drawPath(innerTorn, whitePaint)
        canvas.drawPath(innerTorn, whiteBorder)

        // 4. The Person's Best Photo Crop
        val photoMargin = 12f
        val pLeft = whiteLeft + photoMargin
        val pTop = whiteTop + photoMargin
        val pW = whiteW - (photoMargin * 2f)
        val pH = whiteH - (photoMargin * 2f)
        val photoRect = RectF(pLeft, pTop, pLeft + pW, pTop + pH)

        val faceBmp = cluster.representativeShot.generousCropBitmap ?: cluster.representativeShot.alignedCropBitmap
        if (faceBmp != null) {
            canvas.save()
            canvas.clipRect(photoRect)
            val src = Rect(0, 0, faceBmp.width, faceBmp.height)
            val dst = Rect(pLeft.toInt(), pTop.toInt(), (pLeft + pW).toInt(), (pTop + pH).toInt())
            canvas.drawBitmap(faceBmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }

        // Inner photo outline
        val photoOutline = Paint().apply {
            color = Color.parseColor("#20000000")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRect(photoRect, photoOutline)

        // 5. Washi Tape on Top Center
        val tapeBmp = if (tapeIsPink) washiPinkBmp else washiYellowBmp
        tapeBmp?.let { tape ->
            val tw = 160f
            val th = 50f
            val tLeft = cx - tw / 2f
            val tTop = top - th / 2f + 4f
            val src = Rect(0, 0, tape.width, tape.height)
            val dst = Rect(tLeft.toInt(), tTop.toInt(), (tLeft + tw).toInt(), (tTop + th).toInt())
            canvas.drawBitmap(tape, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }

        canvas.restore()
    }

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
     * Saves bitmap to MediaStore Pictures gallery.
     */
    suspend fun saveToGallery(bitmap: Bitmap, title: String = "unique_person_collage"): Uri? = withContext(Dispatchers.IO) {
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

        if (uri != null) {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        }
        uri
    }

    /**
     * Creates an ACTION_SEND share intent with FileProvider for the collage.
     */
    fun createShareIntent(bitmap: Bitmap): Intent {
        val cachePath = File(context.cacheDir, "images").apply { mkdirs() }
        val file = File(cachePath, "collage_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }

        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
