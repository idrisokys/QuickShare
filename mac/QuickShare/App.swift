import SwiftUI
import AppKit
import ServiceManagement
import UniformTypeIdentifiers

@main
struct QuickShareApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        // Menü çubuğunda ikon; tıklayınca 300x450 pencere, dışına tıklayınca kapanır.
        MenuBarExtra(L("app_name"), systemImage: "arrow.up.arrow.down.circle") {
            ContentView()
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationWillFinishLaunching(_ n: Notification) {
        NSAppleEventManager.shared().setEventHandler(
            self, andSelector: #selector(getURL(_:reply:)),
            forEventClass: AEEventClass(kInternetEventClass), andEventID: AEEventID(kAEGetURL))
    }

    func applicationDidFinishLaunching(_ n: Notification) {
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
        do { try Identity.setup(); try Net.shared.start() }
        catch { Store.shared.post(L("start_failed", error.localizedDescription)) }
    }

    /// Paylaş menüsü eklentisi quickshare://send?p=/yol/dosya şeklinde çağırır.
    @objc func getURL(_ e: NSAppleEventDescriptor, reply: NSAppleEventDescriptor) {
        guard let s = e.paramDescriptor(forKeyword: keyDirectObject)?.stringValue,
              let u = URL(string: s), u.scheme == "quickshare",
              let items = URLComponents(url: u, resolvingAgainstBaseURL: false)?.queryItems else { return }
        let files = items.filter { $0.name == "p" }.compactMap { $0.value }
            .map { URL(fileURLWithPath: $0) }.filter(isItem)
        if !files.isEmpty { DispatchQueue.main.async { Store.shared.dispatch(files, confirm: false, tries: 0) } }
    }

    /// Servisler menüsü → "QuickShare ile Gönder" (Info.plist'teki NSServices, NSMessage = sendFiles).
    /// Finder'da dosya/klasör seçip Sistem Ayarları → Klavye → Klavye Kısayolları → Servisler'den
    /// bu girişe atanan kısayolla çalışır. Açık tek cihaz varsa sormadan ona gönderir.
    @objc func sendFiles(_ pboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        let urls = (pboard.readObjects(forClasses: [NSURL.self],
                                       options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        let items = urls.filter(isItem)
        if !items.isEmpty { DispatchQueue.main.async { Store.shared.dispatch(items, confirm: false, tries: 0) } }
    }
}

/// Dosya ya da klasör var mı?
func isItem(_ u: URL) -> Bool { FileManager.default.fileExists(atPath: u.path) }

func isDir(_ u: URL) -> Bool {
    var d: ObjCBool = false
    return FileManager.default.fileExists(atPath: u.path, isDirectory: &d) && d.boolValue
}

/// Ekranın altında kısa süre görünen küçük bildirim (kısayolla gönderirken pencere açık olmadığı için).
enum HUD {
    private static var panel: NSPanel?

    static func show(_ text: String) {
        DispatchQueue.main.async {
            panel?.close()
            let label = NSTextField(labelWithString: text)
            label.font = .systemFont(ofSize: 14, weight: .medium)
            label.textColor = .white
            label.lineBreakMode = .byTruncatingMiddle
            label.sizeToFit()
            let pad: CGFloat = 16
            let w = min(label.frame.width, 420)
            let size = NSSize(width: w + pad * 2, height: label.frame.height + pad)
            label.frame = NSRect(x: pad, y: pad / 2, width: w, height: label.frame.height)

            let p = NSPanel(contentRect: NSRect(origin: .zero, size: size),
                            styleMask: [.borderless, .nonactivatingPanel],
                            backing: .buffered, defer: false)
            p.isOpaque = false
            p.backgroundColor = .clear
            p.level = .statusBar
            p.hasShadow = true
            p.ignoresMouseEvents = true
            p.isReleasedWhenClosed = false
            let bg = NSView(frame: NSRect(origin: .zero, size: size))
            bg.wantsLayer = true
            bg.layer?.backgroundColor = NSColor.black.withAlphaComponent(0.82).cgColor
            bg.layer?.cornerRadius = 10
            bg.addSubview(label)
            p.contentView = bg
            if let scr = NSScreen.main {
                p.setFrameOrigin(NSPoint(x: scr.visibleFrame.midX - size.width / 2, y: scr.visibleFrame.minY + 80))
            }
            p.orderFrontRegardless()
            panel = p
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                if panel === p { p.close(); panel = nil }
            }
        }
    }
}

extension Store {
    /// Cihaz tek ise doğrudan gönderir, birden fazlaysa sordurur. only verilirse sadece o cihaza gider.
    /// confirm: dış kaynaktan (paylaş menüsü) gelen isteklerde her zaman onay ister.
    func dispatch(_ files: [URL], confirm: Bool, only: String? = nil, tries: Int = 7) {
        let ps = peers.values.filter { only == nil || $0.fp == only }.sorted { $0.name < $1.name }
        if ps.isEmpty {
            if tries < 10 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    self.dispatch(files, confirm: confirm, only: only, tries: tries + 1)
                }
            } else { post(L("no_device")); HUD.show(L("no_device")) }
            return
        }
        var target: Peer? = ps[0]
        if ps.count > 1 || confirm { target = choose(ps, files, confirm) }
        guard let t = target else { return }
        Sender.send(files, to: t) { _ in }
    }

    private func choose(_ ps: [Peer], _ files: [URL], _ confirm: Bool) -> Peer? {
        let a = NSAlert()
        a.messageText = confirm ? L("confirm_send", files.count) : L("which_device")
        a.informativeText = files.prefix(5).map { $0.lastPathComponent }.joined(separator: "\n")
            + (files.count > 5 ? "\n…" : "")
        let pop = NSPopUpButton(frame: NSRect(x: 0, y: 0, width: 240, height: 26))
        pop.addItems(withTitles: ps.map { $0.name })
        if let i = ps.firstIndex(where: { $0.fp == last }) { pop.selectItem(at: i) }
        a.accessoryView = pop
        a.addButton(withTitle: L("send"))
        a.addButton(withTitle: L("cancel"))
        NSApp.activate(ignoringOtherApps: true)
        return a.runModal() == .alertFirstButtonReturn ? ps[pop.indexOfSelectedItem] : nil
    }

    func pickFiles(to fp: String? = nil) {
        let p = NSOpenPanel()
        p.allowsMultipleSelection = true
        p.canChooseDirectories = true
        NSApp.activate(ignoringOtherApps: true)
        p.begin { r in
            if r == .OK { DispatchQueue.main.async { Store.shared.dispatch(p.urls, confirm: false, only: fp) } }
        }
    }
}

