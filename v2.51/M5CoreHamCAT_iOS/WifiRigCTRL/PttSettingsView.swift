import SwiftUI

struct PttSettingsView: View {
    @Bindable var vm: MainViewModel
    @State private var pttPortText: String = ""

    var body: some View {
        Form {
            Section(header: Label("WiFi PTT (M5Atom)", systemImage: "wifi").foregroundStyle(.red)) {
                Toggle("Use WiFi PTT", isOn: $vm.useWifiPTT)
                LabeledContent("PTT Host") {
                    TextField("192.168.x.x", text: $vm.pttHost)
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                }
                LabeledContent("PTT Port") {
                    TextField("8888", text: $pttPortText)
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.numberPad)
                        .onChange(of: pttPortText) { _, new in
                            if let p = Int(new) { vm.pttPort = p }
                        }
                }
            }
            Section {
                Button {
                    vm.persistConnectionSettings()
                } label: {
                    Text("Save").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .navigationTitle(Text("PTT Settings"))
        .onAppear { pttPortText = String(vm.pttPort) }
    }
}
