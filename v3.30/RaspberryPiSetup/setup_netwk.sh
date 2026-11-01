#!/bin/bash
set -e

echo "net.ipv6.conf.all.disable_ipv6 = 1" | sudo tee /etc/sysctl.d/99-disable-ipv6.conf
echo "net.ipv6.conf.default.disable_ipv6 = 1" | sudo tee -a /etc/sysctl.d/99-disable-ipv6.conf

# Wi-Fi 省電力(パワーセーブ)を無効化: Pi Zero 2W が「Wi-Fiから消える/Unknown host」頻発を防ぐ
# 恒久化(NetworkManager conf.d, 再起動後有効)＋即時適用(iw)。SSH断防止のため NM 再起動はしない。
if command -v nmcli >/dev/null 2>&1; then
    sudo mkdir -p /etc/NetworkManager/conf.d
    printf '[connection]\nwifi.powersave = 2\n' | sudo tee /etc/NetworkManager/conf.d/wifi-powersave-off.conf >/dev/null
fi
for _wif in wlan0 wlan1; do sudo iw dev "$_wif" set power_save off 2>/dev/null || true; done