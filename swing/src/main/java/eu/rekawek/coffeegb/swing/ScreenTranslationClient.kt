package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.swing.io.ScreenTranslationOverlay
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class ScreenTranslationResult(
    val regions: List<ScreenTranslationOverlay.Region>,
    val fallbackText: String = "",
) {
  val isEmpty: Boolean
    get() = regions.isEmpty() && fallbackText.isBlank()

  fun toOverlay(frame: SwingDisplay.CapturedTranslationFrame): ScreenTranslationOverlay =
      ScreenTranslationOverlay(
          frame.rotatedWidth(),
          frame.rotatedHeight(),
          regions,
          fallbackText,
          frame.rawWidth(),
          frame.rawHeight(),
          frame.rawRgb(),
      )
}

internal data class DetectedTextBand(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
) {
  val width: Int
    get() = x2 - x1 + 1

  val height: Int
    get() = y2 - y1 + 1

  val centerX: Double
    get() = (x1 + x2 + 1) / 2.0

  val centerY: Double
    get() = (y1 + y2 + 1) / 2.0

  fun toNormalizedBbox(sourceWidth: Int, sourceHeight: Int): List<Int> =
      listOf(
          (x1 * 1000.0 / sourceWidth).roundToInt().coerceIn(0, 1000),
          (y1 * 1000.0 / sourceHeight).roundToInt().coerceIn(0, 1000),
          ((x2 + 1) * 1000.0 / sourceWidth).roundToInt().coerceIn(0, 1000),
          ((y2 + 1) * 1000.0 / sourceHeight).roundToInt().coerceIn(0, 1000),
      )
}

internal fun interface ScreenTranslationBackend {
  @Throws(IOException::class, InterruptedException::class)
  fun translate(
      frame: SwingDisplay.CapturedTranslationFrame,
      config: ResolvedScreenTranslationConfig,
  ): ScreenTranslationResult
}

internal data class ScreenTranslationHttpRequest(
    val uri: URI,
    val headers: Map<String, String>,
    val body: String,
)

internal data class ScreenTranslationHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface ScreenTranslationHttpTransport {
  @Throws(IOException::class, InterruptedException::class)
  fun post(request: ScreenTranslationHttpRequest): ScreenTranslationHttpResponse
}

internal class JdkScreenTranslationHttpTransport(
    private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val requestTimeout: Duration = Duration.ofSeconds(120),
) : ScreenTranslationHttpTransport {
  override fun post(request: ScreenTranslationHttpRequest): ScreenTranslationHttpResponse {
    val builder =
        HttpRequest.newBuilder(request.uri)
            .timeout(requestTimeout)
            .POST(HttpRequest.BodyPublishers.ofString(request.body, StandardCharsets.UTF_8))
    request.headers.forEach(builder::header)
    val response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    return ScreenTranslationHttpResponse(response.statusCode(), response.body())
  }
}

