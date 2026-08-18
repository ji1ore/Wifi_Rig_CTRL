# Wifi_RIG_CTRL for M5Stack StopWatch  Ver 2.50

Firmware that turns an M5Stack Stopwatch Dev Kit (466×466 round AMOLED) into a remote
controller for your radio (rig). Controls the rig via a Raspberry Pi (Wifi_Rig_CTRL
FastAPI backend) or directly via ICOM WLAN Remote (CI-V over WiFi), with APRS beacon
transmission and PTT control via Hamlib or CI-V.

by JI1ORE

---

## 1. Included Files

| File | Description |
|---|---|
| `src/` `include/` `platformio.ini` `merge_bin.py` | Full source (PlatformIO project) |
| `M5CoreHamCAT_M5StopWatch_v2.50.bin` | Pre-built firmware (merged, flashable directly at address 0x0) |

To flash the pre-built `.bin` directly, use esptool at address 0x0:

```bash
esptool.py --chip esp32s3 write_flash 0x0 M5CoreHamCAT_M5StopWatch_v2.50.bin
```

To build from source, open this folder as a PlatformIO project in VSCode or the CLI:

```bash
pio run             # build only
pio run -t upload    # build and upload
```

Separate folders are available for M5Stack Core2, Core2 Tough, and CoreS3 SE.

---

## 2. Hardware (No External Peripherals Required)

The M5Stack Stopwatch Dev Kit does **not** have the external ports used by other supported
boards (Core2 / CoreS3 SE). No external hardware is needed.

| Peripheral on other boards | On M5StopWatch |
|---|---|
| Unit Encoder (Port A rotary encoder) | Replaced by **BtnA / BtnB** (side buttons) |
| Module Audio / ES8388 (external codec) | **Not present** — built-in mic/speaker only |
| NeoPixel LED (Port B, G9) | **Not present** — TX state shown on-screen (TX pill) |
| External PTT switch (Port B, G8) | **Not present** — PTT via screen button + side buttons |

### Physical Side Buttons

The M5Stack Stopwatch has two side buttons: Blue (BtnA) and Yellow (BtnB).

| Button | Normal operation | PTT armed | CW mode |
|---|---|---|---|
| **BtnA (Blue)** | **Increase** selected value (UP) | **Transmit ON** while held | **Dot** (dit) |
| **BtnB (Yellow)** | **Decrease** selected value (DOWN) | **Transmit ON** while held | **Dash** (dah) |

### PTT Operation

1. Tap the **PTT** button on screen → **Armed** state ("PTT?" displayed)
2. Hold **BtnA or BtnB** → Transmit ON (PTT ON while held)
3. Release button → Transmit OFF

> **Accidental-transmission prevention**: Two actions are required — screen tap (arm) +
> physical button hold. Tapping the screen alone does not transmit.

### CW Mode

Long-press the Mode button to switch PTT into CW keyer mode.

- **BtnA (Blue)**: dit (dot)
- **BtnB (Yellow)**: dah (dash)

Long-press Mode again to return to normal PTT mode.

---

## 3. Startup and Connection Flow

### 3-1. Splash Screen

On power-on, "Wifi_Rig_CTRL" is displayed, followed by **Normal** / **Skip** buttons.

- **Normal**: Standard startup — prompts for Wi-Fi → Pi/CI-V connection → rig selection → PTT method.
- **Skip**: Uses previously saved settings to connect directly to the main screen.

The small **"Default: Normal" / "Default: Skip"** toggle in the top-left sets which option
is selected automatically after 8 seconds of inactivity.

### 3-2. Wi-Fi Screen

Scans for nearby Wi-Fi networks. Tap an SSID and enter the password to connect.

### 3-3. Raspberry Pi / CI-V Connection Screen

Use the **"Pi Mode" / "CI-V"** toggle (top-right) to switch connection method.

