import SwiftUI
import UserNotifications
import AVFoundation

@main
struct FukRegApp: App {
    @StateObject var link = Link()
    init() { UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in } }
    var body: some Scene { WindowGroup { RootView().environmentObject(link) } }
}

enum Notifier {
    static func sms(from: String, body: String, otp: String?) {
        let c = UNMutableNotificationContent()
        c.title = otp.map { "\(String(localized: "code")) \($0)" } ?? from
        c.subtitle = otp != nil ? from : ""
        c.body = body; c.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }
}

struct RootView: View {
    @EnvironmentObject var link: Link
    var body: some View {
        if !link.paired { PairView() } else {
            TabView {
                MessagesView().tabItem { Label("messages", systemImage: "message") }
                ContactsView().tabItem { Label("contacts", systemImage: "person.2") }
                SettingsView().tabItem { Label("settings", systemImage: "gear") }
            }
        }
    }
}

struct PairView: View {
    @EnvironmentObject var link: Link
    @State var error = false
    var body: some View {
        VStack(spacing: 20) {
            Text("pair_title").font(.title2.bold())
            Text("pair_hint").foregroundStyle(.secondary).multilineTextAlignment(.center)
            QRScanner { code in if !link.pair(uri: code) { error = true } }
                .frame(height: 320).clipShape(RoundedRectangle(cornerRadius: 20))
            if error { Text("pair_bad").foregroundStyle(.red) }
        }.padding()
    }
}

struct StatusBar: View {
    @EnvironmentObject var link: Link
    var body: some View {
        HStack {
            Circle().fill(link.connected ? .green : .orange).frame(width: 8)
            Text(link.connected ? "status_connected" : "status_searching").font(.caption)
        }
    }
}

struct MessagesView: View {
    @EnvironmentObject var link: Link
    @State var compose = false
    var threads: [(addr: String, last: [String: Any])] {
        var seen = Set<String>(); var out: [(String, [String: Any])] = []
        for m in link.messages { let a = m["addr"] as? String ?? ""; if seen.insert(a).inserted { out.append((a, m)) } }
        return out
    }
    var body: some View {
        NavigationStack {
            List {
                if let otp = link.lastOTP {
                    Section("last_code") {
                        HStack { Text(otp).font(.system(.largeTitle, design: .monospaced)); Spacer()
                            Button("copy") { UIPasteboard.general.string = otp } }
                    }
                }
                ForEach(threads, id: \.addr) { t in
                    NavigationLink { ThreadView(addr: t.addr) } label: {
                        VStack(alignment: .leading) {
                            Text(link.name(for: t.addr)).bold()
                            Text(t.last["body"] as? String ?? "").lineLimit(2).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("messages")
            .toolbar {
                ToolbarItem(placement: .principal) { StatusBar() }
                ToolbarItem { Button { compose = true } label: { Image(systemName: "square.and.pencil") } }
            }
            .sheet(isPresented: $compose) { NavigationStack { ThreadView(addr: "", editableAddr: true) } }
        }
    }
}

struct ThreadView: View {
    @EnvironmentObject var link: Link
    @State var addr: String
    var editableAddr = false
    @State var text = ""
    @AppStorage("defaultSim") var sim = -1
    var items: [[String: Any]] { link.messages.filter { $0["addr"] as? String == addr }.reversed() }
    var body: some View {
        VStack {
            if editableAddr { TextField("number", text: $addr).keyboardType(.phonePad).textFieldStyle(.roundedBorder).padding() }
            ScrollView {
                ForEach(Array(items.enumerated()), id: \.offset) { _, m in
                    let out = m["dir"] as? String == "out"
                    HStack { if out { Spacer() }
                        Text(m["body"] as? String ?? "").padding(10)
                            .background(out ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.15), in: RoundedRectangle(cornerRadius: 14))
                        if !out { Spacer() } }.padding(.horizontal)
                }
            }
            HStack {
                if link.sims.count > 1 {
                    Picker("sim", selection: $sim) {
                        ForEach(link.sims.indices, id: \.self) { i in Text("SIM \(i + 1)").tag(link.sims[i]["sub"] as? Int ?? -1) }
                    }.pickerStyle(.menu)
                }
                TextField("message", text: $text, axis: .vertical).textFieldStyle(.roundedBorder)
                Button { 
                    link.send(["t": "sms.send", "addr": addr, "body": text, "sim": sim])
                    link.messages.insert(["addr": addr, "body": text, "dir": "out", "ts": Date().timeIntervalSince1970 * 1000], at: 0)
                    text = ""
                } label: { Image(systemName: "arrow.up.circle.fill").font(.title) }
                .disabled(text.isEmpty || addr.isEmpty || !link.connected)
            }.padding()
        }.navigationTitle(link.name(for: addr))
    }
}

struct ContactsView: View {
    @EnvironmentObject var link: Link
    @State var q = ""
    var body: some View {
        NavigationStack {
            List(link.contacts.indices.filter { q.isEmpty || (link.contacts[$0]["name"] as? String ?? "").localizedCaseInsensitiveContains(q) }, id: \.self) { i in
                let c = link.contacts[i]
                VStack(alignment: .leading) {
                    Text(c["name"] as? String ?? "").bold()
                    ForEach(c["phones"] as? [String] ?? [], id: \.self) { Text($0).font(.caption).foregroundStyle(.secondary) }
                }
            }.searchable(text: $q).navigationTitle("contacts")
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject var link: Link
    var body: some View {
        NavigationStack {
            Form {
                Section { StatusBar() }
                Button("unpair", role: .destructive) { link.unpair() }
            }.navigationTitle("settings")
        }
    }
}

// MARK: QR scanner
struct QRScanner: UIViewControllerRepresentable {
    var onCode: (String) -> Void
    func makeUIViewController(context: Context) -> ScannerVC { let v = ScannerVC(); v.onCode = onCode; return v }
    func updateUIViewController(_ v: ScannerVC, context: Context) {}
}

final class ScannerVC: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: (String) -> Void = { _ in }
    private let session = AVCaptureSession()
    override func viewDidLoad() {
        super.viewDidLoad()
        guard let dev = AVCaptureDevice.default(for: .video), let inp = try? AVCaptureDeviceInput(device: dev) else { return }
        session.addInput(inp)
        let out = AVCaptureMetadataOutput(); session.addOutput(out)
        out.setMetadataObjectsDelegate(self, queue: .main); out.metadataObjectTypes = [.qr]
        let pl = AVCaptureVideoPreviewLayer(session: session); pl.videoGravity = .resizeAspectFill
        pl.frame = view.bounds; view.layer.addSublayer(pl)
        DispatchQueue.global().async { self.session.startRunning() }
    }
    override func viewDidLayoutSubviews() { view.layer.sublayers?.first?.frame = view.bounds }
    func metadataOutput(_ o: AVCaptureMetadataOutput, didOutput objs: [AVMetadataObject], from c: AVCaptureConnection) {
        if let s = (objs.first as? AVMetadataMachineReadableCodeObject)?.stringValue { session.stopRunning(); onCode(s) }
    }
}
