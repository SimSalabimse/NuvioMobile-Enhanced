import SwiftUI

struct DiagnosticsIntroSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var isEnabled = true
    
    var body: some View {
        NavigationView {
            VStack(spacing: 24) {
                Spacer()
                
                Image(systemName: "stethoscope")
                    .font(.system(size: 60))
                    .foregroundStyle(.blue)
                    .padding(.bottom, 8)
                
                Text("Diagnostics log")
                    .font(.title2.bold())
                    .accessibilityIdentifier("diagnostics.intro.title")
                
                VStack(alignment: .leading, spacing: 16) {
                    bulletPoint(
                        "Saves crashes, errors and speed info to help fix bugs"
                    )
                    
                    bulletPoint(
                        "Stays on this phone and is never uploaded"
                    )
                    
                    bulletPoint(
                        "Never saves passwords, keys, debrid links or account info"
                    )
                    
                    bulletPoint(
                        "Export or clear anytime in Settings > Diagnostics"
                    )
                }
                .padding(.horizontal, 8)
                
                Spacer()
                
                Toggle("Save diagnostics log", isOn: $isEnabled)
                    .tint(.blue)
                    .accessibilityIdentifier("diagnostics.intro.saveToggle")
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
            }
            .padding(24)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        DiagnosticsManager.shared.isEnabled = isEnabled
                        DiagnosticsManager.shared.hasSeenIntro = true
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .accessibilityIdentifier("diagnostics.intro.done")
                }
            }
        }
    }
    
    private func bulletPoint(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 20))
                .foregroundStyle(.blue)
            
            Text(text)
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

@MainActor
final class DiagnosticsIntroCoordinator: ObservableObject {
    @Published var shouldShowIntro = false
    
    func checkAndShowIntro() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self = self else { return }
            if !DiagnosticsManager.shared.hasSeenIntro {
                self.shouldShowIntro = true
            }
        }
    }
}
