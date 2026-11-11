// Adapted from Boring Notch v2.7.3 NotchHomeView.swift, GPL-3.0.
// Hugo Persson; Harsh Vardhan Goswami; Richard Kunkli; Mustafa Ramadan.
// Auralis adaptation, 2026-11-11: fixed music controls, direct Auralis state, no feature settings.
import SwiftUI

struct MusicControlsView: View {
    @ObservedObject var musicManager = MusicManager.shared
    @NotchStateValue private var sliderValue: Double = 0
    @NotchStateValue private var dragging = false
    @NotchStateValue private var lastDragged: Date = .distantPast

    init() { _sliderValue = NotchStateValue(initialValue: MusicManager.shared.elapsedTime) }

    var body: some View {
        VStack(alignment: .leading) {
            GeometryReader { geo in
                VStack(alignment: .leading, spacing: 4) {
                    VStack(alignment: .leading, spacing: 0) {
                        MarqueeText($musicManager.songTitle, font: .headline, nsFont: .headline,
                            textColor: .white, frameWidth: geo.size.width)
                        MarqueeText($musicManager.artistName, font: .headline, nsFont: .headline,
                            textColor: Color(nsColor: musicManager.avgColor).ensureMinimumBrightness(factor: 0.6),
                            frameWidth: geo.size.width)
                            .fontWeight(.medium)
                    }
                    TimelineView(.animation(minimumInterval: 0.1)) { timeline in
                        MusicSliderView(sliderValue: $sliderValue, duration: $musicManager.songDuration,
                            lastDragged: $lastDragged, color: musicManager.avgColor, dragging: $dragging,
                            currentDate: timeline.date, timestampDate: musicManager.timestampDate,
                            elapsedTime: musicManager.elapsedTime, playbackRate: musicManager.playbackRate,
                            isPlaying: musicManager.isPlaying) { musicManager.seek(to: $0) }
                            .padding(.top, 5)
                            .frame(height: 36)
                            .disabled(musicManager.songDuration <= 0)
                    }
                }
            }
            .padding(.top, 10)
            .padding(.leading, 5)
            HStack(spacing: 6) {
                Color.clear.frame(height: 1)
                HoverButton(icon: "backward.fill", scale: .medium, action: musicManager.previousTrack)
                    .disabled(!musicManager.canPrevious).opacity(musicManager.canPrevious ? 1 : 0.35)
                    .accessibilityLabel("Previous")
                HoverButton(icon: musicManager.isPlaying || musicManager.isBuffering ? "pause.fill" : "play.fill",
                    scale: .large, action: musicManager.togglePlay)
                    .accessibilityLabel(musicManager.isPlaying || musicManager.isBuffering ? "Pause" : "Play")
                HoverButton(icon: "forward.fill", scale: .medium, action: musicManager.nextTrack)
                    .disabled(!musicManager.canNext).opacity(musicManager.canNext ? 1 : 0.35)
                    .accessibilityLabel("Next")
                Color.clear.frame(height: 1)
            }
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .buttonStyle(.plain)
    }
}
