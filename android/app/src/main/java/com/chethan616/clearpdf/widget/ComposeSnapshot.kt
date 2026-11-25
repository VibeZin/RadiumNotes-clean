package com.chethan616.clearpdf.widget

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Draws real Compose UI (the desk's own cards, liquid glass and all) into a bitmap for a home screen
 * widget. The composable runs in a window on a private virtual display, so it gets the same
 * hardware renderer, shaders and backdrop layers as on the desk; the display's frames come back
 * through an [ImageReader]. Main thread only.
 */
internal object ComposeSnapshot {
    /** Lifecycle, saved state and view models for a ComposeView with no activity around it. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        fun start() {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    /**
     * Renders [content] at [w] × [h] pixels and calls [done] with the frame, its outside cut to a
     * rounded rectangle of [cornerPx] (the card's own corners), or null if it could not be drawn.
     * [settleMs] lets pictures load and the glass draw its first settled frame.
     */
    fun render(
        ctx: Context,
        w: Int,
        h: Int,
        cornerPx: Float,
        settleMs: Long = 200,
        /** The part of the frame to keep (the card's own bounds), read when the frame is taken; null keeps it all. */
        crop: () -> android.graphics.Rect? = { null },
        content: @Composable () -> Unit,
        done: (Bitmap?) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var presentation: Presentation? = null
        val owner = Owner()
        var latest: Bitmap? = null
        var frames = 0
        var firstAt = 0L
        var finished = false
        fun cleanUp() {
            runCatching { presentation?.dismiss() }
            runCatching { display?.release() }
            runCatching { reader?.close() }
            runCatching { owner.stop() }
        }
        try {
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            reader = r
            r.setOnImageAvailableListener({ ir ->
                if (finished) return@setOnImageAvailableListener
                val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val plane = img.planes[0]
                    val rowPx = plane.rowStride / plane.pixelStride
                    val full = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(plane.buffer)
                    latest?.recycle()
                    latest = if (rowPx == w) full else Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() }
                    if (frames++ == 0) firstAt = android.os.SystemClock.uptimeMillis()
                } finally {
                    img.close()
                }
            }, main)
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay("artefact-widget", w, h, ctx.resources.displayMetrics.densityDpi, r.surface, 0)
            display = vd
            val p = Presentation(ctx, vd.display)
            presentation = p
            owner.start()
            val view = ComposeView(p.context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setContent(content)
            }
            p.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            p.setContentView(view, ViewGroup.LayoutParams(w, h))
            p.window?.decorView?.let {
                it.setViewTreeLifecycleOwner(owner)
                it.setViewTreeSavedStateRegistryOwner(owner)
                it.setViewTreeViewModelStoreOwner(owner)
            }
            p.window?.setLayout(w, h)
            p.show()
        } catch (t: Throwable) {
            android.util.Log.w("ArtefactWidget", "could not draw the card", t)
            cleanUp()
            done(null)
            return
        }
        val started = android.os.SystemClock.uptimeMillis()
        val take = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.uptimeMillis()
                // wait for the first frame, then let pictures load and the glass draw its settled frame
                val ready = frames > 0 && now - firstAt >= settleMs
                if (!ready && now - started < 1500L) {
                    main.postDelayed(this, 40L)
                    return
                }
                finished = true
                val frame = latest
                if (frame == null) android.util.Log.w("ArtefactWidget", "no frame after ${now - started} ms ($w x $h)")
                latest = null
                cleanUp()
                val cut = crop()?.takeIf { it.width() > 0 && it.height() > 0 }
                val kept = if (frame != null && cut != null) {
                    val r = android.graphics.Rect(cut).apply { intersect(0, 0, frame.width, frame.height) }
                    Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height()).also { if (it !== frame) frame.recycle() }
                } else {
                    frame
                }
                done(kept?.let { clipCorners(it, cornerPx) })
            }
        }
        main.postDelayed(take, 40L)
    }

    /** The frame with everything outside the card's rounded rectangle made clear. */
    private fun clipCorners(src: Bitmap, r: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), r, r, paint)
        src.recycle()
        return out
    }
}
