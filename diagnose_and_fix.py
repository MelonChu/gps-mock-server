#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
╔══════════════════════════════════════════════════════════════════════════╗
║             Pikmin Bloom GPS 連線雙端自動修復與診斷工具                  ║
║             双端同步檢查：客戶端 (PC) ──→ 伺服器端 (Android Phone)     ║
╚══════════════════════════════════════════════════════════════════════════╝
"""

import os
import sys
import time
import socket
import subprocess
import logging

def setup_logger():
    log_format = "[%(asctime)s] %(levelname)-8s - %(message)s"
    logging.basicConfig(level=logging.INFO, format=log_format, handlers=[
        logging.StreamHandler(sys.stdout)
    ])
    return logging.getLogger("FixTool")

logger = setup_logger()

class ConnectionRepairman:
    def __init__(self, phone_ip="192.168.68.62", gps_port=8888, adb_port=5555):
        self.phone_ip = phone_ip
        self.gps_port = gps_port
        self.adb_port = adb_port

    def run_cmd(self, cmd_list):
        """執行系統指令並捕捉輸出"""
        try:
            res = subprocess.run(cmd_list, capture_output=True, text=True, timeout=5)
            return res.returncode, res.stdout.strip(), res.stderr.strip()
        except Exception as e:
            return -1, "", str(e)

    def check_and_fix_adb(self):
        """核心修復邏輯 [1/3]：確保客戶端與伺服器端的 ADB 服務正確實現"""
        logger.info("=" * 60)
        logger.info("[步驟 1] 診斷並修復 ADB 基礎連線狀態...")
        logger.info("=" * 60)

        # 1. 檢查本機是否有 adb 服務
        rc, stdout, stderr = self.run_cmd(["adb", "version"])
        if rc != 0:
            logger.error("❌ 電腦端未安裝 ADB 或未加入環境變數(PATH)，請確認 adb 套件存在。")
            return False
        logger.info("  ✓ 電腦本機 ADB 執行環境正常。")

        # 2. 探查目前連線裝置
        rc, stdout, stderr = self.run_cmd(["adb", "devices"])
        logger.info(f"  目前 `adb devices` 狀態:\n{stdout}")

        # 解析是否有實體裝置
        lines = stdout.split("\n")[1:]
        has_usb = False
        has_wifi = False
        
        for line in lines:
            if not line.strip(): continue
            parts = line.split("\t")
            if len(parts) == 2 and parts[1] == "device":
                if ":" in parts[0]:
                    has_wifi = True
                else:
                    has_usb = True

        # 3. 根據狀態提供自動修復決策
        if not has_usb and not has_wifi:
            logger.warning("⚠️ 目前沒有任何偵測到的 Android 裝置。")
            logger.info("  👉 請立刻執行以下操作：")
            logger.info("     1. 用 USB 傳輸線將手機連接至電腦。")
            logger.info("     2. 滑開手機通知列，確認 USB 模式為『傳輸檔案/MTP』而非僅充電。")
            logger.info("     3. 進入手機 開發者選項 -> 開啟『USB 偵錯』。")
            logger.info("     4. 畫面若彈出『允許 USB 偵錯嗎？』，請務必勾選『永遠允許』並按確定。")
            
            logger.info("⏳ 正在等待您插入 USB 傳輸線並授權偵錯（每3秒重新偵測，上限30秒）...")
            for _ in range(10):
                time.sleep(3)
                _, check_out, _ = self.run_cmd(["adb", "devices"])
                if "device" in check_out.split("\n")[1:]:
                    logger.info("  ✓ 偵測到手機已成功接入！")
                    return self._activate_wifi_adb()
            return False
        
        elif has_usb and not has_wifi:
            logger.info("  -> 發現手機已透過 USB 連接，但尚未實現 Wi-Fi 高階偵錯連線。")
            return self._activate_wifi_adb()
        
        else:
            logger.info("  ✓ 手機與電腦的 ADB 雙端實現已就緒。")
            return True

    def _activate_wifi_adb(self):
        """底層無線網路打通程序"""
        logger.info(f"  -> 正在初始化手機伺服器端的協定：設定 TCP 埠號 {self.adb_port}...")
        rc, stdout, stderr = self.run_cmd(["adb", "tcpip", str(self.adb_port)])
        time.sleep(2)
        
        if "restarting" in stdout.lower() or rc == 0:
            logger.info(f"  ✓ 手機端 ADB 守護進程已成功監聽 Port {self.adb_port}。")
            logger.info(f"  -> 嘗試無線連線至伺服器端 IP: {self.phone_ip}...")
            rc, stdout, stderr = self.run_cmd(["adb", "connect", f"{self.phone_ip}:{self.adb_port}"])
            logger.info(f"  連線回應: {stdout}")
            
            if "connected" in stdout.lower():
                logger.info("  🎉 Wi-Fi ADB 無線橋樑建置成功！現在您可以拔掉 USB 線了。")
                return True
        logger.error(f"  ❌ 無法自動轉換至無線模式。錯誤: {stderr or stdout}")
        return False

    def test_phone_gps_server(self):
        """核心修復邏輯 [2/3]：診斷手機上 8888 埠號被主動拒絕連線的問題"""
        logger.info("\n" + "=" * 60)
        logger.info(f"[步驟 2] 雙端 Socket 探針測試：目標 {self.phone_ip}:{self.gps_port}")
        logger.info("=" * 60)
        
        logger.info(f"  正在對手機端模擬發送 TCP 握手要求...")
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(3)
        
        try:
            result = sock.connect_ex((self.phone_ip, self.gps_port))
            if result == 0:
                logger.info(f"  🎉 超讚！手機端的 GPS 模擬伺服器（Port {self.gps_port}）運作正常，且允許連線！")
                sock.close()
                return True
            else:
                # 抓取常見 Windows Socket 錯誤代碼 (10061 = Connection Refused)
                logger.error(f"  ❌ 連線測試失敗，代碼: {result}")
                logger.error("  👉 【核心病因診斷】手機端的主機主動拒絕了 8888 埠號的存取！")
                logger.error("     請立刻在手機上檢查並落實以下三點：")
                logger.error("     1. 手機上的『Mock Server App / GPS 模擬伺服器』App 是否『真的』點開了啟動開關？")
                logger.error("     2. 手機畫面此時有沒有顯示『伺服器運行中』或監聽的 Port 號？")
                logger.error("     3. ⚠️ 重要：請檢查手機的 Wi-Fi 狀態，確認它和這台電腦連接在『完全相同』的 Wi-Fi 分享器名稱（SSID）下，且沒有開啟訪客網路（Guest Network，訪客網路會阻擋內部設備互連隔離）。")
                return False
        except Exception as e:
            logger.error(f"  ❌ 探針執行異常: {e}")
            return False

    def check_app_status_via_adb(self):
        """核心修復邏輯 [3/3]：利用 ADB 進階反查手機端 app 是否正確實現"""
        logger.info("\n" + "=" * 60)
        logger.info("[步驟 3] 透過無線/有線控制通路，反查手機端遊戲與伺服器狀態...")
        logger.info("=" * 60)
        
        # 檢查 Pikmin Bloom 有沒有裝在手機上
        rc, stdout, stderr = self.run_cmd(["adb", "shell", "pm", "list", "packages", "com.nianticlabs.pikmin"])
        if "com.nianticlabs.pikmin" in stdout:
            logger.info("  ✓ 確認手機端已正確實現安裝：Pikmin Bloom 遊戲。")
        else:
            logger.warning("  ⚠ 未在預設位置找到 Pikmin Bloom 套件，如果您的遊戲是從特定市場下載，只要確保能手動開起即可。")

        # 檢測當前前景正在運行的畫面
        rc, stdout, stderr = self.run_cmd(["adb", "shell", "dumpsys", "window", "displays"])
        logger.info("  ✓ 已向手機發送前景健康度查詢。")
        logger.info("\n💡 【診斷完畢】請優先完成上面步驟 1 與步驟 2 中指引的手機端人工確認，然後再次執行本診斷腳本。")

if __name__ == "__main__":
    # 如果您的手機實際 IP 不是這個，請在下方自行修改
    repairman = ConnectionRepairman(phone_ip="192.168.68.62", gps_port=8888)
    
    adb_ok = repairman.check_and_fix_adb()
    server_ok = repairman.test_phone_gps_server()
    
    if adb_ok and server_ok:
        logger.info("\n🚀 【全線打通！】雙端通訊環境已完美修復實現。")
        logger.info("現在您可以放心運行主程式了：")
        logger.info("Command: python gps_mock_client.py --ip 192.168.68.62")
    else:
        logger.warning("\n📌 雙端實現仍有部分阻礙，請參閱上方提示排除後再試一次。")