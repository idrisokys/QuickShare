import Foundation
import Security
import CryptoKit
import Network

struct QSError: LocalizedError {
    let m: String
    init(_ m: String) { self.m = m }
    var errorDescription: String? { m }
}

func sha256hex(_ d: Data) -> String {
    SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined()
}

/// Cihaz kimliği: ilk açılışta self-signed EC sertifika üretilir (macOS'un kendi openssl'i ile).
/// Parmak izi = sertifikanın SHA-256'sı (Android sürümüyle aynı yöntem).
enum Identity {
    static let name: String = Host.current().localizedName ?? ProcessInfo.processInfo.hostName
    static var fp = ""
    static var identity: SecIdentity!
    private static let pass = "qs"

    static func setup() throws {
        let fm = FileManager.default
        let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("QuickShare")
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let p12 = dir.appendingPathComponent("id.p12")

        if !fm.fileExists(atPath: p12.path) {
            let tmp = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try fm.createDirectory(at: tmp, withIntermediateDirectories: true)
            defer { try? fm.removeItem(at: tmp) }
            let k = tmp.appendingPathComponent("k.pem").path
            let c = tmp.appendingPathComponent("c.pem").path
            try run(["ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", k])
            try run(["req", "-new", "-x509", "-key", k, "-out", c, "-subj", "/CN=QuickShare", "-days", "36500"])
            try run(["pkcs12", "-export", "-inkey", k, "-in", c, "-out", p12.path, "-passout", "pass:" + pass])
            try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: p12.path)
        }

        let data = try Data(contentsOf: p12)
        var items: CFArray?
        let opts: [String: Any] = [
            kSecImportExportPassphrase as String: pass,
            kSecImportToMemoryOnly as String: true,
        ]
        let st = SecPKCS12Import(data as CFData, opts as CFDictionary, &items)
        guard st == errSecSuccess,
              let arr = items as? [[String: Any]],
              let any = arr.first?[kSecImportItemIdentity as String]
        else { throw QSError("Kimlik yüklenemedi (\(st))") }
        let id = any as! SecIdentity
        identity = id
        var cert: SecCertificate?
        SecIdentityCopyCertificate(id, &cert)
        guard let cert else { throw QSError("Sertifika okunamadı") }
        fp = sha256hex(SecCertificateCopyData(cert) as Data)
    }

    private static func run(_ args: [String]) throws {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        p.arguments = args
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        try p.run()
        p.waitUntilExit()
        guard p.terminationStatus == 0 else { throw QSError("Sertifika üretilemedi") }
    }
}

struct Peer: Identifiable {
    let fp: String
    let name: String
    let endpoint: NWEndpoint
    var id: String { fp }
}

struct Row: Identifiable {
    let fp: String
    let name: String
    let trusted: Bool
    let online: Bool
    var id: String { fp }
}

/// Uygulama durumu: bulunan cihazlar, güvenilir liste, ilerleme.
final class Store: ObservableObject {
    static let shared = Store()

    @Published var peers: [String: Peer] = [:]
    @Published var trusted: [String: String]
    @Published var status = ""
    @Published var pct = -1.0

    private let lock = NSLock()
    private var fps = Set<String>()

    init() {
        trusted = UserDefaults.standard.dictionary(forKey: "trusted") as? [String: String] ?? [:]
        fps = Set(trusted.keys)
    }

    var last: String? {
        get { UserDefaults.standard.string(forKey: "last") }
        set { UserDefaults.standard.set(newValue, forKey: "last") }
    }

    /// Her thread'den çağrılabilir (TLS doğrulaması).
    func isTrusted(_ fp: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return fps.contains(fp)
    }

    func trust(_ fp: String, _ name: String) { trusted[fp] = name; persist() }
    func untrust(_ fp: String) { trusted.removeValue(forKey: fp); persist() }

    private func persist() {
        UserDefaults.standard.set(trusted, forKey: "trusted")
        lock.lock(); fps = Set(trusted.keys); lock.unlock()
    }

    func post(_ s: String, _ p: Double = -1) {
        DispatchQueue.main.async { self.status = s; self.pct = p }
    }

    var rows: [Row] {
        let on = peers.values.sorted { $0.name < $1.name }
            .map { Row(fp: $0.fp, name: $0.name, trusted: trusted[$0.fp] != nil, online: true) }
        let off = trusted.filter { peers[$0.key] == nil }.sorted { $0.value < $1.value }
            .map { Row(fp: $0.key, name: $0.value, trusted: true, online: false) }
        return on + off
    }
}
