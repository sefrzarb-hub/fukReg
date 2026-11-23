import Foundation
import CoreBluetooth
import CryptoKit

/// BLE central: finds the Android host, reads the L2CAP PSM, opens the channel,
/// pairs once and then runs the encrypted session (see docs/PROTOCOL.md).
@MainActor
final class Link: NSObject, ObservableObject {
    static let service = CBUUID(string: "6f2a0001-6b7e-4c3a-9f1e-fa0c0de00001")
    static let psmChar = CBUUID(string: "6f2a0002-6b7e-4c3a-9f1e-fa0c0de00001")

    @Published var connected = false
    @Published var paired = Store.pairKey != nil
    @Published var contacts: [[String: Any]] = []
    @Published var messages: [[String: Any]] = []
    @Published var sims: [[String: Any]] = []
    @Published var lastOTP: String?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var channel: CBL2CAPChannel?
    private var rx: Sealer?, tx: Sealer?
    private var inBuf = Data()
    private var pendingPair: (hostPub: Data, pairId: Data)?
    private var state: Phase = .idle
    private var eph: Curve25519.KeyAgreement.PrivateKey?
    private var nonceC = Data()
    private enum Phase { case idle, pairing, hello, session }

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "fukreg.central"])
    }

    /// Called with the scanned QR string.
    func pair(uri: String) -> Bool {
        guard let c = URLComponents(string: uri), c.scheme == "fukreg",
              let id = c.queryItems?.first(where: { $0.name == "id" })?.value.flatMap({ Data(b64url: $0) }),
              let pk = c.queryItems?.first(where: { $0.name == "pk" })?.value.flatMap({ Data(b64url: $0) })
        else { return false }
        pendingPair = (pk, id)
        disconnect(); scan(); return true
    }

    func unpair() { Store.clear(); paired = false; disconnect() }

    private func scan() {
        guard central.state == .poweredOn, paired || pendingPair != nil else { return }
        central.scanForPeripherals(withServices: [Self.service])
    }

    private func disconnect() {
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        channel = nil; rx = nil; tx = nil; inBuf = Data(); state = .idle; connected = false
    }

    // MARK: framing
    private func writeRaw(_ d: Data) {
        guard let os = channel?.outputStream else { return }
        var len = UInt32(d.count).bigEndian
        var f = Data(bytes: &len, count: 4); f.append(d)
        f.withUnsafeBytes { _ = os.write($0.bindMemory(to: UInt8.self).baseAddress!, maxLength: f.count) }
    }
    private func writePlain(_ o: [String: Any]) { writeRaw(try! JSONSerialization.data(withJSONObject: o)) }

    func send(_ o: [String: Any]) {
        guard let tx, let d = try? JSONSerialization.data(withJSONObject: o), let f = try? tx.seal(d) else { return }
        writeRaw(f)
    }

    private func b64(_ d: Data) -> String { d.base64EncodedString() }

    private func startHandshake() {
        if let pp = pendingPair {
            let cl = Curve25519.KeyAgreement.PrivateKey()
            guard let shared = try? FKCrypto.dh(cl, pp.hostPub) else { return }
            let pk = FKCrypto.hkdf(shared, salt: pp.pairId, info: "fukreg-pair").withUnsafeBytes { Data($0) }
            writePlain(["t": "pair.req", "pk": b64(cl.publicKey.rawRepresentation)])
            writePlain(["t": "pair.confirm", "mac": b64(FKCrypto.hmac(pk, Data("confirm".utf8)))])
            Store.pairKey = pk; state = .pairing
        } else { sendHello() }
    }

    private func sendHello() {
        let e = Curve25519.KeyAgreement.PrivateKey(); eph = e; nonceC = FKCrypto.random(32)
        writePlain(["t": "hello", "pk": b64(e.publicKey.rawRepresentation), "n": b64(nonceC)])
        state = .hello
    }

    private func handleFrame(_ d: Data) {
        switch state {
        case .pairing:
            if let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], o["t"] as? String == "pair.ok" {
                pendingPair = nil; paired = true; sendHello()
            }
        case .hello:
            guard let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
                  let hp = (o["pk"] as? String).flatMap({ Data(base64Encoded: $0) }),
                  let nh = (o["n"] as? String).flatMap({ Data(base64Encoded: $0) }),
                  let eph, let pairKey = Store.pairKey, let dh = try? FKCrypto.dh(eph, hp) else { return }
            let ikm = dh + pairKey, salt = nonceC + nh
            tx = Sealer(FKCrypto.hkdf(ikm, salt: salt, info: "fukreg-session-c2h"))
            rx = Sealer(FKCrypto.hkdf(ikm, salt: salt, info: "fukreg-session-h2c"))
            state = .session; connected = true
            send(["t": "sims"]); send(["t": "contacts.sync"]); send(["t": "sms.list", "limit": 100])
        case .session:
            guard let p = try? rx?.open(d), let o = try? JSONSerialization.jsonObject(with: p) as? [String: Any] else { return }
            onMessage(o)
        case .idle: break
        }
    }

    private func onMessage(_ o: [String: Any]) {
        switch o["t"] as? String {
        case "contacts.list": contacts = o["items"] as? [[String: Any]] ?? []
        case "sims": sims = o["items"] as? [[String: Any]] ?? []
        case "sms.items": messages = o["items"] as? [[String: Any]] ?? []
        case "sms.new":
            if let it = o["item"] as? [String: Any] {
                messages.insert(it, at: 0)
                if let otp = it["otp"] as? String { lastOTP = otp }
                Notifier.sms(from: name(for: it["addr"] as? String ?? ""), body: it["body"] as? String ?? "", otp: it["otp"] as? String)
            }
        default: break
        }
    }

    func name(for addr: String) -> String {
        let tail = String(addr.filter(\.isNumber).suffix(10))
        for c in contacts {
            if let ph = c["phones"] as? [String], ph.contains(where: { String($0.filter(\.isNumber).suffix(10)) == tail && !tail.isEmpty }) {
                return c["name"] as? String ?? addr
            }
        }
        return addr
    }
}

