① Introduction
M5CoreHamCAT is a system that uses a Raspberry Pi Zero 2W connected to a transceiver and an M5CoreS3SE.
The Raspberry Pi Zero 2W performs CAT control of the radio, and the M5CoreS3SE retrieves information and performs operations through it.

Technically, Hamlib running on the Raspberry Pi Zero 2W is wrapped with FastAPI, and the M5CoreS3SE accesses FastAPI to operate the radio and obtain radio information.

The system can display radio information and receive audio.

Version 1.10 added support for sending PTT signals.
After sending audio to the rig using a radio microphone, this device can toggle PTT ON/OFF.

Version 1.20 added support for APRS transmission  
(confirmed working only with IC‑705 at 1200 bps).

APRS transmission is mutually exclusive with audio reception, but for radios without APRS capability, you can use DireWolf on the Raspberry Pi to send APRS packets.
In APRS mode, the system switches from the current frequency to a separately configured APRS frequency (e.g., 144.66 MHz) and transmits APRS.

Note: The baud rate seems to depend on the transmitting radio, and APRS transmission did not work from the IC‑705 via USB.

2026/3/1  
Since M5CoreHamCAT_Speaker can now output audio via Module Audio, it has been discontinued.

Currently, operation has only been confirmed with the Yaesu FT‑991A.
Operation with other radios, M5CoreS3, M5CoreS3Lite, or other M5Core series devices has not been tested.

② Required Items
To run this system, you will need the following:

M5CoreS3SE / M5Core2 ver1.1  
(M5CoreS3SE performs more smoothly)

Module Audio (M5 genuine, SKU: M144) — connect to Port A

Raspberry Pi Zero 2W

Wi‑Fi router (both devices must be on the same network)

Unit Encoder (M5 genuine, SKU: U135)
Optional, but improves usability

Battery Bottom for M5Stack CoreS3  
Optional, but increases convenience

MicroSD card (16 GB or more, high reliability recommended)

USB cables for powering devices and obtaining CAT data

For APRS operation:
Smartphone (must support the Tasker app)

Tasker app (paid)

For transmitting audio signals:
Mechanical Key (M5 genuine, SK6812)
Connect to Port C (M5Core2) or Port B (M5CoreS3SE)

Microphone capable of sending audio to the radio (e.g., wireless mic)

③ Setup Procedure (Raspberry Pi Zero 2W)
Follow the instructions in:
https://github.com/ji1ore/M5CoreHamCAT/blob/main/v1.11/RaspberryPiSetup/readme.txt (github.com in Bing)

Main steps:

Install Raspberry Pi Imager

Create the Raspberry Pi image
(configure Wi‑Fi SSID and user password here)

SSH login

Run required commands
(shell scripts are provided; simple but time‑consuming)

④ Setup Procedure (M5CoreS3SE / M5Core2)
Use M5Burner to write the firmware.

Source code is available here:
https://github.com/ji1ore/M5CoreHamCAT/main/M5CoreHamCAT (github.com in Bing)

The source is intended to be compiled using PlatformIO on Visual Studio Code.

Firmware installation steps:

Download and install M5Burner

Launch M5Burner and register an account

Download M5CoreHamCAT

Connect M5CoreS3SE/M5Core2 via USB and Burn
(Search for “M5CoreHamCAT” in M5Burner)

For APRS:
Long‑press the grayed‑out APRS button on the main screen to modify settings.
You can also send GPS data from your smartphone to the Raspberry Pi’s FastAPI using Tasker.
See the “RaspberryPiSetup” folder for configuration instructions.

⑤ Notes / Cautions
The system is still somewhat unstable.
If it doesn’t work properly, try rebooting a few times.

If radio information stops updating, SSH into the Raspberry Pi Zero 2W and reboot it:

コード
sudo reboot now
Be careful not to select the wrong CAT device, or it will fail to connect.

Rapid tapping may cause the system to reboot.

To prevent audio delay, the system reconnects every 10 minutes.
During this time, audio may drop for a few seconds.

On M5Core2, you may need to press and hold slightly longer on the main screen.

⑥ Android Version (Wifi_RIG_CTRL_ForAndroid v2.13)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Starting from v1.30, an Android smartphone app is available as an alternative to the M5CoreS3SE for remote rig control. (Latest: v2.13)
No M5Core / Module Audio / Unit Encoder hardware is required.
The Raspberry Pi setup is the same as for the M5Core version.

● What's New in v2.13 (compared to v2.12)

Improvements:
- CW sidetone cut-off timing improved (matches iOS response)
  - AudioTrack buffer reduced from ~2 seconds → ~200ms

