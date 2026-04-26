import SwiftUI

struct SettingsView: View {

    @EnvironmentObject var app: AppState
    @State private var openLime = false
    @State private var testPrintError: ErrorMessage?
    @State private var testPrinting = false

    private struct ErrorMessage: Identifiable {
        let id = UUID()
        let message: String
    }

    var body: some View {
        NavigationStack {
            Form {
                statusSection
                printerSection
                paperSection
                actionsSection
                aboutSection
            }
            .navigationTitle("Lime Print Bridge")
            .alert(item: $testPrintError) { msg in
                Alert(title: Text("Napaka"), message: Text(msg.message), dismissButton: .default(Text("V redu")))
            }
            .navigationDestination(isPresented: $openLime) {
                WebViewScreen()
                    .environmentObject(app)
                    .navigationBarBackButtonHidden(false)
                    .toolbar(.hidden, for: .tabBar)
            }
            .onAppear {
                Task {
                    // Give CB a moment to publish .poweredOn
                    try? await Task.sleep(nanoseconds: 200_000_000)
                    app.autoReconnectSavedPrinter()
                }
            }
        }
    }

    // MARK: - Sections

    private var statusSection: some View {
        Section("Status") {
            HStack {
                Circle()
                    .fill(statusColor)
                    .frame(width: 10, height: 10)
                Text(statusText)
                Spacer()
            }
            if let saved = app.registry.saved() {
                LabeledContent("Shranjen tiskalnik", value: saved.name)
            }
        }
    }

    private var printerSection: some View {
        Section {
            if isPoweredOff {
                Label("Vklopite Bluetooth v sistemskih nastavitvah.", systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
            } else if isUnauthorized {
                Label("App nima dovoljenja za Bluetooth — odprite Nastavitve → Lime Print Bridge.", systemImage: "lock")
                    .foregroundStyle(.orange)
            } else {
                if isScanning {
                    Button(role: .cancel, action: { app.bleManager.stopScan() }) {
                        Label("Ustavi iskanje", systemImage: "stop.circle")
                    }
                } else {
                    Button(action: { app.bleManager.startScan() }) {
                        Label("Iskanje BLE tiskalnikov", systemImage: "magnifyingglass")
                    }
                }
            }

            ForEach(filteredDevices) { dev in
                Button(action: { connectAndSave(dev) }) {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(dev.name)
                                .fontWeight(dev.isLikelyPrinter ? .semibold : .regular)
                            Text("RSSI \(dev.rssi)").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if isConnected(dev) {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                        } else if isConnecting(dev) {
                            ProgressView()
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text("BLE termalni tiskalnik")
        } footer: {
            Text("Krepke postavke so verjetni tiskalniki (ime ali oglaševani GATT service ustreza). Če vašega ne vidite, pritisnite gumb feed na tiskalniku za prebuditev.")
        }
    }

    private var paperSection: some View {
        Section("Širina papirja") {
            Picker("Stolpci", selection: paperBinding) {
                Text("58 mm (33 stolpcev)").tag(33)
                Text("80 mm (48 stolpcev)").tag(48)
            }
            .pickerStyle(.segmented)
        }
    }

    private var actionsSection: some View {
        Section {
            Button {
                Task { await runTestPrint() }
            } label: {
                HStack {
                    Label("Testno tiskanje", systemImage: "printer")
                    Spacer()
                    if testPrinting { ProgressView() }
                }
            }
            .disabled(!isReadyToPrint || testPrinting)

            Button {
                openLime = true
            } label: {
                Label("Odpri Lime Booking", systemImage: "globe")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!isReadyToPrint)
        }
    }

    private var aboutSection: some View {
        Section("O aplikaciji") {
            Text("Bridge med Lime Booking PWA in BLE termalnim tiskalnikom. Igra vlogo QZ Tray daemona — Lime PWA misli, da govori z desktop QZ Tray-jem in pošilja iste ESC/POS payload-e, mi pa jih preusmerimo na vaš BLE tiskalnik.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Helpers

    private var paperBinding: Binding<Int> {
        Binding(get: { app.registry.paperColumns },
                set: { app.registry.paperColumns = $0; app.objectWillChange.send() })
    }

    private var filteredDevices: [BleManager.Discovered] {
        // Show known printers first, then anything else with a real name
        let likely = app.bleManager.discovered.filter { $0.isLikelyPrinter }
        let rest   = app.bleManager.discovered.filter { !$0.isLikelyPrinter && $0.name != "(neimenovan)" }
        return likely + rest
    }

    private var statusText: String {
        switch app.bleManager.state {
        case .unknown:           return "Bluetooth: čakam…"
        case .poweredOff:        return "Bluetooth izklopljen"
        case .unauthorized:      return "Manjka Bluetooth dovoljenje"
        case .unsupported:       return "BLE ni podprt na tej napravi"
        case .poweredOn:         return "Bluetooth vklopljen"
        case .scanning:          return "Iščem tiskalnike…"
        case .connecting(let d): return "Povezujem na \(d.name)…"
        case .connected:         return "Povezan"
        case .error(let m):      return m
        }
    }

    private var statusColor: Color {
        switch app.bleManager.state {
        case .connected:    return .green
        case .scanning, .connecting: return .yellow
        case .error:        return .red
        case .poweredOn:    return .blue
        default:            return .gray
        }
    }

    private var isReadyToPrint: Bool {
        if case .connected = app.bleManager.state { return true }
        return false
    }

    private var isScanning: Bool {
        if case .scanning = app.bleManager.state { return true }
        return false
    }

    private var isPoweredOff: Bool {
        if case .poweredOff = app.bleManager.state { return true }
        return false
    }

    private var isUnauthorized: Bool {
        if case .unauthorized = app.bleManager.state { return true }
        return false
    }

    private func isConnected(_ d: BleManager.Discovered) -> Bool {
        if case .connected = app.bleManager.state, app.registry.saved()?.id == d.id { return true }
        return false
    }

    private func isConnecting(_ d: BleManager.Discovered) -> Bool {
        if case .connecting(let other) = app.bleManager.state, other.id == d.id { return true }
        return false
    }

    private func connectAndSave(_ d: BleManager.Discovered) {
        app.registry.save(.init(id: d.id, name: d.name))
        app.bleManager.connect(d)
    }

    private func runTestPrint() async {
        testPrinting = true
        defer { testPrinting = false }
        do {
            try await app.printer.testPrint()
        } catch {
            testPrintError = ErrorMessage(message: error.localizedDescription)
        }
    }
}

#Preview {
    SettingsView().environmentObject(AppState())
}
