# 🦞 Pikmin Bloom GPS 模擬器 + 自動掛機機器人

> 桌面 Python 客戶端 + Android 伺服器 APK  
> 雙向驗證架構，全自動玩 Pikmin Bloom

---

## 目錄

1. [功能總覽](#1-功能總覽)
2. [架構說明](#2-架構說明)
3. [檔案結構](#3-檔案結構)
4. [第一次運行完整流程](#4-第一次運行完整流程)
5. [Android APK 打包](#5-android-apk-打包)
6. [ADB Wi-Fi 設定](#6-adb-wi-fi-設定)
7. [使用方法](#7-使用方法)
8. [完整檢核清單](#8-完整檢核清單)
9. [疑難排解](#9-疑難排解)

---

## 1. 功能總覽

### ✅ 已有功能

| 功能 | 說明 |
|------|------|
| 🚶 **GPS 步行模擬** | 1.1~1.5 m/s 人類步速，隨機方位角飄移 |
| 🔄 **雙向交握驗證** | TCP 握手 + 定時心跳，斷線即時偵測 |
| 📱 **ADB 裝置檢查** | 自動偵測 Android 裝置並取得 IP |
| 🗺️ **航點路線** | 可設定固定路線或隨機漫步 |
| 🛡️ **完整異常處理** | 連線超時、權限不足、裝置斷線等 |
| 🎮 **螢幕互動 (ADB)** | 模擬點擊、滑動、按鍵等操作 |
| 🌸 **自動種花** | 定時檢查並啟動 Pikmin Bloom 種花功能 |
| 🎁 **自動收集** | 自動收取果實、花苗、遠征獎勵 |
| 📊 **即時狀態** | 終端機顯示步數、點擊數、位置等資訊 |
| 🔌 **Wi-Fi 控制** | 支援 ADB over Wi-Fi，不需 USB 連線 |
| 🔄 **自動恢復** | 遊戲崩潰或斷線後自動重新啟動 |

### 🚧 限制說明

- ⚠️ **無法模擬真實加速度計資料**：部分遊戲會比對 GPS 與感測器，這是本工具無法模擬的
- ⚠️ **無法處理複雜對話**：無法閱讀遊戲對話內容，僅能點擊固定按鈕位置
- ⚠️ **建議間歇使用**：連續 24 小時不間斷移動仍可能觸發遊戲偵測機制

---

## 2. 架構說明

```
                        ┌──────────────────────────────────┐
                        │      Linux 模擬機 / 桌面電腦       │
                        │  ┌────────────────────────────┐  │
                        │  │  選用：完整自動模式          │  │
                        │  │  auto_bot/pikmin_bot.py    │  │
                        │  │  ├── ADBController (螢幕互動)│  │
                        │  │  └── Bot Engine (遊戲邏輯)  │  │
                        │  └────────────────────────────┘  │
                        │  ┌────────────────────────────┐  │
                        │  │  核心：GPS 模擬客戶端        │  │
                        │  │  gps_mock_client.py          │  │
                        │  │  ├── WalkSimulator (步行)    │  │
                        │  │  ├── PhoneClient (TCP)       │  │
                        │  │  └── ADBChecker (裝置偵測)   │  │
                        │  └────────────────────────────┘  │
                        └──────────┬───────────────────────┘
                                   │ TCP Socket (Wi-Fi)
                                   │ 協定：JSON over TCP
                                   ▼
                        ┌──────────────────────────────────┐
                        │      Android 手機 (伺服器端)      │
                        │  GPS Mock Server APK             │
                        │  ├── GPSServerService (TCP 監聽) │
                        │  ├── MockLocationProvider (GPS)  │
                        │  └── 前景服務 + Wake Lock        │
                        └──────────────────────────────────┘
                                   │ 注入至系統 LocationManager
                                   ▼
                        ┌──────────────────────────────────┐
                        │      Pikmin Bloom (遊戲)          │
                        │  接收模擬 GPS 座標 + 螢幕互動     │
                        └──────────────────────────────────┘
```

**完整運作流程**：

1. 📱 手機端：安裝並啟動 **GPS Mock Server APK** → 開始監聽 TCP 埠 8888
2. 💻 電腦端（選用 a）：執行 `gps_mock_client.py` → **僅 GPS 步行模擬**
3. 💻 電腦端（選用 b）：執行 `pikmin_bot.py` → **完整自動化（步行 + 螢幕互動）**
4. 🔗 電腦透過 **ADB over Wi-Fi** 傳送點擊指令至手機
5. 🔗 電腦透過 **TCP Socket** 傳送 GPS 座標至手機 APK
6. 📱 手機 APK 將座標注入 **Android LocationManager**
7. 🎮 Pikmin Bloom 讀取到模擬座標 → 角色開始移動
8. 🌸 Bot 定時點擊種花、收集按鈕 → 全自動掛機

---

## 3. 檔案結構

```
C:\Users\user\Desktop\pikmin-gps-mock\
│
├── gps_mock_client.py          # 📌 GPS 模擬器 (純步行)
├── auto_bot/                   # 📌 自動掛機模組
│   ├── __init__.py
│   ├── adb_controller.py       #   ADB 螢幕互動控制器
│   ├── pikmin_bot.py           #   Pikmin Bloom Bot 主程式
│   └── screen_analyzer.py      #   螢幕分析（選擇性）
├── android-server/             # Android APK 原始碼
│   ├── build_apk.sh            #   [Linux] 一鍵建構腳本
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle/wrapper/
│   │   └── gradle-wrapper.properties
│   └── app/
│       ├── build.gradle.kts
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/
│           │   ├── layout/activity_main.xml
│           │   ├── drawable/*.xml
│           │   └── values/strings.xml
│           └── java/com/gpsmock/server/
│               ├── MainActivity.kt          # UI 介面
│               ├── GPSServerService.kt      # TCP 伺服器 + GPS 注入
│               └── GPSMockLocationProvider.kt # Mock Location 管理
├── requirements.txt
└── README.md
```

---

## 4. 第一次運行完整流程

### 階段一：建構 APK（在 Linux 模擬機上）

> 如果你還沒有 Linux 環境，建議使用：
> - **WSL2** (Windows)：`wsl --install`
> - **VirtualBox** 安裝 Ubuntu 22.04 LTS
> - **雲端服務**：GitHub Codespaces 或 GitLab CI

#### 快速建構

將 `android-server/` 資料夾複製到 Linux 機器，然後執行：

```bash
cd android-server
bash build_apk.sh
```

這個腳本會自動：
1. 安裝 Java 17
2. 下載 Android SDK
3. 下載所需 SDK 元件
4. 編譯 APK (assembleRelease)
5. 產生存取金鑰並簽署 APK
6. 輸出 `gps-mock-server.apk`

> 腳本執行時間約 10~20 分鐘（取決於網路速度）

#### 手動建構（如果腳本失敗）

```bash
# 1. 安裝 Java
sudo apt update
sudo apt install openjdk-17-jdk unzip wget -y

# 2. 下載 Android SDK Command Line Tools
cd ~
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip
mkdir -p ~/android-sdk/cmdline-tools
mv cmdline-tools ~/android-sdk/cmdline-tools/latest
export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
echo 'export ANDROID_HOME=~/android-sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin' >> ~/.bashrc
source ~/.bashrc

# 3. 安裝 SDK 元件
sdkmanager "platforms;android-34" "build-tools;34.0.0"

# 4. 編譯
cd android-server
./gradlew assembleRelease

# 5. 簽署
keytool -genkey -v -keystore mock_gps.keystore \
  -alias mock_gps -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 123456 -keypass 123456 \
  -dname "CN=GPSMock, OU=Dev, O=GPSMock, L=Unknown, ST=Unknown, C=TW"

jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 \
  -keystore mock_gps.keystore -storepass 123456 \
  app/build/outputs/apk/release/app-release-unsigned.apk mock_gps

cp app/build/outputs/apk/release/app-release-unsigned.apk gps-mock-server.apk
```

### 階段二：安裝 APK 與手機設定

```
1. 傳送 APK 到手機
   adb install gps-mock-server.apk

2. 開啟開發者選項
   設定 → 關於手機 → 連續點擊「版本號碼」7 次

3. 開啟 USB 偵錯
   設定 → 開發者選項 → USB 偵錯

4. 設定 Mock Location App
   設定 → 開發者選項 → 選擇模擬位置資訊應用程式
   → 選擇「GPS Mock Server」

5. 開啟 App → 點擊「啟動伺服器」
   → 記下顯示的 IP 位址
```

### 階段三：ADB Wi-Fi 設定（如需自動種花／收集）

> **此步驟只需要做一次**，之後都可透過 Wi-Fi 控制手機。

```bash
# 1. USB 連接手機到電腦
# 2. 確認裝置已授權
adb devices
# 應顯示: xxxxxxxx    device

# 3. 切換 ADB 為 TCP/IP 模式 (連接埠 5555)
adb tcpip 5555

# 4. 拔掉 USB 線
# 5. 透過 Wi-Fi 連線
adb connect 192.168.1.100:5555

# 6. 確認連線成功
adb devices
# 應顯示: 192.168.1.100:5555    device
```

### 階段四：執行 Bot

```powershell
# 切換到桌面專案目錄
cd C:\Users\user\Desktop\pikmin-gps-mock

# 完整自動模式 (步行 + 種花 + 收集)
python auto_bot\pikmin_bot.py --ip 192.168.1.100 --adb-wifi --random-route 15

# 如果僅需步行（不控制遊戲）
python gps_mock_client.py --ip 192.168.1.100 --random-route 15
```

---

## 5. Android APK 打包

### 在 Linux 模擬機上一鍵打包

```bash
# 複製 android-server 目錄到 Linux 模擬機
scp -r android-server user@linux-vm:~/   # 或用隨身碟複製

# SSH 登入 Linux 模擬機
ssh user@linux-vm

# 執行建構腳本
cd ~/android-server
chmod +x build_apk.sh
./build_apk.sh

# 完成後在目錄下找到 gps-mock-server.apk
```

### 使用 GitHub Actions 自動打包（進階）

建立 `.github/workflows/build-apk.yml`：

```yaml
name: Build APK
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: 17
      - name: Build APK
        run: |
          cd android-server
          chmod +x gradlew
          ./gradlew assembleRelease
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: gps-mock-server
          path: android-server/app/build/outputs/apk/release/*.apk
```

---

## 6. ADB Wi-Fi 設定（詳細）

ADB over Wi-Fi 允許你**不需 USB 線**就能控制手機螢幕。這是自動種花與收集的關鍵。

### 第一次設定（只需一次）

| 步驟 | 指令 | 說明 |
|------|------|------|
| 1 | `adb devices` | 確認 USB 連線正常 |
| 2 | `adb tcpip 5555` | 切換 ADB 至 TCP/IP 模式（手機將重新啟動 adbd） |
| 3 | 拔掉 USB 線 | — |
| 4 | `adb connect 192.168.1.100:5555` | 透過 Wi-Fi 連線（請取代為實際 IP） |
| 5 | `adb devices` | 確認顯示 `device` 狀態 |

### 之後使用

手機重新開機後，ADB Wi-Fi 模式會自動關閉，需要重新設定：

```bash
# 如果手機重新開機了，用 USB 重複一次
adb tcpip 5555
adb connect 192.168.1.100:5555
```

### 斷線處理

```bash
adb disconnect 192.168.1.100:5555
adb connect 192.168.1.100:5555
```

---

## 7. 使用方法

### 模式 A：純 GPS 步行模擬（最輕量）

```powershell
cd C:\Users\user\Desktop\pikmin-gps-mock

# 基本使用（跳過 ADB 檢查）
python gps_mock_client.py --ip 192.168.1.100 --skip-adb

# 環形路線（在大安森林公園漫步）
python gps_mock_client.py --ip 192.168.1.100 `
    --lat 25.0310 --lon 121.5345 `
    --random-route 20 --route-radius 300 `
    --speed-min 1.1 --speed-max 1.5 --drift 8
```

### 模式 B：完整自動掛機（步行 + 種花 + 收集）

```powershell
cd C:\Users\user\Desktop\pikmin-gps-mock

# 基本完整模式
python auto_bot\pikmin_bot.py --ip 192.168.1.100 --adb-wifi

# 完整模式 + 環形路線（推薦）
python auto_bot\pikmin_bot.py --ip 192.168.1.100 --adb-wifi `
    --random-route 15 --route-radius 200 `
    --plant-interval 300 --collect-interval 180 `
    --verbose

# USB 模式（如果有接 USB）
python auto_bot\pikmin_bot.py --ip 192.168.1.100 --adb-usb

# 不自動啟動遊戲
python auto_bot\pikmin_bot.py --ip 192.168.1.100 --adb-wifi --no-launch
```

### 模式 C：分開執行（除錯用）

```powershell
# 終端機 1：GPS 模擬器
python gps_mock_client.py --ip 192.168.1.100 --skip-adb --random-route 10

# 終端機 2：ADB 控制（僅種花與收集）
python auto_bot\pikmin_bot.py --ip 192.168.1.100 --adb-wifi --no-launch
```

### Bot 參數說明

| 參數 | 預設 | 說明 |
|------|------|------|
| `--ip` | (必填) | 手機 IP 位址 |
| `--port` | 8888 | GPS 伺服器埠號 |
| `--adb-wifi` | — | 使用 ADB Wi-Fi 模式 |
| `--adb-usb` | — | 使用 ADB USB 模式 |
| `--adb-port` | 5555 | ADB Wi-Fi 埠號 |
| `--lat` | 25.0478 | 起始緯度 |
| `--lon` | 121.5170 | 起始經度 |
| `--speed-min` | 1.1 | 最低步速 (m/s) |
| `--speed-max` | 1.5 | 最高步速 (m/s) |
| `--random-route` | — | 環形路線（航點數） |
| `--route-radius` | 200 | 路線半徑 (m) |
| `--plant-interval` | 300 | 種花檢查間隔 (秒) |
| `--collect-interval` | 180 | 收集檢查間隔 (秒) |
| `--no-launch` | — | 不自動啟動遊戲 |
| `--verbose` | — | 詳細除錯輸出 |

---

## 8. 完整檢核清單

### ☐ 第一關：APK 安裝

- [ ] Linux 模擬機已安裝 Java 17
- [ ] Android SDK 已下載
- [ ] `build_apk.sh` 執行成功
- [ ] `gps-mock-server.apk` 已產生
- [ ] APK 已安裝到手機 (`adb install gps-mock-server.apk`)

### ☐ 第二關：手機設定

- [ ] 開發者選項已開啟
- [ ] USB 偵錯已開啟
- [ ] 「選擇模擬位置資訊應用程式」已設為「GPS Mock Server」
- [ ] Pikmin Bloom 的定位權限為「一律允許」
- [ ] 手機已關閉省電模式（避免背景斷線）
- [ ] 手機已連線至 Wi-Fi（與電腦同一網路）
- [ ] 已開啟 GPS Mock Server App 並點擊「啟動伺服器」

### ☐ 第三關：ADB Wi-Fi

- [ ] USB 連線已執行 `adb tcpip 5555`
- [ ] USB 線已拔除
- [ ] `adb connect 192.168.1.x:5555` 成功
- [ ] `adb devices` 顯示 `device` 狀態
- [ ] `adb shell echo OK` 回傳 `OK`

### ☐ 第四關：Bot 測試

- [ ] `python gps_mock_client.py --ip 192.168.1.x --skip-adb --check-only`
- [ ] 所有檢查項目通過
- [ ] `python auto_bot\pikmin_bot.py --ip 192.168.1.x --adb-wifi --random-route 5 --verbose`
- [ ] 終端機顯示步行資訊
- [ ] 手機上 Pikmin Bloom 角色開始移動

### ☐ 第五關：背景運行（最後確認）

- [ ] 關閉手機螢幕後角色仍在移動
- [ ] 5 分鐘後種花功能自動啟動
- [ ] 3 分鐘後收集功能自動執行
- [ ] 遊戲意外關閉後自動重啟

---

## 9. 疑難排解

### ADB 找不到裝置

```
adb: no devices/emulators found
```

```bash
# 解決方案
adb kill-server
adb start-server
adb devices
# 如果還是找不到，換一條 USB 線
```

### ADB Wi-Fi 連不上

```
unable to connect to 192.168.1.100:5555
```

```bash
# 確認手機 IP
adb shell ip route

# 重新設定
adb usb                # 切回 USB 模式
adb tcpip 5555         # 重新設為 TCP/IP
# 拔掉 USB
adb connect 192.168.1.100:5555
```

### GPS 座標不更新

- 確認手機 App 顯示「運行中」
- 確認手機與電腦在同一 Wi-Fi 子網路
- 檢查防火牆是否阻擋 TCP 8888

### 種花沒反應

- 確認 Pikmin Bloom 在前景運行
- 確認種花按鈕位置是否正確（不同解析度可能需要調整）
- 調整 `pikmin_bot.py` 中的 `BUTTON_POSITIONS` 百分比

### 遊戲錯誤代碼

| 錯誤 | 可能原因 | 解決方法 |
|------|----------|----------|
| Error 401 | 帳號認證問題 | 重新登入 |
| Error 0 (GPS) | 位置異常 | 降低飄移角度或暫停一下 |
| Network Error | 網路不穩 | 檢查 Wi-Fi 訊號 |
| Session Expired | 遊戲太久沒互動 | Bot 會自動重新啟動 |

### 如何讓 Bot 24 小時運行

1. 使用 PC 或 **樹莓派** 作為 Bot 主機
2. 手機設定：
   - 關閉自動休眠（開發者選項→保持喚醒）
   - 關閉省電模式
   - 連接充電器
3. 電腦端設定：
   - 使用 `--verbose` 監控運作狀況
   - 設定 Windows 不自動休眠
4. 建議每 6~8 小時重啟一次 Bot 與遊戲

---

## 免責聲明

本專案僅供**學習與研究**目的使用。使用此工具可能違反相關遊戲的服務條款（ToS），可能導致遊戲帳號被限制或封鎖。

**使用前請注意**：
- ⚠️ 請自行承擔使用風險
- ⚠️ 建議使用分身帳號測試
- ⚠️ 避免在重要帳號上使用
- ⚠️ 不要長時間連續運行（建議間歇使用）

---

## License

MIT License
