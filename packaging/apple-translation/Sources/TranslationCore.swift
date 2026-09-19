import Foundation
import CoreGraphics
import ImageIO
import NaturalLanguage
import Vision

enum HelperFailure: String, Error {
    case invalidRequest = "invalid_request"
    case unsupportedLanguage = "unsupported_language"
    case languageDetection = "language_detection"
    case cancelled
    case downloadFailed = "download_failed"
    case translationFailed = "translation_failed"
}

struct ScreenshotRequest: Codable {
    let version: Int
    let image: String
    let width: Int
    let height: Int

    static let maximumRequestBytes = 2 * 1024 * 1024

    static func decode(_ data: Data) throws -> ScreenshotRequest {
        guard data.count <= maximumRequestBytes,
              let request = try? JSONDecoder().decode(Self.self, from: data),
              request.version == 1,
              (1...512).contains(request.width), (1...512).contains(request.height),
              !request.image.isEmpty else {
            throw HelperFailure.invalidRequest
        }
        return request
    }

    func decodeImage() throws -> CGImage {
        guard let data = Data(base64Encoded: image), data.count <= 1024 * 1024,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetType(source) as String? == "public.png",
              CGImageSourceGetCount(source) == 1,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              properties[kCGImagePropertyPixelWidth] as? Int == width,
              properties[kCGImagePropertyPixelHeight] as? Int == height,
              let decoded = CGImageSourceCreateImageAtIndex(source, 0, nil),
              decoded.width == width, decoded.height == height else {
            throw HelperFailure.invalidRequest
        }
        return decoded
    }

    /// Read one bounded JSON line without waiting for the parent to close stdin.
    static func read(from input: FileHandle) throws -> ScreenshotRequest {
        var data = Data()
        while true {
            let chunk = try input.read(upToCount: 4096) ?? Data()
            if chunk.isEmpty {
                return try decode(data)
            }
            if let newline = chunk.firstIndex(of: 10) {
                data.append(chunk[..<newline])
                return try decode(data)
            }
            data.append(chunk)
            guard data.count <= maximumRequestBytes else { throw HelperFailure.invalidRequest }
        }
    }
}

struct PixelBounds: Equatable {
    let x: Int
    let y: Int
    let width: Int
    let height: Int

    /// Vision uses normalized lower-left coordinates. Overlays use native top-left pixels.
    static func fromVision(_ box: CGRect, width: Int, height: Int) -> PixelBounds? {
        guard width > 0, height > 0,
              box.origin.x.isFinite, box.origin.y.isFinite,
              box.size.width.isFinite, box.size.height.isFinite,
              box.width > 0, box.height > 0 else { return nil }
        let clipped = box.intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
        guard !clipped.isNull, !clipped.isEmpty else { return nil }
        let left = max(0, min(width, Int(floor(clipped.minX * CGFloat(width)))))
        let top = max(0, min(height, Int(floor((1 - clipped.maxY) * CGFloat(height)))))
        let right = max(0, min(width, Int(ceil(clipped.maxX * CGFloat(width)))))
        let bottom = max(0, min(height, Int(ceil((1 - clipped.minY) * CGFloat(height)))))
        guard right > left, bottom > top else { return nil }
        return PixelBounds(x: left, y: top, width: right - left, height: bottom - top)
    }
}

struct RecognizedRegion {
    let text: String
    let bounds: PixelBounds
}

struct TranslatedRegion: Codable {
    let text: String
    let x: Int
    let y: Int
    let width: Int
    let height: Int

    init(text: String, bounds: PixelBounds) {
        self.text = text
        x = bounds.x
        y = bounds.y
        width = bounds.width
        height = bounds.height
    }
}

struct HelperEvent: Encodable {
    let event: String
    var regions: [TranslatedRegion]? = nil
    var code: String? = nil
    var ok: Bool? = nil

    func write() {
        // Never send diagnostic descriptions, source text, or screenshot data to stdout/stderr.
        guard var data = try? JSONEncoder().encode(self) else { return }
        data.append(10)
        try? FileHandle.standardOutput.write(contentsOf: data)
    }
}

