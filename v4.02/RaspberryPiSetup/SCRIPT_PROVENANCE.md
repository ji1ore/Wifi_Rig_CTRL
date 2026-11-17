# Script provenance / スクリプトの出所 (v4.00)

This folder is the **manual Raspberry Pi setup** distribution. Some scripts here are
intentionally **not** embedded in the Android/iOS app bundles. This note records why,
so the difference is not mistaken for drift.

このフォルダは**手動セットアップ**用の配布物です。一部スクリプトはアプリ（Android/iOS）に
**意図的に同梱していません**。差分ではなく仕様であることを明記します。

## App-embedded scripts — byte-identical across Android / iOS / FastAPI
These are shipped inside both apps (`app/src/main/assets/` and the iOS bundle) **and** here,
and are verified **byte-for-byte identical** in all three locations:

- `api.py`            (FastAPI server; API_VERSION = 4.00)
- `create_api.sh`     (installs/updates api.py + builds mfsk-decode; used by **Update Pi**)
- `setup_fastapi_radio.sh`
- `install_hamlib.sh`
- `set_api_key.sh`
- `cw_bridge.py`

The app's **Setup Pi / Update Pi** flow runs `create_api.sh`, which performs the FT8-encoder
build and network tweaks **inline**. No hardcoded `pi` user — all scripts resolve the user via
`${SUDO_USER:-$(whoami)}` and `getent passwd`, so any username works. All scripts are LF (no CRLF).

## Manual-setup / developer-only scripts — present here only (reason documented)
Not embedded in the apps because the app flow does the equivalent via `create_api.sh`:

- `update_pi.sh`        — **macOS developer tool** (pushes api.py over SSH via the login
                          keychain/`sshpass`). Never deployed to the Pi; dev machine only.
- `setup_ft8_encode.sh` — standalone helper for a **manual** Pi setup (builds `ft8_encode`).
                          The app path builds/uses the encoder via `create_api.sh`.
- `setup_netwk.sh`      — standalone **manual** network tweak (IPv6 off / Wi-Fi power-save).
                          Optional; applied inline during the app setup path.

## Security note
`setup_fastapi_radio.sh` sets the Pi's VNC password to the classic default `raspberry`
(line ~422) so first-time VNC access works out of the box. This is a **chosen default**, not a
leaked credential. Change it on the Pi for anything exposed beyond a trusted LAN.
