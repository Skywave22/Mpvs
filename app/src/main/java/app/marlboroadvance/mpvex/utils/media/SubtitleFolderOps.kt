package app.marlboroadvance.mpvex.utils.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Automatic subtitle loading from a user-selected subtitles folder.
 *
 * The user picks a folder once (Settings -> Subtitles -> Subtitles folder).
 * Every time a video starts, this scans the folder, finds the subtitle file
 * that best matches the playing episode (by filename and episode number) and
 * loads it into mpv automatically - no user interaction.
 *
 * Matching strategy, in order of confidence:
 *  1. Exact basename match (video "Bleach - 05.mkv" -> "Bleach - 05.srt")
 *  2. Normalized-name containment (release tags/brackets stripped)
 *  3. Episode-number match: numbers are extracted from both names
 *     ("Bleach - 05", "[HorribleSubs] Bleach - 05 [480p]", "E05", "05x12", "Ep 5")
 *     and compared; season/episode patterns win over bare numbers.
 */
object SubtitleFolderOps {
  private const val TAG = "SubtitleFolderOps"

  private val SUB_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup", "txt", "pgs")

  // ---------------------------------------------------------------------------
  // Public entry point
  // ---------------------------------------------------------------------------

  /**
   * Finds the best-matching subtitle in [folderUriString] for [videoFileName]
   * and adds it to mpv. Returns the display name of the loaded subtitle or
   * null when nothing suitable was found.
   */
  suspend fun autoloadFromFolder(
    context: Context,
    folderUriString: String,
    videoFileName: String,
  ): String? = withContext(Dispatchers.IO) {
    try {
      if (folderUriString.isBlank() || videoFileName.isBlank()) return@withContext null

      val folder = DocumentFile.fromTreeUri(context, folderUriString.toUri())
        ?: return@withContext null
      if (!folder.isDirectory) return@withContext null

      val candidates = folder.listFiles().mapNotNull { f ->
        val name = f.name ?: return@mapNotNull null
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (f.isFile && ext in SUB_EXTENSIONS) name to f.uri else null
      }
      if (candidates.isEmpty()) {
        Log.d(TAG, "No subtitle files in selected folder")
        return@withContext null
      }

      val best = findBestMatch(videoFileName, candidates.map { it.first })
        ?: return@withContext null
      val (matchName, score) = best
      Log.d(TAG, "Best subtitle match for '$videoFileName': '$matchName' (score=$score)")

      val uri = candidates.first { it.first == matchName }.second
      val localPath = materialize(context, uri, matchName) ?: return@withContext null

      MPVLib.command("sub-add", localPath, "select", matchName)
      matchName
    } catch (e: Exception) {
      Log.e(TAG, "Subtitle folder autoload failed", e)
      null
    }
  }

