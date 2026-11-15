package com.auralis.music.ui.lyrics.share

import com.auralis.music.ui.i18n.str

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.auralis.music.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the shareable lyric card: blurred artwork behind left-aligned lines, the Auralis mark top-right
 * and the song (art, title, artist) bottom-left. The share panel previews this exact bitmap, so what
 * the user sees is what gets saved or shared.
 */
object LyricCardRenderer {

    private const val CARD_WIDTH = 1080

    /** One design unit; the card is laid out as a 360-unit-wide card, like a phone-width preview. */
    private const val U = CARD_WIDTH / 360f

    /** Blurs the artwork once per track so re-rendering on every line toggle stays cheap. */
    fun prepareBackground(artwork: Bitmap): Bitmap {
        val downscaled = Bitmap.createScaledBitmap(artwork, 160, 160, true)
        val saturated = Bitmap.createBitmap(downscaled.width, downscaled.height, Bitmap.Config.ARGB_8888)
        Canvas(saturated).drawBitmap(
            downscaled, 0f, 0f,
            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.2f) })
            }
        )
        if (downscaled !== artwork) downscaled.recycle()
        val blurred = fastBlur(saturated, 14)
        saturated.recycle()
        return blurred
    }

    fun renderCard(
        context: Context,
        trackTitle: String,
        artistName: String,
        lines: List<String>,
        artworkBitmap: Bitmap?,
        blurredBackground: Bitmap?,
        lyricTypeface: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
        titleTypeface: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
        bodyTypeface: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    ): Bitmap {
        val padH = 18 * U
        val padTop = 18 * U
        val padBottom = 18 * U
        val brandHeight = 22 * U
        val gapBrandToLyrics = 16 * U
        val gapLyricsToFooter = 20 * U
        val footerHeight = 44 * U
        val contentWidth = (CARD_WIDTH - 2 * padH).toInt()

        val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 16.5f * U
            typeface = lyricTypeface
            setShadowLayer(4 * U, 0f, U, AndroidColor.argb(70, 0, 0, 0))
        }
        val lyricText = lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        val lyricLayout = buildLayout(lyricText, lyricPaint, contentWidth)

        val height = (padTop + brandHeight + gapBrandToLyrics + lyricLayout.height +
            gapLyricsToFooter + footerHeight + padBottom).toInt()
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cardRect = RectF(0f, 0f, CARD_WIDTH.toFloat(), height.toFloat())
        val corner = 22 * U

        // ── Background ──
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(cardRect, corner, corner, Path.Direction.CW) })
        if (blurredBackground != null && !blurredBackground.isRecycled) {
            canvas.drawBitmap(
                blurredBackground,
                centerCropSource(blurredBackground.width, blurredBackground.height, cardRect),
                cardRect,
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        } else {
            canvas.drawRect(cardRect, Paint().apply {
                shader = LinearGradient(
                    0f, 0f, CARD_WIDTH.toFloat(), height.toFloat(),
                    AndroidColor.rgb(74, 52, 60), AndroidColor.rgb(28, 22, 26), Shader.TileMode.CLAMP
                )
            })
        }
        // Even frosting so white text reads on any artwork, a touch deeper behind the footer.
        canvas.drawRect(cardRect, Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(AndroidColor.argb(70, 0, 0, 0), AndroidColor.argb(85, 0, 0, 0), AndroidColor.argb(120, 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        })
        canvas.restore()
        canvas.drawRoundRect(
            RectF(U / 2, U / 2, CARD_WIDTH - U / 2, height - U / 2), corner, corner,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = U
                color = AndroidColor.argb(46, 255, 255, 255)
            }
        )

        // ── Brand mark, top-right ──
        val logoDiameter = brandHeight
        val logoLeft = CARD_WIDTH - padH - logoDiameter
        val logoCx = logoLeft + logoDiameter / 2f
        val logoCy = padTop + logoDiameter / 2f
        canvas.drawCircle(logoCx, logoCy, logoDiameter / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(64, 255, 255, 255)
        })
        try {
            val glyph = (logoDiameter * 0.58f).toInt()
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_notification)
                ?.toBitmap(glyph, glyph, Bitmap.Config.ARGB_8888)
                ?.let {
                    canvas.drawBitmap(it, logoCx - glyph / 2f, logoCy - glyph / 2f, Paint(Paint.ANTI_ALIAS_FLAG))
                    it.recycle()
                }
        } catch (_: Exception) {}
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 11.5f * U
            typeface = titleTypeface
            letterSpacing = 0.12f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("AURALIS", logoLeft - 8 * U, baselineCentered(brandPaint, logoCy), brandPaint)

        // ── Lyrics ──
        val lyricsTop = padTop + brandHeight + gapBrandToLyrics
        canvas.save()
        canvas.translate(padH, lyricsTop)
        lyricLayout.draw(canvas)
        canvas.restore()

        // ── Footer: art + title + artist ──
        val footerTop = lyricsTop + lyricLayout.height + gapLyricsToFooter
        val artSize = footerHeight
        val artRect = RectF(padH, footerTop, padH + artSize, footerTop + artSize)
        if (artworkBitmap != null && !artworkBitmap.isRecycled) {
            val rounded = roundedSquare(artworkBitmap, artSize.toInt(), 7 * U)
            canvas.drawBitmap(rounded, artRect.left, artRect.top, null)
            rounded.recycle()
        } else {
            canvas.drawRoundRect(artRect, 7 * U, 7 * U, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.argb(70, 255, 255, 255)
            })
        }
        val textLeft = artRect.right + 12 * U
        val textWidth = CARD_WIDTH - padH - textLeft
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 14.5f * U
            typeface = titleTypeface
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(196, 255, 255, 255)
            textSize = 12.5f * U
            typeface = bodyTypeface
        }
        canvas.drawText(
            TextUtils.ellipsize(trackTitle, titlePaint, textWidth, TextUtils.TruncateAt.END).toString(),
            textLeft, baselineCentered(titlePaint, footerTop + artSize * 0.30f), titlePaint
        )
        canvas.drawText(
            TextUtils.ellipsize(artistName, artistPaint, textWidth, TextUtils.TruncateAt.END).toString(),
            textLeft, baselineCentered(artistPaint, footerTop + artSize * 0.74f), artistPaint
        )

        return bitmap
    }

    /** Saves the card to Pictures/Auralis. */
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

        return try {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Hands the card to the system share sheet through the app's FileProvider. */
    fun shareImage(context: Context, bitmap: Bitmap, trackTitle: String, artistName: String) {
        val cacheFolder = File(context.cacheDir, "shared_lyrics").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
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
            putExtra(Intent.EXTRA_TEXT, "$trackTitle — $artistName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, str(R.string.share_lyric_card)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.28f)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.28f, 0f, false)
        }

    private fun baselineCentered(paint: Paint, centerY: Float): Float {
        val fm = paint.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    private fun centerCropSource(srcW: Int, srcH: Int, dst: RectF): Rect {
        val dstRatio = dst.width() / dst.height()
        val srcRatio = srcW.toFloat() / srcH
        return if (srcRatio > dstRatio) {
            val w = (srcH * dstRatio).toInt()
            val left = (srcW - w) / 2
            Rect(left, 0, left + w, srcH)
        } else {
            val h = (srcW / dstRatio).toInt()
            val top = (srcH - h) / 2
            Rect(0, top, srcW, top + h)
        }
    }

    private fun roundedSquare(bitmap: Bitmap, size: Int, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, centerCropSource(bitmap.width, bitmap.height, rect), rect, paint)
        return output
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
                pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

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
}
