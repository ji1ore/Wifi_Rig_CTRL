#!/bin/bash
# update_pi.sh — macOS 開発機から api.py / mfsk-decode を Pi へ転送して再起動する開発用スクリプト。
# アプリの「Update Pi」ボタンと同等の処理を、ssh/scp 経由で行います (sshpass / rsync が必要)。
#
# ※ Pi 側のスクリプト (setup_fastapi_radio.sh / create_api.sh / set_api_key.sh) は
#    ${SUDO_USER:-$(whoami)} で実行ユーザーを取得するためユーザー名に依存しません。
#    本スクリプトだけは Mac 側から接続先を指定する必要があるため、以下の変数で指定します。
set -e

# ── 環境に合わせて変更するか、環境変数 / 引数で指定してください ──────────────
#   例: PI_USER=jiro PI_HOST=raspizero.local bash update_pi.sh
#   例: bash update_pi.sh jiro raspizero.local
PI_USER="${1:-${PI_USER:-$USER}}"               # Pi のログインユーザー名 (既定: Mac のユーザー名)
PI_HOST="${2:-${PI_HOST:-raspberrypi.local}}"   # Pi のホスト名または IP アドレス
# ────────────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REMOTE_PATH="~/fastapi/api.py"
LOCAL_API="$SCRIPT_DIR/api.py"
KEYCHAIN_SERVICE="ssh_pi_hamcat"

echo "接続先: ${PI_USER}@${PI_HOST}"

# キーチェーンからパスワード取得、なければGUIダイアログで入力して保存
PI_PASS=$(security find-generic-password -a "$PI_USER" -s "$KEYCHAIN_SERVICE" -w 2>/dev/null || true)

if [ -z "$PI_PASS" ]; then
    PI_PASS=$(osascript \
        -e "Tell application \"System Events\" to display dialog \"Pi (${PI_USER}@${PI_HOST}) SSH password:\" default answer \"\" with hidden answer buttons {\"Cancel\", \"OK\"} default button \"OK\"" \
        -e 'text returned of result' 2>/dev/null || true)
    if [ -z "$PI_PASS" ]; then
        echo "キャンセルしました" >&2
        exit 1
    fi
    security add-generic-password -a "$PI_USER" -s "$KEYCHAIN_SERVICE" -w "$PI_PASS"
    echo "パスワードをキーチェーンに保存しました"
fi

SSH="sshpass -p $PI_PASS ssh -o StrictHostKeyChecking=accept-new"
SCP="sshpass -p $PI_PASS scp -o StrictHostKeyChecking=accept-new"

echo "=== api.py デプロイ ==="
$SCP "$LOCAL_API" "${PI_USER}@${PI_HOST}:${REMOTE_PATH}"

echo "=== mfsk-decode ソース同期 ==="
sshpass -p "$PI_PASS" rsync -av --delete --exclude '.git' \
    -e "ssh -o StrictHostKeyChecking=accept-new" \
    "$SCRIPT_DIR/mfsk-decode/" \
    "${PI_USER}@${PI_HOST}:~/mfsk-decode/"

echo "=== mfsk-decode ビルド (初回は数分かかります) ==="
$SSH "${PI_USER}@${PI_HOST}" bash <<'REMOTE'
set -e
# Rust が未インストールの場合はインストール
if ! command -v cargo >/dev/null 2>&1; then
    echo "[mfsk-decode] Rust をインストール中..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --no-modify-path
fi
. "$HOME/.cargo/env"
cd ~/mfsk-decode
cargo build --release 2>&1 | tail -5
echo "[mfsk-decode] バイナリ: $(ls -lh target/release/mfsk-decode)"
REMOTE

echo "=== fastapi 再起動 ==="
$SSH "${PI_USER}@${PI_HOST}" "sudo systemctl restart fastapi && systemctl is-active fastapi"

echo "=== 完了 ==="