New features:
- Picture-in-Picture (PiP) support
  - Auto-enter PiP when transmitting or keying CW and pressing the home button
  - On Android 12+: automatic via setAutoEnterEnabled

Bug fixes & improvements:
- Full edge-to-edge display support (Google Play policy compliance)
  - enableEdgeToEdge() + WindowInsetsCompat handles system bar insets
  - Deprecated APIs setStatusBarColor / setNavigationBarColor removed
- Fixed layout issue (1-row display) when connecting USB CW keyer
  - suppressPip flag prevents PiP entry during USB permission dialog
- App icon added to splash screen
- Fixed "+ New" button being partially hidden
- No changes to Pi-side scripts (same as v2.12)

● What's New in v2.12 (compared to v2.11)

New features:
- Hamlib 4.7.2 support added
  - Hamlib 4.7.x is not available via apt; source build is now supported
  - Installed to ~/.local/bin/rigctld (no sudo required; built with RPATH)
  - Install via the app's "Update" → "Update Hamlib" button (30–60 min on Pi Zero)
  - rigctld uses ~/.local/bin/rigctld (4.7.2) preferentially; falls back to system version if not present

- New "Update" screen (UI reorganization)
  - Update Pi, Update Hamlib, Pi Log, and Hamlib Log consolidated into one screen
  - Log area (green monospace) shows build progress in real time
  - Reload button for manual refresh
  - RIG CONNECT screen simplified to 6 buttons (2 rows × 3 columns)

- About screen now displays Pi API version and Hamlib version
  - Shows FastAPI version and rigctld version of the connected Pi

Bug fixes:
- Fixed webFT8 frequency change bug when editing Rig→TX / RX fields
  - Occurred in landscape orientation only
  - Cause: DOM change event listener was misinterpreting audio offset values as radio frequencies
  - localStorage.setItem-based frequency sync continues to work correctly

- WID (filter width), POW (TX power), and SQL (squelch) are now adjustable with ◀▶ buttons
  - WID ±100 Hz, POW ±1%, SQL ±1% per button press
  - Tap WID / POW / SQL button to select it, then use ◀▶ to adjust

- Fixed "Pi API version mismatch" shown after Update Pi
  - The app's expected-version constant was still set to v2.11, causing a false mismatch after update

● What's New in v2.11 (compared to v2.10)

New features:
- BLE CW keyer support added (DualKey-BLE / RemoteKeyer-BLE)
  - Connect DualKey-BLE (M5AtomS3) or RemoteKeyer-BLE to Android wirelessly via BLE (Bluetooth LE)
  - Uses Nordic UART Service (NUS) protocol
  - After pairing in Android Bluetooth settings, tap the BT button to auto-detect and connect
  - DualKey-BLE USB CDC / BLE auto-switching
    · Power-on window (10 sec): left paddle (DAH) → USB CDC mode,
                                 right paddle (DIT) → BLE mode (default)
    · While in BLE mode: USB app data received → auto-restart into USB CDC mode
    · While in USB CDC mode: USB disconnected → auto-restart into BLE mode

Improvements:
- CW connection status display improved
  - BLE connected: shows "BLE" in green
  - Disconnected: shows "BLE" in grey

● What's New in v2.10 (compared to v2.09)

Bug fixes & improvements:
- Enhanced Noise Reduction (NR) with 5 levels (unified to afftdn-based)
  - Level 1 (Light)   : afftdn=nf=-30:nr=15
  - Level 2 (Medium)  : afftdn=nf=-25:nr=20
  - Level 3 (Strong)  : afftdn=nf=-20:nr=25:tn=1
  - Level 4 (Stronger): afftdn=nf=-20:nr=33:tn=1
  - Level 5 (Max)     : afftdn=nf=-20:nr=40:tn=1
  - Long-press SQL button to cycle 0→1→2→3→4→5→0
  - NR settings now synced to dual-server (apiPort / audioPort)

- Improved Update Pi button reliability
  - sudoers now generated with actual runtime username (fixed whoami bug during sudo)
  - Fallback restart wait extended 3s → 15s (Pi Zero support)
  - Fallback now also restarts fastapi-audio (port 50000)

● What's New in v2.09 (compared to v2.08)

Bug fixes & improvements:
- CW decoder accuracy improvements (RX main screen & TX CW panel)
  - dit/dah boundary threshold improved (×2 → ×1.8): reduced dah misdetection
  - Inter-character gap threshold relaxed (×2 → ×2.5): reduced false character splits
  - ditWins convergence slowed for better tolerance of rapid speed changes
  - Energy calculation expanded from 3-bin to 5-bin total (improved SNR for weak signals)
  - Noise floor estimation changed from 30th → 20th percentile (improved interference tolerance)
  - TX side: ditMs lower limit changed 15ms → 20ms (prevents false counts)

