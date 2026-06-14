#!/bin/bash
# ============================================================
# GPS Mock Server — APK 建構腳本 (Linux 模擬機用)
# ============================================================
# 使用方式：
#   將 android-server/ 目錄複製到 Linux 模擬機
#   執行: bash build_apk.sh
#
# 產出：app/build/outputs/apk/release/gps-mock-server.apk
# ============================================================
set -e

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
echo "========================================"
echo " GPS Mock Server APK Builder"
echo " 目錄: $APP_DIR"
echo "========================================"

# --- 檢查 Java ---
if ! command -v java &> /dev/null; then
    echo "[1/6] 安裝 Java 17 ..."
    sudo apt update && sudo apt install -y openjdk-17-jdk
else
    echo "[1/6] Java: $(java -version 2>&1 | head -1)"
fi

# --- 檢查 Android SDK ---
ANDROID_SDK="${ANDROID_HOME:-$HOME/android-sdk}"
if [ ! -d "$ANDROID_SDK" ]; then
    echo "[2/6] 下載 Android SDK Command Line Tools ..."
    
    cd ~
    # 下載最新 cmdline-tools
    CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    wget -q "$CMDLINE_URL" -O cmdline-tools.zip
    unzip -q cmdline-tools.zip
    mkdir -p "$ANDROID_SDK/cmdline-tools"
    mv cmdline-tools "$ANDROID_SDK/cmdline-tools/latest"
    rm cmdline-tools.zip
    
    export ANDROID_HOME="$ANDROID_SDK"
    export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin"
    echo "export ANDROID_HOME=\$HOME/android-sdk" >> ~/.bashrc
    echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin' >> ~/.bashrc
else
    echo "[2/6] Android SDK: $ANDROID_SDK"
fi

# --- 安裝 SDK 元件 ---
echo "[3/6] 安裝 SDK 元件 (platforms;android-34, build-tools)..."
yes | "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" \
    "platforms;android-34" \
    "build-tools;34.0.0" 2>/dev/null || true

# --- 產生 Gradle Wrapper (若無) ---
echo "[4/6] 準備 Gradle Wrapper ..."
cd "$APP_DIR"

# 檢查 gradlew
if [ ! -f "gradlew" ]; then
    # 安裝 Gradle 以產生 wrapper
    if ! command -v gradle &> /dev/null; then
        echo "下載 Gradle ..."
        wget -q https://services.gradle.org/distributions/gradle-8.5-bin.zip -O /tmp/gradle.zip
        sudo unzip -q /tmp/gradle.zip -d /opt/
        sudo ln -sf /opt/gradle-8.5/bin/gradle /usr/local/bin/gradle
    fi
    gradle wrapper --gradle-version 8.5
fi
chmod +x gradlew

# --- 編譯 ---
echo "[5/6] 編譯 APK ..."
export ANDROID_HOME="$ANDROID_SDK"
./gradlew assembleRelease

# --- 簽署 ---
echo "[6/6] 簽署 APK ..."

APK_UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
APK_SIGNED="gps-mock-server.apk"

if [ -f "$APK_UNSIGNED" ]; then
    # 檢查金鑰
    KEYSTORE="mock_gps.keystore"
    KEYSTORE_ALIAS="mock_gps"
    
    if [ ! -f "$KEYSTORE" ]; then
        echo "產生簽署金鑰 ..."
        keytool -genkey -v -keystore "$KEYSTORE" \
            -alias "$KEYSTORE_ALIAS" \
            -keyalg RSA -keysize 2048 \
            -validity 10000 \
            -storepass 123456 \
            -keypass 123456 \
            -dname "CN=GPSMock, OU=Dev, O=GPSMock, L=Unknown, ST=Unknown, C=TW" 2>/dev/null
    fi
    
    echo "正在簽署 APK ..."
    jarsigner -verbose -sigalg SHA1withRSA \
        -digestalg SHA1 \
        -keystore "$KEYSTORE" \
        -storepass 123456 \
        -keypass 123456 \
        "$APK_UNSIGNED" "$KEYSTORE_ALIAS" 2>/dev/null
    
    # 優化 APK (zipalign)
    if command -v zipalign &> /dev/null; then
        zipalign -v 4 "$APK_UNSIGNED" "$APK_SIGNED" 2>/dev/null
        echo "✓ 簽署完成: $APK_SIGNED"
    else
        cp "$APK_UNSIGNED" "$APK_SIGNED"
        echo "✓ 簽署完成 (未優化): $APK_SIGNED"
    fi
    
    echo ""
    echo "========================================"
    echo " ✓ APK 建構完成！"
    echo "   檔案: $APP_DIR/$APK_SIGNED"
    echo "   大小: $(ls -lh "$APK_SIGNED" | awk '{print $5}')"
    echo "========================================"
    echo ""
    echo "安裝方式: adb install $APP_DIR/$APK_SIGNED"
else
    echo "錯誤: APK 編譯失敗，未產生 $APK_UNSIGNED"
    exit 1
fi
