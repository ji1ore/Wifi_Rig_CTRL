import SwiftUI

struct SplashView: View {
    var onContinue: () -> Void
    @State private var showButton: Bool = false
    @State private var glow: Bool = false

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.12, green: 0.20, blue: 0.55),  // deep blue
                    Color(red: 0.45, green: 0.20, blue: 0.70),  // purple
                    Color(red: 0.85, green: 0.30, blue: 0.55),  // pink
                    Color(red: 0.95, green: 0.55, blue: 0.30)   // warm amber
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: 16) {
                Image("AppLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 100, height: 100)
                    .clipShape(RoundedRectangle(cornerRadius: 22))
                    .shadow(color: .white.opacity(glow ? 0.7 : 0.3), radius: glow ? 18 : 8)
                    .animation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true), value: glow)
                Text("Wifi_RIG_CTRL")
                    .font(.largeTitle.bold())
                    .foregroundStyle(.white)
                Text("WiFi Ham Radio Controller")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.85))
                Spacer().frame(height: 24)
                if showButton {
                    Button("Start") { onContinue() }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .tint(.white)
                        .foregroundStyle(Color(red: 0.45, green: 0.20, blue: 0.70))
                        .transition(.opacity)
                } else {
                    ProgressView().tint(.white)
                }
            }
        }
        .task {
            glow = true
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            withAnimation { showButton = true }
            try? await Task.sleep(nanoseconds: 7_000_000_000)
            onContinue()
        }
    }
}
