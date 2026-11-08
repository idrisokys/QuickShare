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
        do { try Identity.setup(); try Net.shared.start() }
        catch { Store.shared.post(L("start_failed", error.localizedDescription)) }
    }

    /// Paylaş menüsü eklentisi quickshare://send?p=/yol/dosya şeklinde çağırır.
    @objc func getURL(_ e: NSAppleEventDescriptor, reply: NSAppleEventDescriptor) {
        guard let s = e.paramDescriptor(forKeyword: keyDirectObject)?.stringValue,
              let u = URL(string: s), u.scheme == "quickshare",
              let items = URLComponents(url: u, resolvingAgainstBaseURL: false)?.queryItems else { return }
        let files = items.filter { $0.name == "p" }.compactMap { $0.value }
            .map { URL(fileURLWithPath: $0) }.filter(isFile)
        if !files.isEmpty { DispatchQueue.main.async { Store.shared.dispatch(files, confirm: true, tries: 0) } }
    }
}

func isFile(_ u: URL) -> Bool {
    var d: ObjCBool = false
    return FileManager.default.fileExists(atPath: u.path, isDirectory: &d) && !d.boolValue
}

extension Store {
    /// Güvenilir cihaz tek ise doğrudan gönderir, birden fazlaysa sordurur.
    /// confirm: dış kaynaktan (paylaş menüsü) gelen isteklerde her zaman onay ister.
    func dispatch(_ files: [URL], confirm: Bool, tries: Int = 7) {
        let ps = peers.values.filter { trusted[$0.fp] != nil }.sorted { $0.name < $1.name }
        if ps.isEmpty {
            if tries < 10 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    self.dispatch(files, confirm: confirm, tries: tries + 1)
                }
            } else { post(L("no_trusted")) }
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

    func pickFiles() {
        let p = NSOpenPanel()
        p.allowsMultipleSelection = true
        p.canChooseDirectories = false
        NSApp.activate(ignoringOtherApps: true)
        p.begin { r in
            if r == .OK { DispatchQueue.main.async { Store.shared.dispatch(p.urls, confirm: false) } }
        }
    }
}

struct ContentView: View {
    @ObservedObject var s = Store.shared
    @State private var launch = SMAppService.mainApp.status == .enabled

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(Identity.name).font(.headline).lineLimit(1)
                Spacer()
                Circle().fill(Color.green).frame(width: 8, height: 8)
            }
            Text(L("nearby")).font(.caption).foregroundColor(.secondary)

            ScrollView {
                VStack(spacing: 6) {
                    ForEach(s.rows) { r in
                        HStack {
                            Image(systemName: r.trusted ? "checkmark.circle.fill" : "circle")
                                .foregroundColor(r.trusted ? .accentColor : .secondary)
                            Text(r.name).foregroundColor(r.online ? .primary : .secondary).lineLimit(1)
                            if !r.online { Text(L("offline")).font(.caption).foregroundColor(.secondary) }
                            Spacer()
                        }
                        .padding(8)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color.secondary.opacity(0.12)))
                        .contentShape(Rectangle())
                        .onTapGesture { if !r.trusted && r.online { s.trust(r.fp, r.name) } }
                        .contextMenu {
                            if r.trusted { Button(L("remove")) { s.untrust(r.fp) } }
                        }
                    }
                    if s.rows.isEmpty {
                        Text(L("searching")).font(.callout).foregroundColor(.secondary).padding(.top, 20)
                    }
                }
            }
            .frame(maxHeight: .infinity)

            Text(s.status.isEmpty ? L("hint") : s.status)
                .font(.caption).foregroundColor(.secondary).lineLimit(2)
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
                    if let u, isFile(u) { lock.lock(); urls.append(u); lock.unlock() }
                    g.leave()
                }
            }
            g.notify(queue: .main) { if !urls.isEmpty { s.dispatch(urls, confirm: false) } }
            return true
        }
    }
}
