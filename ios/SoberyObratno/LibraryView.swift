import SwiftUI

struct LibraryView: View {
    @EnvironmentObject private var library: MemoryLibrary
    @State private var showSync = false
    @State private var deleting: MemorySession?

    var body: some View {
        NavigationStack {
            List {
                if !library.pendingDeletionIDs.isEmpty {
                    Label("\(library.pendingDeletionIDs.count) deletion(s) waiting to sync", systemImage: "arrow.triangle.2.circlepath")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if !library.message.isEmpty {
                    Text(library.message).font(.footnote).foregroundStyle(.secondary)
                }
                let visibleSessions = library.sessions.filter { !$0.steps.isEmpty }
                if visibleSessions.isEmpty {
                    ContentUnavailableView("No disassemblies yet", systemImage: "camera.viewfinder",
                        description: Text("Capture steps on your glasses, then sync them here."))
                }
                ForEach(visibleSessions.sorted { $0.createdAt > $1.createdAt }) { session in
                    NavigationLink(value: session.id) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(session.name.replacingOccurrences(of: "Разборка ", with: "Disassembly ")).font(.headline)
                            Text("\(session.steps.count) steps · \(session.endedAt == nil ? "In progress" : "Finished")")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) { deleting = session } label: {
                            Label("Delete", systemImage: "trash")
                        }.disabled(library.syncing)
                    }
                }
            }
            .navigationTitle("ReStep")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Sync", systemImage: "arrow.triangle.2.circlepath") {
                        showSync = true
                    }
                }
            }
            .navigationDestination(for: String.self) { SessionView(sessionID: $0) }
            .sheet(isPresented: $showSync) { SyncView() }
            .alert("Delete disassembly?", isPresented: Binding(
                get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { session in
                Button("Delete", role: .destructive) { library.delete(sessionID: session.id); deleting = nil }
                Button("Cancel", role: .cancel) { deleting = nil }
            } message: { session in
                Text("Delete \(session.name) and all its photos and notes? It will also be deleted from your glasses at the next sync. This cannot be undone.")
            }
        }
    }
}

struct SyncView: View {
    @EnvironmentObject private var library: MemoryLibrary
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var connection = GlassesConnection()
    @State private var showManual = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label("Connect directly to your glasses", systemImage: "eyeglasses")
                        .font(.headline)
                    Text("On the glasses: open ReStep and swipe back to Sync. Keep the glasses awake.")
                    Button {
                        library.message = ""
                        connection.connect { network in
                            Task { await library.sync(ip: network.host, code: network.token) }
                        }
                    } label: {
                        Label(connection.busy ? "Connecting…" : "Connect & sync",
                              systemImage: "arrow.triangle.2.circlepath")
                    }
                    .disabled(connection.busy || library.syncing)
                    if connection.busy || library.syncing { ProgressView() }
                    Text(connection.message).font(.subheadline)
                }
                if connection.needsWiFi, let network = connection.network {
                    Section("Join glasses Wi-Fi") {
                        Text(network.ssid).font(.headline).textSelection(.enabled)
                        Text("Open iPhone Settings → Wi-Fi and choose this network. If a password is requested, paste it using the button below. A ‘No Internet Connection’ message is normal.")
                        Button("Copy Wi-Fi password") { connection.copyPassword() }
                        Text("Return to ReStep after joining. Import starts automatically; you do not need an IP address or pairing code.")
                        Button("Continue") { connection.retryNetwork() }
                            .disabled(library.syncing)
                    }
                }
                if !library.message.isEmpty {
                    Section { Text(library.message) }
                }
                Section {
                    Text("Bluetooth exchanges the connection settings. Photos and notes are copied over the glasses’ private Wi-Fi and remain on the glasses.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Button("Manual connection") { showManual = true }
                        .disabled(connection.busy || library.syncing)
                }
            }
            .navigationTitle("Sync")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { connection.cancel(); dismiss() }.disabled(library.syncing)
                }
            }
            .sheet(isPresented: $showManual) { ManualSyncView() }
            .interactiveDismissDisabled(library.syncing)
            .onDisappear { connection.cancel() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active && connection.needsWiFi { connection.retryNetwork() }
            }
        }
    }
}
struct ManualSyncView: View {
    @EnvironmentObject private var library: MemoryLibrary
    @Environment(\.dismiss) private var dismiss
    @AppStorage("glassesIP") private var ip = ""
    @AppStorage("pairCode") private var code = ""
    @State private var ipParts = ["", "", "", ""]
    @FocusState private var focusedPart: Int?

