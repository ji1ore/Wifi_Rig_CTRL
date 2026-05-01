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