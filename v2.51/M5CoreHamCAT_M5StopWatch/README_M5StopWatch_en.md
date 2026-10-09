# Wifi_RIG_CTRL for M5Stack StopWatch  Ver 2.50

*English version of `M5CoreHamCAT_M5StopWatch/README.md`*

Firmware that turns an M5Stack Stopwatch Development Kit (466×466 circular AMOLED)
into a remote controller for your radio (rig). Controls the rig via a Raspberry Pi
(Wifi_Rig_CTRL FastAPI backend) or directly via ICOM WLAN Remote (CI-V over WiFi),
and supports APRS beacon transmission and PTT control via Hamlib or CI-V.

by JI1ORE

---

## 1. Included Files

| File | Contents |
|---|---|
| `src/` `include/` `platformio.ini` `merge_bin.py` | Full source (PlatformIO project) |
| `M5CoreHamCAT_M5StopWatch_v2.50.bin` | Pre-built firmware (merged, flashable directly at address 0x0) |

To use the pre-built `.bin` directly, flash it at address 0x0 with esptool or similar.

```bash
esptool.py --chip esp32s3 write_flash 0x0 M5CoreHamCAT_M5StopWatch_v2.50.bin
```

To build it yourself, open this folder as a project in PlatformIO (VSCode extension
or CLI) and run `Build` / `Upload`.

```bash
pio run             # build
pio run -t upload    # build + flash
```

Builds for M5Stack Core2 / M5Stack Core2 Tough / M5Stack CoreS3 SE are published
in their own folders.

---

## 2. Hardware (No External Connections Required)

The M5Stack Stopwatch Development Kit **does not have** the ports used by other
models (Core2 / CoreS3 SE). No external hardware is needed.

| Other-model peripheral | Handling on M5StopWatch |
|---|---|
| Unit Encoder (Port A rotary encoder) | Replaced by the **BtnA / BtnB** side buttons (increase/decrease) |
| Module Audio / ES8388 (external audio codec) | **Not installed**. Built-in mic/speaker only |
| NeoPixel LED (Port B, G9) | **Not installed** (TX state is shown as the TX pill on screen) |
| External PTT switch (Port B, G8) | **Not installed**. Operated via the on-screen PTT button + side buttons |

### Physical Side Button Roles

The M5Stack Stopwatch has two side buttons: blue = BtnA, yellow = BtnB.

| Button | Normal operation | PTT armed | CW mode |
|---|---|---|---|
| **BtnA (blue)** | **Increase** selected item (UP) | Transmit ON **while held** | **Dot** (dit) |
| **BtnB (yellow)** | **Decrease** selected item (DOWN) | Transmit ON **while held** | **Dash** (dah) |

### PTT Operation Flow

1. Tap the **"PTT"** button on screen → **armed state** ("PTT?" shown)
2. **Hold BtnA or BtnB** to transmit (PTT ON)
3. Release the button → PTT OFF

> **Accidental-transmission prevention design**: Two actions are required — tapping
> the screen (arm) and pressing a physical button. Tapping the on-screen PTT button
> alone does not transmit.

### CW Mode

Long-press the Mode button to switch the PTT button into CW keyer mode.

- **BtnA (blue)**: dot (dit)
- **BtnB (yellow)**: dash (dah)

Long-press the Mode button again to return to normal PTT mode.

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

Tap "Connect" after entering the details.

### 3-4. Rig Selection Screen

Select the target rig from the list retrieved from the connection target (Pi/CI-V).

### 3-5. PTT Method Selection Screen

- **Wifi_PTT**: Sends PTT signals over WiFi/UDP to a separately sold Wifi_Rig_PTT
  relay (Remotekeyer).
  - Configure PTT Host (mDNS name or IP address) and PTT Port
- **Hamlib**: Standard CAT PTT control via rigctld.
  - Configure PTT Device (serial device) and PTT Type (RTS/DTR)

After configuring, tap "OK" to go to the main screen.

---

## 4. Main Screen Operation

The UI is optimized for the circular display (466×466).

### Top Row (Status Display)

- Left: currently connected rig model name
- Chips in the top right (left to right):
  1. **Theme chip** (e.g., "OCN"): tap to cycle the design theme
  2. **Day/Night chip** ("NGT"/"DAY"): tap to switch between night and day display
  3. **TX pill**: lights up red while transmitting (Hamlib) or orange while APRS is
     transmitting
- Frequency display (large): tap to open the direct frequency entry screen
- S-meter: displays received signal strength as a gradient

### Buttons

| Button | Function |
|---|---|
| Freq | Select frequency (adjust with BtnA/BtnB) |
| Step | Select/change the frequency step size |
| Mode | Select/change the operating mode (SSB/CW/FM etc.). **Long press to switch to CW keyer mode** |
| Wid | Select/change the filter width |
| Pow | Select/change the transmit power |
| SQL | Select/change the squelch level |
| APRS | **Short press**: toggle APRS transmission ON/OFF. **Long press**: open APRS settings screen |
| PTT | Toggle the on-screen PTT armed state ON/OFF (actual transmission requires holding BtnA or BtnB) |
| Back | Disconnect and return to the rig selection screen |
| SPK | Toggle the speaker (received audio) ON/OFF |
| DOWN / UP | Increase/decrease the value of the currently selected item (equivalent to BtnA/BtnB) |

### BtnA / BtnB (Side Buttons)

With one of Freq/Step/Mode/Wid/Pow/SQL selected, press BtnA or BtnB to
increase/decrease that item's value. This replaces the rotary encoder used on other
models.

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

## 9. Changes in v2.50

- **New**: Added support for the M5Stack Stopwatch Development Kit (466×466 circular
  AMOLED, ESP32-S3R8)
