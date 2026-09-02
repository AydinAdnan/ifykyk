package com.iykyk.assignment.domain.pipeline

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.iykyk.assignment.domain.model.PersonCluster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CollageCanvasRenderer(private val context: Context) {

    /**
     * Renders a 1080x1920 Instagram-Story formatted scrapbook poster bitmap.
     */
    suspend fun renderCollageBitmap(
        clusters: List<PersonCluster>,
        videoName: String = "VIDEO COLLAGE"
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Warm cream paper background
        canvas.drawColor(Color.parseColor("#F7F4EB"))

        // Subtle background dotted grid
        val dotPaint = Paint().apply {
            color = Color.parseColor("#E0DACB")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val gridStep = 40f
        var gx = 20f
        while (gx < width) {
            var gy = 20f
            while (gy < height) {
                canvas.drawCircle(gx, gy, 2f, dotPaint)
                gy += gridStep
            }
            gx += gridStep
        }

        // 2. Header Banner Note (Tilted Yellow Card)
        drawHeaderBanner(canvas, 70f, 100f, 940f, 180f, "UNIQUE PERSON COLLAGE")

        // 3. Subtitle Note
        val subPaint = Paint().apply {
            color = Color.parseColor("#18181B")
            textSize = 28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val totalAppearances = clusters.sumOf { it.appearanceCount }
        canvas.drawText("${clusters.size} UNIQUE PEOPLE  •  $totalAppearances APPEARANCES", width / 2f, 330f, subPaint)

        // 4. Grid of Stamp Face Cards
        val n = clusters.size
        if (n > 0) {
            val cols = if (n <= 4) 2 else 3
            val rows = (n + cols - 1) / cols
            val startY = 380f
            val availableHeight = 1350f
            val cellWidth = (width - 120f) / cols
            val cellHeight = min(cellWidth * 1.25f, availableHeight / rows)

            clusters.forEachIndexed { index, cluster ->
                val col = index % cols
                val row = index / cols
                val left = 60f + col * cellWidth + 12f
                val top = startY + row * cellHeight + 12f
                val cardW = cellWidth - 24f
                val cardH = cellHeight - 24f
                val rotation = if (index % 2 == 0) -1.8f else 1.8f

                drawStampCard(
                    canvas = canvas,
                    left = left,
                    top = top,
                    cardWidth = cardW,
                    cardHeight = cardH,
                    rotation = rotation,
                    faceBitmap = cluster.representativeShot.generousCropBitmap ?: cluster.representativeShot.alignedCropBitmap,
                    label = cluster.personLabel,
                    badgeText = "${cluster.appearanceCount} SHOTS"
                )
            }
        }

        // 5. Footer Branding Note
        val footerPaint = Paint().apply {
            color = Color.parseColor("#71717A")
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("IYKYK  //  ON-DEVICE VIDEO ANALYSIS", width / 2f, 1840f, footerPaint)

        bitmap
    }

    private fun drawHeaderBanner(
        canvas: Canvas,
        left: Float,
        top: Float,
        cardW: Float,
        cardH: Float,
        title: String
    ) {
        canvas.save()
        canvas.rotate(-1.2f, left + cardW / 2f, top + cardH / 2f)

        // Black shadow
        val shadowPaint = Paint().apply {
            color = Color.parseColor("#1E1E1E")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(RectF(left + 8f, top + 8f, left + cardW + 8f, top + cardH + 8f), 16f, 16f, shadowPaint)

        // Yellow card
        val cardPaint = Paint().apply {
            color = Color.parseColor("#FFE66D")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val borderPaint = Paint().apply {
            color = Color.parseColor("#1E1E1E")
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        val rect = RectF(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(rect, 16f, 16f, cardPaint)
        canvas.drawRoundRect(rect, 16f, 16f, borderPaint)

        // Title text
        val textPaint = Paint().apply {
            color = Color.parseColor("#18181B")
            textSize = 48f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(title, left + cardW / 2f, top + cardH / 2f + 16f, textPaint)

        canvas.restore()
    }

    private fun drawStampCard(
        canvas: Canvas,
        left: Float,
        top: Float,
        cardWidth: Float,
        cardHeight: Float,
        rotation: Float,
        faceBitmap: Bitmap?,
        label: String,
        badgeText: String
    ) {
        canvas.save()
        canvas.rotate(rotation, left + cardWidth / 2f, top + cardHeight / 2f)

        val stampPath = createStampPath(left, top, cardWidth, cardHeight, notchR = 7f, notchSpacing = 16f)

        // Hard black drop shadow
        val shadowOffsetMatrix = Matrix().apply { postTranslate(6f, 6f) }
        val shadowPath = Path(stampPath).apply { transform(shadowOffsetMatrix) }
        val shadowPaint = Paint().apply {
            color = Color.parseColor("#1E1E1E")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawPath(shadowPath, shadowPaint)

        // White stamp body
        val bodyPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val borderPaint = Paint().apply {
            color = Color.parseColor("#1E1E1E")
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        canvas.drawPath(stampPath, bodyPaint)
        canvas.drawPath(stampPath, borderPaint)

        // Inner photo rectangle
        val pad = 12f
        val photoLeft = left + pad
        val photoTop = top + pad
        val photoW = cardWidth - (pad * 2f)
        val photoH = photoW // Square photo
        val photoRect = RectF(photoLeft, photoTop, photoLeft + photoW, photoTop + photoH)

        if (faceBitmap != null) {
            canvas.save()
            canvas.clipRect(photoRect)
            val src = Rect(0, 0, faceBitmap.width, faceBitmap.height)
            val dst = Rect(photoLeft.toInt(), photoTop.toInt(), (photoLeft + photoW).toInt(), (photoTop + photoH).toInt())
            canvas.drawBitmap(faceBitmap, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            val placeholderPaint = Paint().apply {
                color = Color.parseColor("#E2E8F0")
                style = Paint.Style.FILL
            }
            canvas.drawRect(photoRect, placeholderPaint)
        }

        // Inner photo border
        val innerBorder = Paint().apply {
            color = Color.parseColor("#3318181B")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawRect(photoRect, innerBorder)

        // Badge in top right corner of photo
        val badgeW = 75f
        val badgeH = 26f
        val badgeL = photoLeft + photoW - badgeW - 6f
        val badgeT = photoTop + 6f
        val badgeBg = Paint().apply {
            color = Color.parseColor("#FFE66D")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val badgeBorder = Paint().apply {
            color = Color.parseColor("#1E1E1E")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawRoundRect(RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH), 4f, 4f, badgeBg)
        canvas.drawRoundRect(RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH), 4f, 4f, badgeBorder)

        val badgeTextPaint = Paint().apply {
            color = Color.parseColor("#18181B")
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(badgeText, badgeL + badgeW / 2f, badgeT + 18f, badgeTextPaint)

        // Label under photo
        val labelPaint = Paint().apply {
            color = Color.parseColor("#18181B")
            textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(label, left + cardWidth / 2f, photoTop + photoH + 26f, labelPaint)

        canvas.restore()
    }

    private fun createStampPath(left: Float, top: Float, width: Float, height: Float, notchR: Float, notchSpacing: Float): Path {
        val base = Path().apply {
            addRect(RectF(left, top, left + width, top + height), Path.Direction.CW)
        }
        val cutouts = Path()

        val hCount = ((width - 2 * notchSpacing) / notchSpacing).roundToInt().coerceAtLeast(1)
        val hStep = width / (hCount + 1)
        for (i in 1..hCount) {
            val cx = left + i * hStep
            cutouts.addCircle(cx, top, notchR, Path.Direction.CW)
            cutouts.addCircle(cx, top + height, notchR, Path.Direction.CW)
        }

        val vCount = ((height - 2 * notchSpacing) / notchSpacing).roundToInt().coerceAtLeast(1)
        val vStep = height / (vCount + 1)
        for (i in 1..vCount) {
            val cy = top + i * vStep
            cutouts.addCircle(left, cy, notchR, Path.Direction.CW)
            cutouts.addCircle(left + width, cy, notchR, Path.Direction.CW)
        }

        val finalPath = Path()
        finalPath.op(base, cutouts, Path.Op.DIFFERENCE)
        return finalPath
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
        val stream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.close()

        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
