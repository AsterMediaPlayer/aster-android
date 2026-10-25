package com.astermediaplayer.astermediaplayer.ui.disc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.disc.DiscAudio
import com.astermediaplayer.astermediaplayer.data.disc.DiscBackground
import com.astermediaplayer.astermediaplayer.data.disc.DiscMenuDocument
import com.astermediaplayer.astermediaplayer.data.disc.DiscMenuRules
import com.astermediaplayer.astermediaplayer.data.disc.DiscSounds
import com.astermediaplayer.astermediaplayer.data.disc.parseUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Turns a menu's picture and sound references into URLs, accepting only what the format allows (§4.8). */
class DiscPictures(private val repo: AsterRepository, private val itemId: UUID) {
    private fun assetOrUrl(reference: String): String =
        if (reference.startsWith("asset:")) repo.discAssetUrl(reference.removePrefix("asset:").split('/')) else reference

    fun picture(reference: String?): String? = reference?.takeIf { DiscMenuRules.isSafeImage(it) }?.let(::assetOrUrl)

    fun sound(reference: String?): String? = reference?.takeIf { DiscMenuRules.isSafeAudio(it) }?.let(::assetOrUrl)

    fun themeSongUrl(id: UUID): String = repo.audioStreamUrl(id)

    /** The still picture of a background, if it has one. */
    fun background(background: DiscBackground?): String? = when (background?.source) {
        "jellyfin" -> repo.itemImageUrl(itemId, background.imageType?.takeIf { it.matches(Regex("[A-Za-z]{1,20}")) } ?: "Backdrop", maxOf(0, background.index ?: 0))
        "tmdb" -> background.tmdbFilePath?.takeIf { DiscMenuRules.isTmdbPath(it) }?.let { path ->
            val size = background.tmdbSize?.takeIf { it in setOf("w780", "w1280", "original") } ?: "w1280"
            "https://image.tmdb.org/t/p/$size$path"
        }
        "fanart" -> background.fanartId?.takeIf { DiscMenuRules.isFanartId(it) }?.let { repo.discFanartUrl(itemId, it) }
        "image" -> picture(background.image)
        else -> null
    }
}

/** A looping, cover-fitted local trailer behind the menu; it stays across screens that use the same trailer. */
@OptIn(UnstableApi::class)
class DiscTrailerPlayer(context: Context, val trailerId: UUID, url: String, val muted: Boolean) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = if (muted) 0f else 1f
        setMediaItem(MediaItem.fromUri(url))
        prepare()
    }
    /** True once the first frame has drawn, so the still picture can give way to it. */
    private val readyState = androidx.compose.runtime.mutableStateOf(false)
    val ready: Boolean get() = readyState.value

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { readyState.value = true }
        })
    }

    fun play() { player.play() }
    fun pause() { player.pause() }
    fun stop() { player.stop(); player.release() }
}

/**
 * Menu music and button sounds (§5.5): looping music with volume fades, and the click, chime and beep presets
 * synthesised as the reference renderer does, or sound files a menu ships with.
 */
@OptIn(UnstableApi::class)
class DiscMenuAudio(private val context: Context, private val pictures: DiscPictures) {
    enum class Sound { Move, Select, Back }

    var enabled = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var music: ExoPlayer? = null
    private var musicKey: String? = null
    private var musicVolume = 0.5f
    private var fade: Job? = null
    private var suspended = false

    private var sounds: DiscSounds? = null
    private var soundFiles = mapOf<Sound, String>()
    private val filePlayers = mutableMapOf<String, ExoPlayer>()
    private var lastMove = 0L
    private val tones = mutableMapOf<String, AudioTrack>()

    fun apply(audio: DiscAudio?, document: DiscMenuDocument) {
        if (!enabled) return
        sounds = audio?.sounds
        soundFiles = buildMap {
            audio?.sounds?.let { spec ->
                pictures.sound(spec.move)?.let { put(Sound.Move, it) }
                pictures.sound(spec.select)?.let { put(Sound.Select, it) }
                pictures.sound(spec.back)?.let { put(Sound.Back, it) }
            }
        }
        soundFiles.values.forEach(::preload)

        val url: String? = audio?.music?.let { m ->
            when (m.source) {
                "file" -> pictures.sound(m.file)
                "themeSong" -> document.themeSongs?.firstOrNull()?.let(::parseUuid)?.let { pictures.themeSongUrl(it) }
                else -> null
            }
        }
        setMusic(url, DiscMenuRules.clamp(audio?.music?.volume, 0.0..1.0, 0.5).toFloat())
    }

    private fun setMusic(url: String?, volume: Float) {
        if (url == musicKey) {
            // The same track: just a fade to the new volume.
            music?.let { if (musicVolume != volume) { musicVolume = volume; fadeTo(it, volume, 0.4) } }
            return
        }
        music?.let { old -> fadeTo(old, 0f, 0.6) { old.release() } }
        musicKey = url
        musicVolume = volume
        music = null
        if (url == null) return
        val player = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            repeatMode = Player.REPEAT_MODE_ALL
            this.volume = 0f
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
        music = player
        if (!suspended) { player.play(); fadeTo(player, volume, 0.8) }
    }

