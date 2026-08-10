# Wifi_RIG_CTRL for M5Stack Core2 Tough  Ver 2.33

*English version of `M5CoreHamCAT_Core2Tough/README.md`*

Firmware that turns an M5Stack Core2 Tough into a remote controller for your radio
(rig). Controls the rig via a Raspberry Pi (Wifi_Rig_CTRL FastAPI backend) or
directly via ICOM WLAN Remote (CI-V over WiFi), and supports APRS beacon
transmission and PTT control via Wifi_Rig_PTT (a separately sold relay board) or
Hamlib.

by JI1ORE

---

## 1. Included Files

| File | Contents |
|---|---|
| `src/` `include/` `platformio.ini` `merge_bin.py` | Full source (PlatformIO project) |
| `M5CoreHamCAT_Core2Tough_v2.33.bin` | Pre-built firmware (merged, flashable directly at address 0x0) |

To use the pre-built `.bin` directly, flash it at address 0x0 with esptool or similar.

```bash
esptool.py --chip esp32 write_flash 0x0 M5CoreHamCAT_Core2Tough_v2.33.bin
```

To build it yourself, open this folder as a project in PlatformIO (VSCode extension
or CLI) and run `Build` / `Upload`.

```bash
pio run             # build
pio run -t upload    # build + flash
```

Builds for M5Stack Core2 / M5Stack CoreS3 SE are published in their own folders
(`M5CoreHamCAT_Core2` / `M5CoreHamCAT_CoreS3SE`).

---

## 2. Hardware Connections (Port Roles)

| Port | Pins | Purpose |
|---|---|---|
| **Port A** | G32 / G33 (+GND/5V) | **Rotary encoder** (a mechanical 2-phase rotary encoder's A/B phases wired directly — not the M5 genuine I2C encoder unit; this firmware reads it via GPIO interrupts instead) |
| **Port B** | G26 / G36 (+GND/5V) | **Unused** (G36 is an input-only pin with no pull-up support, which causes malfunctions for encoder use; this firmware does not use it) |
| **Port C** | G13 / G14 (+GND/5V) | **PTT key input (G13) + status LED (G14)**. Details below |

### Port C Wiring (Important)

- **G13 ↔ GND**: Connect the PTT contact of an external toggle switch, foot switch,
  or hand mic here. G13 is internally pulled up (`INPUT_PULLUP`), so the switch only
  needs to be wired between G13 and Port C's GND (no additional pull-up resistor is
  required).
- **G14**: Data input for a WS2812-compatible NeoPixel (single LED). Displays TX/RX
  status by LED color (see below).

> **Important spec**: Actual transmission (PTT ON) only occurs when **both** the
> on-screen "PTT" button is ON (`txEnabled`) **and** Port C's G13 is closed (LOW).
> Tapping the on-screen "PTT" button alone will not transmit if nothing is connected
> to Port C.
> Intended operation: use the on-screen "PTT" button to arm transmission, and use an
> external switch (e.g., foot switch) to actually key the transmission — a two-stage
> safety design.

### Status LED (Port C, G14) Colors

| Color | State |
|---|---|
| Red | **Actually transmitting** (regardless of method: Hamlib / Wifi_Rig_PTT / CI-V) |
| Blue | Standing by to transmit (on-screen PTT button ON = armed, PTT method = Wifi_Rig_PTT, not actually transmitting) |
| Green | Standing by to transmit (on-screen PTT button ON = armed, PTT method = Hamlib or CI-V) |
| Off | On-screen PTT button OFF (transmit disarmed) |

### Screen Orientation

Due to the Core2 Tough's case design, the screen is rotated 180° (upside down) at
startup (see the `#ifdef M5TOUGH` rotation handling in the source).

### Audio

Mic input/output is handled via the internal I2C (G21/G22) and I2S (internal wiring
for the M5Stack Module Audio). No external wiring is required.

---

## 3. Flow from First Boot to Connection

### 3-1. Boot Screen (Splash)

After powering on, the "Wifi_Rig_CTRL" logo is shown, followed by two buttons:
**Normal** and **Skip**.

- **Normal**: Standard boot. Proceeds through Wi-Fi selection → Raspberry Pi/CI-V
  connection → rig selection → PTT method selection, confirming settings at each
  step every time.
