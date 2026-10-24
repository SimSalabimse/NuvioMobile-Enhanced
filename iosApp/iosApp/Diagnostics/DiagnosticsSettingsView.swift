import SwiftUI
import UniformTypeIdentifiers

struct DiagnosticsSettingsView: View {
    @StateObject private var viewModel = DiagnosticsViewModel()
    @Environment(\.dismiss) private var dismiss
    
    var body: some View {
        List {
            Section {
                Toggle("Save diagnostics log", isOn: $viewModel.isEnabled)
                    .tint(.accentColor)
                    .accessibilityIdentifier("diagnostics.settings.saveToggle")
                
                Text("Stays on this phone. Nothing is uploaded.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
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
                    .accessibilityIdentifier("diagnostics.settings.export")
                    
                    Button(role: .destructive) {
                        viewModel.showingClearConfirmation = true
                    } label: {
                        Label("Clear log", systemImage: "trash")
                    }
                    .disabled(!viewModel.hasLog)
                    .accessibilityIdentifier("diagnostics.settings.clear")
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
        .navigationTitle("Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $viewModel.showingShareSheet) {
            if let url = viewModel.logURL {
                ShareSheet(items: [url])
            }
        }
        .alert("Clear diagnostics log?", isPresented: $viewModel.showingClearConfirmation) {
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
    
    init() {
        self.isEnabled = DiagnosticsManager.shared.isEnabled
        refresh()
    }
    
    func refresh() {
        logSize = DiagnosticsManager.shared.getLogSize()
        hasLog = DiagnosticsManager.shared.exportLog() != nil
    }
    
    func shareLog() {
        logURL = DiagnosticsManager.shared.exportLog()
        if logURL != nil {
            showingShareSheet = true
        }
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