struct ContentView: View {
    @ObservedObject var s = Store.shared
    @State private var launch = SMAppService.mainApp.status == .enabled
    @State private var showInfo = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(Identity.name).font(.headline).lineLimit(1)
                Spacer()
                Circle().fill(Color.green).frame(width: 8, height: 8)
                Button { showInfo.toggle() } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.plain)
                    .help(L("info"))
            }
            if showInfo { InfoPanel() }
            Text(L("nearby")).font(.caption).foregroundColor(.secondary)

            ScrollView {
                VStack(spacing: 6) {
                    ForEach(s.rows) { r in
                        HStack {
                            Image(systemName: "paperplane").foregroundColor(.accentColor)
                            Text(r.name).lineLimit(1)
                            Spacer()
                        }
                        .padding(8)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color.secondary.opacity(0.12)))
                        .contentShape(Rectangle())
                        .onTapGesture { s.pickFiles(to: r.fp) }
                    }
                    if s.rows.isEmpty {
                        Text(L("searching")).font(.callout).foregroundColor(.secondary).padding(.top, 20)
                    }
                }
            }
            .frame(maxHeight: .infinity)

            Text(s.status.isEmpty ? L("hint") : s.status)
                .font(.caption).foregroundColor(s.savedURL != nil ? .accentColor : .secondary).lineLimit(2)
                .contentShape(Rectangle())
                .onTapGesture {
                    // "Kaydedildi" yazısına tıklayınca QuickShare klasörünün içini aç.
                    if let u = s.savedURL { NSWorkspace.shared.open(u.deletingLastPathComponent()) }
                }
                .onHover { inside in
                    if s.savedURL != nil { if inside { NSCursor.pointingHand.push() } else { NSCursor.pop() } }
                }
            if s.pct >= 0 { ProgressView(value: s.pct, total: 100) }

            Button(action: { s.pickFiles() }) {
                Text(L("select_file")).frame(maxWidth: .infinity)
            }
            .controlSize(.large)
            .keyboardShortcut(.defaultAction)

            HStack {
                Toggle(L("launch_login"), isOn: Binding(
                    get: { launch },
                    set: { on in
                        launch = on
                        do { if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() } }
                        catch { launch = SMAppService.mainApp.status == .enabled }
                    }))
                    .toggleStyle(.switch).controlSize(.small)
                Spacer()
                Button(L("quit")) { NSApp.terminate(nil) }
            }
        }
        .padding(16)
        .frame(width: 300, height: 450)
        .onDrop(of: [UTType.fileURL], isTargeted: nil) { providers in
            let g = DispatchGroup()
            let lock = NSLock()
            var urls: [URL] = []
            for p in providers {
                g.enter()
                p.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                    var u: URL?
                    if let x = item as? URL { u = x }
                    else if let d = item as? Data { u = URL(dataRepresentation: d, relativeTo: nil) }
                    if let u, isItem(u) { lock.lock(); urls.append(u); lock.unlock() }
                    g.leave()
                }
            }
            g.notify(queue: .main) { if !urls.isEmpty { s.dispatch(urls, confirm: false) } }
            return true
        }
    }
}

/// Sağ üstteki bilgi (i) ikonu: ana görünümde açılıp kapanan küçük panel (resim, e-posta, GitHub linki).
struct InfoPanel: View {
    var body: some View {
        HStack(spacing: 8) {
            if let img = NSImage(named: "releases_icon") {
                Image(nsImage: img).resizable().scaledToFill()
                    .frame(width: 28, height: 28).clipShape(Circle())
            }
            VStack(alignment: .leading, spacing: 1) {
                Text("sislivadi@gmail.com").textSelection(.enabled)
                Link("https://github.com/idrisokys/", destination: URL(string: "https://github.com/idrisokys/")!)
            }
            .font(.system(size: 11))
            Spacer(minLength: 0)
        }
        .padding(6)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color.secondary.opacity(0.12)))
    }
}
