// Adapted from Boring Notch v2.7.3 ContentView.swift, BoringHeader.swift and sizing/matters.swift.
// Harsh Vardhan Goswami, Richard Kunkli and contributors. GPL-3.0.
// Auralis adaptation, 2026-11-11: music only; removes shelf, calendar, camera, HUD and feature settings.
import AppKit
import SwiftUI

enum MusicPlayerImageSizes {
    static let cornerRadiusInset: (opened: CGFloat, closed: CGFloat) = (13, 4)
}
enum NotchState { case open, closed }
let notchCanvasHeight: CGFloat = 238

@MainActor final class BoringViewModel: ObservableObject {
    @Published var notchState: NotchState = .closed {
        didSet {
            let event: [String: Any] = ["version": 1, "type": "lyricsViewport", "expanded": notchState == .open]
            if let data = try? JSONSerialization.data(withJSONObject: event) {
                FileHandle.standardOutput.write(data + Data([10]))
            }
        }
    }
    @Published var closedNotchSize = CGSize(width: 185, height: 32)
    @Published var openWidth: CGFloat = 640
    @Published var isHovering = false
    @Published var lyricRowVisible = false
    private var hoverTask: Task<Void, Never>?
    var effectiveClosedNotchHeight: CGFloat { closedNotchSize.height }
    var contentWidth: CGFloat {
        notchState == .open ? openWidth : closedNotchSize.width + 2 * max(0, effectiveClosedNotchHeight - 12) + 38
    }
    var contentHeight: CGFloat { notchState == .open ? 190 + (lyricRowVisible ? 28 : 0) : effectiveClosedNotchHeight }

    func open() {
        withAnimation(.spring(response: 0.42, dampingFraction: 0.8, blendDuration: 0)) { notchState = .open }
    }
    func close() {
        withAnimation(.spring(response: 0.45, dampingFraction: 1, blendDuration: 0)) { notchState = .closed }
    }
    func reset() {
        hoverTask?.cancel()
        isHovering = false
        notchState = .closed
    }
    func hover(_ hovering: Bool) {
        guard hovering != isHovering else { return }
        hoverTask?.cancel()
        withAnimation(.interactiveSpring(response: 0.38, dampingFraction: 0.8, blendDuration: 0)) { isHovering = hovering }
        hoverTask = Task { @MainActor in
            try? await Task.sleep(for: hovering ? .milliseconds(300) : .milliseconds(100))
            guard !Task.isCancelled else { return }
            if hovering && isHovering { open() }
            else if !isHovering { close() }
        }
    }
}

struct AuralisNotchView: View {
    @EnvironmentObject var vm: BoringViewModel
    @ObservedObject var musicManager = MusicManager.shared
    @Namespace var albumArtNamespace

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading) {
                if vm.notchState == .closed {
                    musicLiveActivity
                } else {
                    header.frame(height: max(24, vm.effectiveClosedNotchHeight))
                    MusicPlayerView(albumArtNamespace: albumArtNamespace)
                        .transition(.scale(scale: 0.8, anchor: .top).combined(with: .opacity)
                            .animation(.smooth(duration: 0.35)))
                        .zIndex(1)
                }
            }
            .padding(.horizontal, vm.notchState == .open ? 19 : 14)
            .padding([.horizontal, .bottom], vm.notchState == .open ? 12 : 0)
            .frame(width: vm.contentWidth, height: vm.contentHeight, alignment: .top)
            .background(.black)
            .clipShape(NotchShape(topCornerRadius: vm.notchState == .open ? 19 : 6,
                                  bottomCornerRadius: vm.notchState == .open ? 24 : 14))
            .overlay(alignment: .top) {
                Rectangle().fill(.black).frame(height: 1).padding(.horizontal, vm.notchState == .open ? 19 : 6)
            }
            .shadow(color: vm.notchState == .open || vm.isHovering ? .black.opacity(0.7) : .clear, radius: 6)
            .contentShape(Rectangle())
            .onTapGesture { vm.open() }
            .simultaneousGesture(DragGesture(minimumDistance: 20).onEnded { gesture in
                if gesture.translation.height < -20 { vm.close() }
                else if gesture.translation.height > 20 { vm.open() }
            })
            .contextMenu {
                Button("Open Auralis", action: musicManager.openMusicApp)
                Button("Auralis settings") { musicManager.command("openSettings") }
                Divider()
                Button("About this notch") { showNotchCredits() }
            }
        }
        .padding(.bottom, 8)
        .frame(width: vm.openWidth, height: notchCanvasHeight, alignment: .top)
        .compositingGroup()
        .preferredColorScheme(.dark)
    }

    private var header: some View {
        HStack(spacing: 0) {
            Spacer().frame(maxWidth: .infinity)
            Rectangle().fill(.black).frame(width: vm.closedNotchSize.width).mask { NotchShape() }
            HStack(spacing: 4) {
                Button { musicManager.command("openSettings") } label: {
                    Capsule().fill(.black).frame(width: 30, height: 30)
                        .overlay { Image(systemName: "gear").foregroundColor(.white).padding().imageScale(.medium) }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Auralis settings")
            }
            .font(.system(.headline, design: .rounded))
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .foregroundColor(.gray)
    }

    private var musicLiveActivity: some View {
        HStack {
            Image(nsImage: musicManager.albumArt).resizable().clipped()
                .clipShape(RoundedRectangle(cornerRadius: MusicPlayerImageSizes.cornerRadiusInset.closed))
                .matchedGeometryEffect(id: "albumArt", in: albumArtNamespace)
                .frame(width: max(0, vm.effectiveClosedNotchHeight - 12), height: max(0, vm.effectiveClosedNotchHeight - 12))
            Rectangle().fill(.black).frame(width: vm.closedNotchSize.width - 6)
            Rectangle().fill(Color(nsColor: musicManager.avgColor).gradient)
                .frame(width: 50, alignment: .center)
                .matchedGeometryEffect(id: "spectrum", in: albumArtNamespace)
                .mask { AudioSpectrumView(isPlaying: $musicManager.isPlaying).frame(width: 16, height: 12) }
                .frame(width: max(0, vm.effectiveClosedNotchHeight - 12), height: max(0, vm.effectiveClosedNotchHeight - 12))
        }
        .frame(height: vm.effectiveClosedNotchHeight, alignment: .center)
    }
}

@MainActor private func showNotchCredits() {
    let alert = NSAlert()
    alert.messageText = "Auralis Notch"
    alert.informativeText = "Music interface adapted from Boring Notch 2.7.3 by TheBoredTeam and contributors.\n\nGNU GPL v3. Modified for Auralis on 11 November 2026. No warranty. You may modify and redistribute it under the GPL. Source and build instructions are included in this app."
    alert.addButton(withTitle: "OK")
    alert.addButton(withTitle: "Source & licenses")
    if alert.runModal() == .alertSecondButtonReturn,
       let url = Bundle.main.resourceURL?.appendingPathComponent("Source") { NSWorkspace.shared.open(url) }
}