- **Skip**: Uses the previously saved settings (Wi-Fi, Pi connection target, rig,
  PTT method) to skip the confirmation screens and connect straight to the main
  screen.

There is a small toggle in the top-left of the screen labeled **"Default: Normal" /
"Default: Skip"**. Tapping it to set "Default: Skip" makes the **auto-transition
target after 8 seconds of no input become Skip** (tapping the Normal/Skip button
directly still takes priority). If you want to always boot with Skip, set this
default to Skip once, and from then on it will auto-boot into Skip without any
button presses.

### 3-2. Wi-Fi Connection Screen

Scans and lists nearby Wi-Fi networks (e.g., Android tethering). Tap an SSID, enter
the password, and connect.

### 3-3. Raspberry Pi / CI-V Connection Screen

Use **"Pi Mode" / "CI-V"** in the top-right of the screen to switch connection
methods.

- **Pi Mode** (via Raspberry Pi + rigctld/FastAPI backend)
  - Hostname (or IP address; mDNS use can be toggled)
  - API Port / Audio Port
  - API Key (optional; only needed if the backend requires authentication)
- **CI-V** (ICOM WLAN Remote compatible, connects directly to the radio's Wi-Fi, no
  Pi needed)
  - Host (the radio's IP address)
  - Ctrl Port / CIV Port / Addr (hex) (CI-V address)
  - Username / Password (authentication info configured on the radio)
  - Timeout (time until the screen turns off when idle)

Tap "Connect" after entering the details.

### 3-4. Rig Selection Screen

Select the target rig from the list retrieved from the connection target (Pi/CI-V).

### 3-5. PTT Method Selection Screen

- **Wifi_PTT**: Sends PTT signals over WiFi/UDP to a separately sold Wifi_Rig_PTT
  relay (Remotekeyer). Provided as a workaround for ICOM rigs where Hamlib-based CAT
  PTT causes no modulation to be carried.
  - Configure PTT Host (mDNS name or IP address) and PTT Port
- **Hamlib**: Standard CAT PTT control via rigctld.
  - Configure PTT Device (serial device) and PTT Type (RTS/DTR)

After configuring, tap "OK" to go to the main screen.

---

## 4. Main Screen Operation

### Top Row (Status Display)

- Left: currently connected rig model name
- Chips in the top right (left to right):
  1. **Theme chip** (e.g., "OCN"): tap to cycle the design theme **OCN → AMB → MONO**
  2. **Day/Night chip** ("NGT"/"DAY"): tap to switch between night and day display.
     In day mode, screen brightness is maximized and a high-contrast color scheme is
     used for better visibility in bright environments.
  3. **TX pill**: lights up red while transmitting (Hamlib) or orange while APRS is
     transmitting

  Note: The theme and day/night chips can be operated **even while transmitting
  (PTT ON)**.

- Frequency display (large): tap to open the direct frequency entry screen (disabled
  while transmitting)
- S-meter: displays received signal strength as a gradient
- Status chips: ST (step size) / PW (power) / MD (mode) / WD (filter width)

### Buttons (4×3 Grid)

| Button | Function |
|---|---|
| Freq | Select frequency (adjust with the rotary encoder) |
| Step | Select/change the frequency step size |
| Mode | Select/change the operating mode (SSB/CW/FM etc.) |
| Wid | Select/change the filter width |
| Pow | Select/change the transmit power |
| SQL | Select/change the squelch level |
| APRS | **Short press**: toggle APRS transmission ON/OFF (requires APRS Enabled setting + GPS position acquired). **Long press (0.7s+)**: open APRS settings screen |
| PTT | Toggle the on-screen PTT armed state ON/OFF (actual transmission also requires an external switch on Port C — see "Port C Wiring" above) |
| Back | Disconnect and return to the rig selection screen |
| SPK | Toggle the speaker (received audio) ON/OFF |
| DOWN / UP | Increase/decrease the value of the currently selected item (Freq/Step/Mode/Wid/Pow/SQL) via buttons as well |

### Rotary Encoder (Port A)

With one of the Freq/Step/Mode/Wid/Pow/SQL buttons selected, turning the encoder
adjusts that item's value.

---

## 5. APRS Settings (Long-press the APRS button to open)

| Item | Description |
|---|---|
| APRS Enabled | Enable/disable the APRS feature itself |
| Use GPS | When ON, automatically uses location data obtained from Android (Tasker); manual entry fields are locked |
| Latitude / Longitude | Manual location when Use GPS = OFF |
| APRS TXFreq | APRS beacon transmit frequency |
| Baudrate | 1200 / 9600 |
| TX Interval | Beacon transmit interval (30/60/120/180/300/600 seconds) |
| Callsign / SSID | Own station callsign and SSID |
| Path | WIDE1-1 / WIDE1-1,WIDE2-1 / WIDE2-1 / DIRECT / NONE |
| Symbol | APRS symbol (icon shown) |
| Destination | APRS destination code (TNC type) |
| Sound Device | Sound device used on the Pi side |

**About GPS location data**: When Use GPS = ON, a Tasker HTTP server must be running
on the Android side (see the port/path shown at the bottom of the settings screen).
After connecting to Wi-Fi, the M5 periodically fetches location data from Android;
if it tries to start APRS transmission without having obtained a location, a
"GPS location not acquired" warning is shown and it cannot start (in that case, it
automatically attempts to re-fetch once on the spot).

---

## 6. Color Themes

| Theme | Characteristics |
|---|---|
| OCN (Ocean) | Standard color scheme (teal/blue tones) |
| AMB (Amber) | Vacuum-tube/VFD-style amber. A calm scheme suited for night operation |
| MONO | Achromatic, maximum contrast. Prioritizes visibility |

Each theme has a day mode (bright background, high contrast) and a night mode (dark
background), independently switchable via the chip in the top-right of the screen.
Settings are saved on the device and persist across reboots.

---

## 9. Changes in v2.33 (compared to v2.30)

- Updated version label to 2.33
- Fixed APRS behavior when using the rig's built-in APRS modem (e.g. FTX-1,
  TX Method = Rig): APRS can now start and keep running even if the M5 side
  (Android-relayed) GPS position hasn't been acquired or has gone stale. In
  this mode the rig itself attaches its own GPS position, so gating on the
  M5's GPS state was incorrect — previously this blocked starting APRS while
  no fix was available, and auto-stopped it once the relayed position went
  stale.

