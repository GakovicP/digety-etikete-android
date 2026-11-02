import AVFoundation
import CoreLocation
import UIKit
import ComposeApp

/// Najgornji prikazani kontroler (za prikaz skenera, kamere, deljenja).
func topViewController() -> UIViewController? {
    let scene = UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .first { $0.activationState == .foregroundActive } ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
    var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController ?? scene?.windows.first?.rootViewController
    while let presented = top?.presentedViewController { top = presented }
    return top
}

/// Implementacija platformskih funkcija za zajednicki Kotlin kod (IosNative).
final class NativeImpl: NSObject, IosNative, CLLocationManagerDelegate,
    UIImagePickerControllerDelegate, UINavigationControllerDelegate {

    static let shared = NativeImpl()

    // MARK: skener

    func scanBarcode(callback: StringCallback) {
        DispatchQueue.main.async {
            let start = {
                let vc = ScannerViewController()
                vc.onResult = { code in callback.onResult(value: code) }
                vc.modalPresentationStyle = .fullScreen
                topViewController()?.present(vc, animated: true)
            }
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: start()
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { ok in
                    DispatchQueue.main.async { ok ? start() : callback.onResult(value: nil) }
                }
            default:
                self.alert("Kamera nije dozvoljena", "Uključi kameru za NetPlanet link u Podešavanjima telefona.")
                callback.onResult(value: nil)
            }
        }
    }

    // MARK: GPS

    private let location = CLLocationManager()
    private var locationCallbacks: [LocationCallback] = []
    private var locationTimer: Timer?

    func currentLocation(callback: LocationCallback) {
        DispatchQueue.main.async {
            self.location.delegate = self
            self.location.desiredAccuracy = kCLLocationAccuracyBest
            let status = self.location.authorizationStatus
            if status == .denied || status == .restricted {
                callback.onResult(ok: false, lat: 0, lon: 0, accuracy: 0)
                return
            }
            self.locationCallbacks.append(callback)
            if status == .notDetermined { self.location.requestWhenInUseAuthorization() }
            self.location.requestLocation()
            self.locationTimer?.invalidate()
            self.locationTimer = Timer.scheduledTimer(withTimeInterval: 8, repeats: false) { _ in
                // bez signala: poslednja poznata pozicija ili nista
                if let l = self.location.location { self.finishLocation(l) } else { self.failLocation() }
            }
        }
    }

    private func finishLocation(_ l: CLLocation) {
        locationTimer?.invalidate()
        let cbs = locationCallbacks
        locationCallbacks = []
        cbs.forEach { $0.onResult(ok: true, lat: l.coordinate.latitude, lon: l.coordinate.longitude, accuracy: l.horizontalAccuracy) }
    }

    private func failLocation() {
        locationTimer?.invalidate()
        let cbs = locationCallbacks
        locationCallbacks = []
        cbs.forEach { $0.onResult(ok: false, lat: 0, lon: 0, accuracy: 0) }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let l = locations.last { finishLocation(l) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let l = manager.location { finishLocation(l) } else { failLocation() }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let s = manager.authorizationStatus
        if (s == .authorizedWhenInUse || s == .authorizedAlways) && !locationCallbacks.isEmpty {
            manager.requestLocation()
        } else if s == .denied || s == .restricted {
            failLocation()
        }
    }

    // MARK: fotografija

    private var photoCallback: DataCallback?

    func takePhoto(callback: DataCallback) {
        DispatchQueue.main.async {
            let picker = UIImagePickerController()
            picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
            picker.delegate = self
            self.photoCallback = callback
            topViewController()?.present(picker, animated: true)
        }
    }

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        let image = info[.originalImage] as? UIImage
        picker.dismiss(animated: true)
        photoCallback?.onResult(data: image.flatMap { resized($0, maxSide: 1600).jpegData(compressionQuality: 0.85) })
        photoCallback = nil
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
        photoCallback?.onResult(data: nil)
        photoCallback = nil
    }

    private func resized(_ img: UIImage, maxSide: CGFloat) -> UIImage {
        let s = max(img.size.width, img.size.height)
        if s <= maxSide { return img }
        let k = maxSide / s
        let size = CGSize(width: img.size.width * k, height: img.size.height * k)
        return UIGraphicsImageRenderer(size: size).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
    }

    // MARK: deljenje i vibracija

    func share(fileName: String, content: String) {
        DispatchQueue.main.async {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
            try? content.write(to: url, atomically: true, encoding: .utf8)
            let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            if let top = topViewController() {
                vc.popoverPresentationController?.sourceView = top.view
                vc.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 1, height: 1)
                top.present(vc, animated: true)
            }
        }
    }

    func vibrate(error: Bool) {
        DispatchQueue.main.async {
            let g = UINotificationFeedbackGenerator()
            g.notificationOccurred(error ? .error : .success)
        }
    }

    private func alert(_ title: String, _ message: String) {
        let a = UIAlertController(title: title, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "U redu", style: .default))
        topViewController()?.present(a, animated: true)
    }
}

/// Ekran za skeniranje barkoda (AVFoundation).
final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onResult: ((String?) -> Void)?
    private let session = AVCaptureSession()
    private var done = false
    private var cameraFailed = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            // nema kamere (npr. simulator) - zatvori kad se ekran prikaze
            cameraFailed = true
            return
        }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            let wanted: [AVMetadataObject.ObjectType] = [.ean8, .ean13, .upce, .code128, .code39, .code93, .itf14,
                                                         .interleaved2of5, .qr, .dataMatrix, .pdf417, .aztec]
            output.metadataObjectTypes = wanted.filter { output.availableMetadataObjectTypes.contains($0) }
        }
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)

        // okvir za ciljanje
        let frame = UIView()
        frame.layer.borderColor = UIColor(red: 0.96, green: 0.47, blue: 0.13, alpha: 1).cgColor
        frame.layer.borderWidth = 3
        frame.layer.cornerRadius = 12
        frame.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(frame)

        let cancel = UIButton(type: .system)
        cancel.setTitle("Otkaži", for: .normal)
        cancel.setTitleColor(.white, for: .normal)
        cancel.titleLabel?.font = .boldSystemFont(ofSize: 18)
        cancel.backgroundColor = UIColor(white: 0, alpha: 0.5)
        cancel.layer.cornerRadius = 10
        cancel.translatesAutoresizingMaskIntoConstraints = false
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        view.addSubview(cancel)

        NSLayoutConstraint.activate([
            frame.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            frame.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            frame.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.8),
            frame.heightAnchor.constraint(equalTo: frame.widthAnchor, multiplier: 0.55),
            cancel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
            cancel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            cancel.widthAnchor.constraint(equalToConstant: 160),
            cancel.heightAnchor.constraint(equalToConstant: 48),
        ])

        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if cameraFailed { finish(nil) }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        view.layer.sublayers?.compactMap { $0 as? AVCaptureVideoPreviewLayer }.forEach { $0.frame = view.bounds }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let code = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        finish(code)
    }

    @objc private func cancelTapped() { finish(nil) }

    private func finish(_ code: String?) {
        if done { return }
        done = true
        if session.isRunning { session.stopRunning() }
        let cb = onResult
        onResult = nil
        if presentingViewController != nil {
            dismiss(animated: true) { cb?(code) }
        } else {
            cb?(code)
        }
    }
}
