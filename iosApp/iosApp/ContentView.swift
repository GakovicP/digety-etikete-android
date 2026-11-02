import SwiftUI
import UIKit
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        // Compose sam vodi racuna o statusnoj liniji i donjoj ivici (traka se boji do vrha ekrana)
        ComposeView()
            .ignoresSafeArea()
    }
}
