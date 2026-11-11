import Foundation
import Network
import Security

/// mDNS + TLS: Android sürümüyle aynı protokol (_qshare._tcp, POST /u, gönderilen cihazın parmak izi doğrulanır).
final class Net {
    static let shared = Net()
    let q = DispatchQueue(label: "qs.net")
    private var listener: NWListener?
    private var browser: NWBrowser?

    func params(verify: @escaping (String) -> Bool) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let so = tls.securityProtocolOptions
        if let id = sec_identity_create(Identity.identity) {
            sec_protocol_options_set_local_identity(so, id)
        }
        sec_protocol_options_set_peer_authentication_required(so, true)
        sec_protocol_options_set_verify_block(so, { _, trust, complete in
            let t = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(t) as? [SecCertificate],
                  let c = chain.first else { complete(false); return }
            complete(verify(sha256hex(SecCertificateCopyData(c) as Data)))
        }, q)
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        return NWParameters(tls: tls, tcp: tcp)
    }

    func start() throws {
        // Ağdaki her cihazdan gelen bağlantı kabul edilir.
        let l = try NWListener(using: params { _ in true })
        l.service = NWListener.Service(
            name: "QS" + String(Identity.fp.prefix(10)),
            type: "_qshare._tcp",
            txtRecord: NWTXTRecord(["n": Identity.name, "f": Identity.fp])
        )
        l.newConnectionHandler = { c in Receiver(c).start() }
        l.start(queue: q)
        listener = l

        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_qshare._tcp", domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { results, _ in
            var d: [String: Peer] = [:]
            for r in results {
                if case .bonjour(let txt) = r.metadata,
                   let f = txt.dictionary["f"], f != Identity.fp {
                    d[f] = Peer(fp: f, name: txt.dictionary["n"] ?? "?", endpoint: r.endpoint)
                }
            }
            DispatchQueue.main.async { Store.shared.peers = d }
        }
        b.start(queue: q)
        browser = b
    }
}

/// Gelen bağlantı: başlığı oku, dosyayı ~/Link içine yaz.
final class Receiver {
    private let c: NWConnection
    private var buf = Data()
    private var fh: FileHandle?
    private var path: URL?
    private var final: URL?
    private var total = 0, got = 0, lastPct = -1
    private var name = "file"
    private var finished = false

    init(_ c: NWConnection) { self.c = c }

    func start() {
        c.stateUpdateHandler = { [self] s in
            switch s {
            case .failed, .cancelled: finish(false, reply: false)
            default: break
            }
        }
        c.start(queue: Net.shared.q)
        readHead()
    }

    private func readHead() {
        c.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [self] d, _, eof, err in
            if let d { buf.append(d) }
            if let r = buf.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(decoding: buf[..<r.lowerBound], as: UTF8.self)
                let rest = Data(buf[r.upperBound...])
                buf = Data()
                begin(head, rest)
            } else if err != nil || eof || buf.count > 16384 {
                finish(false)
            } else {
                readHead()
            }
        }
    }

    private func begin(_ head: String, _ rest: Data) {
        var len = -1
        for line in head.components(separatedBy: "\r\n") {
            let k = line.prefix(while: { $0 != ":" }).lowercased()
            let v = line.drop(while: { $0 != ":" }).dropFirst().trimmingCharacters(in: .whitespaces)
            if k == "content-length" { len = Int(v) ?? -1 }
            else if k == "x-name" {
                name = v.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? v
            }
        }
        guard len >= 0 else { finish(false); return }
        total = len

        let bad = CharacterSet(charactersIn: "/\\:*?\"<>|").union(.controlCharacters)
        var clean = name.components(separatedBy: bad).joined(separator: "_")
        while clean.hasPrefix(".") { clean.removeFirst() }
        clean = String(clean.prefix(120))
        if clean.isEmpty { clean = "file" }
        name = clean

        let dir = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Link")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // Aynı adlı dosya varsa üzerine yazılır; önce .part dosyasına yazılır, başarılı olunca yerine konur.
        let dest = dir.appendingPathComponent(clean)
        let url = dir.appendingPathComponent(clean + ".part")
        try? FileManager.default.removeItem(at: url)
        final = dest
        guard FileManager.default.createFile(atPath: url.path, contents: nil),
              let h = try? FileHandle(forWritingTo: url) else { finish(false); return }
        fh = h; path = url

        let first = rest.prefix(total)
        if !first.isEmpty { try? h.write(contentsOf: first); got += first.count }
        if got >= total { finish(true) } else { readBody() }
    }

    private func readBody() {
        c.receive(minimumIncompleteLength: 1, maximumLength: min(1 << 20, total - got)) { [self] d, _, eof, err in
            if let d, !d.isEmpty {
                try? fh?.write(contentsOf: d)
                got += d.count
                let p = total > 0 ? got * 100 / total : 100
                if p != lastPct { lastPct = p; Store.shared.post(L("receiving", name), Double(p)) }
            }
            if got >= total { finish(true) }
            else if err != nil || eof { finish(false) }
            else { readBody() }
        }
    }

    private func finish(_ ok: Bool, reply: Bool = true) {
        if finished { return }
        finished = true
        try? fh?.close()
        var ok = ok
        if let p = path {
            if ok, let d = final {
                try? FileManager.default.removeItem(at: d)
                if (try? FileManager.default.moveItem(at: p, to: d)) != nil { path = d } else { ok = false }
            }
            if !ok { try? FileManager.default.removeItem(at: p) }
        }
        if path != nil { Store.shared.post(ok ? L("saved", name) : L("recv_partial"), saved: ok ? path : nil) }
        if reply {
            let st = ok ? "200 OK" : "500 Error"
            let resp = Data("HTTP/1.1 \(st)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8)
            c.send(content: resp, completion: .contentProcessed { [c] _ in c.cancel() })
        } else {
            c.cancel()
        }
    }
}