internal class HttpScreenTranslationClient(
    private val transport: ScreenTranslationHttpTransport = JdkScreenTranslationHttpTransport(),
    private val upscaleFactor: Int = DEFAULT_UPSCALE_FACTOR,
) : ScreenTranslationBackend {

  init {
    require(upscaleFactor in 1..6) { "Upscale factor must be between 1 and 6" }
  }

  override fun translate(
      frame: SwingDisplay.CapturedTranslationFrame,
      config: ResolvedScreenTranslationConfig,
  ): ScreenTranslationResult {
    val candidateBands =
        detectCandidateTextBands(
            rgb = frame.rotatedRgb(),
            width = frame.rotatedWidth(),
            height = frame.rotatedHeight(),
        )
    val pngBytes =
        encodeRgbPng(
            width = frame.rotatedWidth() * upscaleFactor,
            height = frame.rotatedHeight() * upscaleFactor,
            rgb =
                upscaleNearestNeighbor(
                    frame.rotatedRgb(),
                    frame.rotatedWidth(),
                    frame.rotatedHeight(),
                    upscaleFactor,
                ),
        )
    val base64Png = Base64.getEncoder().encodeToString(pngBytes)
    val prompt =
        buildTranslationPrompt(
            sourceWidth = frame.rotatedWidth(),
            sourceHeight = frame.rotatedHeight(),
            targetLanguage = config.targetLanguage,
            candidateBands = candidateBands,
        )

    val httpRequest =
        when (config.protocol) {
          ResolvedTranslationProtocol.GEMINI -> buildGeminiRequest(config, prompt, base64Png)
          ResolvedTranslationProtocol.OPENAI_COMPATIBLE ->
              buildOpenAiRequest(config, prompt, base64Png)
        }

    val response = transport.post(httpRequest)
    if (response.statusCode !in 200..299) {
      val detail = extractErrorDetail(response.body)
      throw IOException(
          "Translation service returned HTTP ${response.statusCode}" +
              (if (detail.isNotEmpty()) ": $detail" else ""),
      )
    }

    val modelText =
        when (config.protocol) {
          ResolvedTranslationProtocol.GEMINI -> extractGeminiContentText(response.body)
          ResolvedTranslationProtocol.OPENAI_COMPATIBLE -> extractOpenAiContentText(response.body)
        }
    return parseTranslationPayload(
        rawModelText = modelText,
        sourceWidth = frame.rotatedWidth(),
        sourceHeight = frame.rotatedHeight(),
        candidateBands = candidateBands,
    )
  }

  companion object {
    const val DEFAULT_UPSCALE_FACTOR = 2

    private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val IHDR = "IHDR".toByteArray(StandardCharsets.US_ASCII)
    private val IDAT = "IDAT".toByteArray(StandardCharsets.US_ASCII)
    private val IEND = "IEND".toByteArray(StandardCharsets.US_ASCII)

    internal fun buildTranslationPrompt(
        sourceWidth: Int,
        sourceHeight: Int,
        targetLanguage: String,
        candidateBands: List<DetectedTextBand> = emptyList(),
    ): String {
      val candidateSection =
          if (candidateBands.isNotEmpty()) {
            val formattedBands =
                candidateBands.joinToString("\n") { band ->
                  val (bx1, by1, bx2, by2) = band.toNormalizedBbox(sourceWidth, sourceHeight)
                  "- [$bx1, $by1, $bx2, $by2]"
                }
            """
            Detected candidate text bands (in 0..1000 [x1, y1, x2, y2] coordinates from top to bottom):
            $formattedBands
            """
                .trimIndent() + "\n"
          } else {
            ""
          }
      return """
        Locate every visible non-$targetLanguage text block (title banners, menu options, dialogue boxes) on this retro Game Boy screen (${sourceWidth}x${sourceHeight} logical aspect) and translate its meaning into natural $targetLanguage.
        ${candidateSection}Rules:
        - Put each distinct menu option or title banner in its own region. For dialogue sentences spanning two lines inside a dialogue box, you may either keep each line as its own region or combine the lines' `bbox_2d` into `[min_x1, min_y1, max_x2, max_y2]`.
        - Use the matching candidate `[x1, y1, x2, y2]` coordinates for each translated text block when candidate bands are listed above, and omit bands that are already in $targetLanguage (such as PUSH START or copyright lines).
        - Retro Japanese games often write common vocabulary in hiragana/katakana (e.g. たいきょく = Play Match, つめれんじゅ = Puzzle Mode, せん手 = First Move, はじめる = Start, さいしょからはじめる = New Game, せっていをかえる = Options). Always translate their meaning into $targetLanguage; never output romaji/phonetics.
        - Provide `bbox_2d` as `[x1, y1, x2, y2]` in 0..1000 normalized screen coordinates covering the text block.
        - Return ONLY valid JSON (no markdown fences) matching:
        {"regions":[{"bbox_2d":[x1,y1,x2,y2],"original":"...","translation":"..."}]}
        - If there is no non-$targetLanguage text on screen, return {"regions":[]}.
        """
          .trimIndent()
    }

    internal fun detectCandidateTextBands(
        rgb: IntArray,
        width: Int,
        height: Int,
    ): List<DetectedTextBand> {
      if (width <= 8 || height <= 8 || rgb.size < width * height) {
        return emptyList()
      }
      val trans = Array(width) { BooleanArray(height) }
      for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 1 until width) {
          val curr = rgb[rowOffset + x] and 0x00FFFFFF
          val prev = rgb[rowOffset + x - 1] and 0x00FFFFFF
          trans[x][y] = curr != prev
        }
      }

      // Suppress continuous vertical box border edges so blank rows inside framed boxes are not
      // mistaken for text rows.
      for (x in 1 until width) {
        var y = 0
        while (y < height) {
          if (!trans[x][y]) {
            y++
            continue
          }
          val leftColor = rgb[y * width + x - 1] and 0x00FFFFFF
          val rightColor = rgb[y * width + x] and 0x00FFFFFF
          var yEnd = y + 1
          while (yEnd < height &&
              trans[x][yEnd] &&
              (rgb[yEnd * width + x - 1] and 0x00FFFFFF) == leftColor &&
              (rgb[yEnd * width + x] and 0x00FFFFFF) == rightColor) {
            yEnd++
          }
          if (yEnd - y >= 14) {
            for (yy in y until yEnd) {
              trans[x][yy] = false
            }
          }
          y = yEnd
        }
      }

      val bands = mutableListOf<DetectedTextBand>()
      for (y in 0 until height) {
        var minX = -1
        var maxX = -1
        var count = 0
        for (x in 1 until width) {
          if (trans[x][y]) {
            if (minX == -1) minX = x
            maxX = x
            count++
          }
        }
        if (count >= 6 && minX >= 0 && maxX >= minX) {
          val last = bands.lastOrNull()
          if (last == null || y - last.y2 > 2) {
            bands += DetectedTextBand(x1 = minX, y1 = y, x2 = maxX, y2 = y)
          } else {
            bands[bands.lastIndex] =
                DetectedTextBand(
                    x1 = min(last.x1, minX),
                    y1 = last.y1,
                    x2 = max(last.x2, maxX),
                    y2 = y,
                )
          }
        }
      }

      return bands.filter { band ->
        band.height in 5..36 && band.width >= (band.height * 1.2).roundToInt()
      }
    }

    internal fun buildGeminiRequest(
        config: ResolvedScreenTranslationConfig,
        prompt: String,
        base64Png: String,
    ): ScreenTranslationHttpRequest {
      val base = config.baseUrl.trimEnd('/')
      val modelPath =
          if (config.model.startsWith("models/")) config.model else "models/${config.model}"
      val uri = URI.create("$base/$modelPath:generateContent")
      val headers = buildMap {
        put("Content-Type", "application/json; charset=utf-8")
        if (config.apiKey.isNotEmpty()) {
          put("x-goog-api-key", config.apiKey)
        }
      }
      val body =
          """
          {
            "contents": [
              {
                "parts": [
                  {"text": ${jsonEscape(prompt)}},
                  {
                    "inline_data": {
                      "mime_type": "image/png",
                      "data": ${jsonEscape(base64Png)}
                    }
                  }
                ]
              }
            ],
            "generationConfig": {
              "responseMimeType": "application/json",
              "temperature": 0.1
            }
          }
          """
              .trimIndent()
      return ScreenTranslationHttpRequest(uri = uri, headers = headers, body = body)
    }

    internal fun buildOpenAiRequest(
        config: ResolvedScreenTranslationConfig,
        prompt: String,
        base64Png: String,
    ): ScreenTranslationHttpRequest {
      val base = config.baseUrl.trimEnd('/')
      val path = runCatching { URI(base).path }.getOrNull().orEmpty()
      val endpoint =
          when {
            base.endsWith("/chat/completions") -> base
            path.isEmpty() || path == "/" -> "$base/v1/chat/completions"
            else -> "$base/chat/completions"
          }
      val uri = URI.create(endpoint)
      val headers = buildMap {
        put("Content-Type", "application/json; charset=utf-8")
        if (config.apiKey.isNotEmpty()) {
          put("Authorization", "Bearer ${config.apiKey}")
        }
      }
      val dataUri = "data:image/png;base64,$base64Png"
      val body =
          """
          {
            "model": ${jsonEscape(config.model)},
            "temperature": 0.0,
            "response_format": {"type": "json_object"},
            "messages": [
              {
                "role": "user",
                "content": [
                  {"type": "text", "text": ${jsonEscape(prompt)}},
                  {"type": "image_url", "image_url": {"url": ${jsonEscape(dataUri)}}}
                ]
              }
            ]
          }
          """
              .trimIndent()
      return ScreenTranslationHttpRequest(uri = uri, headers = headers, body = body)
    }

    internal fun extractGeminiContentText(responseBody: String): String {
      val root =
          MiniJson.parse(responseBody) as? Map<*, *>
              ?: throw IOException("Invalid JSON response from Gemini")
      val candidates =
          root["candidates"] as? List<*>
              ?: throw IOException("Gemini response did not contain candidates")
      val firstCandidate = candidates.firstOrNull() as? Map<*, *> ?: return ""
      val content = firstCandidate["content"] as? Map<*, *> ?: return ""
      val parts = content["parts"] as? List<*> ?: return ""
      return parts
          .mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
          .joinToString("\n")
          .trim()
    }

    internal fun extractOpenAiContentText(responseBody: String): String {
      val root =
          MiniJson.parse(responseBody) as? Map<*, *>
              ?: throw IOException("Invalid JSON response from translation endpoint")
      val choices =
          root["choices"] as? List<*>
              ?: throw IOException("Translation response did not contain choices")
      val firstChoice = choices.firstOrNull() as? Map<*, *> ?: return ""
      val message = firstChoice["message"] as? Map<*, *> ?: return ""
      return when (val content = message["content"]) {
        is String -> content.trim()
        is List<*> ->
            content
                .mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
                .joinToString("\n")
                .trim()
        else -> ""
      }
    }

    internal fun parseTranslationPayload(
        rawModelText: String,
        sourceWidth: Int,
        sourceHeight: Int,
        candidateBands: List<DetectedTextBand> = emptyList(),
    ): ScreenTranslationResult {
      val cleaned = stripMarkdownCodeFences(rawModelText).trim()
      if (cleaned.isEmpty()) {
        return ScreenTranslationResult(emptyList(), "")
      }

      val parsed = runCatching { MiniJson.parse(cleaned) }.getOrNull()
      if (parsed !is Map<*, *>) {
        return ScreenTranslationResult(emptyList(), cleaned)
      }

      val rawRegions = parsed["regions"] as? List<*> ?: emptyList<Any?>()
      val parsedRegions =
          rawRegions.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            parseRegion(map, sourceWidth, sourceHeight)
          }
      val regions =
          alignRegionsToCandidateBands(
              regions = parsedRegions,
              candidateBands = candidateBands,
              sourceWidth = sourceWidth,
              sourceHeight = sourceHeight,
          )
      val unlocatedTranslations =
          if (regions.isEmpty()) {
            rawRegions
                .mapNotNull { item ->
                  val map = item as? Map<*, *> ?: return@mapNotNull null
                  ((map["translation"] as? String) ?: (map["text"] as? String))?.trim()?.takeIf {
                    it.isNotEmpty()
                  }
                }
                .joinToString("\n")
          } else {
            ""
          }
      val summary =
          ((parsed["summary"] as? String)
                  ?: (parsed["translation"] as? String)
                  ?: (parsed["text"] as? String))
              ?.trim()
              .orEmpty()
      val fallback =
          when {
            regions.isNotEmpty() -> ""
            summary.isNotEmpty() -> summary
            else -> unlocatedTranslations
          }
      return ScreenTranslationResult(regions = regions, fallbackText = fallback)
    }

    internal fun alignRegionsToCandidateBands(
        regions: List<ScreenTranslationOverlay.Region>,
        candidateBands: List<DetectedTextBand>,
        sourceWidth: Int,
        sourceHeight: Int,
    ): List<ScreenTranslationOverlay.Region> {
      if (regions.isEmpty()) return emptyList()
      if (candidateBands.isEmpty()) {
        return alignStackedPeerColumns(regions, sourceWidth)
      }

      val sortedRegions = regions.sortedWith(compareBy({ it.y() }, { it.x() }))

      // Case 1: Single region that merges 2+ closely spaced lines inside one menu/dialogue box.
      if (sortedRegions.size == 1 && candidateBands.size >= 2) {
        val single = sortedRegions.single()
        val exactSingle =
            candidateBands.firstOrNull { band ->
              abs(single.y() - band.y1) <= 4 && abs(single.height() - band.height) <= 6
            }
        if (exactSingle != null) {
          return listOf(snapRegionToBand(single, exactSingle, sourceWidth, sourceHeight))
        }
        val paragraphClusters = clusterAdjacentParagraphBands(candidateBands)
        val bestCluster =
            paragraphClusters.minByOrNull { cluster ->
              val cy = (cluster.y1 + cluster.y2 + 1) / 2.0
              val ry = single.y() + single.height() / 2.0
              abs(ry - cy)
            }
        if (bestCluster != null) {
          return listOf(snapRegionToBand(single, bestCluster, sourceWidth, sourceHeight))
        }
      }

      // Case 2: Every region already matches a candidate band closely (within 4px).
      val directMatches =
          sortedRegions.map { region ->
            candidateBands.firstOrNull { band ->
              abs(region.y() - band.y1) <= 4 &&
                  abs((region.y() + region.height()) - (band.y2 + 1)) <= 6
            }
          }
      if (directMatches.none { it == null }) {
        val snapped =
            sortedRegions.indices.map { i ->
              snapRegionToBand(
                  sortedRegions[i],
                  checkNotNull(directMatches[i]),
                  sourceWidth,
                  sourceHeight,
              )
            }
        return alignStackedPeerColumns(snapped, sourceWidth)
      }

      // Case 3: Order-preserving DP alignment between sortedRegions (k) and candidateBands (m).
      if (sortedRegions.size <= candidateBands.size) {
        val k = sortedRegions.size
        val m = candidateBands.size
        val dp = Array(k) { DoubleArray(m) { Double.POSITIVE_INFINITY } }
        val prev = Array(k) { IntArray(m) { -1 } }

        for (i in 0 until k) {
          val region = sortedRegions[i]
          for (j in i..(m - (k - i))) {
            val cost = bandMatchCost(region, candidateBands[j], k, m, sourceWidth, sourceHeight)
            if (i == 0) {
              dp[0][j] = cost
            } else {
              for (p in (i - 1) until j) {
                val candidateTotal = dp[i - 1][p] + cost
                if (candidateTotal < dp[i][j]) {
                  dp[i][j] = candidateTotal
                  prev[i][j] = p
                }
              }
            }
          }
        }

        var bestLastBand = k - 1
        for (j in (k - 1) until m) {
          if (dp[k - 1][j] < dp[k - 1][bestLastBand]) {
            bestLastBand = j
          }
        }

        val assignedBands = IntArray(k)
        var curr = bestLastBand
        for (i in (k - 1) downTo 0) {
          assignedBands[i] = curr
          curr = if (i > 0) prev[i][curr] else -1
        }

        val snapped =
            sortedRegions.indices.map { i ->
              snapRegionToBand(
                  sortedRegions[i],
                  candidateBands[assignedBands[i]],
                  sourceWidth,
                  sourceHeight,
              )
            }
        return alignStackedPeerColumns(snapped, sourceWidth)
      }

      return alignStackedPeerColumns(sortedRegions, sourceWidth)
    }

    private fun clusterAdjacentParagraphBands(
        bands: List<DetectedTextBand>,
    ): List<DetectedTextBand> {
      val clusters = mutableListOf<DetectedTextBand>()
      for (band in bands) {
        val prev = clusters.lastOrNull()
        if (prev != null &&
            band.y1 - prev.y2 <= 14 &&
            abs(band.x1 - prev.x1) <= 16 &&
            band.height <= 14 &&
            prev.height <= 28) {
          clusters[clusters.lastIndex] =
              DetectedTextBand(
                  x1 = min(prev.x1, band.x1),
                  y1 = prev.y1,
                  x2 = max(prev.x2, band.x2),
                  y2 = band.y2,
              )
        } else {
          clusters += band
        }
      }
      return clusters
    }

    private fun bandMatchCost(
        region: ScreenTranslationOverlay.Region,
        band: DetectedTextBand,
        regionCount: Int,
        bandCount: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Double {
      val regionCenterY = (region.y() + region.height() / 2.0) / sourceHeight
      val bandCenterY = band.centerY / sourceHeight
      val yDiff = abs(regionCenterY - bandCenterY)

      val regionWidthNorm = region.width().toDouble() / sourceWidth
      val bandWidthNorm = band.width.toDouble() / sourceWidth
      val widthDiff = abs(regionWidthNorm - bandWidthNorm) * 0.45

      val regionCenterX = (region.x() + region.width() / 2.0) / sourceWidth
      val bandCenterX = band.centerX / sourceWidth
      val xDiff = abs(regionCenterX - bandCenterX) * 0.35

      // Penalize matching a non-bottom region to a wide bottom copyright footer when there are
      // more bands than translated regions.
      val footerPenalty =
          if (regionCount < bandCount &&
              band.y1 >= sourceHeight * 0.74 &&
              band.width >= sourceWidth * 0.65 &&
              regionCenterY < 0.72) {
            0.65
          } else {
            0.0
          }
      return yDiff + widthDiff + xDiff + footerPenalty
    }

    private fun snapRegionToBand(
        region: ScreenTranslationOverlay.Region,
        band: DetectedTextBand,
        sourceWidth: Int,
        sourceHeight: Int,
    ): ScreenTranslationOverlay.Region {
      val x = band.x1.coerceIn(0, (sourceWidth - 8).coerceAtLeast(0))
      val y = band.y1.coerceIn(0, (sourceHeight - 7).coerceAtLeast(0))
      val width = band.width.coerceIn(8, (sourceWidth - x).coerceAtLeast(8))
      val height = band.height.coerceIn(7, (sourceHeight - y).coerceAtLeast(7))
      return ScreenTranslationOverlay.Region(
          x,
          y,
          width,
          height,
          region.text(),
          region.originalText(),
      )
    }

    internal fun alignStackedPeerColumns(
        regions: List<ScreenTranslationOverlay.Region>,
        sourceWidth: Int,
    ): List<ScreenTranslationOverlay.Region> {
      if (regions.size < 2) return regions
      val result = regions.toMutableList()
      var i = 0
      while (i < result.size) {
        var j = i + 1
        while (j < result.size) {
          val prev = result[j - 1]
          val curr = result[j]
          val verticalGap = curr.y() - (prev.y() + prev.height())
          val closeLeftEdge = abs(curr.x() - prev.x()) <= 16
          val bothCompactRows =
              prev.height() <= 16 &&
                  curr.height() <= 16 &&
                  prev.width() <= (sourceWidth * 0.85).roundToInt() &&
                  curr.width() <= (sourceWidth * 0.85).roundToInt()
          if (verticalGap in 0..20 && closeLeftEdge && bothCompactRows) {
            j++
          } else {
            break
          }
        }
        if (j - i >= 2) {
          val minX = (i until j).minOf { result[it].x() }
          val maxRight = (i until j).maxOf { result[it].x() + result[it].width() }
          val commonWidth = (maxRight - minX).coerceAtLeast(8)
          for (idx in i until j) {
            val r = result[idx]
            result[idx] =
                ScreenTranslationOverlay.Region(
                    minX,
                    r.y(),
                    commonWidth,
                    r.height(),
                    r.text(),
                    r.originalText(),
                )
          }
        }
        i = j
      }
      return result
    }

    private fun parseRegion(
        map: Map<*, *>,
        sourceWidth: Int,
        sourceHeight: Int,
    ): ScreenTranslationOverlay.Region? {
      val bboxList = (map["bbox_2d"] ?: map["box_2d"] ?: map["bbox"]) as? List<*>
      val extraOriginal =
          if (bboxList != null && bboxList.size >= 6) (bboxList[4] as? String)?.trim() else null
      val extraTranslation =
          when {
            bboxList != null && bboxList.size >= 6 -> (bboxList[5] as? String)?.trim()
            bboxList != null && bboxList.size == 5 -> (bboxList[4] as? String)?.trim()
            else -> null
          }

      val translation =
          ((map["translation"] as? String) ?: (map["text"] as? String) ?: extraTranslation)
              ?.trim()
              .orEmpty()
      if (translation.isEmpty()) {
        return null
      }
      val original =
          ((map["original"] as? String) ?: (map["source"] as? String) ?: extraOriginal)
              ?.trim()
              .orEmpty()

      val scaledX: Double
      val scaledY: Double
      val scaledW: Double
      val scaledH: Double

      if (bboxList != null && bboxList.size >= 4) {
        val x1 = numberOrNull(bboxList[0])
        val y1 = numberOrNull(bboxList[1])
        val x2 = numberOrNull(bboxList[2])
        val y2 = numberOrNull(bboxList[3])
        if (x1 == null || y1 == null || x2 == null || y2 == null || x2 <= x1 || y2 <= y1) {
          return null
        }
        val isFraction =
            x1 in 0.0..1.0 &&
                y1 in 0.0..1.0 &&
                x2 in 0.0..1.0 &&
                y2 in 0.0..1.0 &&
                (x2 < 1.0 || y2 < 1.0)
        if (isFraction) {
          scaledX = x1 * sourceWidth
          scaledY = y1 * sourceHeight
          scaledW = (x2 - x1) * sourceWidth
          scaledH = (y2 - y1) * sourceHeight
        } else {
          scaledX = x1 * sourceWidth / 1000.0
          scaledY = y1 * sourceHeight / 1000.0
          scaledW = (x2 - x1) * sourceWidth / 1000.0
          scaledH = (y2 - y1) * sourceHeight / 1000.0
        }
      } else {
        val rawX = numberOrNull(map["x"])
        val rawY = numberOrNull(map["y"])
        val rawW = numberOrNull(map["width"]) ?: numberOrNull(map["w"])
        val rawH = numberOrNull(map["height"]) ?: numberOrNull(map["h"])

        if (rawX == null ||
            rawY == null ||
            rawW == null ||
            rawH == null ||
            rawW <= 0.0 ||
            rawH <= 0.0) {
          return null
        }

        // Support 0..1 fractional coordinates, 0..1000 normalized coordinates, or native source pixels.
        val isNormalizedFraction =
            rawX in 0.0..1.0 &&
                rawY in 0.0..1.0 &&
                rawW in 0.0..1.0 &&
                rawH in 0.0..1.0 &&
                (rawW < 1.0 || rawH < 1.0)
        val exceedsSource =
            rawX + rawW > sourceWidth * 1.15 || rawY + rawH > sourceHeight * 1.15

        when {
          isNormalizedFraction -> {
            scaledX = rawX * sourceWidth
            scaledY = rawY * sourceHeight
            scaledW = rawW * sourceWidth
            scaledH = rawH * sourceHeight
          }
          exceedsSource -> {
            scaledX = rawX * sourceWidth / 1000.0
            scaledY = rawY * sourceHeight / 1000.0
            scaledW = rawW * sourceWidth / 1000.0
            scaledH = rawH * sourceHeight / 1000.0
          }
          else -> {
            scaledX = rawX
            scaledY = rawY
            scaledW = rawY.let { rawW }
            scaledH = rawH
          }
        }
      }

      val x = scaledX.roundToInt().coerceIn(0, (sourceWidth - 8).coerceAtLeast(0))
      val y = scaledY.roundToInt().coerceIn(0, (sourceHeight - 7).coerceAtLeast(0))
      val width = scaledW.roundToInt().coerceIn(8, (sourceWidth - x).coerceAtLeast(8))
      val height = scaledH.roundToInt().coerceIn(7, (sourceHeight - y).coerceAtLeast(7))

      return ScreenTranslationOverlay.Region(x, y, width, height, translation, original)
    }

    private fun numberOrNull(value: Any?): Double? =
        when (value) {
          is Number -> value.toDouble().takeIf { it.isFinite() }
          is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
          else -> null
        }

    internal fun stripMarkdownCodeFences(input: String): String {
      val trimmed = input.trim()
      if (trimmed.startsWith("```")) {
        val firstNewline = trimmed.indexOf('\n')
        val body =
            if (firstNewline >= 0) trimmed.substring(firstNewline + 1)
            else trimmed.removePrefix("```")
        return body.removeSuffix("```").trim()
      }
      val firstBrace = trimmed.indexOf('{')
      val lastBrace = trimmed.lastIndexOf('}')
      if (firstBrace >= 0 && lastBrace > firstBrace) {
        return trimmed.substring(firstBrace, lastBrace + 1)
      }
      return trimmed
    }

    private fun extractErrorDetail(body: String): String {
      val root = runCatching { MiniJson.parse(body) as? Map<*, *> }.getOrNull() ?: return ""
      val error = root["error"]
      return when (error) {
        is Map<*, *> -> (error["message"] as? String)?.trim().orEmpty()
        is String -> error.trim()
        else -> ""
      }.take(160)
    }

    internal fun upscaleNearestNeighbor(
        source: IntArray,
        width: Int,
        height: Int,
        factor: Int,
    ): IntArray {
      if (factor == 1) return source.clone()
      val targetWidth = Math.multiplyExact(width, factor)
      val targetHeight = Math.multiplyExact(height, factor)
      val output = IntArray(Math.multiplyExact(targetWidth, targetHeight))
      for (y in 0 until targetHeight) {
        val srcY = y / factor
        val rowOffset = y * targetWidth
        val srcRowOffset = srcY * width
        for (x in 0 until targetWidth) {
          output[rowOffset + x] = source[srcRowOffset + (x / factor)]
        }
      }
      return output
    }

    internal fun encodeRgbPng(width: Int, height: Int, rgb: IntArray): ByteArray {
      require(width > 0 && height > 0) { "PNG dimensions must be positive" }
      require(rgb.size == Math.multiplyExact(width, height)) { "RGB length mismatch" }
      val raw =
          ByteArray(Math.multiplyExact(height, Math.addExact(Math.multiplyExact(width, 3), 1)))
      var offset = 0
      var pixel = 0
      repeat(height) {
        raw[offset++] = 0
        repeat(width) {
          val color = rgb[pixel++]
          raw[offset++] = (color shr 16).toByte()
          raw[offset++] = (color shr 8).toByte()
          raw[offset++] = color.toByte()
        }
      }

      val output = ByteArrayOutputStream()
      output.write(PNG_SIGNATURE)
      writeChunk(
          output,
          IHDR,
          ByteArray(13).also { header ->
            writeInt(header, 0, width)
            writeInt(header, 4, height)
            header[8] = 8
            header[9] = 2
          },
      )
      writeChunk(output, IDAT, deflate(raw))
      writeChunk(output, IEND, ByteArray(0))
      return output.toByteArray()
    }

    private fun deflate(raw: ByteArray): ByteArray {
      val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
      try {
        deflater.setInput(raw)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
          val count = deflater.deflate(buffer)
          output.write(buffer, 0, count)
        }
        return output.toByteArray()
      } finally {
        deflater.end()
      }
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: ByteArray, data: ByteArray) {
      val lengthBytes = ByteArray(4)
      writeInt(lengthBytes, 0, data.size)
      output.write(lengthBytes)
      output.write(type)
      output.write(data)
      val crc = CRC32()
      crc.update(type)
      crc.update(data)
      val crcBytes = ByteArray(4)
      writeInt(crcBytes, 0, crc.value.toInt())
      output.write(crcBytes)
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
      target[offset] = (value ushr 24).toByte()
      target[offset + 1] = (value ushr 16).toByte()
      target[offset + 2] = (value ushr 8).toByte()
      target[offset + 3] = value.toByte()
    }

    internal fun jsonEscape(value: String): String {
      val sb = StringBuilder(value.length + 16)
      sb.append('"')
      for (ch in value) {
        when (ch) {
          '"' -> sb.append("\\\"")
          '\\' -> sb.append("\\\\")
          '\b' -> sb.append("\\b")
          '\u000C' -> sb.append("\\f")
          '\n' -> sb.append("\\n")
          '\r' -> sb.append("\\r")
          '\t' -> sb.append("\\t")
          else -> {
            if (ch.code < 0x20) {
              sb.append(String.format("\\u%04x", ch.code))
            } else {
              sb.append(ch)
            }
          }
        }
      }
      sb.append('"')
      return sb.toString()
    }
  }
}

