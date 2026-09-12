package com.envi.wispr.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.envi.wispr.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class WelcomeScene(val video: Int, val poster: String, val title: String, val copy: String)
private val welcomeScenes = listOf(
    WelcomeScene(R.raw.onboarding_on_device, "on_device.png", "On your device. On your terms.", "Your voice stays with you."),
    WelcomeScene(R.raw.onboarding_offline, "offline.png", "Offline. Still ready.", "No Wi-Fi? Your words still flow."),
    WelcomeScene(R.raw.onboarding_polished, "polished.png", "Say it. Send it.", "Keep moving. We’ll tidy your words."),
    WelcomeScene(R.raw.onboarding_free, "free.png", "Free. Private. Yours.", "No account. No subscription."),
)

@Composable
internal fun OnboardingWelcomeStory(surface: Color, foreground: Color, muted: Color, accent: Color) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val reduced = onboardingReducedMotion()
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(reduced) }
    var host by remember { mutableStateOf<WelcomeVideoHost?>(null) }
    var foregrounded by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val bitmap by produceState<Bitmap?>(null, index) {
        value = withContext(Dispatchers.IO) { runCatching { context.assets.open("onboarding/${welcomeScenes[index].poster}").use(BitmapFactory::decodeStream) }.getOrNull() }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> foregrounded = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); host?.release() }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            bitmap?.let { Image(it.asImageBitmap(), welcomeScenes[index].title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            AndroidView(factory = { WelcomeVideoHost(it, !reduced) { scene -> index = scene }.also { host = it } },
                update = { it.setPlaying(foregrounded && !paused) }, modifier = Modifier.fillMaxSize())
        }
        Text(welcomeScenes[index].title, color = foreground, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp), fontSize = 16.sp)
        Text(welcomeScenes[index].copy, color = muted, textAlign = TextAlign.Center, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            welcomeScenes.indices.forEach { scene ->
                TextButton(onClick = { index = scene; host?.select(scene) }, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(34.dp)) {
                    Text(if (index == scene) "●" else "○", color = accent)
                }
            }
            TextButton(onClick = { paused = !paused }) { Text(if (paused) "Play" else "Pause", color = accent, fontSize = 12.sp) }
        }
    }
}

/** Two textures retain the outgoing frame until the next clip has actually rendered. */
private class WelcomeVideoHost(context: Context, private val animate: Boolean, private val onScene: (Int) -> Unit) : FrameLayout(context) {
    private data class Clip(val view: TextureView, var player: MediaPlayer? = null, var surface: Surface? = null, var prepared: Boolean = false)
    private var current: Clip? = null
    private var outgoing: Clip? = null
    private var index = 0
    private var playing = false
    private var ended = false
    private var released = false
    private var failed = false

    fun setPlaying(value: Boolean) {
        if (failed) return
        playing = value
        if (!value) { current?.player?.let { runCatching { it.pause() } }; return }
        if (current == null || ended) select(if (ended) (index + 1) % welcomeScenes.size else index)
        else if (current?.prepared == true) runCatching { current?.player?.start() }
    }

    fun select(scene: Int) {
        if (released) return
        failed = false
        index = scene
        onScene(scene)
        ended = false
        releaseClip(outgoing)
        outgoing = current
        outgoing?.player?.let { runCatching { it.pause() } }
        val clip = Clip(TextureView(context).apply { alpha = 0f })
        current = clip
        addView(clip.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clip.view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                if (released || current !== clip) return
                clip.prepared = false
                clip.surface = Surface(texture)
                val player = MediaPlayer()
                clip.player = player
                player.setSurface(clip.surface)
                player.setVolume(0f, 0f)
                player.setOnPreparedListener {
                    clip.prepared = true
                    if (playing && current === clip && !released) it.start()
                    else it.seekTo(1)
                }
                player.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && current === clip && !released) {
                        clip.view.animate().alpha(1f).setDuration(if (animate) 650 else 0).withEndAction {
                            if (current === clip) { releaseClip(outgoing); outgoing = null }
                        }.start()
                    }
                    false
                }
                player.setOnCompletionListener { if (current === clip && !released) { ended = true; if (playing) select((index + 1) % welcomeScenes.size) } }
                player.setOnErrorListener { _, _, _ ->
                    if (current === clip) { releaseClip(clip); releaseClip(outgoing); outgoing = null; current = null; playing = false; failed = true }
                    true
                }
                runCatching { player.setDataSource(context, Uri.parse("android.resource://${context.packageName}/${welcomeScenes[scene].video}")); player.prepareAsync() }
                    .onFailure { releaseClip(clip); releaseClip(outgoing); outgoing = null; current = null; playing = false; failed = true }
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { clip.prepared = false; clip.player?.let { runCatching { it.release() } }; clip.player = null; clip.surface?.release(); clip.surface = null; return true }
        }
    }

    private fun releaseClip(clip: Clip?) {
        if (clip == null) return
        clip.prepared = false
        clip.view.animate().cancel()
        clip.player?.let { runCatching { it.release() } }
        clip.player = null
        clip.surface?.release(); clip.surface = null
        removeView(clip.view)
    }

    fun release() { released = true; releaseClip(current); releaseClip(outgoing); current = null; outgoing = null }
}
