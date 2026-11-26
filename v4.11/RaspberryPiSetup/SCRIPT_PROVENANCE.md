# Script provenance & parity (v4.11)

This file documents which Pi scripts are shipped inside the mobile apps vs which
live only in the FASTAPI project, and why. Checked at release time.

## Bundled in BOTH apps — kept byte-identical across all three locations

These 6 files MUST be byte-identical in:
- `Projects/Wifi_Rig_CTRL_FastAPI_4.11/` (canonical)
- `Projects/Wifi_RIG_CTRL_ForAndroid_4.11/app/src/main/assets/`
- `WifiRigCTRL_iOS/WifiRigCTRL_iOS _4.11/WifiRigCTRL/` (iOS bundle)

| file | role | pushed/used by app |
|------|------|--------------------|
| `api.py` | FastAPI backend (API_VERSION 4.11) | SSH Setup, Update Pi, WireGuard pushApiPy |
| `create_api.sh` | writes api.py on the Pi (embeds a byte-identical copy of api.py) + installs deps + fixes current IP static | SSH Setup, Update Pi |
| `setup_fastapi_radio.sh` | first-time setup: venv, Hamlib build, services | SSH Setup |
| `install_hamlib.sh` | (re)build Hamlib | `/admin/install_hamlib` endpoint |
| `set_api_key.sh` | set/rotate API key | SSH Setup / WireGuard |
| `cw_bridge.py` | UDP→serial CW bridge | installed by create_api.sh |

`create_api.sh` embeds api.py between `<<'APIEOF'` … `APIEOF`; the embed md5 MUST
equal api.py md5 (regenerate on every api.py change).

## FASTAPI-only — intentionally NOT bundled in the apps (reason noted)

| file | why FASTAPI/dev-only |
|------|----------------------|
| `build_installer.sh` | macOS **developer-machine** tool that packages the one-click Pi installer zip (`dist/WifiRigCTRL_Installer.zip`). Never runs on the Pi via the app. |
| `setup_ft8_encode.sh` | standalone/installer-path FT8 encoder build. The app path builds ft8_encode **inline inside create_api.sh**, so the app does not need this separate script. |
| `setup_netwk.sh` | standalone network helper for the manual/installer setup path. The app configures networking via api.py endpoints (`/network/set_static_ip`) + create_api.sh. |
| `update_pi.sh` | standalone manual "update the Pi" script for users who installed via the GitHub zip. The app's **Update Pi** button does the same job in-app (send api.py → run create_api.sh → restart), so it is not bundled. |

These four are not referenced by `setup_fastapi_radio.sh`, `create_api.sh`, or the
Android/iOS app code — verified at v4.11 release.
