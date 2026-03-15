package com.example.link_pi.miniapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.link_pi.MainActivity
import java.io.File

object ShortcutHelper {

    private const val EXTRA_MINIAPP_ID = "miniapp_id"
    private const val EXTRA_LAUNCH_PAGE = "launch_page"
    private const val ICON_SIZE = 192

    /** Request the launcher to pin a mini app shortcut to the home screen. */
    fun pinToHomeScreen(context: Context, appId: String, label: String, iconPath: String?) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_MINIAPP_ID, appId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val bitmap = loadIconBitmap(context, iconPath)
        val icon = IconCompat.createWithBitmap(bitmap)

        val shortcut = ShortcutInfoCompat.Builder(context, "miniapp_$appId")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(intent)
            .build()

        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /** Pin a built-in page (e.g. ssh_home) to the home screen. */
    fun pinBuiltInToHomeScreen(context: Context, pageRoute: String, label: String, iconPath: String?) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_LAUNCH_PAGE, pageRoute)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val bitmap = loadIconBitmap(context, iconPath)
        val icon = IconCompat.createWithBitmap(bitmap)

        val shortcut = ShortcutInfoCompat.Builder(context, "builtin_$pageRoute")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(intent)
            .build()

        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    fun getMiniAppIdFromIntent(intent: Intent?): String? {
        return intent?.getStringExtra(EXTRA_MINIAPP_ID)
    }

    fun getLaunchPageFromIntent(intent: Intent?): String? {
        return intent?.getStringExtra(EXTRA_LAUNCH_PAGE)
    }

    /**
     * Save a user-picked image as the shortcut icon.
     * Center-crops to a square, scales to [ICON_SIZE], saves to internal storage.
     * Returns the saved file path.
     */
    fun saveIconFromUri(context: Context, uri: Uri, id: String): String? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(input)
            input.close()
            if (original == null) return null

            val cropped = centerCropSquare(original)
            val scaled = Bitmap.createScaledBitmap(cropped, ICON_SIZE, ICON_SIZE, true)
            if (cropped != original) cropped.recycle()

            val dir = File(context.filesDir, "shortcut_icons").also { it.mkdirs() }
            val file = File(dir, "$id.png")
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            scaled.recycle()
            original.recycle()

            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /** Load icon bitmap from saved file path, or generate a default icon. */
    private fun loadIconBitmap(context: Context, iconPath: String?): Bitmap {
        if (iconPath != null) {
            val file = File(iconPath)
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(iconPath)
                if (bmp != null) return bmp
            }
        }
        return createDefaultIcon()
    }

    /** Center-crop a bitmap to a square from its center point outward. */
    private fun centerCropSquare(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2
        return Bitmap.createBitmap(source, x, y, size, size)
    }

    /** Fallback icon: simple colored rounded rect with "App" label. */
    private fun createDefaultIcon(): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6750A4.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, ICON_SIZE.toFloat(), ICON_SIZE.toFloat()), 40f, 40f, bgPaint
        )
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 64f
            textAlign = Paint.Align.CENTER
        }
        val textY = ICON_SIZE / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("App", ICON_SIZE / 2f, textY, textPaint)
        return bitmap
    }
}
