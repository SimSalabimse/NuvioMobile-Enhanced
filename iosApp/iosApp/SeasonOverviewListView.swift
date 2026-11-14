import SwiftUI
import UIKit

// Read-only list inside the existing system sheet.
// Kotlin sends the rows. This view does not write watch progress or submit anything.
// Swipe stays installed when Reduce Motion is on. Symbol effects are the only motion,
// and they run when a mark changes or while a download is in progress, not while scrolling.

private let seasonOverviewField = "\u{001F}"

struct SeasonOverviewSymbol: Equatable {
    /// `Y` yours, `U` public, `M` missing.
    let state: String
    let token: String
    let accessibility: String
}

struct SeasonOverviewRow: Identifiable, Equatable {
    let id: String
    let episode: String
    let title: String
    let watched: Bool
    let publicLine: String
    let yoursLine: String
    let included: Bool
    let swipeWatchPrivately: Bool
    let symbols: [SeasonOverviewSymbol]
    let spoken: String
}

struct SeasonOverviewSnapshot: Equatable {
    var title: String
    var statusLine: String?
    var selecting: Bool
    var selectLabel: String
    var downloadLabel: String
    var running: Bool
    var buttonEnabled: Bool
    var downloadOffered: Bool
    var openLabel: String
    var watchLabel: String
    var includeLabel: String
    var removeLabel: String
    var swipeInstalled: Bool
    var watchedLabel: String
    var legend: String
    var rows: [SeasonOverviewRow]

    static let empty = SeasonOverviewSnapshot(
        title: "",
        statusLine: "Loading",
        selecting: false,
        selectLabel: "Select",
        downloadLabel: "",
        running: false,
        buttonEnabled: false,
        downloadOffered: false,
        openLabel: "Open",
        watchLabel: "Watch privately",
        includeLabel: "Include in download",
        removeLabel: "Remove from download",
        swipeInstalled: true,
        watchedLabel: "Watched",
        legend: "",
        rows: []
    )

    static func parse(_ payload: String) -> SeasonOverviewSnapshot? {
        let lines = payload.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard lines.count >= 2, lines[0] == "SO1" else { return nil }
        let header = splitFields(lines[1])
        guard header.count == 15 else { return nil }
        var rows: [SeasonOverviewRow] = []
        if lines.count > 2 {
            for line in lines.dropFirst(2) where !line.isEmpty {
                let fields = splitFields(line)
                guard fields.count == 14 else { return nil }
                let codes = fields[7].split(separator: ",").map(String.init)
                guard codes.count == 4 else { return nil }
                let symbols: [SeasonOverviewSymbol] = (0..<4).map { index in
                    let state = codes[index]
                    return SeasonOverviewSymbol(
                        state: state,
                        token: "\(index):\(state)",
                        accessibility: fields[8 + index]
                    )
                }
                guard let _ = Int(fields[1]) else { return nil }
                rows.append(
                    SeasonOverviewRow(
                        id: fields[0],
                        episode: fields[1],
                        title: fields[2],
                        watched: fields[3] == "1",
                        publicLine: fields[4],
                        yoursLine: fields[13],
                        included: fields[5] == "1",
                        swipeWatchPrivately: fields[6] == "1",
                        symbols: symbols,
                        spoken: fields[12]
                    )
                )
            }
        }
        return SeasonOverviewSnapshot(
            title: header[0],
            statusLine: header[1].isEmpty ? nil : header[1],
            selecting: header[2] == "1",
            selectLabel: header[3],
            downloadLabel: header[4],
            running: header[5] == "1",
            buttonEnabled: header[6] == "1",
            downloadOffered: header[7] == "1",
            openLabel: header[8],
            watchLabel: header[9],
            includeLabel: header[10],
            removeLabel: header[11],
            swipeInstalled: header[12] == "1",
            watchedLabel: header[13],
            legend: header[14],
            rows: rows
        )
    }
}

private func splitFields(_ line: String) -> [String] {
    line.components(separatedBy: seasonOverviewField).map(unescapeField)
}