  /**
   * SAF document URIs are not directly readable by mpv, so copy the matched
   * subtitle into the app cache and hand mpv a real file path. Cached copies
   * are reused between plays of the same episode.
   */
  private fun materialize(context: Context, uri: Uri, displayName: String): String? {
    return try {
      val dir = File(context.cacheDir, "folder_subs").apply { mkdirs() }
      // Prune cache if it grows beyond ~50 files
      dir.listFiles()?.let { files ->
        if (files.size > 50) files.sortedBy { it.lastModified() }.take(files.size - 50).forEach { it.delete() }
      }
      val safeName = displayName.replace(Regex("""[/\\:*?"<>|]"""), "_")
      val out = File(dir, safeName)
      if (!out.exists() || out.length() == 0L) {
        context.contentResolver.openInputStream(uri)?.use { input ->
          out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
      }
      out.absolutePath
    } catch (e: Exception) {
      Log.e(TAG, "Failed to copy subtitle from SAF", e)
      null
    }
  }

  // ---------------------------------------------------------------------------
  // Matching
  // ---------------------------------------------------------------------------

  /**
   * Returns the best matching subtitle filename and its score, or null if no
   * candidate reaches the minimum confidence.
   */
  fun findBestMatch(videoFileName: String, subtitleNames: List<String>): Pair<String, Int>? {
    val videoBase = videoFileName.substringBeforeLast('.')
    val videoNorm = normalize(videoBase)
    val videoEp = extractEpisode(videoBase)

    var best: Pair<String, Int>? = null
    for (subName in subtitleNames) {
      val subBase = subName.substringBeforeLast('.')
      var score = 0

      // 1) exact basename
      if (subBase.equals(videoBase, ignoreCase = true)) {
        score = 1000
      } else {
        val subNorm = normalize(subBase)
        // 2) normalized equality / containment
        if (subNorm == videoNorm && subNorm.isNotBlank()) {
          score = 900
        } else if (subNorm.isNotBlank() && videoNorm.isNotBlank() &&
          (subNorm.contains(videoNorm) || videoNorm.contains(subNorm))
        ) {
          score = 600
        }

        // 3) episode number matching
        val subEp = extractEpisode(subBase)
        if (videoEp != null && subEp != null) {
          if (videoEp.episode == subEp.episode) {
            val seasonsCompatible = videoEp.season == null || subEp.season == null ||
              videoEp.season == subEp.season
            if (seasonsCompatible) {
              score += if (videoEp.season != null && videoEp.season == subEp.season) 500 else 400
              // bonus when the show titles also roughly agree
              val titleV = titleTokens(videoNorm)
              val titleS = titleTokens(subNorm)
              if (titleV.isNotEmpty() && titleS.isNotEmpty() &&
                titleV.intersect(titleS).isNotEmpty()
              ) {
                score += 100
              }
            }
          } else {
            // wrong episode number is a hard veto over containment matches
            score = 0
          }
        }
      }

      if (score > (best?.second ?: 0)) best = subName to score
    }
    return best?.takeIf { it.second >= 300 }
  }

  data class EpisodeInfo(val season: Int?, val episode: Int)

  /**
   * Extracts season/episode info from a media filename.
   * Understands: S01E05, 01x05, "Episode 5", "Ep 05", "E05",
   * " - 05", "_05_", trailing/bare numbers like "Bleach 122".
   * Numbers that look like resolutions/years/hashes are ignored.
   */
  fun extractEpisode(rawName: String): EpisodeInfo? {
    // remove bracketed release tags first: [HorribleSubs], (1080p), [ABC123]...
    var name = rawName
      .replace(Regex("""\[[^\]]*]"""), " ")
      .replace(Regex("""\([^)]*\)"""), " ")

    // strip common noise tokens so their digits don't confuse us
    name = name.replace(
      Regex("""(?i)\b(480p|720p|1080p|2160p|4k|x264|x265|h264|h265|hevc|10bit|8bit|bluray|bdrip|webrip|web-dl|hdtv|aac|flac|dual[- ]?audio|v2)\b"""),
      " ",
    )

    // S01E05 / s1e5
    Regex("""(?i)\bs(\d{1,2})\s*e(\d{1,3})\b""").find(name)?.let {
      return EpisodeInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    // 01x05
    Regex("""\b(\d{1,2})x(\d{1,3})\b""").find(name)?.let {
      return EpisodeInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    // Episode 5 / Ep 05 / E05
    Regex("""(?i)\b(?:episode|ep|e)\s*\.?\s*(\d{1,4})\b""").find(name)?.let {
      return EpisodeInfo(null, it.groupValues[1].toInt())
    }
    // " - 05" separator style (most anime releases)
    Regex("""[-_.]\s*(\d{1,4})\s*(?:[-_.]|$)""").find(name)?.let {
      val n = it.groupValues[1].toInt()
      if (n in 0..1999) return EpisodeInfo(null, n)
    }
    // bare trailing number: "Bleach 122"
    Regex("""(?:^|\s)(\d{1,4})\s*$""").find(name.trim())?.let {
      val n = it.groupValues[1].toInt()
      if (n in 0..1999) return EpisodeInfo(null, n)
    }
    // any standalone number that is not a year
    val numbers = Regex("""\b(\d{1,4})\b""").findAll(name)
      .map { it.groupValues[1].toInt() }
      .filter { it in 1..1899 }
      .toList()
    if (numbers.size == 1) return EpisodeInfo(null, numbers[0])

    return null
  }

  /** Lowercase, strip bracket groups, keep letters/digits/spaces, squeeze spaces. */
  private fun normalize(s: String): String =
    s.lowercase(Locale.ROOT)
      .replace(Regex("""\[[^\]]*]"""), " ")
      .replace(Regex("""\([^)]*\)"""), " ")
      .replace(Regex("""[^a-z0-9]+"""), " ")
      .trim()
      .replace(Regex("""\s+"""), " ")

  /** Alphabetic tokens only (the "title words"), noise words removed. */
  private fun titleTokens(norm: String): Set<String> =
    norm.split(' ')
      .filter { it.length > 2 && it.none(Char::isDigit) }
      .filterNot {
        it in setOf(
          "480p", "720p", "1080p", "the", "and", "sub", "subs", "eng", "english",
          "x264", "x265", "hevc", "web", "bluray", "hdtv", "aac", "flac",
        )
      }
      .toSet()
}
