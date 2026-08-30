package com.auralis.music.ui.lyrics.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.auralis.music.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

enum class LyricCardStyle {
    SOLID,
    BLUR,
    GRADIENT
}

object LyricCardRenderer {

    private const val CARD_SIZE = 1080

    /**
     * Renders a 1080x1080 high-definition lyric card bitmap.
     */
    fun renderCard(
        context: Context,
        trackTitle: String,
        artistName: String,
        lyricsText: String,
        artworkBitmap: Bitmap?,
        style: LyricCardStyle,
        backgroundColor: Int,
        textColor: Int,
        secondaryTextColor: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(CARD_SIZE, CARD_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cardRect = RectF(0f, 0f, CARD_SIZE.toFloat(), CARD_SIZE.toFloat())
        val cornerRadius = 64f

        // ── 1. BACKGROUND RENDERING ──
        when (style) {
            LyricCardStyle.SOLID -> {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = backgroundColor
                    this.style = Paint.Style.FILL
                }
                canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, bgPaint)
            }
            LyricCardStyle.BLUR -> {
                if (artworkBitmap != null && !artworkBitmap.isRecycled) {
                    val blurred = createBlurredBackground(artworkBitmap, CARD_SIZE, CARD_SIZE)
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                    
                    // Clip to rounded rect
                    val clipPath = Path().apply {
                        addRoundRect(cardRect, cornerRadius, cornerRadius, Path.Direction.CW)
                    }
                    canvas.save()
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(blurred, 0f, 0f, bgPaint)
                    
                    // Rich cinematic vertical vignette scrim for crisp contrast without washing out
                    val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(
                            0f, 0f, 0f, CARD_SIZE.toFloat(),
                            intArrayOf(
                                AndroidColor.argb(38, 0, 0, 0),
                                AndroidColor.argb(95, 0, 0, 0),
                                AndroidColor.argb(185, 0, 0, 0)
                            ),
                            floatArrayOf(0f, 0.45f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(cardRect, overlayPaint)
                    canvas.restore()
                    blurred.recycle()
                } else {
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = backgroundColor
                        this.style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, bgPaint)
                }
            }
            LyricCardStyle.GRADIENT -> {
                val clipPath = Path().apply {
                    addRoundRect(cardRect, cornerRadius, cornerRadius, Path.Direction.CW)
                }
                canvas.save()
                canvas.clipPath(clipPath)

                val hsv = FloatArray(3)
                AndroidColor.colorToHSV(backgroundColor, hsv)
                val hue = hsv[0]
                val sat = hsv[1].coerceAtLeast(0.70f)
                val valBri = hsv[2].coerceIn(0.40f, 0.90f)

                val topColor = AndroidColor.HSVToColor(floatArrayOf((hue + 14f) % 360f, sat, (valBri * 1.12f).coerceIn(0.50f, 0.95f)))
                val midColor = AndroidColor.HSVToColor(floatArrayOf(hue, sat, valBri))
                val botColor = AndroidColor.HSVToColor(floatArrayOf((hue - 16f + 360f) % 360f, (sat * 1.1f).coerceIn(0.75f, 1.0f), (valBri * 0.32f).coerceIn(0.12f, 0.35f)))

                val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, CARD_SIZE.toFloat(), CARD_SIZE.toFloat(),
                        intArrayOf(topColor, midColor, botColor),
                        floatArrayOf(0f, 0.45f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(cardRect, gradientPaint)
                canvas.restore()
            }
        }

        // Subtle inner card border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = AndroidColor.argb(35, 255, 255, 255)
        }
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)

        // ── 2. HEADER: ALBUM ART + SONG TITLE + ARTIST ──
        val margin = 72f
        val artSize = 160f
        val artRect = RectF(margin, margin, margin + artSize, margin + artSize)
        val artRadius = 24f

