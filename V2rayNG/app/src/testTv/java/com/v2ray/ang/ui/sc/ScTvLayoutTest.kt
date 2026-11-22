package com.v2ray.ang.ui.sc

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AnimationDrawable
import android.media.MediaPlayer
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.v2ray.ang.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScTvLayoutTest {

    @Test
    fun overscanTracksBothViewportDimensions() = withLayout { root, page, _ ->
        for ((width, height) in listOf(1920 to 1080, 1280 to 720)) {
            layout(root, width, height)
            assertEquals(width / 20, page.paddingLeft)
            assertEquals(width / 20, page.paddingRight)
            assertEquals(height / 20, page.paddingTop)
            assertEquals(height / 20, page.paddingBottom)
        }
    }

    @Test
    fun allNativeTvButtonsCanReceiveFocusAndShowTheFocusBorder() = withLayout { root, page, main ->
        val wolfRow = children(page).filterIsInstance<FrameLayout>().single()
        val buttons = (children(page) + children(main) + children(wolfRow)).filterIsInstance<Button>()
        assertEquals(6, buttons.size)
        buttons.forEach { it.visibility = View.VISIBLE }
        layout(root, 1280, 720)

        for (button in buttons) {
            assertTrue(button.isFocusable)
            assertTrue(button.isFocusableInTouchMode)
            assertTrue(button.height >= 64)
            assertTrue(button.requestFocus())
            assertTrue(button.hasFocus())
            val bitmap = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
            button.background.setBounds(0, 0, button.width, button.height)
            button.background.draw(Canvas(bitmap))
            assertEquals(Color.parseColor("#A3F1FF"), bitmap.getPixel(button.width / 2, 2))
        }
    }

    @Test
    fun wolfStaysInItsCircularFrameAndMuteKeepsTheOriginalAnimation() = withLayout { root, page, _ ->
        layout(root, 1280, 720)
        val row = children(page).filterIsInstance<FrameLayout>().single()
        val wolf = children(row).filterIsInstance<ImageView>().single()
        val sound = children(row).filterIsInstance<Button>().single()
        assertEquals(wolf.width, wolf.height)
        assertTrue(wolf.clipToOutline)
        assertTrue(wolf.paddingLeft > 2)
        assertTrue(wolf.isFocusable)
        assertTrue(wolf.requestFocus())
        val magic = wolf.resources.openRawResource(R.raw.sc_tv_wolf_howl).use { it.readNBytes(4) }
        assertEquals("OggS", magic.decodeToString())

        saveWolfPreview(wolf, "idle")
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(4900, 0) }
        assertTrue(wolf.performClick())
        val player = ReflectionHelpers.getField<MediaPlayer>(root.context, "wolfAudio")
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(player).state)
        assertTrue(sound.performClick())
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
        assertTrue(wolf.performClick())
        val motion = wolf.drawable as AnimationDrawable
        assertEquals(9, motion.numberOfFrames)
        assertEquals(4900, (0 until motion.numberOfFrames).sumOf(motion::getDuration))
        assertEquals(null, ReflectionHelpers.getField<Any?>(root.context, "wolfAudio"))
        motion.selectDrawable(4)
        wolf.invalidate()
        saveWolfPreview(wolf, "howl")
    }

    private fun saveWolfPreview(wolf: ImageView, frame: String) {
        val directory = System.getenv("SC_WOLF_PREVIEW_DIR") ?: return
        val bitmap = Bitmap.createBitmap(wolf.width, wolf.height, Bitmap.Config.ARGB_8888)
        wolf.draw(Canvas(bitmap))
        File(directory, "wolf-tv-$frame.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun serverNameWrapsWithoutEllipsisOrClipping() = withLayout { root, _, main ->
        val location = children(main).filterIsInstance<Button>()[1]
        val names = listOf(
            "Сервер: Ледокол · Быстрый",
            "Сервер: Ледокол · Резерв",
            "Сервер: Ледокол · Резерв · Финляндия · Альтернативный транспорт ".repeat(3)
        )
        for (name in names) {
            location.text = name
            layout(root, 1280, 720)
            val textLayout = location.layout
            val lastLine = textLayout.lineCount - 1
            assertEquals(name.length, textLayout.getLineEnd(lastLine))
            assertTrue((0..lastLine).all { textLayout.getEllipsisCount(it) == 0 })
            val requiredHeight = textLayout.height + location.compoundPaddingTop + location.compoundPaddingBottom
            assertTrue("Text requires ${requiredHeight}px, button has ${location.height}px for ${name.length} characters", requiredHeight <= location.height)
            if (name == names.last()) assertTrue(textLayout.lineCount > 1)
        }
    }

    private fun withLayout(check: (ScrollView, LinearLayout, LinearLayout) -> Unit) {
        // Build the real view hierarchy without starting account refresh, pairing or VPN services.
        val activity = Robolectric.buildActivity(ScTvActivity::class.java).get()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "buildLayout")
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as ScrollView
        val page = root.getChildAt(0) as LinearLayout
        val main = children(page).filterIsInstance<LinearLayout>().last()
        main.visibility = View.VISIBLE
        check(root, page, main)
    }

    private fun children(group: ViewGroup) = (0 until group.childCount).map(group::getChildAt)

    private fun layout(view: View, width: Int, height: Int) {
        // The first layout computes safe-area padding and requests another layout.
        repeat(2) {
            view.forceLayout()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, width, height)
        }
    }
}
