import SwiftUI

struct ProfileListView: View {
    @Bindable var vm: MainViewModel
    @State private var showSaveDialog: Bool = false
    @State private var newName: String = ""

    var body: some View {
        Form {
            Section(header: Label("Saved Profiles", systemImage: "list.bullet.rectangle.portrait").foregroundStyle(.indigo)) {
                if vm.profiles.isEmpty {
                    Text("No saved profiles").foregroundStyle(.secondary)
                } else {
                    ForEach(vm.profiles) { p in
                        Button {
                            vm.loadProfile(p)
                            vm.path.removeLast()
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.name).font(.body)
                                Text("\(p.hostName):\(p.apiPort)")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { offsets in
                        for off in offsets {
                            vm.deleteProfile(vm.profiles[off].id)
                        }
                    }
                }
            }

            Section {
                Button {
                    newName = defaultProfileName()
                    showSaveDialog = true
                } label: {
                    Label("Save Current Settings as Profile", systemImage: "square.and.arrow.down")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .navigationTitle(Text("Profiles"))
        .alert("Profile Name", isPresented: $showSaveDialog) {
            TextField("Profile Name", text: $newName)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                let trimmed = newName.trimmingCharacters(in: .whitespaces)
                if !trimmed.isEmpty {
                    vm.saveProfile(name: trimmed)
                }
            }
        } message: {
            Text("Enter a name for this profile")
        }
    }

    private func defaultProfileName() -> String {
        let base = vm.hostName.isEmpty ? "Profile" : vm.hostName
        var name = base
        var n = 1
        while vm.profiles.contains(where: { $0.name == name }) {
            n += 1
            name = "\(base) \(n)"
        }
        return name
    }
}