extension Link: CBCentralManagerDelegate, CBPeripheralDelegate, StreamDelegate {
    nonisolated func centralManagerDidUpdateState(_ c: CBCentralManager) { MainActor.assumeIsolated { scan() } }
    nonisolated func centralManager(_ c: CBCentralManager, willRestoreState dict: [String: Any]) {}

    nonisolated func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        MainActor.assumeIsolated {
            central.stopScan(); peripheral = p; p.delegate = self; central.connect(p)
        }
    }
    nonisolated func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        p.discoverServices([Link.service])
    }
    nonisolated func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { disconnect(); scan() }
    }
    nonisolated func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { scan() }
    }
    nonisolated func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == Link.service }) else { return }
        p.discoverCharacteristics([Link.psmChar], for: s)
    }
    nonisolated func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        if let ch = s.characteristics?.first(where: { $0.uuid == Link.psmChar }) { p.readValue(for: ch) }
    }
    nonisolated func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard let v = ch.value, v.count >= 2 else { return }
        p.openL2CAPChannel(CBL2CAPPSM(UInt16(v[0]) << 8 | UInt16(v[1])))
    }
    nonisolated func peripheral(_ p: CBPeripheral, didOpen ch: CBL2CAPChannel?, error: Error?) {
        MainActor.assumeIsolated {
            guard let ch else { return }
            channel = ch
            for s in [ch.inputStream as Stream, ch.outputStream] { s.delegate = self; s.schedule(in: .main, forMode: .default); s.open() }
            startHandshake()
        }
    }
    nonisolated func stream(_ s: Stream, handle e: Stream.Event) {
        MainActor.assumeIsolated {
            guard e == .hasBytesAvailable, let ins = s as? InputStream else { return }
            var buf = [UInt8](repeating: 0, count: 4096)
            while ins.hasBytesAvailable { let n = ins.read(&buf, maxLength: buf.count); if n <= 0 { break }; inBuf.append(buf, count: n) }
            while inBuf.count >= 4 {
                let len = Int(inBuf.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
                guard inBuf.count >= 4 + len else { break }
                let f = Data(inBuf.dropFirst(4).prefix(len)); inBuf = Data(inBuf.dropFirst(4 + len))
                handleFrame(f)
            }
        }
    }
}

enum Store {
    static var pairKey: Data? {
        get { UserDefaults.standard.data(forKey: "pairKey") }
        set { UserDefaults.standard.set(newValue, forKey: "pairKey") }
    }
    static func clear() { pairKey = nil }
}
