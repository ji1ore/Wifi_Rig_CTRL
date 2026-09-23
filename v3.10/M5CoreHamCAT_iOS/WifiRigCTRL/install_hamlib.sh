#!/bin/bash
HAMLIB_VER="4.7.2"
PREFIX="$HOME/.local"
echo "=== Hamlib ${HAMLIB_VER} インストール開始 $(date) ==="
echo "インストール先: ${PREFIX}"

# すでにインストール済みか確認
if "$PREFIX/bin/rigctld" --version 2>/dev/null | grep -qF "${HAMLIB_VER}"; then
    echo "Hamlib ${HAMLIB_VER} はすでにインストール済みです (${PREFIX})"
    echo "=== 完了 ==="
    exit 0
fi
if /usr/local/bin/rigctld --version 2>/dev/null | grep -qF "${HAMLIB_VER}"; then
    echo "Hamlib ${HAMLIB_VER} はすでにインストール済みです (/usr/local)"
    echo "=== 完了 ==="
    exit 0
fi

echo "=== 依存パッケージをインストール中 ==="
sudo -n apt-get -o DPkg::Lock::Timeout=120 install -y \
    build-essential libtool autoconf automake libusb-dev pkg-config \
    && echo "依存パッケージ OK" || echo "警告: 依存パッケージ一部失敗"

echo "=== ソースをダウンロード中 ==="
cd /tmp
rm -rf hamlib-${HAMLIB_VER} hamlib-${HAMLIB_VER}.tar.gz
wget --progress=dot:mega --tries=3 --timeout=60 \
    "https://github.com/Hamlib/Hamlib/releases/download/${HAMLIB_VER}/hamlib-${HAMLIB_VER}.tar.gz" \
    && echo "ダウンロード完了" || { echo "エラー: ダウンロード失敗"; exit 1; }
tar tf hamlib-${HAMLIB_VER}.tar.gz > /dev/null 2>&1 \
    && echo "tarball 確認 OK" || { echo "エラー: tarball が壊れています"; exit 1; }
tar xf hamlib-${HAMLIB_VER}.tar.gz && echo "展開完了" || { echo "エラー: 展開失敗"; exit 1; }
cd hamlib-${HAMLIB_VER}

echo "=== configure 中 ==="
mkdir -p "${PREFIX}/bin" "${PREFIX}/lib"
./configure --disable-static --prefix="${PREFIX}" \
    LDFLAGS="-Wl,-rpath,${PREFIX}/lib" \
    && echo "configure 完了" || { echo "エラー: configure 失敗"; exit 1; }

echo "=== コンパイル中 ($(nproc) コア) ==="
make -j$(nproc) && echo "make 完了" || { echo "エラー: make 失敗"; exit 1; }

echo "=== インストール中 (sudo 不要) ==="
make install && echo "インストール完了" || { echo "エラー: make install 失敗"; exit 1; }

INSTALLED=$("${PREFIX}/bin/rigctld" --version 2>/dev/null | head -1)
echo "インストール済み: ${INSTALLED}"
echo "=== 完了 ==="
