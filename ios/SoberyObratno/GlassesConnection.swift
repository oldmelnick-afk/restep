import Foundation
import CoreBluetooth
import NetworkExtension
import SwiftUI
import UIKit

struct GlassesNetwork: Decodable {
    let version: Int
    let ssid: String
    let password: String
    let host: String
    let port: Int
    let token: String

    var valid: Bool {
        let parts = host.split(separator: ".")
        return version == 1 && ssid.hasPrefix("DIRECT-RS-ReStep-") &&
            (8...63).contains(password.utf8.count) && port == 8765 &&
            parts.count == 4 && parts.allSatisfy { UInt8($0) != nil } &&
            (host.hasPrefix("192.168.") || host.hasPrefix("10.")) && UUID(uuidString: token) != nil
    }
}

/// BLE carries only network setup. Photos travel over the glasses' WPA2 Wi-Fi.
final class GlassesConnection: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let service = CBUUID(string: "712e3100-92e5-4c52-8d0f-332baeef6001")
    static let info = CBUUID(string: "712e3101-92e5-4c52-8d0f-332baeef6001")
    @Published private(set) var message = "Open ReStep on your glasses and swipe back."
    @Published private(set) var busy = false
    @Published private(set) var network: GlassesNetwork?
    @Published private(set) var needsWiFi = false
    @Published private(set) var ready = false
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var timeout: DispatchWorkItem?
    private var reachability: Task<Void, Never>?
    private var requested = false
    private var generation = 0
    private var onReady: ((GlassesNetwork) -> Void)?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func connect(onReady: @escaping (GlassesNetwork) -> Void) {
        cancel()
        self.onReady = onReady
        requested = true
        busy = true
        if central.state == .poweredOn { scan() }
        else { centralManagerDidUpdateState(central) }
    }

    func cancel() {
        generation += 1
        requested = false
        timeout?.cancel(); timeout = nil
        reachability?.cancel(); reachability = nil
        central?.stopScan()
        if let peripheral { central?.cancelPeripheralConnection(peripheral) }
        peripheral = nil
        busy = false; ready = false; needsWiFi = false
        network = nil
        onReady = nil
    }

    private func fail(_ message: String) {
        cancel()
        self.message = message
    }

    private func deadline(_ seconds: Double, message: String) {
        timeout?.cancel()
        let run = generation
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.generation == run else { return }
            self.fail(message)
        }
        timeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    private func scan() {
        message = "Looking for ReStep glasses nearby…"
        central.scanForPeripherals(withServices: [Self.service])
        deadline(25, message: "No glasses found. Open ReStep on the glasses, swipe back and check that it says READY FOR IPHONE. Then retry.")
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard requested else { return }
        switch central.state {
        case .poweredOn: scan()
        case .poweredOff: fail("Turn on Bluetooth on your iPhone, then retry.")
        case .unauthorized: fail("Allow Bluetooth for ReStep in iPhone Settings, then retry.")
        case .unsupported: fail("Bluetooth is unavailable on this device.")
        default: message = "Waiting for Bluetooth…"
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard requested, self.peripheral == nil else { return }
        central.stopScan()
        self.peripheral = peripheral
        peripheral.delegate = self
        message = "Connecting to glasses…"
        deadline(40, message: "Bluetooth connection timed out. Keep the glasses awake and retry.")
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard self.peripheral === peripheral, requested else { return }
        peripheral.discoverServices([Self.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard self.peripheral === peripheral else { return }
        fail("Could not connect over Bluetooth. Keep the glasses on the sync screen and retry.")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard self.peripheral === peripheral else { return }
        // Once received, network settings are enough to complete the Wi-Fi transfer.
        if network == nil { fail("Glasses disconnected. Open their sync screen and retry.") }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard self.peripheral === peripheral else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == Self.service }) else {
            fail("ReStep connection service is unavailable. Update the glasses app."); return
        }
        peripheral.discoverCharacteristics([Self.info], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard self.peripheral === peripheral else { return }
        guard error == nil, let characteristic = service.characteristics?.first(where: { $0.uuid == Self.info }) else {
            fail("Glasses network settings are unavailable. Retry."); return
        }
        message = "Accept Bluetooth pairing on your iPhone and glasses if asked."
        deadline(90, message: "Pairing timed out. Accept Bluetooth pairing on both devices, then retry.")
        // The encrypted GATT read lets iOS initiate standard Bluetooth bonding.
        peripheral.readValue(for: characteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard self.peripheral === peripheral, characteristic.uuid == Self.info else { return }
        guard error == nil, let data = characteristic.value,
              let settings = try? JSONDecoder().decode(GlassesNetwork.self, from: data), settings.valid else {
            fail("Could not read connection settings. Accept Bluetooth pairing, then retry."); return
        }
        timeout?.cancel(); timeout = nil
        network = settings
        message = "Connecting to the glasses’ Wi-Fi…"
        #if AUTO_WIFI
        let config = NEHotspotConfiguration(ssid: settings.ssid, passphrase: settings.password, isWEP: false)
        config.joinOnce = true
        let run = generation
        NEHotspotConfigurationManager.shared.apply(config) { [weak self] error in
            DispatchQueue.main.async {
                guard let self, self.generation == run else { return }
                if let error, (error as NSError).code != NEHotspotConfigurationError.alreadyAssociated.rawValue {
                    self.message = "Wi-Fi could not join automatically. Use the connection steps below."
                    self.needsWiFi = true
                    self.busy = false
                } else { self.waitForNetwork() }
            }
        }
        #else
        needsWiFi = true
        message = "Join the glasses’ Wi-Fi in iPhone Settings, then return here. No IP or code is needed."
        waitForNetwork()
        #endif
    }

    func copyPassword() {
        guard let network else { return }
        UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: network.password]],
            options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(180)])
        message = "Password copied. Open Settings → Wi-Fi, choose \(network.ssid), and paste. Then return to ReStep."
    }

    func retryNetwork() {
        guard network != nil, !ready else { return }
        waitForNetwork()
    }

    private func waitForNetwork() {
        reachability?.cancel()
        guard let network, let url = URL(string: "http://\(network.host):\(network.port)/manifest") else { return }
        let run = generation
        busy = true
        reachability = Task { @MainActor [weak self] in
            let config = URLSessionConfiguration.ephemeral
            config.timeoutIntervalForRequest = 3
            config.timeoutIntervalForResource = 4
            config.waitsForConnectivity = false
            let session = URLSession(configuration: config)
            defer { session.invalidateAndCancel() }
            for _ in 0..<60 {
                guard !Task.isCancelled, let self, self.generation == run else { return }
                var request = URLRequest(url: url)
                request.setValue(network.token, forHTTPHeaderField: "X-Pair-Code")
                do {
                    let (data, response) = try await session.data(for: request)
                    guard !Task.isCancelled, self.generation == run else { return }
                    if (response as? HTTPURLResponse)?.statusCode == 200,
                       (try? JSONDecoder().decode(MemoryManifest.self, from: data))?.schemaVersion == 1 {
                        self.ready = true; self.needsWiFi = false; self.busy = false
                        self.message = "Connected. Importing photos and notes…"
                        self.onReady?(network)
                        return
                    }
                    if (response as? HTTPURLResponse)?.statusCode == 403 {
                        self.fail("The glasses restarted sync. Tap Connect & sync again."); return
                    }
                } catch { if Task.isCancelled { return } }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
            guard let self, self.generation == run else { return }
            self.busy = false; self.needsWiFi = true
            self.message = "Still waiting for glasses Wi-Fi. Join \(network.ssid) in Settings and tap Continue. Allow Local Network access for ReStep if asked."
        }
    }
}
