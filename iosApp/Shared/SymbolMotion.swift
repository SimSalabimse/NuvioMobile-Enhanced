import SwiftUI
import UIKit

/// How an SF Symbol is drawn. Rendering stays on when Reduce Motion is on; effects do not.
enum NuvioSymbolStyle: Equatable {
    case hierarchical
    case palette
}

/// An effect that keeps running while a state is active.
enum NuvioRepeatingSymbol: Equatable {
    case none
    case pulse
    case variableColor
}

/// An effect that plays once when a value changes.
enum NuvioDiscreteSymbol: Equatable {
    case none
    case bounce
    case replace
}

struct NuvioSymbolRequest: Equatable {
    var style: NuvioSymbolStyle
    var repeating: NuvioRepeatingSymbol
    var discrete: NuvioDiscreteSymbol
}

struct NuvioSymbolPlan: Equatable {
    var style: NuvioSymbolStyle
    var repeating: NuvioRepeatingSymbol
    var discrete: NuvioDiscreteSymbol

    var hasEffect: Bool {
        repeating != .none || discrete != .none
    }

    var renderingMode: SymbolRenderingMode {
        style == .palette ? .palette : .hierarchical
    }
}

/// The one decision for symbol effects. Reduce Motion leaves a static symbol.
enum NuvioSymbolMotion {
    static let still = NuvioSymbolRequest(style: .hierarchical, repeating: .none, discrete: .none)

    /// Profile add. Bounces when the trigger changes.
    static let add = NuvioSymbolRequest(style: .hierarchical, repeating: .none, discrete: .bounce)

    /// A running transfer uses variable color. A status flip replaces the symbol.
    static func download(downloading: Bool) -> NuvioSymbolRequest {
        NuvioSymbolRequest(
            style: .hierarchical,
            repeating: downloading ? .variableColor : .none,
            discrete: .replace
        )
    }

    /// StandBy download glyph: the circle and the arrow take two shades.
    static func standby(downloading: Bool) -> NuvioSymbolRequest {
        NuvioSymbolRequest(
            style: .palette,
            repeating: downloading ? .variableColor : .none,
            discrete: .replace
        )
    }

    static func plan(_ request: NuvioSymbolRequest, reduceMotion: Bool) -> NuvioSymbolPlan {
        guard !reduceMotion else {
            return NuvioSymbolPlan(style: request.style, repeating: .none, discrete: .none)
        }
        return NuvioSymbolPlan(
            style: request.style,
            repeating: request.repeating,
            discrete: request.discrete
        )
    }
}

extension View {
    /// Hierarchical or palette SF Symbol. Effects need iOS 17 and are omitted when Reduce Motion is on.
    func nuvioSymbol<Trigger: Equatable>(
        _ request: NuvioSymbolRequest = NuvioSymbolMotion.still,
        active: Bool = false,
        trigger: Trigger
    ) -> some View {
        modifier(NuvioSymbolModifier(request: request, active: active, trigger: trigger))
    }

    func nuvioSymbol(
        _ request: NuvioSymbolRequest = NuvioSymbolMotion.still,
        active: Bool = false
    ) -> some View {
        nuvioSymbol(request, active: active, trigger: 0)
    }
}

enum NuvioSymbolImage {
    /// Tab fallback when the custom asset is missing. Hierarchical, no effect.
    static func hierarchical(systemName: String) -> UIImage {
        let configuration = UIImage.SymbolConfiguration(hierarchicalColor: .tintColor)
        return UIImage(systemName: systemName, withConfiguration: configuration) ?? UIImage()
    }
}

private struct NuvioSymbolModifier<Trigger: Equatable>: ViewModifier {
    var request: NuvioSymbolRequest
    var active: Bool
    var trigger: Trigger

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @ViewBuilder
    func body(content: Content) -> some View {
        let plan = NuvioSymbolMotion.plan(request, reduceMotion: reduceMotion)
        if #available(iOS 17.0, *), plan.hasEffect {
            NuvioAppliedSymbol(
                plan: plan,
                active: active,
                trigger: trigger,
                symbol: content.symbolRenderingMode(plan.renderingMode)
            )
        } else {
            content.symbolRenderingMode(plan.renderingMode)
        }
    }
}

@available(iOS 17.0, *)
private struct NuvioAppliedSymbol<Trigger: Equatable, Symbol: View>: View {
    var plan: NuvioSymbolPlan
    var active: Bool
    var trigger: Trigger
    var symbol: Symbol

    var body: some View {
        switch (plan.repeating, plan.discrete) {
        case (.none, .none):
            symbol
        case (.none, .bounce):
            symbol.symbolEffect(.bounce, value: trigger)
        case (.none, .replace):
            symbol
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.replace, value: trigger)
        case (.pulse, .none):
            symbol.symbolEffect(.pulse, isActive: active)
        case (.pulse, .bounce):
            symbol
                .symbolEffect(.bounce, value: trigger)
                .symbolEffect(.pulse, isActive: active)
        case (.pulse, .replace):
            symbol
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.replace, value: trigger)
                .symbolEffect(.pulse, isActive: active)
        case (.variableColor, .none):
            symbol.symbolEffect(.variableColor, isActive: active)
        case (.variableColor, .bounce):
            symbol
                .symbolEffect(.bounce, value: trigger)
                .symbolEffect(.variableColor, isActive: active)
        case (.variableColor, .replace):
            symbol
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.replace, value: trigger)
                .symbolEffect(.variableColor, isActive: active)
        }
    }
}
