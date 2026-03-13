① Introduction
M5CoreHamCAT is a system that uses a Raspberry Pi Zero 2W and an M5CoreS3SE to control a transceiver via CAT commands.
The Raspberry Pi Zero 2W is connected to the radio, and Hamlib running on the Pi is wrapped with FastAPI.
The M5CoreS3SE communicates with FastAPI to operate the radio and retrieve its status.

The system currently supports:

Displaying radio information

Receiving audio

(Since Ver.1.10) Sending PTT control signals

By combining this with a wireless microphone or similar device that sends audio to the radio, the system can transmit audio while controlling PTT from the device.

2026/3/1 Update  
M5CoreHamCAT_Speaker has been discontinued because audio output via the Module Audio is now supported.

Currently, operation has only been confirmed with the Yaesu FT-991A.
Other radios have not been tested.
Likewise, operation on M5CoreS3, M5CoreS3Lite, or other M5Core models has not been verified.

② Required Items
To run this system, you will need the following:

M5CoreS3SE / M5Core2 ver1.1
(M5CoreS3SE provides better performance)

Module Audio (M5 official, SKU: M144)

Raspberry Pi Zero 2W

Wi-Fi router
(Both devices must be on the same Wi-Fi network)

Unit Encoder (M5 official, SKU: U135)
Optional, but improves usability

Battery Bottom for M5Stack CoreS3
Optional, but convenient

microSD card (16GB or larger, reliable brand recommended)

USB cables for powering devices and connecting CAT interfaces

Additional items required for audio transmission

Mechanical key (M5 official, SK6812)

A microphone capable of sending audio to the radio (e.g., wireless mic)

③ Setup Procedure (Raspberry Pi Zero 2W)
Follow the instructions in the link below:
https://github.com/ji1ore/M5CoreHamCAT/blob/main/RaspberryPiSetup/readme.txt (github.com in Bing)

Main steps include:

Install Raspberry Pi Imager

Create the OS image (configure Wi-Fi SSID and user password here)

SSH login

Run the required setup commands
(A shell script is provided; it is simple but takes time)

④ Setup Procedure (M5CoreS3SE / M5Core2)
Use M5Burner to flash the firmware onto the device.

Source code is available here:
https://github.com/ji1ore/M5CoreHamCAT/main/M5CoreHamCAT (github.com in Bing)

The firmware is intended to be compiled using PlatformIO in Visual Studio Code.

Flashing procedure:

Download and install M5Burner

Launch M5Burner and create an account

Download M5CoreHamCAT

Connect the M5CoreS3SE/M5Core2 via USB and burn the firmware
(Search for “M5CoreHamCAT” inside M5Burner)

⑤ Notes
The system is still somewhat unstable.
If it does not work properly, try rebooting a few times.

If the radio information stops updating, SSH into the Raspberry Pi Zero 2W and reboot it:

sudo reboot now
Be careful to select the correct CAT device.
Pressing buttons repeatedly may cause the device to reboot.

To reduce audio latency, the system reconnects every 10 minutes.
During reconnection, audio may stop for a few seconds.

On M5Core2, you may need to press and hold UI buttons slightly longer.