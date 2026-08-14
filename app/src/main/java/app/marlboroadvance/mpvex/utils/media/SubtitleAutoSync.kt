package app.marlboroadvance.mpvex.utils.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import `is`.xyz.mpv.MPVLib
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Automatic subtitle synchronization engine ("listen to the voice").
 *
 * How it works:
 *  1. Decodes a window of the video's audio track with MediaExtractor/MediaCodec.
 *  2. Runs a lightweight voice-activity detector: the audio is band-passed to the
 *     human speech band (300-3400 Hz) and an RMS energy envelope is computed in
 *     50 ms bins.
 *  3. Parses the external subtitle file (.srt/.ass/.ssa/.vtt) into an
 *     "a subtitle is on screen" activity timeline.
 *  4. Cross-correlates the two signals over a +/-20 s search range: the delay at
 *     which speech energy best coincides with subtitle activity is the offset
 *     the subtitles are off by.
 *  5. The caller applies the negated offset as mpv's `sub-delay`.
 *
 * Same approach as the desktop tool ffsubsync, implemented with pure Android APIs
 * (no extra dependencies).
 */
object SubtitleAutoSync {

  private const val DT = 0.05 // seconds per analysis bin
  private const val MAX_SHIFT = 20.0 // search range in seconds

  enum class Confidence { HIGH, MEDIUM, LOW }

  data class SyncResult(
    /** Value to assign to mpv's sub-delay (seconds). */
    val offsetSeconds: Double,
    /** How far off the subtitles were: positive = they appeared late. */
    val subsWereLateBySeconds: Double,
    val confidence: Confidence,
  )

  class SyncException(message: String) : Exception(message)

  /**
   * Returns the file path / URI of the currently selected external subtitle
   * track, or null if the active subtitle track is embedded (or none selected).
   */
  fun currentExternalSubtitlePath(): String? {
    val sid = MPVLib.getPropertyInt("sid") ?: return null
    if (sid <= 0) return null
    val count = MPVLib.getPropertyInt("track-list/count") ?: 0
    for (i in 0 until count) {
      if (MPVLib.getPropertyString("track-list/$i/type") != "sub") continue
      if ((MPVLib.getPropertyInt("track-list/$i/id") ?: -1) != sid) continue
      if (MPVLib.getPropertyBoolean("track-list/$i/external") != true) return null
      return MPVLib.getPropertyString("track-list/$i/external-filename")
    }
    return null
  }

  /**
   * Runs the full auto-sync analysis.
   *
   * @param videoSource source of the video: content://, file://, fd://,
   *   plain filesystem path or http(s) URL (typically mpv's `path` property).
   * @param subtitleSource path/URI of the external subtitle file.
   * @param scanStartSec where in the media to start listening.
   * @param scanLenSec how much audio to analyze (120-240 s recommended).
   */
  suspend fun run(
    context: Context,
    videoSource: String,
    subtitleSource: String,
    scanStartSec: Double,
    scanLenSec: Double = 150.0,
  ): SyncResult = withContext(Dispatchers.IO) {
    val cues = parseSubtitleTimings(context, subtitleSource)
    if (cues.size < 10) throw SyncException("Subtitle file has too few lines to sync against")
    val envelope = extractSpeechEnvelope(context, videoSource, scanStartSec, scanLenSec)
    correlate(envelope, cues, scanStartSec)
  }

  // ---------------------------------------------------------------------------
  // Subtitle parsing
  // ---------------------------------------------------------------------------

  private fun readSubtitleText(context: Context, source: String): String = when {
    source.startsWith("content://") ->
      context.contentResolver.openInputStream(source.toUri())
        ?.bufferedReader()?.use { it.readText() }
        ?: throw SyncException("Could not open subtitle file")
    source.startsWith("http://") || source.startsWith("https://") ->
      java.net.URL(source).openStream().bufferedReader().use { it.readText() }
    source.startsWith("file://") -> File(source.removePrefix("file://")).readText()
    else -> File(source).readText()
  }