enum Sender {
    static func send(_ items: [URL], to p: Peer, done: @escaping (String) -> Void) {
        DispatchQueue.global().async {
            var msg = L("sent")
            var ok = true
            var temps: [URL] = []
            defer { temps.forEach { try? FileManager.default.removeItem(at: $0) } }
            do {
                // Klasörler zip'lenip tek dosya olarak gönderilir.
                var files: [URL] = []
                for u in items {
                    if isDir(u) {
                        Store.shared.post(L("zipping", u.lastPathComponent))
                        let z = try zipDir(u)
                        temps.append(z.deletingLastPathComponent())
                        files.append(z)
                    } else { files.append(u) }
                }
                for f in files { try one(f, p) }
            } catch { ok = false; msg = L("error_fmt", error.localizedDescription) }
            if ok { DispatchQueue.main.async { Store.shared.last = p.fp } }
            Store.shared.post(msg)
            HUD.show(ok ? L("sent_to", p.name) : msg)
            done(msg)
        }
    }

    private static func zipDir(_ dir: URL) throws -> URL {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        let out = tmp.appendingPathComponent(dir.lastPathComponent + ".zip")
        let pr = Process()
        pr.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        pr.arguments = ["-c", "-k", "--sequesterRsrc", "--keepParent", dir.path, out.path]
        pr.standardOutput = FileHandle.nullDevice
        pr.standardError = FileHandle.nullDevice
        try pr.run()
        pr.waitUntilExit()
        guard pr.terminationStatus == 0 else { throw QSError(L("err_zip")) }
        return out
    }

    private static func one(_ f: URL, _ p: Peer) throws {
        let size = (try FileManager.default.attributesOfItem(atPath: f.path)[.size] as? NSNumber)?.int64Value ?? 0
        let name = f.lastPathComponent
        let enc = name.addingPercentEncoding(
            withAllowedCharacters: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._*"))) ?? "file"

        let c = NWConnection(to: p.endpoint, using: Net.shared.params { $0 == p.fp })
        let ready = DispatchSemaphore(value: 0)
        var isReady = false
        var lastErr = L("err_nocon")
        c.stateUpdateHandler = { s in
            switch s {
            case .ready: isReady = true; ready.signal()
            case .failed(let e): lastErr = e.localizedDescription; ready.signal()
            case .waiting(let e): lastErr = e.localizedDescription
            default: break
            }
        }
        c.start(queue: Net.shared.q)
        if ready.wait(timeout: .now() + 15) == .timedOut || !isReady {
            c.cancel()
            throw QSError(L("err_connect", lastErr))
        }
        defer { c.cancel() }

        func push(_ d: Data) throws {
            let s = DispatchSemaphore(value: 0)
            var e: NWError?
            c.send(content: d, completion: .contentProcessed { e = $0; s.signal() })
            s.wait()
            if let e { throw e }
        }

        try push(Data("POST /u HTTP/1.1\r\nX-Name: \(enc)\r\nContent-Length: \(size)\r\nConnection: close\r\n\r\n".utf8))
        let h = try FileHandle(forReadingFrom: f)
        defer { try? h.close() }
        var sent: Int64 = 0
        var last = -1
        while let chunk = try h.read(upToCount: 1 << 20), !chunk.isEmpty {
            try push(chunk)
            sent += Int64(chunk.count)
            let pc = size > 0 ? Int(sent * 100 / size) : 0
            if pc != last { last = pc; Store.shared.post(L("sending", name), Double(pc)) }
        }

        let rs = DispatchSemaphore(value: 0)
        var resp = ""
        c.receive(minimumIncompleteLength: 1, maximumLength: 1024) { d, _, _, _ in
            resp = String(decoding: d ?? Data(), as: UTF8.self)
            rs.signal()
        }
        if rs.wait(timeout: .now() + 30) == .timedOut { throw QSError(L("err_no_reply")) }
        guard resp.contains(" 200") else {
            throw QSError(L("err_remote"))
        }
    }
}
