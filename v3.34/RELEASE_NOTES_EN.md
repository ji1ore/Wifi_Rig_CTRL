# Wifi_RIG_CTRL v3.34 Release Notes

**Version**: 3.34 (Android versionCode 161 / iOS build 78)  
**Release date**: 2026-11-06

---

## What's changed

This update adds **noise reduction (NR) for RX CW (Morse) decoding.**

### New CW-decode NR (for crowded / noisy bands)

- While decoding CW you can now enable an in-app **narrow bandpass centred on the received tone (CW pitch).** Tap the RX frequency to toggle it (on iOS, tap the "NR" chip).
- It suppresses nearby interfering stations and band noise, improving **both listenability and decode accuracy.** The centre is fixed to the pitch while decoding, and a gentle AGC emphasises the signal in the playback audio.
- The afftdn FFT denoiser (Pi-side) is no longer used during CW decode, so there are **no CPU-induced audio dropouts.**
- NR is optional and off by default.

### GA / GE buttons swapped on the CW / RTTY screens

- The GA and GE greeting-macro buttons were swapped left-right to reduce mis-taps.

---

## Pi update

**This version updates the Pi API to 3.34.** Use the app's "Update Pi" button to apply the new api.py bundled in the app (it restarts the Pi automatically). After updating, the "Update" badge on the main screen disappears.