  /** Parses SRT / VTT / ASS / SSA content into (start, end) second pairs. */
  private fun parseSubtitleTimings(context: Context, source: String): List<Pair<Double, Double>> {
    val text = readSubtitleText(context, source)
    val cues = ArrayList<Pair<Double, Double>>()

    if (text.contains("[Script Info]") || text.lineSequence().any { it.startsWith("Dialogue:") }) {
      // ASS/SSA: Dialogue: 0,0:00:01.55,0:00:05.17,...
      val assTime = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2})""")
      for (line in text.lineSequence()) {
        if (!line.startsWith("Dialogue:")) continue
        val parts = line.substringAfter("Dialogue:").split(',')
        if (parts.size < 4) continue
        val ms = assTime.find(parts[1].trim()) ?: continue
        val me = assTime.find(parts[2].trim()) ?: continue
        val s = assToSec(ms)
        val e = assToSec(me)
        if (e > s) cues.add(s to e)
      }
    } else {
      // SRT/VTT: 00:00:01,550 --> 00:00:05,170 (VTT uses '.')
      val srtLine = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{2,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{2,3})""",
      )
      for (m in srtLine.findAll(text)) {
        val g = m.groupValues
        val s = srtToSec(g[1], g[2], g[3], g[4])
        val e = srtToSec(g[5], g[6], g[7], g[8])
        if (e > s) cues.add(s to e)
      }
    }
    cues.sortBy { it.first }
    return cues
  }

  private fun assToSec(m: MatchResult): Double {
    val g = m.groupValues
    return g[1].toDouble() * 3600 + g[2].toDouble() * 60 + g[3].toDouble() + g[4].toDouble() / 100.0
  }

  private fun srtToSec(h: String, m: String, s: String, ms: String): Double {
    val frac = ms.toDouble() / if (ms.length == 2) 100.0 else 1000.0
    return h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble() + frac
  }

  // ---------------------------------------------------------------------------
  // Audio analysis
  // ---------------------------------------------------------------------------

  /** RBJ biquad band-pass roughly covering the speech band (300-3400 Hz). */
  private class SpeechBandFilter(sampleRate: Int) {
    private val b0: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
      val f0 = 1000.0 // geometric-ish center of the speech band
      val bw = 3100.0
      val w0 = 2.0 * PI * f0 / sampleRate
      val q = f0 / bw
      val alpha = sin(w0) / (2.0 * q)
      val a0 = 1.0 + alpha
      b0 = alpha / a0
      b2 = -alpha / a0
      a1 = -2.0 * cos(w0) / a0
      a2 = (1.0 - alpha) / a0
    }

    fun process(x: Double): Double {
      val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
      x2 = x1
      x1 = x
      y2 = y1
      y1 = y
      return y
    }
  }

  private fun MediaFormat.intOr(key: String, def: Int): Int =
    if (containsKey(key)) getInteger(key) else def

  /**
   * Decodes [lenSec] seconds of audio starting at [startSec] and returns a
   * z-normalized speech-band RMS envelope in DT-second bins.
   */
  private fun extractSpeechEnvelope(
    context: Context,
    videoSource: String,
    startSec: Double,
    lenSec: Double,
  ): DoubleArray {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var ownedPfd: android.os.ParcelFileDescriptor? = null
    try {
      when {
        videoSource.startsWith("http://") || videoSource.startsWith("https://") ->
          extractor.setDataSource(videoSource)
        videoSource.startsWith("fd://") -> {
          // mpv is playing from a file descriptor we passed it; duplicate it for our own reads.
          val fdNum = videoSource.removePrefix("fd://").toIntOrNull()
            ?: throw SyncException("Unsupported video source")
          ownedPfd = android.os.ParcelFileDescriptor.fromFd(fdNum)
          extractor.setDataSource(ownedPfd.fileDescriptor)
        }
        videoSource.startsWith("content://") ->
          extractor.setDataSource(context, videoSource.toUri(), null)
        videoSource.startsWith("file://") ->
          extractor.setDataSource(videoSource.removePrefix("file://"))
        else -> extractor.setDataSource(videoSource)
      }

      var trackIndex = -1
      var format: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
          trackIndex = i
          format = f
          break
        }
      }
      val fmt = format ?: throw SyncException("No decodable audio track found in this video")
      extractor.selectTrack(trackIndex)
      extractor.seekTo((startSec * 1_000_000L).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

      val mime = fmt.getString(MediaFormat.KEY_MIME)!!
      codec = MediaCodec.createDecoderByType(mime)
      codec.configure(fmt, null, null, 0)
      codec.start()

      val nBins = (lenSec / DT).toInt() + 1
      val sumSq = DoubleArray(nBins)
      val cnt = LongArray(nBins)

      var sampleRate = fmt.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
      var channels = fmt.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
      var filter = SpeechBandFilter(sampleRate)

      val info = MediaCodec.BufferInfo()
      var inputDone = false
      var outputDone = false
      val endUs = ((startSec + lenSec) * 1_000_000L).toLong()
      var idleRounds = 0

      while (!outputDone && idleRounds < 500) {
        if (!inputDone) {
          val inIdx = codec.dequeueInputBuffer(10_000)
          if (inIdx >= 0) {
            val buf = codec.getInputBuffer(inIdx)!!
            val size = extractor.readSampleData(buf, 0)
            if (size < 0 || extractor.sampleTime > endUs) {
              codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }

        val outIdx = codec.dequeueOutputBuffer(info, 10_000)
        when {
          outIdx >= 0 -> {
            idleRounds = 0
            if (info.size > 0) {
              val outBuf = codec.getOutputBuffer(outIdx)!!
              outBuf.position(info.offset)
              outBuf.limit(info.offset + info.size)
              val shorts = ShortArray(info.size / 2)
              outBuf.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
              val frames = shorts.size / channels
              val baseSec = info.presentationTimeUs / 1_000_000.0
              var frame = 0
              while (frame < frames) {
                var mono = 0.0
                for (c in 0 until channels) mono += shorts[frame * channels + c]
                mono /= channels * 32768.0
                val v = filter.process(mono)
                val bin = ((baseSec + frame.toDouble() / sampleRate - startSec) / DT).toInt()
                if (bin in 0 until nBins) {
                  sumSq[bin] += v * v
                  cnt[bin]++
                }
                frame++
              }
            }
            codec.releaseOutputBuffer(outIdx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
              info.presentationTimeUs > endUs
            ) {
              outputDone = true
            }
          }
          outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val of = codec.outputFormat
            sampleRate = of.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            channels = of.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
            filter = SpeechBandFilter(sampleRate)
          }
          else -> idleRounds++
        }
      }

      var filled = 0
      val env = DoubleArray(nBins)
      var last = 0.0
      for (i in 0 until nBins) {
        if (cnt[i] > 0) {
          last = sqrt(sumSq[i] / cnt[i])
          filled++
        }
        env[i] = last
      }
      if (filled < nBins / 4) {
        throw SyncException("Could not decode enough audio (unsupported codec?)")
      }

      var mean = 0.0
      for (v in env) mean += v
      mean /= env.size
      var sd = 0.0
      for (v in env) sd += (v - mean) * (v - mean)
      sd = sqrt(sd / env.size)
      if (sd < 1e-12) throw SyncException("Audio appears silent in the scanned section")
      for (i in env.indices) env[i] = (env[i] - mean) / sd
      return env
    } finally {
      try {
        codec?.stop()
        codec?.release()
      } catch (_: Exception) {
      }
      extractor.release()
      try {
        ownedPfd?.close()
      } catch (_: Exception) {
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Cross-correlation
  // ---------------------------------------------------------------------------

  private fun correlate(
    envelope: DoubleArray,
    cues: List<Pair<Double, Double>>,
    scanStart: Double,
  ): SyncResult {
    val nBins = envelope.size
    val extra = (2 * MAX_SHIFT / DT).toInt() + 4
    val subActive = ByteArray(nBins + extra)
    val base = scanStart - MAX_SHIFT
    for ((s, e) in cues) {
      val b0 = max(0, floor((s - base) / DT).toInt())
      val b1 = min(subActive.size - 1, ceil((e - base) / DT).toInt())
      if (b1 < 0 || b0 >= subActive.size) continue
      for (b in b0..b1) subActive[b] = 1
    }

    var bestD = 0.0
    var bestScore = Double.NEGATIVE_INFINITY
    val scores = ArrayList<Double>()
    val steps = (2 * MAX_SHIFT / DT).roundToInt()
    for (k in 0..steps) {
      val d = -MAX_SHIFT + k * DT
      var sOn = 0.0
      var nOn = 0
      var sOff = 0.0
      var nOff = 0
      for (i in 0 until nBins) {
        val idx = ((i * DT + MAX_SHIFT + d) / DT).roundToInt()
        if (idx < 0 || idx >= subActive.size) continue
        if (subActive[idx].toInt() == 1) {
          sOn += envelope[i]
          nOn++
        } else {
          sOff += envelope[i]
          nOff++
        }
      }
      if (nOn >= nBins * 0.05 && nOff >= nBins * 0.05) {
        val score = sOn / nOn - sOff / nOff
        scores.add(score)
        if (score > bestScore) {
          bestScore = score
          bestD = d
        }
      }
    }

    if (scores.isEmpty()) {
      throw SyncException("Not enough dialogue in the scanned section — seek to a talking scene and retry")
    }
    scores.sort()
    val median = scores[scores.size / 2]
    val hi = scores[min(scores.size - 1, (scores.size * 0.84).toInt())]
    val spread = max(hi - median, 1e-9)
    val confidenceRatio = (bestScore - median) / spread

    val lateBy = (bestD * 100).roundToInt() / 100.0
    return SyncResult(
      offsetSeconds = -lateBy,
      subsWereLateBySeconds = lateBy,
      confidence = when {
        confidenceRatio > 2.2 -> Confidence.HIGH
        confidenceRatio > 1.2 -> Confidence.MEDIUM
        else -> Confidence.LOW
      },
    )
  }
}
