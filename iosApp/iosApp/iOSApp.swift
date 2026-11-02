import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        // Swift implementacija kamere, GPS-a, fotografisanja i deljenja za zajednicki Kotlin kod
        IosBridge.shared.impl = NativeImpl.shared
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