/** Minimal dependency-free JSON parser for structured Vision LLM responses. */
internal object MiniJson {
  fun parse(input: String): Any? = Parser(input).parseRoot()

  private class Parser(private val src: String) {
    private var pos = 0

    fun parseRoot(): Any? {
      skipWhitespace()
      val value = parseValue()
      skipWhitespace()
      if (pos != src.length) {
        throw IllegalArgumentException("Unexpected trailing characters at position $pos")
      }
      return value
    }

    private fun parseValue(): Any? {
      skipWhitespace()
      if (pos >= src.length) throw IllegalArgumentException("Unexpected end of JSON")
      return when (src[pos]) {
        '{' -> parseObject()
        '[' -> parseArray()
        '"' -> parseString()
        't', 'f' -> parseBoolean()
        'n' -> parseNull()
        '-', in '0'..'9' -> parseNumber()
        else -> throw IllegalArgumentException("Unexpected character '${src[pos]}' at $pos")
      }
    }

    private fun parseObject(): Map<String, Any?> {
      expect('{')
      skipWhitespace()
      val map = LinkedHashMap<String, Any?>()
      if (peek('}')) {
        pos++
        return map
      }
      while (true) {
        skipWhitespace()
        val key = parseString()
        skipWhitespace()
        expect(':')
        val value = parseValue()
        map[key] = value
        skipWhitespace()
        if (peek(',')) {
          pos++
        } else if (peek('}')) {
          pos++
          return map
        } else {
          throw IllegalArgumentException("Expected ',' or '}' at $pos")
        }
      }
    }

