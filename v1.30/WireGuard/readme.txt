WireGuard VPN セットアップ（Wifi_Rig_CTRL v1.30 外出先接続用）
JI1ORE

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
概要
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Wifi_Rig_CTRL は自宅の Raspberry Pi に WiFi 経由でアクセスして
トランシーバーをリモート制御するアプリです。

自宅 LAN 内のみ使用する場合は WireGuard 不要です。
外出先（モバイル回線など）からアクセスする場合に本手順が必要です。

構成イメージ：
  [Android + WireGuard] ─── インターネット ───> [自宅ルーター]
                                                     │
                                              [Raspberry Pi (WireGuard サーバー)]
                                                     │
                                              [トランシーバー]


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
前提条件
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
・Raspberry Pi に Raspberry Pi OS Lite (64bit) がインストール済み
・Wifi_Rig_CTRL の RaspberryPiSetup が完了済み
・ルーターで UDP ポート 51820 を Raspberry Pi に転送設定済み
・DDNS サービスへの登録（DuckDNS 推奨）
  → https://www.duckdns.org/
  → サブドメインを作成し、自宅の外部 IP と紐づけておく

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
スクリプト
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
setup_wireguard.sh ：WireGuard サーバー構築（Raspberry Pi 上で実行）

  - wlan0 に固定 IP を設定
  - WireGuard インストール・サーバー鍵生成
  - サーバー設定ファイル（wg0.conf）生成
  - Android クライアント鍵生成・設定ファイル生成
  - WireGuard 起動・自動起動設定
  - Android 用 QR コード表示

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
①スクリプト実行前の変数設定
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
スクリプト冒頭の以下2箇所を自分の環境に合わせて変更してください。

【固定 IP 設定（セクション 0）】
  STATIC_IP="192.168.0.XX/24"   ← Raspberry Pi に割り当てる IP アドレス
  GATEWAY="192.168.0.1"         ← ルーターのゲートウェイ

【DuckDNS ドメイン名（セクション 6）】
  DUCKDNS_DOMAIN="yourdomain"   ← DuckDNS で作成したサブドメイン名

※ STATIC_IP の「XX」部分を実際の数値（例：50）に変更してください。
※ DUCKDNS_DOMAIN は DuckDNS のサブドメイン名のみ（".duckdns.org" は不要）。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
②スクリプト実行
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Raspberry Pi に SSH ログインして実行：

  wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v1.30/WireGuard/setup_wireguard.sh
  chmod +x setup_wireguard.sh
  nano setup_wireguard.sh      ← 変数を編集して保存
  bash setup_wireguard.sh

実行後、ターミナルに Android 用の QR コードと設定ファイルが表示されます。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
③Android WireGuard アプリの設定
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. Google Play から「WireGuard」をインストール
   https://play.google.com/store/apps/details?id=com.wireguard.android

2. アプリを開き「+」→「QR コードから作成」を選択

3. ターミナルに表示された QR コードをスキャン

4. トンネル名を入力（例：home-rig）して保存

5. トンネルを ON にして接続確認

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④Wifi_Rig_CTRL アプリ側の設定
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
WireGuard 接続時、Raspberry Pi は VPN 内の IP アドレス（10.0.0.1）
でアクセス可能になります。

アプリの Connect 画面で以下を設定：
  ホスト名  : 10.0.0.1
  API Port  : 8000
  Audio Port: 50000

mDNS（.local）は VPN 経由では動作しないため、IP アドレスで指定してください。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑤クライアント追加（2台目以降）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
スクリプトは android1 用の設定を自動生成します。
2台目以降を追加するには Raspberry Pi 上で手動で鍵生成・Peer 追加が必要です。

  # 鍵生成
  mkdir ~/wgkeys/android2 && cd ~/wgkeys/android2
  wg genkey | tee privatekey | wg pubkey > publickey

  # wg0.conf に Peer を追加（10.0.0.3/32 など IP を変える）
  sudo nano /etc/wireguard/wg0.conf

  # WireGuard 再起動
  sudo wg-quick down wg0 && sudo wg-quick up wg0

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑥ポート転送（ルーター設定）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ルーターの設定画面でポートフォワーディングを設定：

  プロトコル : UDP
  外部ポート : 51820
  転送先 IP  : Raspberry Pi の LAN 内 IP アドレス（例：192.168.0.50）
  転送先ポート: 51820

設定方法はルーターのメーカーにより異なります。