● What's New in v2.08 (compared to v2.07)

Bug fixes & improvements:
- Fixed CW TX start lag (USB serial connection)
  - open_radio now saves effective PTT type (RIG) to current_ptt_type
  - Eliminated rigctld restart on every CW TX for ttyACM/ttyUSB (was causing 0–7s lag)

- CW TX panel UI improvements
  - Enlarged buttons, layout now fits in one screen
  - Landscape mode: 2-column layout

● What's New in v2.07 (compared to v2.06)

Bug fixes & improvements:
- USB serial PTT auto-optimization (IC-705 USB enhancement)
  - Auto-switch PTT from RTS → CAT (CI-V) for ttyACM/ttyUSB devices
  - Avoids IC-705 USB audio reset issue

- PTT forced release after rigctld start
  - Prevents accidental TX during rigctld restart

- Banned rigctld restart during TX
  - Fixed: DTR would be cut during CW/voice transmission causing transmission drop

- PTT type display changed "RIG" → "CAT" (UI unification)

- CW CQ repeat UI improved

● What's New in v2.06 (compared to v2.05)

Bug fixes & improvements:
- Fixed CW USB (DualKey) sync behavior when Pi is not connected
  - Keying timing is now calculated correctly even without Pi present

● What's New in v2.05 (compared to v2.04)

Bug fixes & improvements:
- Fixed CW TX being cut off mid-transmission
  - Removed set_morse_code_speed (K command) which was blocking rigctld for 2+ seconds
  - IC-7300 / IC-705 internal keyer manages PTT automatically; CAT PTT not needed

- Added CW TX end mode selection
  - Time-prediction mode (default): for IC-7300 / IC-705 internal keyer
  - PTT polling mode: for rigs with CAT PTT support (FT-991, etc.)
  - Toggle with "TX end: PTT poll" switch in CW TX panel

- Reduced CW TX start delay (600ms → 100ms)

- Fixed S-meter always showing S9 with IC-705

- Improved Update Pi button
  - One-tap update if Pi is already running v2.03 or later
  - No longer depends on Pi username (pi / pizero / etc.)

FastAPI update (via Update Pi button):
- CW TX end mode selection (ptt_poll parameter)
- BK-IN status auto-polling every 15 seconds
- Path handling generalized (username-independent)

● What's New in v2.04 (compared to v2.03)

Bug fixes & improvements:
- Fixed BK-IN status always showing OFF in CW mode
  - Added SBKIN / FBKIN polling in FastAPI poll_signal() (15-second interval)
  - Polling skipped during TX (IC-7300 PA relay protection)
  - Auto-detects semi break-in (SBKIN) then full break-in (FBKIN)

- Improved Update Pi button reliability
  - Added retry logic for api.py resend after create_api.sh (up to 5 x 5s)
  - Resend failure now reported to user (was silently ignored before)
  - Fixed misleading success message

FastAPI update (Update Pi button or re-run create_api.sh):
- Added CW-mode BK-IN auto-polling to poll_signal()

● What's New in v2.03 (compared to v2.02)

New features:
- FT8/FT4 decode (webft8-based), multi-profile support

Improvements:
- Sync Time button, SSL cert pinning, ALSA device separation

FastAPI (re-run create_api.sh):
- webft8 HTTPS server, improved time sync, home directory generalized
- API version 2.03

● What's New in v2.02 (compared to v2.01)

New features:
- CW TX panel added
  - Preset buttons: CQ / CALL K / AGN / UR 5NN BK
  - WPM slider (5–60 WPM, SeekBar)
  - Free text input (automatically shows English keyboard)
  - CW mode: sends morse via Hamlib keyer
  - FM-CW mode: streams PCM tone to Raspberry Pi
    Note: FM-CW has ~0.5–1 second latency due to audio buffering

Improvements & fixes:
- CW/CWR mode now automatically sets filter width to 500 Hz
- POW UP/DOWN now uses 1% steps (was 5%)
- Removed "%" from step labels in PWR/SQL dialogs
- All in-app messages translated to English