    private fun parseArray(): List<Any?> {
      expect('[')
      skipWhitespace()
      val list = ArrayList<Any?>()
      if (peek(']')) {
        pos++
        return list
      }
      while (true) {
        list.add(parseValue())
        skipWhitespace()
        if (peek(',')) {
          pos++
        } else if (peek(']')) {
          pos++
          return list
        } else {
          throw IllegalArgumentException("Expected ',' or ']' at $pos")
        }
      }
    }

    private fun parseString(): String {
      expect('"')
      val sb = StringBuilder()
      while (pos < src.length) {
        val ch = src[pos++]
        when (ch) {
          '"' -> return sb.toString()
          '\\' -> {
            if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
            when (val esc = src[pos++]) {
              '"', '\\', '/' -> sb.append(esc)
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'u' -> {
                if (pos + 4 > src.length) throw IllegalArgumentException("Invalid unicode escape")
                val code = src.substring(pos, pos + 4).toInt(16)
                pos += 4
                sb.append(code.toChar())
              }
              else -> throw IllegalArgumentException("Invalid escape '\\$esc'")
            }
          }
          else -> sb.append(ch)
        }
      }
      throw IllegalArgumentException("Unterminated string literal")
    }

    private fun parseBoolean(): Boolean {
      if (src.startsWith("true", pos)) {
        pos += 4
        return true
      }
      if (src.startsWith("false", pos)) {
        pos += 5
        return false
      }
      throw IllegalArgumentException("Invalid boolean at $pos")
    }

    private fun parseNull(): Any? {
      if (src.startsWith("null", pos)) {
        pos += 4
        return null
      }
      throw IllegalArgumentException("Invalid null at $pos")
    }

    private fun parseNumber(): Number {
      val start = pos
      if (peek('-')) pos++
      while (pos < src.length && src[pos] in '0'..'9') pos++
      var isFloating = false
      if (pos < src.length && src[pos] == '.') {
        isFloating = true
        pos++
        while (pos < src.length && src[pos] in '0'..'9') pos++
      }
      if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
        isFloating = true
        pos++
        if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
        while (pos < src.length && src[pos] in '0'..'9') pos++
      }
      val token = src.substring(start, pos)
      return if (isFloating) token.toDouble() else token.toLongOrNull() ?: token.toDouble()
    }

    private fun skipWhitespace() {
      while (pos < src.length && src[pos].isWhitespace()) {
        pos++
      }
    }

    private fun peek(expected: Char): Boolean = pos < src.length && src[pos] == expected

    private fun expect(expected: Char) {
      if (pos >= src.length || src[pos] != expected) {
        throw IllegalArgumentException("Expected '$expected' at $pos")
      }
      pos++
    }
  }
}
