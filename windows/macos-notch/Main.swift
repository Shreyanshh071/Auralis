import AppKit
import SwiftUI

@main @MainActor struct AuralisNotchMain {
    static func main() {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        if CommandLine.arguments.contains("--check-snapshot") {
            do {
                let state = try JSONDecoder().decode(PlayerSnapshot.self, from: FileHandle.standardInput.readDataToEndOfFile())
                guard state.version == 1, state.type == "state", state.positionMs.isFinite, state.durationMs.isFinite else { exit(1) }
                MusicManager.shared.apply(state)
                FileHandle.standardOutput.write(try JSONEncoder().encode(state))
            } catch { exit(1) }
            return
        }
        if CommandLine.arguments.contains("--self-test") {
            var snapshot = PlayerSnapshot()
            snapshot.trackId = "check"; snapshot.title = "Auralis"; snapshot.artist = "Test"
            snapshot.durationMs = 120_000; snapshot.positionMs = 30_000; snapshot.playing = true
            let decoded = try! JSONDecoder().decode(PlayerSnapshot.self, from: JSONEncoder().encode(snapshot))
            MusicManager.shared.apply(decoded)
            precondition(MusicManager.shared.songTitle == "Auralis")
            precondition(MusicManager.shared.estimatedPlaybackPosition(at: Date().addingTimeInterval(2)) >= 32)
            snapshot.playing = false; snapshot.positionMs = 500_000
            MusicManager.shared.apply(snapshot)
            precondition(MusicManager.shared.elapsedTime == 120)
            let vm = BoringViewModel()
            vm.closedNotchSize = CGSize(width: 206, height: 38)
            precondition(vm.contentWidth == 296)
            vm.open(); precondition(vm.contentWidth == 640 && vm.contentHeight == 190)
            vm.close(); precondition(vm.contentHeight == 38)
            let panel = AuralisNotchPanel()
            precondition(panel.level == .mainMenu + 3 && !panel.canBecomeKey && !panel.canBecomeMain && !panel.isVisible)
            precondition(panel.collectionBehavior.contains(.fullScreenAuxiliary))
            print("PASS: native protocol, Auralis-only model, progress interpolation, clamping, upstream sizing, nonactivating panel")
            return
        }
        if let index = CommandLine.arguments.firstIndex(of: "--render-preview"), CommandLine.arguments.count > index + 1 {
            let manager = MusicManager.shared
            var state = PlayerSnapshot()
            state.visible = true; state.trackId = "preview"; state.title = "Auralis"; state.artist = "Your music, in the notch"
            state.durationMs = 240_000; state.positionMs = 65_000; state.playing = true; state.canNext = true; state.canPrevious = true
            manager.apply(state); manager.albumArt = AuralisArtwork.appIcon
            manager.avgColor = AuralisArtwork.averageColor(manager.albumArt)
            let vm = BoringViewModel()
            vm.closedNotchSize = CGSize(width: 206, height: 38); vm.open()
            let renderer = ImageRenderer(content: AuralisNotchView().environmentObject(vm))
            renderer.scale = 2
            guard let tiff = renderer.nsImage?.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff),
                  let png = bitmap.representation(using: .png, properties: [:]) else {
                fputs("Could not render notch preview\n", stderr); exit(1)
            }
            do { try png.write(to: URL(fileURLWithPath: CommandLine.arguments[index + 1])) }
            catch { fputs("Preview write failed: \(error)\n", stderr); exit(1) }
            return
        }
        let delegate = NotchApplication(preview: CommandLine.arguments.contains("--preview"))
        app.delegate = delegate
        withExtendedLifetime(delegate) { app.run() }
    }
}
