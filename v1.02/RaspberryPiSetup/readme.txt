RaspberryPiのセットアップについて
①raspberryPiイメージャーのインストール
https://www.raspberrypi.com/software/

②起動用のMicroSDカード作成
・対象のイメージはRaspberry Pi OS Lite(64bit)
・Hostnameは「raspizero」だと以降の設定が楽
・設定上はユーザ名piを想定
・SSIDはイメージ作成時点で設定
・SSHを使ってセットアップを行う
ことを想定します。

なお、RaspberryPiZero2WのWifiですが、Wifiルータの
高速ローミング(11r)がONになっていると接続できないことがあります。
RaspberryPiZero2WがWifiに繋がらない場合、この可能性を疑ってください。

③SSHログイン
Dos窓やPowerShellに、

ssh pi@raspizero

と入力し、RaspberryPiZero2Wにログインします。

④各種インストール
以下コマンドを実施してください。
なお、結構時間がかかります。

sudo apt -y update
sudo apt -y install bash
wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v0.02/RaspberryPiSetup/setup_netwk.sh
chmod +x setup_netwk.sh
bash setup_netwk.sh

※ここでログアウトして再度SSHログインしてください。
logout

※ログイン後ネットワーク確認。
※IPv4アドレスが取得できてることを確認してください。

ifconfig

※以下コマンドでupgradeするかの選択肢が出てきたら回答してください(Nでよいかと)。

wget https://raw.githubusercontent.com/ji1ore/M5CoreHamCAT/main/v0.02/RaspberryPiSetup/setup_fastapi_radio.sh
chmod +x setup_fastapi_radio.sh
bash setup_fastapi_radio.sh

sudo systemctl status fastapi
rm setup_netwk.sh
rm setup_fastapi_radio.sh

⑤確認
インストールが完了すると以下のようなメッセージが出るはずです。
Created symlink '/etc/systemd/system/multi-user.target.wants/fastapi.service' → '/etc/systemd/system/fastapi.service'.
● fastapi.service - FastAPI Service
     Loaded: loaded (/etc/systemd/system/fastapi.service; enabled; preset: enabled)
     Active: active (running) since Thu 2026-02-12 00:18:45 JST; 177ms ago
 Invocation: b3e4094b4ea64e44b10691a3781b23d7
   Main PID: 68850 (uvicorn)
      Tasks: 1 (limit: 163)
        CPU: 62ms
     CGroup: /system.slice/fastapi.service
             └─68850 /home/pi/fastapi/bin/python3 /home/pi/fastapi/bin/uvicorn api:app --host 0.0.0.0 --port 8000

Feb 12 00:18:45 raspizero systemd[1]: Started fastapi.service - FastAPI Service.

このようなメッセージが出れば正常にサービスが起動していますので、インストール完了です。

