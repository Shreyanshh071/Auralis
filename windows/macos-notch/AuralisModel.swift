// Auralis's private stdio connection. No system Now Playing, scripting, or media scraping.
import AppKit
import Combine
import SwiftUI

// Explicit property-wrapper alias avoids SDK 27’s optional Xcode-only @State macro.
typealias NotchStateValue<Value> = SwiftUI.State<Value>

struct PlayerSnapshot: Codable {
    var version: Int = 1
    var type: String = "state"
    var visible: Bool = false
    var trackId: String = ""
    var title: String = ""
    var artist: String = ""
    var artwork: String = ""
    var playing: Bool = false
    var buffering: Bool = false
    var positionMs: Double = 0
    var durationMs: Double = 0
    var canPrevious: Bool = false
    var canNext: Bool = false
    var lyrics: NotchLyrics? = nil
    var playbackRate: Double? = nil
}

struct LyricsFrame: Decodable {
    var version: Int
    var type: String
    var trackId: String
    var text: String
    var png: String
}

struct PlayerCommand: Encodable {
    let version = 1
    let type = "command"
    let action: String
    let trackId: String
    var positionMs: Double? = nil
}

@MainActor final class MusicManager: ObservableObject {
    static let shared = MusicManager()
    @Published var songTitle = ""
    @Published var artistName = ""
    @Published var albumArt = NSImage(systemSymbolName: "music.note", accessibilityDescription: "Album artwork")!
    @Published var avgColor = NSColor.systemPurple
    @Published var isPlaying = false
    @Published var isVisible = false
    @Published var isBuffering = false
    @Published var songDuration = 0.0
    @Published var elapsedTime = 0.0
    @Published var timestampDate = Date()
    @Published var canPrevious = false
    @Published var canNext = false
    @Published var lyrics: NotchLyrics? = nil
    @Published var lyricFrame: NSImage? = nil
    var lyricFrameText = ""
    func applyFrame(_ frame: LyricsFrame) {
        guard frame.trackId == trackId, frame.png.count <= 110_000,
              let data = Data(base64Encoded: frame.png), let image = NSImage(data: data) else { return }
        lyricFrameText = frame.text
        lyricFrame = image
    }
    private var rate = 1.0
    var playbackRate: Double { isPlaying && !isBuffering ? rate : 0 }
    let bundleIdentifier: String? = "com.auralis.music"
    var usingAppIconForArtwork = false
    private(set) var trackId = ""
    private var artworkURL = ""
    private var artworkTask: URLSessionDataTask?

    func apply(_ state: PlayerSnapshot) {
        let changedTrack = trackId != state.trackId
        if changedTrack { lyricFrame = nil; lyricFrameText = "" }
        trackId = state.trackId
        songTitle = state.title
        artistName = state.artist
        isPlaying = state.playing
        isVisible = state.visible
        isBuffering = state.buffering
        songDuration = max(0, state.durationMs / 1000)
        elapsedTime = min(max(0, state.positionMs / 1000), songDuration)
        timestampDate = Date()
        canPrevious = state.canPrevious
        canNext = state.canNext
        lyrics = state.lyrics
        rate = state.playbackRate.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil } ?? 1
        if changedTrack || artworkURL != state.artwork {
            artworkURL = state.artwork
            artworkTask?.cancel()
            if changedTrack {
                albumArt = AuralisArtwork.placeholder
                avgColor = .systemPurple
            }
            guard let url = URL(string: artworkURL), ["https", "http"].contains(url.scheme?.lowercased() ?? "") else { return }
            let requested = artworkURL
            artworkTask = URLSession.shared.dataTask(with: url) { data, response, _ in
                guard let response = response as? HTTPURLResponse, response.statusCode == 200,
                      let data, data.count <= 8 * 1024 * 1024, let image = NSImage(data: data) else { return }
                DispatchQueue.main.async {
                    guard self.artworkURL == requested else { return }
                    withAnimation(.easeInOut(duration: 0.28)) { self.albumArt = image }
                    self.avgColor = AuralisArtwork.averageColor(image)
                }
            }
            artworkTask?.resume()
        }
    }

    func estimatedPlaybackPosition(at date: Date) -> Double {
        min(max(elapsedTime + max(0, date.timeIntervalSince(timestampDate)) * playbackRate, 0), songDuration)
    }

    func command(_ action: String, position: Double? = nil) {
        let event = PlayerCommand(action: action, trackId: trackId, positionMs: position.map { min(max(0, $0), songDuration) * 1000 })
        guard let data = try? JSONEncoder().encode(event) else { return }
        FileHandle.standardOutput.write(data + Data([10]))
    }
    func openMusicApp() { command("openPlayer") }
    func togglePlay() { command("playPause") }
    func previousTrack() { command("previous") }
    func nextTrack() { command("next") }
    func seek(to seconds: Double) {
        elapsedTime = min(max(0, seconds), songDuration)
        timestampDate = Date()
        command("seek", position: elapsedTime)
    }
}

enum AuralisArtwork {
    static var appIcon: NSImage {
        if let image = NSImage(contentsOf: Bundle.main.resourceURL!.appendingPathComponent("Auralis.icns")) { return image }
        return NSImage(systemSymbolName: "music.note", accessibilityDescription: "Auralis")!
    }
    static var placeholder: NSImage { appIcon }
    static func averageColor(_ image: NSImage) -> NSColor {
        guard let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil),
              let context = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return .systemPurple }
        context.draw(cg, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        guard let bytes = context.data?.assumingMemoryBound(to: UInt8.self) else { return .systemPurple }
        return NSColor(srgbRed: CGFloat(bytes[0]) / 255, green: CGFloat(bytes[1]) / 255, blue: CGFloat(bytes[2]) / 255, alpha: 1)
    }
}

// Kept as an Image-producing function to preserve upstream's artwork-overlay composition.
@MainActor func AppIcon(for bundle: String) -> Image { Image(nsImage: AuralisArtwork.appIcon) }

// Adapted from upstream extensions/NSImage+Extensions.swift (Richard Kunkli, GPL-3.0).
extension Color {
    func ensureMinimumBrightness(factor: CGFloat) -> Color {
        guard let rgb = NSColor(self).usingColorSpace(.sRGB) else { return self }
        let brightness = 0.2126 * rgb.redComponent + 0.7152 * rgb.greenComponent + 0.0722 * rgb.blueComponent
        guard brightness > 0 else { return Color(white: factor) }
        let scale = factor / brightness
        return Color(red: Double(min(rgb.redComponent * scale, 1)), green: Double(min(rgb.greenComponent * scale, 1)),
                     blue: Double(min(rgb.blueComponent * scale, 1)), opacity: Double(rgb.alphaComponent))
    }
}
