import Foundation
import UIKit
import QuartzCore
import CoreVideo
import Metal
import ObjectiveC

/// Player view and layer changes belong on the main thread. mpv's `vo` thread
/// must not lay out a UIView; Auto Layout asserts and the process dies.
enum PlayerSurfaceThreading {
    static var isMainThread: Bool { Thread.isMainThread }

    static func assertMainThread(_ what: StaticString = #function) {
        #if DEBUG
        precondition(Thread.isMainThread, "\(what) changes the player view off the main thread")
        #endif
    }
}

class MetalLayer: CAMetalLayer {
    weak var pictureInPictureSink: PlayerPictureInPictureController?
    private let pictureInPictureLock = NSLock()
    private var consumesVideo = false
    private var presentationGeneration: UInt = 0
    private var presentationObserver: (generation: UInt, callback: (TimeInterval) -> Void)?

    func observeNextPresentedDrawable(_ callback: @escaping (TimeInterval) -> Void) {
        pictureInPictureLock.lock()
        presentationGeneration &+= 1
        presentationObserver = (presentationGeneration, callback)
        pictureInPictureLock.unlock()
    }

    func cancelPresentationObserver() {
        pictureInPictureLock.lock()
        presentationGeneration &+= 1
        presentationObserver = nil
        pictureInPictureLock.unlock()
    }

