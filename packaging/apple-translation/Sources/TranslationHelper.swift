import AppKit
import Foundation
import SwiftUI
import Translation

@MainActor
final class TranslationModel: ObservableObject {
    @Published var configuration: TranslationSession.Configuration?
    @Published var languageName = "the source language"
    @Published var preparing = true
    var input: TranslationInput?
    var needsDownload = false
    var sessionSelfTest = false
    weak var owner: HelperDelegate?
    private var sessionStarted = false

    func run(_ session: TranslationSession) async {
        guard !sessionStarted else { return }
        sessionStarted = true
        if sessionSelfTest {
            // Validate that the real hidden SwiftUI host provides a session, without invoking
            // any methods that translate text or ask the system to download language models.
            owner?.finish(HelperEvent(event: "self_test", ok: true))
            return
        }
        guard let input else {
            owner?.fail(.translationFailed)
            return
        }
        let source = Locale.Language(identifier: input.language.rawValue)
        let target = Locale.Language(identifier: "en")
        do {
            if needsDownload {
                // This invokes Apple's permission sheet. It can return while a download that
                // another app started is still running, so keep setup active until installed.
                try await session.prepareTranslation()
                let availability = LanguageAvailability()
                while await availability.status(from: source, to: target) != .installed {
                    try Task.checkCancellation()
                    try await Task.sleep(nanoseconds: 250_000_000)
                }
                owner?.finishedSetup()
            }
            preparing = false
            let requests = input.regions.enumerated().map { index, region in
                TranslationSession.Request(sourceText: region.text, clientIdentifier: String(index))
            }
            let responses = try await session.translations(from: requests)
            var translations: [Int: TranslatedRegion] = [:]
            for response in responses {
                guard let identifier = response.clientIdentifier,
                      let index = Int(identifier), input.regions.indices.contains(index),
                      translations[index] == nil else { throw HelperFailure.translationFailed }
                let text = response.targetText.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty, text.count <= 2048 else { throw HelperFailure.translationFailed }
                translations[index] = TranslatedRegion(text: text, bounds: input.regions[index].bounds)
            }
            guard translations.count == requests.count else { throw HelperFailure.translationFailed }
            owner?.finish(HelperEvent(event: "result", regions: translations.keys.sorted().compactMap {
                translations[$0]
            }))
        } catch is CancellationError {
            owner?.fail(.cancelled)
        } catch TranslationError.unsupportedSourceLanguage {
            owner?.fail(.unsupportedLanguage)
        } catch TranslationError.unsupportedTargetLanguage {
            owner?.fail(.unsupportedLanguage)
        } catch TranslationError.unsupportedLanguagePairing {
            owner?.fail(.unsupportedLanguage)
        } catch TranslationError.unableToIdentifyLanguage {
            owner?.fail(.languageDetection)
        } catch {
            if (error as NSError).domain == NSCocoaErrorDomain,
               (error as NSError).code == NSUserCancelledError {
                owner?.fail(.cancelled)
            } else {
                owner?.fail(needsDownload && preparing ? .downloadFailed : .translationFailed)
            }
        }
    }
}

struct TranslationWindow: View {
    @ObservedObject var model: TranslationModel

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(model.preparing ? "Set up game translation" : "Translating game screen").font(.title2).bold()
            Text(model.preparing
                 ? "Allow macOS to download \(model.languageName) and English for local translation."
                 : "The languages are ready. Your translation will appear over the game.")
            Text("Your game screen stays on this Mac.")
                .foregroundStyle(.secondary)
            HStack {
                ProgressView().controlSize(.small)
                Text(model.preparing ? "Preparing languages…" : "Translating…")
                Spacer()
                Button("Cancel") { model.owner?.fail(.cancelled) }
                    .keyboardShortcut(.cancelAction)
            }
        }
        .padding(24)
        .frame(width: 420)
        .translationTask(model.configuration) { session in
            await model.run(session)
        }
    }
}

