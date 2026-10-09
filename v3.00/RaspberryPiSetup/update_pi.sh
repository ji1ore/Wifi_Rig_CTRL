#!/bin/bash
set -e

# ── 環境に合わせて変更してください ──────────────────
PI_USER="pi"             # Pi のログインユーザー名
PI_HOST="raspizero.local" # Pi のホスト名または IP アドレス
# ────────────────────────────────────────────────────
REMOTE_PATH="~/fastapi/api.py"
LOCAL_API="$(dirname "$0")/api.py"
KEYCHAIN_SERVICE="ssh_pi_hamcat"

# キーチェーンからパスワード取得、なければGUIダイアログで入力して保存
PI_PASS=$(security find-generic-password -a "$PI_USER" -s "$KEYCHAIN_SERVICE" -w 2>/dev/null || true)

if [ -z "$PI_PASS" ]; then
    PI_PASS=$(osascript \
        -e 'Tell application "System Events" to display dialog "Pi (raspizero.local) SSH password:" default answer "" with hidden answer buttons {"Cancel", "OK"} default button "OK"' \
        -e 'text returned of result' 2>/dev/null || true)
    if [ -z "$PI_PASS" ]; then
        echo "キャンセルしました" >&2
        exit 1
    fi
    security add-generic-password -a "$PI_USER" -s "$KEYCHAIN_SERVICE" -w "$PI_PASS"
    echo "パスワードをキーチェーンに保存しました"
fi

echo "=== Pi デプロイ ==="
sshpass -p "$PI_PASS" scp -o StrictHostKeyChecking=accept-new \
    "$LOCAL_API" "${PI_USER}@${PI_HOST}:${REMOTE_PATH}"

sshpass -p "$PI_PASS" ssh -o StrictHostKeyChecking=accept-new \
    "${PI_USER}@${PI_HOST}" "sudo systemctl restart fastapi && systemctl is-active fastapi"

echo "=== 完了 ==="