    private func attachPresentationObserver(to drawable: CAMetalDrawable, generation: UInt?) {
        guard let generation else { return }
        pictureInPictureLock.lock()
        let observer = presentationObserver?.generation == generation ? presentationObserver : nil
        if observer != nil { presentationObserver = nil }
        pictureInPictureLock.unlock()
        if let observer {
            let notify: (TimeInterval) -> Void = { [weak self] time in
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    self.pictureInPictureLock.lock()
                    let current = self.presentationGeneration == generation
                    self.pictureInPictureLock.unlock()
                    if current { observer.callback(time) }
                }
            }
#if targetEnvironment(simulator)
            notify(CACurrentMediaTime())
#else
            drawable.addPresentedHandler { notify($0.presentedTime) }
#endif
        }
    }

    @objc var nuvioPiPConsumesVideo: Bool {
        get {
            pictureInPictureLock.lock()
            defer { pictureInPictureLock.unlock() }
            return consumesVideo
        }
        set {
            pictureInPictureLock.lock()
            consumesVideo = newValue
            pictureInPictureLock.unlock()
        }
    }

    @objc func nuvioEnqueuePiPFrame(_ pixelBuffer: CVPixelBuffer, presentationTime: Double, isPictureInPictureFrame: Bool) {
        pictureInPictureSink?.submit(pixelBuffer, pts: presentationTime, isPictureInPictureFrame: isPictureInPictureFrame)
    }

    var onDrawablePresented: ((CAMetalDrawable) -> Void)?

    var onRenderingSuspensionChanged: ((Bool, String) -> Void)?

    var isDrawableCaptureArmed: Bool {
        get {
            captureLock.lock()
            defer { captureLock.unlock() }
            return storedIsDrawableCaptureArmed
        }
        set {
            captureLock.lock()
            storedIsDrawableCaptureArmed = newValue
            captureLock.unlock()
        }
    }

    var capturesWithoutPresentation: Bool {
        get {
            captureLock.lock()
            defer { captureLock.unlock() }
            return storedCapturesWithoutPresentation
        }
        set {
            captureLock.lock()
            storedCapturesWithoutPresentation = newValue
            captureLock.unlock()
        }
    }

    private(set) var capturedDrawableCount: UInt64 = 0

    private(set) var nextDrawableCallCount: UInt64 = 0

    private var storedIsDrawableCaptureArmed = false
    private var storedCapturesWithoutPresentation = false
    private var pendingDrawable: CAMetalDrawable?
    private let captureLock = NSLock()

    private static let failureThresholdBeforeSuspension = 2
    private static let suspendedRetryInterval: CFTimeInterval = 1.0
    private static let suspendedIdleSleep: TimeInterval = 0.03

    private var isRenderingSuspended = false
    private var isSuspensionLatched = false
    private var consecutiveAcquisitionFailures = 0
    private var lastSuspendedProbeTime: CFTimeInterval = 0

    var isSuspended: Bool {
        captureLock.lock()
        defer { captureLock.unlock() }
        return isRenderingSuspended
    }

    override var drawableSize: CGSize {
        get { return super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 {
                // MoltenVK writes this from the vo thread. Commit that write
                // before returning so the thread's exit has nothing to lay out.
                withoutLeakingVoTransaction { super.drawableSize = newValue }
            }
        }
    }

    override var wantsExtendedDynamicRangeContent: Bool {
        get { return super.wantsExtendedDynamicRangeContent }
        set {
            if Thread.isMainThread {
                super.wantsExtendedDynamicRangeContent = newValue
            } else {
                DispatchQueue.main.async {
                    super.wantsExtendedDynamicRangeContent = newValue
                }
            }
        }
    }

    func setRenderingSuspended(_ suspended: Bool, reason: String) {
        captureLock.lock()
        let changed = isRenderingSuspended != suspended
        isRenderingSuspended = suspended
        isSuspensionLatched = suspended
        consecutiveAcquisitionFailures = 0
        lastSuspendedProbeTime = suspended ? CACurrentMediaTime() : 0
        let stale = pendingDrawable
        pendingDrawable = nil
        captureLock.unlock()

        withExtendedLifetime(stale) {}

        if changed { notifyRenderingSuspension(suspended, reason: reason) }
    }

    func releasePendingDrawable() {
        captureLock.lock()
        let stale = pendingDrawable
        pendingDrawable = nil
        captureLock.unlock()
        withExtendedLifetime(stale) {}
    }

    override func nextDrawable() -> CAMetalDrawable? {
        captureLock.lock()
        nextDrawableCallCount &+= 1
        if isRenderingSuspended {
            let now = CACurrentMediaTime()
            let shouldProbe = now - lastSuspendedProbeTime >= Self.suspendedRetryInterval
            if shouldProbe {
                lastSuspendedProbeTime = now
            }
            let stale = pendingDrawable
            pendingDrawable = nil
            captureLock.unlock()
            withExtendedLifetime(stale) {}

            guard shouldProbe else {
                Thread.sleep(forTimeInterval: Self.suspendedIdleSleep)
                return nil
            }
        } else {
            captureLock.unlock()
        }

        pictureInPictureLock.lock()
        let observerGeneration = presentationObserver?.generation
        pictureInPictureLock.unlock()
        // mpv calls this on the `vo` thread. nextDrawable opens an implicit
        // CATransaction. When that thread exits (next episode tears the
        // player down), Core Animation commits it and lays out the hosting
        // UIView. Auto Layout then asserts off the main thread
        // (_AssertAutoLayoutOnAllowedThreadsOnly). Commit before returning.
        let drawable = withoutLeakingVoTransaction { super.nextDrawable() }
        if let drawable {
            VoThreadPresent.install(on: drawable)
            attachPresentationObserver(to: drawable, generation: observerGeneration)
        }
        let acquisitionFailed = drawable == nil

        captureLock.lock()
        var didSuspend = false
        var didResume = false
        if acquisitionFailed {
            consecutiveAcquisitionFailures += 1
            if !isRenderingSuspended &&
                consecutiveAcquisitionFailures >= Self.failureThresholdBeforeSuspension {
                isRenderingSuspended = true
                lastSuspendedProbeTime = CACurrentMediaTime()
                didSuspend = true
            }
        } else {
            consecutiveAcquisitionFailures = 0
            if isRenderingSuspended && !isSuspensionLatched {
                isRenderingSuspended = false
                lastSuspendedProbeTime = 0
                didResume = true
            }
        }

        let suspendedNow = isRenderingSuspended
        let armed = storedIsDrawableCaptureArmed
        let handler = onDrawablePresented
        let deferred = storedCapturesWithoutPresentation && !suspendedNow
        let previous = pendingDrawable
        #if targetEnvironment(simulator)
        // iPhoneSimulator 27.0's CAMetalDrawable has no addPresentedHandler.
        // Hold this drawable and deliver it on the next frame, after the
        // renderer has submitted it. Device builds use the presented callback.
        let holdForLaterCapture = !deferred && !suspendedNow && armed && handler != nil && drawable != nil
        pendingDrawable = (deferred || holdForLaterCapture) ? drawable : nil
        #else
        pendingDrawable = deferred ? drawable : nil
        #endif
        captureLock.unlock()

        if didSuspend { notifyRenderingSuspension(true, reason: "drawable-acquisition-stalled") }
        if didResume { notifyRenderingSuspension(false, reason: "drawable-acquired") }

        guard !suspendedNow else { return drawable }

        guard armed, let drawable, let handler else { return drawable }

        if deferred {
            if let previous {
                captureLock.lock()
                capturedDrawableCount &+= 1
                captureLock.unlock()
                deliverPresented(previous, handler: handler)
            }
            return drawable
        }

        #if targetEnvironment(simulator)
        if let previous {
            captureLock.lock()
            capturedDrawableCount &+= 1
            captureLock.unlock()
            deliverPresented(previous, handler: handler)
        }
        #else
        drawable.addPresentedHandler { [weak self] _ in
            guard let self else { return }
            self.captureLock.lock()
            self.capturedDrawableCount &+= 1
            self.captureLock.unlock()
            self.deliverPresented(drawable, handler: handler)
        }
        #endif

        return drawable
    }

    /// Layer writes from mpv's `vo` thread must not leave an implicit
    /// transaction for `CA::Transaction::release_thread` to commit.
    private func withoutLeakingVoTransaction(_ body: () -> Void) {
        guard !Thread.isMainThread else {
            body()
            return
        }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        body()
        CATransaction.commit()
    }

    private func withoutLeakingVoTransaction<T>(_ body: () -> T) -> T {
        guard !Thread.isMainThread else { return body() }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let value = body()
        CATransaction.commit()
        return value
    }

    private func deliverPresented(_ drawable: CAMetalDrawable, handler: @escaping (CAMetalDrawable) -> Void) {
        if Thread.isMainThread {
            handler(drawable)
        } else {
            DispatchQueue.main.async { handler(drawable) }
        }
    }

    private func notifyRenderingSuspension(_ suspended: Bool, reason: String) {
        guard let callback = onRenderingSuspensionChanged else { return }
        if Thread.isMainThread {
            callback(suspended, reason)
        } else {
            DispatchQueue.main.async { callback(suspended, reason) }
        }
    }
}

/// `present` runs on the vo thread after `nextDrawable` returns. Commit that
/// transaction too, or the thread's exit lays out the UIView above this layer.
private enum VoThreadPresent {
    static var installed = false
    static var original: IMP?
    static let lock = NSLock()

    static func install(on drawable: CAMetalDrawable) {
        lock.lock()
        defer { lock.unlock() }
        guard !installed else { return }
        guard let cls = object_getClass(drawable) else { return }
        let selector = NSSelectorFromString("present")
        guard let method = class_getInstanceMethod(cls, selector) else { return }
        original = method_getImplementation(method)
        guard original != nil else { return }
        installed = true
        let block: @convention(block) (AnyObject) -> Void = { object in
            let call = unsafeBitCast(
                original,
                to: (@convention(c) (AnyObject, Selector) -> Void).self
            )
            if Thread.isMainThread {
                call(object, selector)
                return
            }
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            call(object, selector)
            CATransaction.commit()
        }
        method_setImplementation(method, imp_implementationWithBlock(block))
    }
}
