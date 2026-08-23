#!/bin/bash
# Pi上でft8_encodeバイナリをビルドするセットアップスクリプト
# 実行: bash setup_ft8_encode.sh
set -e

echo "=== ft8_encode セットアップ開始 ==="

# 依存パッケージ
sudo apt-get install -y git g++ cmake

# ft8_lib をクローン（すでにあればスキップ）
if [ ! -d /opt/ft8_lib ]; then
    sudo git clone https://github.com/kgoba/ft8_lib /opt/ft8_lib
    echo "ft8_lib クローン完了"
else
    echo "ft8_lib は既に存在します: /opt/ft8_lib"
fi

# ft8_encode_main.cpp をコピー
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sudo cp "$SCRIPT_DIR/ft8_encode_main.cpp" /opt/ft8_lib/

# ビルド
cd /opt/ft8_lib
sudo g++ -O2 -std=c++17 -I. \
    ft8/encode.c  ft8/message.c \
    ft8/ldpc.c    ft8/crc.c    \
    ft4/encode.c  \
    ft8_encode_main.cpp \
    -lm -o /usr/local/bin/ft8_encode

echo "=== ビルド完了: /usr/local/bin/ft8_encode ==="

# 動作確認
echo "動作確認:"
echo -n "  ft8_encode 'CQ TEST JA1ABC PM95' 1500 12000 | wc -c → "
ft8_encode 'CQ TEST JA1ABC PM95' 1500 12000 | wc -c
echo "  (期待値: 303360 bytes = 12.64秒分の12kHz S16_LE PCM)"

echo "=== セットアップ完了 ==="
echo ""
echo "次のステップ: api.py に /radio/ft8_tx エンドポイントを追加してください"
echo "  (ft8_tx_endpoint.py の内容を api.py に貼り付け)"
