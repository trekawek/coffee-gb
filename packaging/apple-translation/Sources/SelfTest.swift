import Foundation
import CoreGraphics
import ImageIO

/// Deterministic checks run on macOS CI without invoking Translation or downloading languages.
enum NativeSelfTest {
    private struct Failed: Error {}

    private static func require(_ condition: @autoclosure () throws -> Bool) throws {
        guard try condition() else { throw Failed() }
    }

    private static func requireInvalid(_ operation: () throws -> Void) throws {
        do {
            try operation()
        } catch HelperFailure.invalidRequest {
            return
        }
        throw Failed()
    }

    static func run() throws {
        try require(PixelBounds.fromVision(CGRect(x: 0.25, y: 0.25, width: 0.5, height: 0.5),
                                          width: 160, height: 144)
                    == PixelBounds(x: 40, y: 36, width: 80, height: 72))
        try require(PixelBounds.fromVision(CGRect(x: -0.25, y: 0.5, width: 0.5, height: 0.75),
                                          width: 160, height: 144)
                    == PixelBounds(x: 0, y: 0, width: 40, height: 72))
        try require(PixelBounds.fromVision(CGRect(x: 2, y: 0, width: 1, height: 1),
                                          width: 160, height: 144) == nil)
        try require(PixelBounds.fromVision(CGRect(x: 0, y: 0, width: 0, height: 1),
                                          width: 160, height: 144) == nil)

        let image = try blankPNG()
        let request = ScreenshotRequest(version: 1, image: image.base64EncodedString(), width: 160, height: 144)
        let decoded = try ScreenshotRequest.decode(JSONEncoder().encode(request))
        try require(decoded.decodeImage().width == 160)
        try require(ScreenOCR.recognize(decoded).isEmpty)
        try requireInvalid {
            _ = try ScreenshotRequest.decode(Data("{\"version\":2,\"image\":\"x\",\"width\":160,\"height\":144}".utf8))
        }
        try requireInvalid {
            _ = try ScreenshotRequest.decode(Data("{\"version\":1,\"image\":\"x\",\"width\":513,\"height\":144}".utf8))
        }
        try requireInvalid { _ = try ScreenshotRequest.decode(Data(repeating: 32, count: 2 * 1024 * 1024 + 1)) }
        try requireInvalid {
            _ = try ScreenshotRequest(version: 1, image: "invalid", width: 160, height: 144).decodeImage()
        }
        try requireInvalid {
            _ = try ScreenshotRequest(version: 1, image: image.base64EncodedString(), width: 159, height: 144).decodeImage()
        }

        let bounds = PixelBounds(x: 0, y: 0, width: 160, height: 12)
        let english = RecognizedRegion(text: "The game is paused. Press start to continue.", bounds: bounds)
        try require(SourceLanguage.identify(in: []).map { _ in true } == nil)
        try require(SourceLanguage.identify(in: [english]).map { _ in true } == nil)
        let japanese = try SourceLanguage.identify(in: [
            RecognizedRegion(text: "GB KISS", bounds: bounds),
            RecognizedRegion(text: "メニュー", bounds: bounds),
            RecognizedRegion(text: "受信", bounds: bounds)
        ])
        try require(japanese?.language == .japanese)
        try require(japanese?.regions.map(\.text) == ["メニュー", "受信"])

        let result = HelperEvent(event: "result", regions: [TranslatedRegion(text: "Menu", bounds: bounds)])
        let encoded = try JSONEncoder().encode(result)
        let object = try JSONSerialization.jsonObject(with: encoded) as? [String: Any]
        try require(object?["event"] as? String == "result")
        try require(object?["code"] == nil)
    }

    static func blankPNG() throws -> Data {
        guard let context = CGContext(data: nil, width: 160, height: 144, bitsPerComponent: 8,
                                      bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { throw Failed() }
        context.setFillColor(CGColor(gray: 1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 160, height: 144))
        guard let image = context.makeImage() else { throw Failed() }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil) else {
            throw Failed()
        }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { throw Failed() }
        return data as Data
    }
}