private func unescapeField(_ value: String) -> String {
    var out = ""
    var index = value.startIndex
    while index < value.endIndex {
        let character = value[index]
        if character == "\\" {
            let next = value.index(after: index)
            if next < value.endIndex {
                switch value[next] {
                case "\\":
                    out.append("\\")
                case "n":
                    out.append("\n")
                case "r":
                    out.append("\r")
                case "f":
                    out.append(seasonOverviewField)
                default:
                    out.append(character)
                    index = next
                    continue
                }
                index = value.index(after: next)
                continue
            }
        }
        out.append(character)
        index = value.index(after: index)
    }
    return out
}

final class SeasonOverviewListStore: ObservableObject {
    static let shared = SeasonOverviewListStore()
    @Published var snapshot = SeasonOverviewSnapshot.empty

    func replace(_ payload: String) {
        snapshot = SeasonOverviewSnapshot.parse(payload) ?? SeasonOverviewSnapshot(
            title: snapshot.title,
            statusLine: "No episodes yet",
            selecting: false,
            selectLabel: snapshot.selectLabel,
            downloadLabel: snapshot.downloadLabel,
            running: false,
            buttonEnabled: false,
            downloadOffered: false,
            openLabel: snapshot.openLabel,
            watchLabel: snapshot.watchLabel,
            includeLabel: snapshot.includeLabel,
            removeLabel: snapshot.removeLabel,
            swipeInstalled: true,
            watchedLabel: snapshot.watchedLabel,
            legend: snapshot.legend,
            rows: []
        )
    }
}

private enum SeasonOverviewBridge {
    static var onOpen: (@convention(c) (UnsafePointer<CChar>?) -> Void)?
    static var onWatch: (@convention(c) (UnsafePointer<CChar>?) -> Void)?
    static var onInclude: (@convention(c) (UnsafePointer<CChar>?) -> Void)?
    static var onSelecting: (@convention(c) (Int32) -> Void)?
    static var onDownload: (@convention(c) () -> Void)?
    static var onCancel: (@convention(c) () -> Void)?

    static func open(_ id: String) { call(onOpen, id) }
    static func watch(_ id: String) { call(onWatch, id) }
    static func include(_ id: String) { call(onInclude, id) }
    static func selecting(_ on: Bool) {
        let flag: Int32 = on ? 1 : 0
        runOnMain { onSelecting?(flag) }
    }
    static func download() { runOnMain { onDownload?() } }
    static func cancel() { runOnMain { onCancel?() } }

    /// Copy the string, then call on the main thread while `withCString` is still in scope.
    private static func call(
        _ function: (@convention(c) (UnsafePointer<CChar>?) -> Void)?,
        _ value: String
    ) {
        let owned = value
        runOnMain {
            owned.withCString { function?($0) }
        }
    }

    private static func runOnMain(_ body: @escaping () -> Void) {
        if Thread.isMainThread {
            body()
        } else {
            DispatchQueue.main.async(execute: body)
        }
    }
}

struct SeasonOverviewListRoot: View {
    @ObservedObject var store: SeasonOverviewListStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let snapshot = store.snapshot
        NavigationStack {
            List {
                if let status = snapshot.statusLine {
                    Text(status)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .accessibilityIdentifier("season-overview-status")
                } else {
                    if !snapshot.legend.isEmpty {
                        Text(snapshot.legend)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                            .accessibilityIdentifier("season-overview-legend")
                    }
                    ForEach(snapshot.rows) { row in
                        SeasonOverviewEpisodeRow(
                            row: row,
                            snapshot: snapshot,
                            reduceMotion: reduceMotion
                        )
                    }
                }
            }
            .listStyle(.insetGrouped)
            .environment(\.defaultMinListRowHeight, 44)
            .navigationTitle(snapshot.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if snapshot.downloadOffered {
                    ToolbarItem(placement: .topBarLeading) {
                        Button(snapshot.selectLabel) {
                            SeasonOverviewBridge.selecting(!snapshot.selecting)
                        }
                        .accessibilityIdentifier("season-overview-select")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            if snapshot.running {
                                SeasonOverviewBridge.cancel()
                            } else {
                                SeasonOverviewBridge.download()
                            }
                        } label: {
                            Label {
                                Text(snapshot.downloadLabel)
                                    .font(.body)
                            } icon: {
                                DownloadGlyph(running: snapshot.running, reduceMotion: reduceMotion)
                            }
                        }
                        .disabled(!snapshot.buttonEnabled)
                        .accessibilityIdentifier("season-overview-download")
                    }
                }
            }
        }
        .accessibilityIdentifier("season-overview-list")
    }
}

