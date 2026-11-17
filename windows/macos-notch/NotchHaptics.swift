import AppKit

/// Request the current device performer each time, respecting macOS haptic preferences.
enum NotchHaptics {
    static func perform(_ pattern: NSHapticFeedbackManager.FeedbackPattern = .generic) {
        NSHapticFeedbackManager.defaultPerformer.perform(pattern, performanceTime: .now)
    }
}