@MainActor
final class HelperDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private let model = TranslationModel()
    private let sessionSelfTest: Bool
    private var window: NSWindow?
    private var parentMonitor: DispatchSourceProcess?
    private var watchdog: Task<Void, Never>?
    private var complete = false

    init(sessionSelfTest: Bool = false) {
        self.sessionSelfTest = sessionSelfTest
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        model.owner = self
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 468, height: 230),
                              styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Coffee GB Translation"
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.center()
        window.contentView = NSHostingView(rootView: TranslationWindow(model: model))
        // macOS 15 sessions must belong to a live SwiftUI view. Keep its host ordered in,
        // transparent and noninteractive until permission/download UI is actually necessary.
        // Ordering it out during a translation invalidates the session's view lifetime.
        window.alphaValue = 0
        window.ignoresMouseEvents = true
        window.orderFront(nil)
        self.window = window

        let parent = getppid()
        if parent <= 1 {
            fail(.cancelled)
            return
        }
        let monitor = DispatchSource.makeProcessSource(identifier: parent, eventMask: .exit, queue: .main)
        monitor.setEventHandler { [weak self] in
            Task { @MainActor in self?.fail(.cancelled) }
        }
        monitor.resume()
        parentMonitor = monitor
        watchdog = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 16 * 60 * 1_000_000_000)
            if !Task.isCancelled { self?.fail(.cancelled) }
        }

        if sessionSelfTest {
            model.sessionSelfTest = true
            model.configuration = TranslationSession.Configuration(
                source: Locale.Language(identifier: "ja"), target: Locale.Language(identifier: "en"))
            return
        }

        Task { [weak self] in
            do {
                // OCR and pipe reads must not block the UI or Apple's permission sheet.
                let input = try await Task.detached(priority: .userInitiated) {
                    let request = try ScreenshotRequest.read(from: .standardInput)
                    return try SourceLanguage.identify(in: ScreenOCR.recognize(request))
                }.value
                guard let self, !self.complete else { return }
                guard let input else {
                    self.finish(HelperEvent(event: "result", regions: []))
                    return
                }
                await self.prepare(input)
            } catch let error as HelperFailure {
                self?.fail(error)
            } catch {
                self?.fail(.translationFailed)
            }
        }
    }

    private func prepare(_ input: TranslationInput) async {
        let source = Locale.Language(identifier: input.language.rawValue)
        let target = Locale.Language(identifier: "en")
        let status = await LanguageAvailability().status(from: source, to: target)
        guard !complete else { return }
        switch status {
        case .unsupported:
            fail(.unsupportedLanguage)
            return
        case .supported:
            model.needsDownload = true
            HelperEvent(event: "setup").write()
            window?.alphaValue = 1
            window?.ignoresMouseEvents = false
            window?.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
        case .installed:
            break
        @unknown default:
            fail(.unsupportedLanguage)
            return
        }
        model.input = input
        model.languageName = Locale.current.localizedString(forLanguageCode: input.language.rawValue)
            ?? input.language.rawValue
        model.configuration = TranslationSession.Configuration(source: source, target: target)
    }

    func finishedSetup() {
        guard !complete else { return }
        HelperEvent(event: "translating").write()
        // Keep the setup window visible and cancellable until this first translation finishes.
        // Subsequent requests start with installed languages and never activate the helper.
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        fail(.cancelled)
        return false
    }

    func fail(_ error: HelperFailure) {
        finish(HelperEvent(event: "error", code: error.rawValue))
    }

    func finish(_ event: HelperEvent) {
        guard !complete else { return }
        complete = true
        event.write()
        watchdog?.cancel()
        parentMonitor?.cancel()
        NSApp.terminate(nil)
    }
}

@main
struct TranslationHelper {
    @MainActor static func main() {
        if CommandLine.arguments.count == 2 && CommandLine.arguments[1] == "--self-test" {
            do {
                try NativeSelfTest.run()
                HelperEvent(event: "self_test", ok: true).write()
                exit(0)
            } catch {
                HelperEvent(event: "self_test", ok: false).write()
                exit(1)
            }
        }
        let sessionSelfTest = CommandLine.arguments.count == 2
            && CommandLine.arguments[1] == "--session-self-test"
        guard CommandLine.arguments.count == 1 || sessionSelfTest else {
            HelperEvent(event: "error", code: HelperFailure.invalidRequest.rawValue).write()
            exit(2)
        }
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        let delegate = HelperDelegate(sessionSelfTest: sessionSelfTest)
        app.delegate = delegate
        withExtendedLifetime(delegate) { app.run() }
    }
}
