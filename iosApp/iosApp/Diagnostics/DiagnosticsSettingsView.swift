import SwiftUI
import UniformTypeIdentifiers

struct DiagnosticsSettingsView: View {
    @StateObject private var viewModel = DiagnosticsViewModel()
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var marked = false
    @State private var confirmCellular = false
    @AppStorage("NuvioShareBehaviourLog") private var shareBehaviourLog = true
    @AppStorage("NuvioBehaviourLastUpload") private var lastUploadStamp = 0.0
    @AppStorage("NuvioBehaviourLastAttempt") private var lastAttemptStamp = 0.0
    @AppStorage("NuvioBehaviourLastAttemptOk") private var lastAttemptOk = false
    @AppStorage("NuvioBehaviourLastAttemptDetail") private var lastAttemptDetail = ""
    
    var body: some View {
        List {
            Section {
                Toggle("Save device error log", isOn: $viewModel.isEnabled)
                    .tint(.accentColor)
                
                Text("The error log stays on this phone.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section {
                Toggle("Share behaviour log", isOn: $shareBehaviourLog)
                    .tint(.accentColor)
                Text("Uploaded on Wi-Fi when sharing is on. Upload now can use cellular after you confirm.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                LabeledContent("Last uploaded", value: BehaviourShare.uploadedLabel(stamp: lastUploadStamp))
                LabeledContent(
                    "Last attempt",
                    value: BehaviourShare.lastAttemptLabel(
                        stamp: lastAttemptStamp,
                        ok: lastAttemptOk,
                        detail: lastAttemptDetail
                    )
                )
                Button("Upload now") {
                    uploadNow()
                }
                .disabled(!shareBehaviourLog)
                .accessibilityHint("Sends the behaviour log. Cellular data asks first.")
                Button {
                    BehaviourEvents.markFromSettings()
                    if reduceMotion {
                        var transaction = Transaction()
                        transaction.disablesAnimations = true
                        withTransaction(transaction) { marked = true }
                    } else {
                        marked = true
                    }
                } label: {
                    Label {
                        Text(marked ? "Marked" : "Something felt off")
                    } icon: {
                        Image(systemName: "flag")
                            .accessibilityHidden(true)
                    }
                }
                .accessibilityLabel("Something felt off")
                .accessibilityValue(marked ? "Marked" : "Not marked")
                .accessibilityHint("Saves a marker on this phone")
            }

            if let analysisTiming = viewModel.analysisTiming, !analysisTiming.isEmpty {
                Section {
                    Text(analysisTiming)
                        .font(.system(.caption, design: .monospaced))
                        .textSelection(.enabled)
                }
            }
            
            if viewModel.isEnabled {
                Section {
                    HStack {
                        Text("Log size")
                        Spacer()
                        Text(viewModel.logSize)
                            .foregroundStyle(.secondary)
                    }
                    
                    Button {
                        viewModel.shareLog()
                    } label: {
                        Label("Export log", systemImage: "square.and.arrow.up")
                    }
                    .disabled(!viewModel.hasLog)
                    
                    Button(role: .destructive) {
                        viewModel.showingClearConfirmation = true
                    } label: {
                        Label("Clear log", systemImage: "trash")
                    }
                    .disabled(!viewModel.hasLog)
                }
                
                Section {
                    Text("Records crashes, errors, HTTP status codes, launch time, playback performance, and buffering events.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    
                    Text("Never saves passwords, API keys, debrid links, full URLs (host only), or account info.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Device error log")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $viewModel.showingShareSheet) {
            if let url = viewModel.logURL {
                ShareSheet(items: [url])
            }
        }
        .confirmationDialog("Upload using cellular?", isPresented: $confirmCellular, titleVisibility: .visible) {
            Button("Upload") {
                BehaviourUploadTask.upload(now: true, allowCellular: true) { _ in }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The behaviour log will use cellular data.")
        }
        .alert("Clear device error log?", isPresented: $viewModel.showingClearConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Clear", role: .destructive) {
                viewModel.clearLog()
            }
        } message: {
            Text("This cannot be undone.")
        }
        .onAppear {
            viewModel.refresh()
        }
    }

    private func uploadNow() {
        switch BehaviourUploadTask.route() {
        case .wifi:
            BehaviourUploadTask.upload(now: true) { _ in }
        case .cellular:
            confirmCellular = true
        case .offline:
            BehaviourShare.recordAttempt(ok: false, detail: "No network")
        }
    }
}

@MainActor
final class DiagnosticsViewModel: ObservableObject {
    @Published var isEnabled: Bool {
        didSet {
            DiagnosticsManager.shared.isEnabled = isEnabled
            refresh()
        }
    }
    
    @Published var logSize: String = "0 KB"
    @Published var hasLog: Bool = false
    @Published var showingShareSheet = false
    @Published var showingClearConfirmation = false
    @Published var logURL: URL?
    @Published var analysisTiming: String?
    
    init() {
        self.isEnabled = DiagnosticsManager.shared.isEnabled
        refresh()
    }
    
    func refresh() {
        logSize = DiagnosticsManager.shared.getLogSize()
        hasLog = DiagnosticsManager.shared.exportLog() != nil
        analysisTiming = DiagnosticsManager.shared.latestAnalysisTiming()
    }
    
    func shareLog() {
        guard let source = DiagnosticsManager.shared.exportLog() else { return }
        let copy = FileManager.default.temporaryDirectory.appendingPathComponent("nuvio-device-error-\(UUID().uuidString).txt")
        do {
            if FileManager.default.fileExists(atPath: copy.path) {
                try FileManager.default.removeItem(at: copy)
            }
            try FileManager.default.copyItem(at: source, to: copy)
        } catch {
            return
        }
        logURL = copy
        showingShareSheet = true
    }
    
    func clearLog() {
        DiagnosticsManager.shared.clearLog()
        refresh()
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        return controller
    }
    
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