    var body: some View {
        NavigationStack {
            Form {
                Section("Connect to glasses") {
                    HStack(spacing: 4) {
                        ForEach(0..<4, id: \.self) { index in
                            TextField("0", text: Binding(
                                get: { ipParts[index] },
                                set: { value in
                                    ipParts[index] = String(value.filter(\.isNumber).prefix(3))
                                    if ipParts[index].count == 3 && index < 3 {
                                        focusedPart = index + 1
                                    }
                                }
                            ))
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.center)
                            .focused($focusedPart, equals: index)
                            .frame(maxWidth: .infinity)
                            if index < 3 { Text(".").font(.title3.bold()) }
                        }
                    }
                    .accessibilityLabel("Glasses IP address, four numbers from 0 to 255")
                    SecureField("Six digit code", text: $code)
                        .keyboardType(.numberPad)
                }
                Section("How to connect") {
                    Text("1. Confirm your glasses are connected to your phone in Hi Rokid.")
                    Text("2. In Hi Rokid, open Toolbox → Wi‑Fi for glasses. Join the same network as your iPhone. You can also use Wi‑Fi settings on the glasses.")
                    Text("3. Open ReStep on the glasses and swipe back. Enter the IP address and code shown there. If Wi‑Fi is off, tap the touchpad to open settings.")
                    Text("4. Tap Import photos and notes. Allow local network access if iPhone asks.")
                    Text("Hi Rokid Gallery's automatic connection applies to its Gallery. ReStep transfers photos over the shared Wi‑Fi network.")
                        .foregroundStyle(.secondary)
                }
                Section {
                    Button {
                        guard ipParts.allSatisfy({ !$0.isEmpty && (Int($0) ?? 256) <= 255 }) else {
                            library.message = "Enter four IP address numbers from 0 to 255."
                            return
                        }
                        ip = ipParts.joined(separator: ".")
                        focusedPart = nil
                        Task { await library.sync(ip: ip, code: code) }
                    } label: {
                        if library.syncing { ProgressView() }
                        else { Text("Import photos and notes") }
                    }
                    .disabled(library.syncing)
                    if !library.message.isEmpty { Text(library.message) }
                }
            }
            .navigationTitle("Sync")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(focusedPart == 3 ? "Done" : "Next") {
                        if let focusedPart, focusedPart < 3 { self.focusedPart = focusedPart + 1 }
                        else { self.focusedPart = nil }
                    }
                }
            }
            .onAppear {
                let saved = ip.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
                ipParts = (0..<4).map { $0 < saved.count ? saved[$0] : "" }
            }
        }
    }
}

struct SessionView: View {
    @EnvironmentObject private var library: MemoryLibrary
    let sessionID: String
    @State private var assemblyMode = true

    private var session: MemorySession? { library.sessions.first { $0.id == sessionID } }

    var body: some View {
        Group {
            if let session {
                VStack(spacing: 0) {
                    TextField("Product name", text: Binding(
                        get: { self.session?.name ?? "" },
                        set: { library.rename(sessionID: sessionID, to: $0) }
                    ))
                    .font(.title2.bold()).padding()
                    Picker("Order", selection: $assemblyMode) {
                        Text("Assembly").tag(true)
                        Text("Disassembly").tag(false)
                    }
                    .pickerStyle(.segmented).padding(.horizontal)
                    if assemblyMode {
                        let reversedSteps = Array(session.steps.reversed())
                        List(reversedSteps.indices, id: \.self) { index in
                            StepCard(step: reversedSteps[index], sessionID: sessionID,
                                     assemblyMode: true, displayNumber: index + 1)
                        }
                    } else {
                        List {
                            ForEach(session.steps) { step in
                                StepCard(step: step, sessionID: sessionID,
                                         assemblyMode: false, displayNumber: step.number)
                            }
                            .onMove { library.moveSteps(sessionID: sessionID, from: $0, to: $1) }
                        }
                        .toolbar { ToolbarItem(placement: .topBarTrailing) { EditButton() } }
                    }
                }
            } else {
                ContentUnavailableView("Disassembly not found", systemImage: "exclamationmark.triangle")
            }
        }
        .navigationTitle(assemblyMode ? "Assembly" : "History")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct StepCard: View {
    @EnvironmentObject private var library: MemoryLibrary
    @State private var showOriginal = false
    let step: MemoryStep
    let sessionID: String
    let assemblyMode: Bool
    let displayNumber: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(assemblyMode ? "Assembly · step \(displayNumber)" : "Disassembly · step \(displayNumber)")
                    .font(.headline)
                Spacer()
                if assemblyMode {
                    Button {
                        library.update(stepID: step.id, sessionID: sessionID,
                                       completed: !step.completed)
                    } label: {
                        Image(systemName: step.completed ? "checkmark.circle.fill" : "circle")
                            .font(.title2)
                    }
                    .accessibilityLabel(step.completed ? "Mark incomplete" : "Mark complete")
                }
            }
            if let image = library.photo(step, enhanced: !showOriginal) {
                Image(uiImage: image)
                    .resizable().scaledToFit()
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                Button(showOriginal ? "Auto brighten" : "Show original") {
                    showOriginal.toggle()
                }
                .font(.caption)
            } else {
                Label("Photo unavailable", systemImage: "photo")
                    .foregroundStyle(.secondary)
            }
            TextField("Add or edit description", text: Binding(
                get: { step.note },
                set: { library.update(stepID: step.id, sessionID: sessionID, note: $0) }
            ), axis: .vertical)
            .lineLimit(2...6)
        }
        .padding(.vertical, 8)
    }
}
