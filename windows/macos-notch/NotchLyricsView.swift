// Auralis timed rendering in Boring Notch beta's lyrics slot (under the artist).
import AppKit
import SwiftUI

struct NotchLyricPiece: Codable {
    var text: String
    var timeMs: Double?
    var durationMs: Double?
    var background: Bool = false

    func progress(at position: Double) -> Double {
        guard let start = timeMs, start.isFinite, position >= start else { return 0 }
        // Unknown ends have an onset, but never an invented syllable sweep.
        guard let duration = durationMs, duration.isFinite, duration > 0 else { return 1 }
        return min(max((position - start) / duration, 0), 1)
    }
}

struct NotchLyricLine: Codable {
    var timeMs: Double
    var endMs: Double?
    var text: String
    var pieces: [NotchLyricPiece]
    var instrumental: Bool = false
}

struct NotchLyrics: Codable {
    var status: String
    var lines: [NotchLyricLine] = []

    func activeLine(at position: Double) -> NotchLyricLine? {
        guard status == "ready", let line = lines.last(where: { $0.timeMs <= position }),
              line.endMs.map({ position < $0 }) ?? true else { return nil }
        return line
    }
}

// Synthetic timings for the standalone preview only; production always uses Auralis's provider data.
func notchPreviewLyrics(mode: String = "syllable") -> NotchLyrics {
    if mode == "static" {
        return NotchLyrics(status: "static", lines: [NotchLyricLine(timeMs: 0, endMs: nil,
            text: "Make every syllable shine\nKeep the words close", pieces: [])])
    }
    if mode == "word" {
        let words = [NotchLyricPiece(text: "Make ", timeMs: 65000, durationMs: 400),
            NotchLyricPiece(text: "every ", timeMs: 65450, durationMs: 650),
            NotchLyricPiece(text: "syllable ", timeMs: 66300, durationMs: 1000),
            NotchLyricPiece(text: "shine", timeMs: 67500, durationMs: 600)]
        return NotchLyrics(status: "ready", lines: [NotchLyricLine(timeMs: 65000, endMs: 68100,
            text: words.map(\.text).joined(), pieces: words)])
    }
    let pieces = [
        NotchLyricPiece(text: "Make ", timeMs: 65000, durationMs: 400),
        NotchLyricPiece(text: "ev", timeMs: 65450, durationMs: 200),
        NotchLyricPiece(text: "er", timeMs: 65650, durationMs: 200),
        NotchLyricPiece(text: "y ", timeMs: 65850, durationMs: 250),
        NotchLyricPiece(text: "syl", timeMs: 66300, durationMs: 400),
        NotchLyricPiece(text: "la", timeMs: 66800, durationMs: 200),
        NotchLyricPiece(text: "ble ", timeMs: 67000, durationMs: 300),
        NotchLyricPiece(text: "shine", timeMs: 67500, durationMs: 600)
    ]
    return NotchLyrics(status: "ready", lines: [NotchLyricLine(timeMs: 65000, endMs: 68100,
        text: pieces.map(\.text).joined(), pieces: pieces)])
}

func verifyNotchLyrics() {
    let piece = NotchLyricPiece(text: "syl", timeMs: 1000, durationMs: 400)
    precondition(piece.progress(at: 999) == 0 && piece.progress(at: 1200) == 0.5 && piece.progress(at: 1600) == 1)
    let unknown = NotchLyricPiece(text: "unknown", timeMs: 1000, durationMs: nil)
    precondition(unknown.progress(at: 999) == 0 && unknown.progress(at: 1000) == 1)
    let lyrics = notchPreviewLyrics()
    precondition(lyrics.activeLine(at: 64999) == nil && lyrics.activeLine(at: 66500)?.text == "Make every syllable shine")
    precondition(lyrics.activeLine(at: 68100) == nil, "Vocal rests must not keep a stale line active")
    precondition(notchPreviewLyrics(mode: "word").lines.first?.pieces.count == 4)
    precondition(notchPreviewLyrics(mode: "static").lines.first?.text.contains("Keep the words close") == true)
}

struct NotchLyricsView: View {
    @ObservedObject var player = MusicManager.shared
    let width: CGFloat

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60,
            paused: !player.isPlaying || player.isBuffering || player.lyrics?.status != "ready")) { timeline in
            let position = player.estimatedPlaybackPosition(at: timeline.date) * 1000
            if player.lyrics?.status == "static", let text = player.lyrics?.lines.first?.text {
                HStack(spacing: 4) {
                    Image(systemName: "text.alignleft").font(.system(size: 9)).foregroundStyle(.gray)
                    MarqueeText(.constant(text.replacingOccurrences(of: "\n", with: " · ")),
                        font: .subheadline, nsFont: .subheadline, textColor: .gray, frameWidth: max(0, width - 16))
                }
                .help("Unsynced lyrics")
            } else if let payload = player.lyrics, let line = payload.activeLine(at: position), !line.instrumental {
                if let frame = player.lyricFrame, player.lyricFrameText == line.text {
                    Image(nsImage: frame).resizable().interpolation(.high)
                        .frame(width: width, height: 22)
                        .accessibilityLabel(line.text)
                } else {
                    Text(line.text).font(.subheadline).foregroundStyle(.gray).lineLimit(1)
                        .frame(width: width, alignment: .leading)
                }
            } else {
                Text(message).font(.subheadline).foregroundStyle(.gray)
                    .frame(width: width, height: 22, alignment: .leading)
            }
        }
        .frame(height: 22)
        .onAppear { reportWidth() }
        .onChange(of: width) { _, _ in reportWidth() }
    }

    private func reportWidth() {
        let event: [String: Any] = ["version": 1, "type": "lyricsSize", "width": width]
        if let data = try? JSONSerialization.data(withJSONObject: event) {
            FileHandle.standardOutput.write(data + Data([10]))
        }
    }

    private var message: String {
        switch player.lyrics?.status {
        case "loading": return "Loading lyrics…"
        case "unsynced": return "Synced lyrics unavailable"
        case "ready": return "♪"
        default: return "No lyrics found"
        }
    }
}