- **Pi Mode** (via Raspberry Pi + rigctld/FastAPI backend)
  - Hostname or IP address (mDNS toggle available)
  - API Port / Audio Port
  - API Key (optional; only if the backend requires authentication)
- **CI-V** (direct connection to rig's Wi-Fi, no Pi required)
  - Host (rig's IP address)
  - Ctrl Port / CIV Port / Addr (hex CI-V address)
  - Username / Password (configured on the rig)

Tap **Connect** to proceed.

### 3-4. Rig Selection Screen

Select the target rig from the list retrieved from the Pi or CI-V connection.

### 3-5. PTT Method Screen

- **Wifi_PTT**: Sends PTT via UDP to an external Wifi_Rig_PTT relay (Remotekeyer).
  Set PTT Host and PTT Port.
- **Hamlib**: Standard CAT PTT via rigctld.
  Set PTT Device (serial device) and PTT Type (RTS/DTR).

Tap **OK** to proceed to the main screen.

---

## 4. Main Screen

The UI is optimized for the round 466×466 AMOLED display.

### Status Bar (top)

- Left: Connected rig model name
- Top-right chips (left to right):
  1. **Theme chip** (e.g. "OCN"): tap to cycle through color themes
  2. **Day/Night chip** ("NGT"/"DAY"): tap to toggle night ⇄ day display
  3. **TX pill**: lights red during transmit (Hamlib), orange during APRS TX
- Large frequency display: tap to open direct frequency entry (disabled during TX)
- S-meter: receive signal strength shown as a gradient bar

### Buttons

| Button | Function |
|---|---|
| Freq | Select frequency (adjust with BtnA/BtnB) |
| Step | Select/change frequency step size |
| Mode | Select/change operating mode (SSB/CW/FM etc.). **Long-press to toggle CW keyer mode** |
| Wid | Select/change filter width |
| Pow | Select/change TX power |
| SQL | Select/change squelch level |
| APRS | **Short press**: toggle APRS beacon ON/OFF. **Long press**: open APRS settings |
| PTT | Arm/disarm PTT (transmit requires BtnA or BtnB to be held) |
| Back | Disconnect and return to rig selection screen |
| SPK | Toggle speaker (RX audio) ON/OFF |
| DOWN / UP | Decrease/increase selected item value (same as BtnB/BtnA) |

### BtnA / BtnB (Side Buttons)

With a menu item selected (Freq/Step/Mode/Wid/Pow/SQL), press BtnA to increase or BtnB
to decrease the value. This replaces the rotary encoder used on other boards.

---

## 5. APRS Settings (long-press APRS button)

| Setting | Description |
|---|---|
| APRS Enabled | Enable/disable APRS function |
| Use GPS | When ON, uses GPS position received from Android (Tasker) |
| Latitude / Longitude | Manual position when Use GPS is OFF |
| APRS TXFreq | APRS beacon TX frequency |
| Baudrate | 1200 / 9600 |
| TX Interval | Beacon interval (30/60/120/180/300/600 sec) |
| Callsign / SSID | Your callsign and SSID |
| Path | WIDE1-1 / WIDE1-1,WIDE2-1 / WIDE2-1 / DIRECT / NONE |
| Symbol | APRS symbol (icon) |
| Destination | APRS destination code (TNC type) |
| Sound Device | ALSA sound device on the Pi side |

---

## 6. Color Themes

| Theme | Description |
|---|---|
| OCN (Ocean) | Default — teal/blue palette |
| AMB (Amber) | Amber/VFD-style — warm tones for night operation |
| MONO | Monochrome — maximum contrast for readability |

Each theme has both day mode (bright background, high contrast) and night mode (dark
background), switched independently via the chip in the top-right corner. Settings are
saved and persist across restarts.

---

## 9. Changes in v2.50

- **New**: Added support for M5Stack Stopwatch Dev Kit (466×466 round AMOLED, ESP32-S3R8)