private struct SeasonOverviewEpisodeRow: View {
    let row: SeasonOverviewRow
    let snapshot: SeasonOverviewSnapshot
    let reduceMotion: Bool

    var body: some View {
        Button {
            if snapshot.selecting {
                SeasonOverviewBridge.include(row.id)
            } else {
                SeasonOverviewBridge.open(row.id)
            }
        } label: {
            SeasonOverviewEpisodeLabel(row: row, snapshot: snapshot, reduceMotion: reduceMotion)
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            if row.swipeWatchPrivately {
                Button {
                    SeasonOverviewBridge.watch(row.id)
                } label: {
                    Label(snapshot.watchLabel, systemImage: "eye.slash")
                }
            }
        }
        .contextMenu {
            Button {
                SeasonOverviewBridge.open(row.id)
            } label: {
                Label(snapshot.openLabel, systemImage: "play")
            }
            Button {
                SeasonOverviewBridge.watch(row.id)
            } label: {
                Label(snapshot.watchLabel, systemImage: "eye.slash")
            }
            if snapshot.downloadOffered {
                Button {
                    SeasonOverviewBridge.include(row.id)
                } label: {
                    Label(
                        row.included ? snapshot.removeLabel : snapshot.includeLabel,
                        systemImage: "arrow.down.circle"
                    )
                }
            }
        }
        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("season-overview-row-\(row.id)")
    }
}

private struct SeasonOverviewEpisodeLabel: View {
    let row: SeasonOverviewRow
    let snapshot: SeasonOverviewSnapshot
    let reduceMotion: Bool

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .center, spacing: 8) {
                leading
                textBlock(fillsWidth: false)
                symbols
            }
            .fixedSize(horizontal: true, vertical: false)
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    leading
                    textBlock(fillsWidth: true)
                }
                symbols
            }
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
    }

    private var leading: some View {
        HStack(spacing: 8) {
            if snapshot.selecting {
                Image(systemName: row.included ? "checkmark.circle.fill" : "circle")
                    .font(.body)
                    .foregroundStyle(row.included ? Color.primary : Color.secondary)
                    .accessibilityLabel(row.included ? snapshot.removeLabel : snapshot.includeLabel)
            }
            Text(row.episode)
                .font(.body.monospacedDigit())
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("season-overview-episode-\(row.id)")
            if row.watched {
                Image(systemName: "checkmark")
                    .font(.body)
                    .foregroundStyle(.primary)
                    .accessibilityLabel(snapshot.watchedLabel)
            }
        }
    }

    private func textBlock(fillsWidth: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(row.title)
                .font(.body)
                .foregroundStyle(.primary)
                .frame(maxWidth: fillsWidth ? CGFloat.infinity : nil, alignment: .leading)
            Text(row.publicLine)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: fillsWidth ? CGFloat.infinity : nil, alignment: .leading)
            if !row.yoursLine.isEmpty {
                Text(row.yoursLine)
                    .font(.footnote)
                    .foregroundStyle(.primary)
                    .frame(maxWidth: fillsWidth ? CGFloat.infinity : nil, alignment: .leading)
                    .accessibilityIdentifier("season-overview-yours-\(row.id)")
            }
        }
    }

    private var symbols: some View {
        HStack(spacing: 8) {
            ForEach(row.symbols.indices, id: \.self) { index in
                SegmentWord(symbol: row.symbols[index], reduceMotion: reduceMotion)
            }
        }
    }
}

/// The words are the state. Colour and fill repeat them; they are not the only difference.
private struct SegmentWord: View {
    let symbol: SeasonOverviewSymbol
    let reduceMotion: Bool

