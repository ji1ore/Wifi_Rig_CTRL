①はじめに
M5CoreHamCATは、Raspberry Pi Zero2WとM5CoreS3SEを用い、無線機に接続したRaspberry Pi Zero2Wを使って
無線機をCAT操作し、その情報の取得、及び操作をM5CoreS3SE上で行うシステムです。
技術的には、Raspberry Pi Zero2W上のHamlibをFastAPIでWrapし、M5CoreS3SEからFastAPIを叩いて無線機の操作や
無線機の情報を取得する、ということを行っています。
できることは、無線機の情報の表示、、音声の受信操作です。
Ver1.10でPTT信号の送付に対応しました。
ラジオマイク等でリグへ音声信号を送付したうえで、当機械よりPTTのON/OFFを発することができます。
Ver1.20にてAPRSの送信に対応しました(動作確認、IC-705/ボーレート1200bpsのみ)。
音声の受信とは排他の関係になりますが、APRS機能のついていない機種からラズパイ上のDireWolfを使い、APRS信号を
送付することができます。APRSモードでは、現在周波数から別に設定したAPRS周波数に遷移して(144.66など)APRSの発信を
行います。
なお、ボーレートについては送信機種に依存するようで、IC-705(USB接続)からでは送信することができませんでした。
--
2026/3/1
M5CoreHamCAT_SpeakerはModule Audio経由で音を出すことができるようになったので、廃止します。
--

現在のところ、Yaesu FT-991A のみで動作確認を行っており、他の無線機での動作は未検証です。
また、 M5CoreS3やM5CoreS3Lite、他のM5Coreシリーズで動作するかは未検証です。

②必要なもの
当システムを動作させるために必要なものは以下のとおりです。
・M5CoreS3SE/M5Core2 ver1.1 (M5CoreS3SEのほうが快適に動作します)
・Module Audio(M5純正 SKU:M144) PortAに刺します。
・Raspberry Pi Zero2W
・Wifiルータ(上記２つの端末が同一Wifiネットワーク上に存在することを前提とします。)
・Unit Encoder(M5純正 SKU:U135) 
 なくても動作しますが、操作性が向上します。
・M5Stack CoreS3用バッテリーボトム
 なくても動作しますが、利便性があがりますので。
・MicroSDカード(16G以上、信頼性の高いもの)
・その他無線機やM5CoreS3SE、Raspberry Pi Zero2Wへの電源取得やCATデータ取得に用いるUSBケーブル類
APRS動作のために
・スマートフォン(アプリ：Taskerが動作するもの)
・スマートフォンアプリ、Tasker(有償)
--
音声信号の送信に必要なもの
・メカニカルキー(M5純正 SK6812) PortC(M5Core2) PortB(M5CoreS3SE)に刺します。
・無線機に音声を飛ばせるマイク(ラジオマイク等)

③セットアップ手順(Raspberry Pi Zero2W)
https://github.com/ji1ore/M5CoreHamCAT/blob/main/v1.11/RaspberryPiSetup/readme.txt
を参照してセットアップを行ってください(verに応じたフォルダを参照ください)。
主な手順は以下の通りです。
・Raspberry Pi Imagerのインストール
・Raspberry Pi Imagerの作成(ここでWifi SSIDの指定やユーザパスワードを指定します)
・SSHログイン
・必要コマンドの実施(シェルファイルを用意してありますので簡単ですが時間がかかります)

④セットアップ手順(M5CoreS3SE/M5Core2)
M5CoreS3SE/M5Core2では、M5Burnerを用いてファームウェアの読み込みを行ってください。
Git上の以下フォルダにソースは公開します。
https://github.com/ji1ore/M5CoreHamCAT/main/M5CoreHamCAT
ソースはVisual Studio Code上のPlatformI/O上でのコンパイルを前提にしています。
ファームウェアの読み込み手順は以下のとおりです。
・M5Burnerをダウンロードし、インストールします。
・M5Burnerを起動します。ユーザー登録を行います。
・M5CoreHamCATをDownloadします。
・コンピュータにM5CoreS3SE/M5Core2をUSBケーブルで接続し、Burnします。
MBurner上では、M5CoreHamCATで検索できます。
APRSについては、メイン画面のグレーアウトしたAPRSボタンを長押しすることにより、設定を修正できます。
また、スマートフォンのGPSデータを、Taskerを使ってラズパイ上のFastAPIに送付することができます。
設定手順は「RaspberryPiSetup」フォルダ内のReadmeを参照願います。

⑤注意点
まだ結構不安定です。上手く動かないこともあると思いますので、何度か再起動するなど試してみてください。
無線機の情報が取れなくなったら、Raspberry Pi Zero2WにSSH接続をしてリブートを行ってください。
リブートのコマンドは以下になります。

sudo reboot now 

CATデバイスの選択を間違えると接続できませんのでご注意ください。
連打をすると再起動がかかることがあります。
音声は遅延防止のために10分ごとに再接続しています。そのタイミングで数秒聞こえなくなりますのでご了承ください。
M5Core2の場合、メイン画面上の操作を長押し気味にする必要があります。

以上。
