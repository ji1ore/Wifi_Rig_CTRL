#!/bin/bash
# API Key を設定・変更・削除するスクリプト
# 使い方:
#   bash set_api_key.sh           # 対話式でキー設定
#   bash set_api_key.sh mykey123  # 引数で直接指定
#   bash set_api_key.sh ""        # キーを空にして認証無効化

ME=${SUDO_USER:-$(whoami)}
ME_HOME=$(getent passwd "$ME" | cut -d: -f6)
ENV_FILE="$ME_HOME/fastapi/.env"

# .env ファイルが存在しない場合は作成
if [ ! -f "$ENV_FILE" ]; then
    mkdir -p "$(dirname "$ENV_FILE")"
    touch "$ENV_FILE"
fi

# キーを引数で受け取るか、対話式で入力
if [ $# -ge 1 ]; then
    NEW_KEY="$1"
else
    echo "現在の API Key:"
    CURRENT=$(grep -E '^API_KEY=' "$ENV_FILE" | cut -d= -f2-)
    if [ -n "$CURRENT" ]; then
        echo "  $CURRENT"
    else
        echo "  (未設定 — 認証なし)"
    fi
    echo ""
    read -rp "新しい API Key を入力 (空Enterで認証無効化): " NEW_KEY
fi

# .env に API_KEY を書き込む（既存行を置換、なければ追記）
if grep -qE '^#?API_KEY=' "$ENV_FILE" 2>/dev/null; then
    if [ -n "$NEW_KEY" ]; then
        sed -i "s|^#\?API_KEY=.*|API_KEY=$NEW_KEY|" "$ENV_FILE"
    else
        sed -i "s|^#\?API_KEY=.*|# API_KEY=|" "$ENV_FILE"
    fi
else
    if [ -n "$NEW_KEY" ]; then
        echo "API_KEY=$NEW_KEY" >> "$ENV_FILE"
    else
        echo "# API_KEY=" >> "$ENV_FILE"
    fi
fi

# 結果表示
if [ -n "$NEW_KEY" ]; then
    echo "API Key を設定しました: $NEW_KEY"
else
    echo "API Key を無効化しました (認証なし)"
fi

# systemd サービスに EnvironmentFile が設定されていなければ追加
_RELOAD_NEEDED=0
for SVC in fastapi fastapi-audio; do
    DROP_DIR="/etc/systemd/system/${SVC}.service.d"
    DROP_FILE="${DROP_DIR}/env.conf"
    if [ ! -f "$DROP_FILE" ]; then
        sudo -n mkdir -p "$DROP_DIR" 2>/dev/null || sudo mkdir -p "$DROP_DIR"
        printf "[Service]\nEnvironmentFile=-%s/fastapi/.env\n" "$ME_HOME" \
            | (sudo -n tee "$DROP_FILE" > /dev/null 2>/dev/null || sudo tee "$DROP_FILE" > /dev/null)
        echo "drop-in 追加: $DROP_FILE"
        _RELOAD_NEEDED=1
    fi
done

if [ "$_RELOAD_NEEDED" = "1" ]; then
    sudo -n systemctl daemon-reload 2>/dev/null || sudo systemctl daemon-reload || true
fi
sudo -n systemctl restart fastapi 2>/dev/null || sudo systemctl restart fastapi || true
sudo -n systemctl restart fastapi-audio 2>/dev/null || sudo systemctl restart fastapi-audio || true
echo "サービス再起動完了。"