enum ScreenOCR {
    static func recognize(_ request: ScreenshotRequest) throws -> [RecognizedRegion] {
        let original = try request.decodeImage()
        let scale = 4
        guard let context = CGContext(data: nil, width: original.width * scale,
                                      height: original.height * scale, bitsPerComponent: 8,
                                      bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
            throw HelperFailure.translationFailed
        }
        context.interpolationQuality = .none
        context.draw(original, in: CGRect(x: 0, y: 0, width: CGFloat(context.width), height: CGFloat(context.height)))
        guard let enlarged = context.makeImage() else { throw HelperFailure.translationFailed }

        let recognition = VNRecognizeTextRequest()
        recognition.recognitionLevel = .accurate
        recognition.usesLanguageCorrection = false
        recognition.automaticallyDetectsLanguage = true
        let supported = try recognition.supportedRecognitionLanguages()
        // Prefer the scripts common in imported games, retaining all other supported languages.
        let preferred = ["ja-JP", "zh-Hans", "zh-Hant", "ko-KR", "en-US"]
        recognition.recognitionLanguages = preferred.filter(supported.contains)
            + supported.filter { !preferred.contains($0) }
        try VNImageRequestHandler(cgImage: enlarged, options: [:]).perform([recognition])

        return (recognition.results ?? []).compactMap { observation in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            let text = candidate.string.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty, text.count <= 2048,
                  text.unicodeScalars.contains(where: { CharacterSet.letters.contains($0) }),
                  let bounds = PixelBounds.fromVision(observation.boundingBox,
                                                      width: request.width, height: request.height) else {
                return nil
            }
            return RecognizedRegion(text: text, bounds: bounds)
        }.sorted {
            $0.bounds.y == $1.bounds.y ? $0.bounds.x < $1.bounds.x : $0.bounds.y < $1.bounds.y
        }.prefix(64).map { $0 }
    }
}

struct TranslationInput {
    let language: NLLanguage
    let regions: [RecognizedRegion]
}

enum SourceLanguage {
    static func identify(in regions: [RecognizedRegion]) throws -> TranslationInput? {
        guard !regions.isEmpty else { return nil }
        let text = regions.map(\.text).joined(separator: "\n")
        let nonLatinText = regions.map(\.text).filter(containsNonLatinLetters).joined(separator: "\n")
        let recognizer = NLLanguageRecognizer()
        // Imported games often surround Japanese text with large English titles. Script-bearing
        // lines provide better evidence than the title, especially for short kanji button labels.
        recognizer.processString(nonLatinText.isEmpty ? text : nonLatinText)
        let language: NLLanguage
        if containsKana(text) {
            language = .japanese
        } else if let detected = recognizer.dominantLanguage, detected != .undetermined {
            language = detected
        } else {
            throw HelperFailure.languageDetection
        }
        guard language != .english else { return nil }

        let candidates = regions.filter { region in
            if containsNonLatinLetters(region.text) { return true }
            let lineRecognizer = NLLanguageRecognizer()
            lineRecognizer.processString(region.text)
            let englishConfidence = lineRecognizer.languageHypotheses(withMaximum: 3)[.english] ?? 0
            // Japanese/Chinese/Korean screens frequently use English names and ASCII UI labels.
            // Those labels need no overlay; ambiguous Latin text in a Latin-language game stays.
            if [.japanese, .simplifiedChinese, .traditionalChinese, .korean].contains(language) {
                return false
            }
            return englishConfidence < 0.8
        }
        return candidates.isEmpty ? nil : TranslationInput(language: language, regions: candidates)
    }

    static func containsKana(_ text: String) -> Bool {
        text.unicodeScalars.contains {
            (0x3040...0x30FF).contains($0.value) || (0xFF66...0xFF9D).contains($0.value)
        }
    }

    static func containsNonLatinLetters(_ text: String) -> Bool {
        text.unicodeScalars.contains {
            CharacterSet.letters.contains($0) && $0.value > 0x024F
        }
    }
}
