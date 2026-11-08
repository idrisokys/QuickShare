import Cocoa
import UniformTypeIdentifiers

/// Paylaş menüsü eklentisi: dosya yollarını ana uygulamaya iletir.
final class ShareViewController: NSViewController {
    override func loadView() { view = NSView(frame: NSRect(x: 0, y: 0, width: 1, height: 1)) }

    override func viewDidLoad() {
        super.viewDidLoad()
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        let providers = items.flatMap { $0.attachments ?? [] }
        var paths: [String] = []
        let lock = NSLock()
        let g = DispatchGroup()
        func add(_ s: String) { lock.lock(); paths.append(s); lock.unlock() }

        for p in providers {
            if p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                g.enter()
                p.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                    if let u = item as? URL { add(u.path) }
                    else if let d = item as? Data, let u = URL(dataRepresentation: d, relativeTo: nil) { add(u.path) }
                    g.leave()
                }
            } else if p.hasItemConformingToTypeIdentifier(UTType.data.identifier) {
                g.enter()
                p.loadFileRepresentation(forTypeIdentifier: UTType.data.identifier) { url, _ in
                    if let url {
                        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                        let dst = dir.appendingPathComponent(url.lastPathComponent)
                        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                        if (try? FileManager.default.copyItem(at: url, to: dst)) != nil { add(dst.path) }
                    }
                    g.leave()
                }
            }
        }

        g.notify(queue: .main) {
            var c = URLComponents()
            c.scheme = "quickshare"
            c.host = "send"
            c.queryItems = paths.map { URLQueryItem(name: "p", value: $0) }
            if !paths.isEmpty, let u = c.url { NSWorkspace.shared.open(u) }
            self.extensionContext?.completeRequest(returningItems: nil)
        }
    }
}