## 8. Changes in v2.30 (compared to v2.20)

- Updated version label to 2.30
- Pi-connected mode now benefits from the **Raspberry Pi side API** (`api.py`)
  update, which dynamically detects the actual mode list supported by the
  connected rig model (e.g. C4FM on the FT-991, D-STAR on the IC-705, etc. —
  rig-specific digital modes that previously could not be selected now appear
  in the Mode picker). Since the M5 firmware has no built-in Pi-update feature,
  applying this change requires either using the **"Update Pi" button in the
  Android/iOS app once**, or redoing the Raspberry Pi setup (see
  `RaspberryPiSetup/readme.txt` for details).
- Added D-STAR (IC-705/IC-9700) and WFM to the Mode picker in direct CI-V mode
  (previously available on Android/iOS but missing from the M5 build).
- On the RasPi Connect screen, copying a discovered device's IP from "Scan" now
  automatically switches to "Use IP" mode (API Port / Audio Port are no longer
  overwritten, keeping your existing settings).
- Adjusted the width of the SCAN/Connect buttons on the RasPi Connect screen so
  the "Connect" label is no longer truncated.
- Fixed CI-V filter selection: since the IC-705 only accepts filter 0x01 for
  D-STAR, filter 0x01 is now sent regardless of the configured width when in
  D-STAR mode.
- Improved the APRS settings screen: changing Enabled / TX Method (rig-modem
  usage) and pressing OK now stops/restarts APRS transmission on the spot,
  without needing a screen transition or reconnect.

## 7. Changes in v2.20 (compared to v2.18)

- Updated version label to 2.20
- Relaxed CI-V mode polling interval (200ms → 500ms) to avoid Wi-Fi disconnects
  caused by load on the radio side
- Added retry handling for PTT OFF in CI-V mode (prevents stuck transmission)
- Added automatic detection/display of VFO A/B and MAIN/SUB
- Added an APRS received-beacon list screen (with distance/bearing display)
- Stability improvements: forced termination handling for streamTask, ENOMEM
  countermeasures, etc.
