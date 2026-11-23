import Foundation
import CryptoKit

/// Mirrors android/.../crypto/Crypto.kt (X25519 + HKDF-SHA256 + AES-256-GCM).
enum FKCrypto {
    static func hkdf(_ ikm: Data, salt: Data, info: String) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt,
                               info: Data(info.utf8), outputByteCount: 32)
    }
    static func dh(_ priv: Curve25519.KeyAgreement.PrivateKey, _ peerRaw: Data) throws -> Data {
        let s = try priv.sharedSecretFromKeyAgreement(with: .init(rawRepresentation: peerRaw))
        return s.withUnsafeBytes { Data($0) }
    }
    static func hmac(_ key: Data, _ msg: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: msg, using: SymmetricKey(data: key)))
    }
    static func random(_ n: Int) -> Data {
        var d = Data(count: n); _ = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }; return d
    }
}

final class Sealer {
    private let key: SymmetricKey
    private var sendCtr: UInt64 = 0
    private var recvCtr: Int64 = -1
    init(_ key: SymmetricKey) { self.key = key }

    private func nonce(_ c: UInt64) throws -> AES.GCM.Nonce {
        var d = Data(count: 4); withUnsafeBytes(of: c.bigEndian) { d.append(contentsOf: $0) }
        return try AES.GCM.Nonce(data: d)
    }
    func seal(_ plain: Data) throws -> Data {
        let c = sendCtr; sendCtr += 1
        let box = try AES.GCM.seal(plain, using: key, nonce: nonce(c))
        var out = Data(); withUnsafeBytes(of: c.bigEndian) { out.append(contentsOf: $0) }
        out.append(box.ciphertext); out.append(box.tag); return out
    }
    func open(_ frame: Data) throws -> Data {
        let c = frame.prefix(8).reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        guard Int64(c) > recvCtr else { throw NSError(domain: "replay", code: 1) }
        let body = frame.dropFirst(8)
        let box = try AES.GCM.SealedBox(nonce: nonce(c), ciphertext: body.dropLast(16), tag: body.suffix(16))
        let p = try AES.GCM.open(box, using: key); recvCtr = Int64(c); return p
    }
}

extension Data {
    init?(b64url s: String) {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        self.init(base64Encoded: t)
    }
}
