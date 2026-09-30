# Relay

Relayは、災害などでインターネットや携帯回線が不安定な状況でも、救助情報をできるだけ届け続けるための実験的な通信プロジェクトです。

Android端末で作成した救助情報を暗号化し、端末間通信・LAN・HTTPS Brokerなどの利用可能な経路でPC Gatewayへ中継します。署名付きReceiptによる受信確認、位置情報、地域設定、公式情報の取り込みに対応します。地域・外部接続は運用者が設定します。

## 主な構成

- `app`：Androidアプリ
- `shared` / `relay-protocol`：共通モデル、暗号化、通信プロトコル
- `pc-gateway`：受信・保存・職員向けWeb画面
- `broker`：暗号化情報を中継するHTTPS Broker
- `composeApp`：共有UI、`pc-ble-bridge`：Windows BLE受信ブリッジ
- `fuzz-jvm` / `staff-console-e2e`：追加検証

## 注意

開発・検証段階です。配送や救助の実行を保証しません。119、消防、警察、自治体などの公式な緊急通信手段を代替しません。

## ビルド・テスト

JDK 17、Android SDK 36 / Build Tools 36.0.0が必要です。SDKの場所を`ANDROID_HOME`または未追跡の`local.properties`で指定してください。初回はGradleと依存関係をダウンロードします。Windowsでは`./gradlew`を`gradlew.bat`に読み替えてください。

```sh
./gradlew projects
./gradlew :app:assembleDebug :pc-gateway:installDist :broker:installDist
./gradlew :shared:jvmTest :relay-protocol:test :pc-gateway:test :broker:test :app:testDebugUnitTest
python scripts/verify-public-source.py
gitleaks dir . --redact
```

GatewayとBrokerはそれぞれ`./gradlew :pc-gateway:run`、`./gradlew :broker:run`で起動できます。環境変数による設定が必要です。AndroidのBroker接続先は`-Prelay.broker.endpoint=https://<運用者のドメイン>`で指定し、デフォルトでは無効です。地域設定の例は`config/region-profile.example.json`にあります。秘密鍵、認証情報、署名鍵、実環境の設定をリポジトリへ保存しないでください。

## ライセンス

[Apache License 2.0](LICENSE)