    private fun fadeTo(player: ExoPlayer, target: Float, seconds: Double, then: (() -> Unit)? = null) {
        fade?.cancel()
        fade = scope.launch {
            val start = player.volume
            val steps = maxOf(1, (seconds * 30).toInt())
            for (step in 1..steps) {
                delay((seconds * 1000 / steps).toLong())
                player.volume = start + (target - start) * step / steps
            }
            player.volume = target
            then?.invoke()
        }
    }

    fun suspend() {
        suspended = true
        music?.let { p -> fadeTo(p, 0f, 0.4) { p.pause() } }
    }

    fun resume() {
        suspended = false
        music?.let { p -> p.play(); fadeTo(p, musicVolume, 0.8) }
    }

    fun stop() {
        fade?.cancel()
        music?.release()
        music = null
        musicKey = null
    }

    fun release() {
        stop()
        filePlayers.values.forEach { it.release() }
        filePlayers.clear()
        tones.values.forEach { it.release() }
        tones.clear()
    }

    fun play(kind: Sound) {
        val spec = sounds
        if (!enabled || spec == null) return
        if (kind == Sound.Move) {
            val now = System.currentTimeMillis()
            if (now - lastMove < 45) return
            lastMove = now
        }
        val volume = DiscMenuRules.clamp(spec.volume, 0.0..1.0, 0.5).toFloat()
        val file = soundFiles[kind]?.let { filePlayers[it] }
        if (file != null) { file.volume = volume; file.seekTo(0); file.play(); return }
        playTone(spec.preset ?: "none", kind, volume)
    }

    private fun preload(url: String) {
        if (filePlayers.containsKey(url)) return
        filePlayers[url] = ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare() }
    }

    // --- Tone synthesis ---

    private data class Tone(val frequency: Double, val square: Boolean, val duration: Double, val gain: Double, val start: Double = 0.0)

    private fun tonesFor(preset: String, kind: Sound): List<Tone> = when (preset to kind) {
        "click" to Sound.Move -> listOf(Tone(1500.0, true, 0.025, 0.5))
        "click" to Sound.Select -> listOf(Tone(1100.0, true, 0.04, 0.6), Tone(700.0, true, 0.05, 0.5, 0.04))
        "click" to Sound.Back -> listOf(Tone(600.0, true, 0.05, 0.6))
        "chime" to Sound.Move -> listOf(Tone(1320.0, false, 0.12, 0.4))
        "chime" to Sound.Select -> listOf(Tone(880.0, false, 0.2, 0.6), Tone(1320.0, false, 0.3, 0.5, 0.08))
        "chime" to Sound.Back -> listOf(Tone(660.0, false, 0.2, 0.5), Tone(440.0, false, 0.25, 0.5, 0.08))
        "beep" to Sound.Move -> listOf(Tone(880.0, false, 0.04, 0.5))
        "beep" to Sound.Select -> listOf(Tone(1040.0, false, 0.09, 0.6))
        "beep" to Sound.Back -> listOf(Tone(520.0, false, 0.09, 0.6))
        else -> emptyList()
    }

    private fun playTone(preset: String, kind: Sound, volume: Float) {
        val list = tonesFor(preset, kind)
        if (list.isEmpty()) return
        val key = "$preset-$kind-$volume"
        val track = tones.getOrPut(key) { render(list, volume.toDouble()) ?: return }
        runCatching { track.stop(); track.reloadStaticData(); track.play() }
    }

    /** Each tone ramps exponentially from 0.0001 to max(0.0002, volume × gain × 0.3) over its first 5 ms, then back down by its end. */
    private fun render(list: List<Tone>, volume: Double): AudioTrack? {
        val rate = 44_100
        val total = list.maxOf { it.start + it.duration }
        val frames = maxOf(1, (total * rate).toInt() + 64)
        val buffer = DoubleArray(frames)
        for (tone in list) {
            val peak = maxOf(0.0002, volume * tone.gain * 0.3)
            val startFrame = (tone.start * rate).toInt()
            val count = (tone.duration * rate).toInt()
            val attack = maxOf(1, (0.005 * rate).toInt())
            for (i in 0 until count) {
                val envelope = if (i < attack) 0.0001 * (peak / 0.0001).pow(i.toDouble() / attack)
                else peak * (0.0001 / peak).pow((i - attack).toDouble() / maxOf(1, count - attack))
                val phase = sin(2 * PI * tone.frequency * i / rate)
                val sample = if (tone.square) (if (phase >= 0) 1.0 else -1.0) else phase
                val index = startFrame + i
                if (index < frames) buffer[index] += sample * envelope
            }
        }
        val pcm = ShortArray(frames) { (buffer[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }
        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build().also { it.write(pcm, 0, pcm.size) }
        }.getOrNull()
    }
}
