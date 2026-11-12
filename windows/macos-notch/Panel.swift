// Panel configuration adapted from Boring Notch 2.7.3 BoringNotchWindow.swift.
// Harsh Vardhan Goswami and contributors, GPL-3.0. Modified for Auralis, 2026-11-11.
import AppKit
import SwiftUI

final class AuralisNotchPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 640, height: notchCanvasHeight),
                   styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        isFloatingPanel = true
        isOpaque = false
        title = "Auralis Notch"
        titleVisibility = .hidden
        titlebarAppearsTransparent = true
        backgroundColor = .clear
        isMovable = false
        collectionBehavior = [.fullScreenAuxiliary, .stationary, .canJoinAllSpaces, .ignoresCycle]
        isReleasedWhenClosed = false
        level = .mainMenu + 3
        hasShadow = false
        hidesOnDeactivate = false
        acceptsMouseMovedEvents = true
        ignoresMouseEvents = true
        animationBehavior = .none
    }
}

@MainActor final class NotchApplication: NSObject, NSApplicationDelegate {
    let vm = BoringViewModel()
    let player = MusicManager.shared
    private var panel: AuralisNotchPanel?
    private var pointerTimer: Timer?
    private var lastUpdate = Date()
    private var screen: NSScreen?
    private var locked = false
    private let preview: Bool
    private let originalParent = getppid()
    init(preview: Bool = false) { self.preview = preview }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        let panel = AuralisNotchPanel()
        panel.contentView = NSHostingView(rootView: AuralisNotchView().environmentObject(vm))
        self.panel = panel
        positionPanel()
        NotificationCenter.default.addObserver(self, selector: #selector(displaysChanged),
            name: NSApplication.didChangeScreenParametersNotification, object: nil)
        DistributedNotificationCenter.default().addObserver(self, selector: #selector(screenLocked),
            name: NSNotification.Name("com.apple.screenIsLocked"), object: nil)
        DistributedNotificationCenter.default().addObserver(self, selector: #selector(screenUnlocked),
            name: NSNotification.Name("com.apple.screenIsUnlocked"), object: nil)
        pointerTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        if preview {
            var state = PlayerSnapshot()
            state.visible = true; state.title = "Auralis"; state.artist = "Your music, in the notch"
            state.trackId = "preview"; state.playing = true; state.durationMs = 240_000; state.positionMs = 65_000
            state.canPrevious = true; state.canNext = true
            state.positionMs = 66_500; state.lyrics = notchPreviewLyrics()
            apply(state)
            player.albumArt = AuralisArtwork.appIcon
            vm.open()
        } else {
            readInput()
        }
    }

    func apply(_ state: PlayerSnapshot) {
        lastUpdate = Date()
        player.apply(state)
        vm.lyricRowVisible = state.lyrics != nil
        if state.visible && !locked {
            if panel?.isVisible != true { positionPanel(); vm.reset(); panel?.orderFrontRegardless() }
        } else { hide() }
    }

    private func hide() {
        panel?.orderOut(nil)
        panel?.ignoresMouseEvents = true
        player.isPlaying = false // Stop the decorative spectrum when the window is hidden.
        vm.reset()
    }

    private func tick() {
        if !preview && (getppid() != originalParent || Date().timeIntervalSince(lastUpdate) > 10) {
            NSApp.terminate(nil); return
        }
        if !preview && Date().timeIntervalSince(lastUpdate) > 3 { hide() }
        guard let panel, panel.isVisible, let screen else { return }
        let origin = preview ? panel.frame : screen.frame
        let rect = NSRect(x: origin.midX - vm.contentWidth / 2,
                          y: origin.maxY - vm.contentHeight, width: vm.contentWidth, height: vm.contentHeight)
        let hovering = rect.insetBy(dx: -4, dy: -4).contains(NSEvent.mouseLocation)
        panel.ignoresMouseEvents = !hovering
        vm.hover(hovering)
    }

    @objc private func displaysChanged() { positionPanel() }
    @objc private func screenLocked() { locked = true; hide() }
    @objc private func screenUnlocked() { locked = false }

    private func positionPanel() {
        guard let panel else { return }
        let selected = NSScreen.screens.first { $0.safeAreaInsets.top > 0 } ?? NSScreen.main ?? NSScreen.screens.first
        guard let selected else { hide(); return }
        screen = selected
        let notchWidth: CGFloat
        if let left = selected.auxiliaryTopLeftArea, let right = selected.auxiliaryTopRightArea {
            notchWidth = selected.frame.width - left.width - right.width + 4
        } else { notchWidth = 185 }
        vm.closedNotchSize = CGSize(width: notchWidth, height: selected.safeAreaInsets.top > 0 ? selected.safeAreaInsets.top : 32)
        vm.openWidth = min(640, selected.frame.width - 20)
        let rect = NSRect(x: selected.frame.midX - vm.openWidth / 2,
                          y: preview ? selected.frame.midY - notchCanvasHeight / 2 : selected.frame.maxY - notchCanvasHeight,
                          width: vm.openWidth, height: notchCanvasHeight)
        panel.setFrame(rect, display: true)
    }

    private func readInput() {
        DispatchQueue.global(qos: .userInitiated).async {
            var pending = Data()
            while true {
                let data = FileHandle.standardInput.availableData
                if data.isEmpty { DispatchQueue.main.async { NSApp.terminate(nil) }; return }
                pending.append(data)
                if pending.count > 131_072 { DispatchQueue.main.async { NSApp.terminate(nil) }; return }
                while let newline = pending.firstIndex(of: 10) {
                    let line = pending.prefix(upTo: newline)
                    pending.removeSubrange(...newline)
                    if let state = try? JSONDecoder().decode(PlayerSnapshot.self, from: line),
                       state.version == 1, state.type == "state", state.positionMs.isFinite, state.durationMs.isFinite {
                        DispatchQueue.main.async { self.apply(state) }
                    } else if let frame = try? JSONDecoder().decode(LyricsFrame.self, from: line),
                              frame.version == 1, frame.type == "lyricsFrame" {
                        DispatchQueue.main.async { MusicManager.shared.applyFrame(frame) }
                    }
                }
            }
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        pointerTimer?.invalidate()
        panel?.orderOut(nil)
        NotificationCenter.default.removeObserver(self)
        DistributedNotificationCenter.default().removeObserver(self)
    }
}