FastAPI update (re-run create_api.sh required):
- Added CW morse TX API (/cw/send_morse / /cw/stop_morse / /cw/morse_status)
- Added Break-in API (/radio/setbkin / /radio/getbkin)
- Added time sync API (/time)
- Added FT8 audio device API (/radio/audio_device_ft8)
- Added cw_bridge.py remote update (/admin/update_cw_bridge)
- APRS beacon now uses symbol/comment/destination from config
- Fixed APRS PTT control to use rigctld via PTT RIG 2 (resolves "cannot transmit" issue)
- Extended APRS KISS port wait timeout to handle Pi Zero startup time
- Suppressed PTT watchdog during APRS TX (resolves TX being cut off mid-packet)
- Made /aprs_config and /aprs_start non-blocking (fixes Android HTTP timeout)

● What's New in v2.01 (for reference)
- FT8/FT4 feature redesigned with WebView-based UI (requires port 8443 on Raspberry Pi)
- Please re-run create_api.sh on the Raspberry Pi side

● v2.00 Features (added from v1.50)
- FT8/FT4 receive decode and transmit (experimental feature)
- Selectable audio stream sampling rate (8k–48kHz)
- Filter width control

● v1.50 Features (no changes)
- Multi-channel CW decoder (long-press SPK button to show/hide decode panel)
  - Up to 5 simultaneous stations (TX row + RX rows ×5)
  - Strongest signal always shown in yellow (RX0), auto-promoted
  - Frequency drift tracking ±125 Hz (prevents duplicate channels)
  - Automatic merging of duplicate channels for the same frequency
  - Accurate decoding above 20 WPM
  - VPN latency support: audio bursts exceeding 2 seconds are skipped

● v1.40 Features (no changes)
- USB CW relay mode (M5ATOM Lite / M5ATOM S3 Lite connected directly to Android via USB)
  - CW mode: relays key state to Raspberry Pi /cw/key
  - Non-CW mode (FM, etc.): streams CW audio tone to /radio/audio_tx
  - Android sidetone playback (low latency, ON/OFF setting remembered)
  - CW VPN buffer setting (key signal delay compensation)
  - FM-CW PTT delay setting (prevents beginning cutoff on VPN; separate from CW delay)

● Features
- Real-time display of receive frequency, mode, and signal strength
- Change frequency, mode, power, squelch, and filter width
- Play received audio through the smartphone speaker (SPK)
- PTT ON/OFF and audio transmission (send microphone audio to the radio)
- WiFi PTT (PTT control via external devices such as M5Atom)
- USB CW relay (connect M5ATOM / DualKey directly to Android to relay CW key signals)
- BLE CW relay (connect DualKey-BLE or RemoteKeyer-BLE to Android via BLE to relay CW key signals)
- FT8/FT4 receive decode and transmit (WebView-based)
- APRS beacon transmission (via DireWolf, with smartphone GPS support)
- Multiple profile support (switch between connection targets)
- API Key authentication support
- Remote access via WireGuard VPN

● Requirements
- Android smartphone (Android 8.0 / API 26 or later)
- Raspberry Pi Zero 2W (already set up)
- Wi-Fi environment

For USB CW relay:
- M5ATOM Lite or M5ATOM S3 Lite (with Wifi_Rig_CW Ver1.40 firmware)
- OTG-compatible USB cable

For BLE CW relay (DualKey-BLE / RemoteKeyer-BLE):
- DualKey-BLE: M5AtomS3 (AtomS3) with Wifi_Rig_CW_DUALKEY Ver1.43 firmware
- RemoteKeyer-BLE: M5Stack Core etc. with Remotekeyer_M5Stack_Server Ver1.43 firmware
- Pair in Android Bluetooth settings (no OTG cable required)

● Installation
Download and install the APK from the following GitHub folder:
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.13/M5CoreHamCAT_Android

  1. Download Wifi_RIG_CTRL_v2.13.apk
  2. Enable "Install unknown apps" in Android settings
  3. Tap the APK to install

Source code is also published in the same folder (buildable with Android Studio).

● Raspberry Pi Setup
Follow the same setup procedure as for the M5Core version.
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.13/RaspberryPiSetup

Upgrading from v2.03 or later: use the "Update" → "Update Pi" button in the app
Hamlib 4.7.2 (added in v2.12): use the "Update" → "Update Hamlib" button in the app
Upgrading from v2.02 or earlier: first-time manual scp required
  scp api.py <username>@raspizero:~/fastapi/api.py
  ssh <username>@raspizero "sudo systemctl restart fastapi"

● Remote Access from Outside Home (WireGuard VPN)
If connecting from outside your home network (e.g., via mobile data), WireGuard setup is required.
https://github.com/ji1ore/M5CoreHamCAT/tree/main/v2.02/WireGuard
(No changes from v1.40)

2026/6/26