        if (artworkBitmap != null && !artworkBitmap.isRecycled) {
            val roundedArt = getRoundedCornerBitmap(artworkBitmap, artSize.toInt(), artRadius)
            canvas.drawBitmap(roundedArt, margin, margin, null)
            roundedArt.recycle()
        } else {
            val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.argb(80, 255, 255, 255)
            }
            canvas.drawRoundRect(artRect, artRadius, artRadius, placeholderPaint)
        }

        val textStartX = margin + artSize + 36f
        val textAvailableWidth = CARD_SIZE - textStartX - margin

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titleText = android.text.TextUtils.ellipsize(
            trackTitle,
            titlePaint,
            textAvailableWidth,
            android.text.TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(titleText, textStartX, margin + 64f, titlePaint)

        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secondaryTextColor
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val artistText = android.text.TextUtils.ellipsize(
            artistName,
            artistPaint,
            textAvailableWidth,
            android.text.TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(artistText, textStartX, margin + 118f, artistPaint)

        // ── 3. CENTER: LYRIC TEXT ──
        val lyricAreaTop = margin + artSize + 60f
        val lyricAreaBottom = CARD_SIZE - margin - 80f
        val lyricAreaHeight = lyricAreaBottom - lyricAreaTop
        val lyricWidth = (CARD_SIZE - (margin * 2)).toInt()

        val lineCount = lyricsText.lines().filter { it.isNotBlank() }.size
        val dynamicTextSize = when {
            lineCount <= 2 -> 64f
            lineCount <= 4 -> 52f
            lineCount <= 6 -> 42f
            else -> 36f
        }

        val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = dynamicTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val staticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(lyricsText, 0, lyricsText.length, lyricPaint, lyricWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(12f, 1.15f)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                lyricsText,
                lyricPaint,
                lyricWidth,
                Layout.Alignment.ALIGN_CENTER,
                1.15f,
                12f,
                false
            )
        }

        val layoutHeight = staticLayout.height
        val lyricStartY = lyricAreaTop + max(0f, (lyricAreaHeight - layoutHeight) / 2f)

        canvas.save()
        canvas.translate(margin, lyricStartY)
        staticLayout.draw(canvas)
        canvas.restore()

        // ── 4. FOOTER: AURALIS LOGO + BRANDING ──
        val brandY = CARD_SIZE - margin + 20f
        val logoSize = 44f

        try {
            val logoDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_notification)
            val logoBmp = logoDrawable?.toBitmap(logoSize.toInt(), logoSize.toInt(), Bitmap.Config.ARGB_8888)
            if (logoBmp != null) {
                val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    alpha = 210
                }
                canvas.drawBitmap(logoBmp, margin, brandY - logoSize + 4f, logoPaint)
                logoBmp.recycle()
            }
        } catch (_: Exception) {}

        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            alpha = 210
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Auralis", margin + logoSize + 16f, brandY, brandPaint)

        return bitmap
    }

    /**
     * Saves bitmap to device MediaStore Pictures/Auralis directory.
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, trackTitle: String): Uri? {
        val sanitizedTitle = trackTitle.replace(Regex("[^a-zA-Z0-9_]"), "_").take(30)
        val filename = "Auralis_${sanitizedTitle}_${System.currentTimeMillis()}.png"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Auralis")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    /**
     * Shares image bitmap to external apps via Android Share Sheet using FileProvider.
     */
    fun shareImage(context: Context, bitmap: Bitmap, trackTitle: String) {
        val cacheFolder = File(context.cacheDir, "shared_lyrics").apply { mkdirs() }
        val imageFile = File(cacheFolder, "lyric_card_${System.currentTimeMillis()}.png")

        FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_TEXT, "Lyrics from $trackTitle on Auralis 🎵")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Share Lyric Card").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, targetSize: Int, cornerRadius: Float): Bitmap {
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat())

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, srcRect, rect, paint)

        return output
    }

    private fun createBlurredBackground(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        // Downscale to 180x180 for ultra-fast StackBlur
        val downscaled = Bitmap.createScaledBitmap(src, 180, 180, true)
        val blurredSmall = fastBlur(downscaled, 22)
        downscaled.recycle()
        // Upscale back with bilinear smoothing
        val result = Bitmap.createScaledBitmap(blurredSmall, targetW, targetH, true)
        blurredSmall.recycle()
        return result
    }

    /**
     * Fast in-place StackBlur implementation without native dependencies.
     */
    private fun fastBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0
            gsum = 0
            rsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius

            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            bsum = 0
            gsum = 0
            rsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - kotlin.math.abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                p = x + vmin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val a = AndroidColor.alpha(color)
        val r = (AndroidColor.red(color) * (1f - factor)).toInt().coerceIn(0, 255)
        val g = (AndroidColor.green(color) * (1f - factor)).toInt().coerceIn(0, 255)
        val b = (AndroidColor.blue(color) * (1f - factor)).toInt().coerceIn(0, 255)
        return AndroidColor.argb(a, r, g, b)
    }

    private fun lightenColor(color: Int, factor: Float): Int {
        val a = AndroidColor.alpha(color)
        val r = (AndroidColor.red(color) + (255 - AndroidColor.red(color)) * factor).toInt().coerceIn(0, 255)
        val g = (AndroidColor.green(color) + (255 - AndroidColor.green(color)) * factor).toInt().coerceIn(0, 255)
        val b = (AndroidColor.blue(color) + (255 - AndroidColor.blue(color)) * factor).toInt().coerceIn(0, 255)
        return AndroidColor.argb(a, r, g, b)
    }
}