    var body: some View {
        label
            .font(.caption)
            .foregroundStyle(foreground)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(background, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay {
                if symbol.state == "U" {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(Color.secondary, lineWidth: 1)
                }
            }
            .accessibilityLabel(symbol.accessibility)
            .accessibilityIdentifier("season-overview-segment-\(symbol.token)")
    }

    @ViewBuilder
    private var label: some View {
        if reduceMotion {
            Text(symbol.accessibility)
        } else if #available(iOS 17.0, *) {
            Text(symbol.accessibility)
                .contentTransition(.interpolate)
                .animation(.default, value: symbol.token)
        } else {
            Text(symbol.accessibility)
        }
    }

    private var foreground: Color {
        switch symbol.state {
        case "Y":
            return Color(uiColor: .systemBackground)
        case "U":
            return .primary
        default:
            return .secondary
        }
    }

    private var background: Color {
        switch symbol.state {
        case "Y":
            return .accentColor
        case "U":
            return Color.primary.opacity(0.08)
        default:
            return .clear
        }
    }
}

private struct DownloadGlyph: View {
    let running: Bool
    let reduceMotion: Bool

    var body: some View {
        if reduceMotion || !running {
            Image(systemName: "arrow.down.circle")
        } else {
            AnimatedDownloadGlyph()
        }
    }
}

private struct AnimatedDownloadGlyph: View {
    var body: some View {
        if #available(iOS 17.0, *) {
            Image(systemName: "arrow.down.circle")
                .symbolEffect(.variableColor.iterative, isActive: true)
        } else {
            Image(systemName: "arrow.down.circle")
        }
    }
}

private final class SeasonOverviewContainer: UIView {
    let controller: UIHostingController<SeasonOverviewListRoot>

    override init(frame: CGRect) {
        controller = UIHostingController(rootView: SeasonOverviewListRoot(store: .shared))
        super.init(frame: frame)
        backgroundColor = .systemGroupedBackground
        controller.view.backgroundColor = .systemGroupedBackground
        controller.view.translatesAutoresizingMaskIntoConstraints = true
        controller.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        controller.view.frame = bounds
        addSubview(controller.view)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        controller.view.frame = bounds
        guard window != nil, controller.parent == nil, let parent = nearestViewController() else { return }
        parent.addChild(controller)
        controller.didMove(toParent: parent)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            detachHostingController()
            return
        }
        guard let parent = nearestViewController(), controller.parent !== parent else { return }
        detachHostingController()
        parent.addChild(controller)
        controller.didMove(toParent: parent)
    }

    private func detachHostingController() {
        guard controller.parent != nil else { return }
        controller.willMove(toParent: nil)
        controller.removeFromParent()
    }

    private func nearestViewController() -> UIViewController? {
        var responder: UIResponder? = self
        while let next = responder?.next {
            if let controller = next as? UIViewController {
                return controller
            }
            responder = next
        }
        return nil
    }
}

@_cdecl("NuvioSeasonOverviewBind")
public func NuvioSeasonOverviewBind(
    _ onOpen: (@convention(c) (UnsafePointer<CChar>?) -> Void)?,
    _ onWatchPrivately: (@convention(c) (UnsafePointer<CChar>?) -> Void)?,
    _ onInclude: (@convention(c) (UnsafePointer<CChar>?) -> Void)?,
    _ onSelecting: (@convention(c) (Int32) -> Void)?,
    _ onDownload: (@convention(c) () -> Void)?,
    _ onCancel: (@convention(c) () -> Void)?
) {
    SeasonOverviewBridge.onOpen = onOpen
    SeasonOverviewBridge.onWatch = onWatchPrivately
    SeasonOverviewBridge.onInclude = onInclude
    SeasonOverviewBridge.onSelecting = onSelecting
    SeasonOverviewBridge.onDownload = onDownload
    SeasonOverviewBridge.onCancel = onCancel
}

@_cdecl("NuvioSeasonOverviewApply")
public func NuvioSeasonOverviewApply(_ payload: UnsafePointer<CChar>?) {
    let text = payload.map { String(cString: $0) } ?? ""
    if Thread.isMainThread {
        SeasonOverviewListStore.shared.replace(text)
    } else {
        DispatchQueue.main.async {
            SeasonOverviewListStore.shared.replace(text)
        }
    }
}

@_cdecl("NuvioSeasonOverviewRootView")
public func NuvioSeasonOverviewRootView() -> UIView {
    SeasonOverviewContainer(frame: .zero)
}
