#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Pikmin Bloom 自動掛機器人 (PikminBloomBot)
=============================================

完整自動化腳本，整合 GPS 步行模擬與螢幕互動，
實現 Pikmin Bloom 全自動掛機。

功能：
1. 自動步行（GPS 模擬）
2. 自動種花（定時點擊種花按鈕）
3. 自動收集果實與花苗（偵測對話框並點擊確認）
4. 自動處理每日登入獎勵
5. 連線中斷自動重連
6. 支援 USB 與 Wi-Fi ADB 模式

使用方式：
    python pikmin_bot.py --ip 192.168.1.100 --adb-wifi

架構：
    PikminBloomBot
    ├── ADBController    → 螢幕互動（點擊/截圖）
    ├── WalkSimulator    → GPS 步行模擬（透過手機伺服器）
    └── PhoneClient      → TCP 連線至手機 GPS 伺服器
"""

import argparse
import json
import logging
import os
import random
import subprocess
import sys
import time
import threading
from datetime import datetime
from typing import Optional, Tuple, List
from enum import Enum
from dataclasses import dataclass

# 匯入同專案模組
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from auto_bot.adb_controller import ADBController, ADBError, DeviceInfo, KeyCode
from gps_mock_client import (
    WalkSimulator, PhoneClient, GPSPoint, setup_logging,
    ConnectionTimeoutError, SocketError,
    DEFAULT_CONFIG, build_waypoint_route,
)


# ====================================================================
# 遊戲狀態列舉
# ====================================================================
class GameState(Enum):
    """Pikmin Bloom 遊戲狀態"""
    UNKNOWN = "unknown"                     # 未知狀態
    LOADING = "loading"                     # 載入中
    MAIN_MENU = "main_menu"                 # 主畫面（遊戲中）
    PLANTING = "planting"                   # 正在種花
    COLLECTING = "collecting"               # 收集果實
    EXPEDITION = "expedition"              # 遠征結果
    DAILY_LOGIN = "daily_login"            # 每日登入獎勵
    LEVEL_UP = "level_up"                  # 升級畫面
    ERROR_DIALOG = "error_dialog"          # 錯誤對話框
    DISCONNECTED = "disconnected"          # 遊戲斷線


# ====================================================================
# Pikmin Bloom Bot
# ====================================================================
class PikminBloomBot:
    """Pikmin Bloom 自動掛機機器人主類別。
    
    整合 GPS 步行模擬與螢幕互動，實現全自動掛機。
    """

    # Package Name
    PIKMIN_PACKAGE = "com.nianticlabs.pikmin"

    # 預設按鈕位置 (以 1080x2400 解析度為基準，使用百分比)
    # 不同解析度會自動換算
    BUTTON_POSITIONS = {
        # 種花按鈕：畫面正下方
        "plant_flower": (50.0, 92.0),
        # 種花啟動確認（第一次點擊後的確認）
        "plant_confirm": (50.0, 75.0),
        # 種花停止
        "plant_stop": (50.0, 92.0),
        # 收集/確認按鈕
        "collect_ok": (50.0, 80.0),
        # 關閉對話框 X
        "close_dialog": (90.0, 5.0),
        # 遠征結果確認
        "expedition_ok": (50.0, 70.0),
        # 升級確認
        "level_up_ok": (50.0, 75.0),
        # 每日登入領取
        "daily_claim": (50.0, 70.0),
        # 錯誤對話框確認
        "error_ok": (50.0, 70.0),
        # 主選單活動按鈕
        "event_banner": (50.0, 10.0),
        # 返回上一頁
        "back_button": (5.0, 5.0),
        # 種花模式切換（如果有的話）
        "plant_mode_switch": (15.0, 92.0),
    }

    def __init__(self, config: dict = None, logger: Optional[logging.Logger] = None):
        """初始化 Pikmin Bloom 機器人。
        
        Args:
            config: 設定字典
            logger: 日誌實例
        """
        self.config = config or DEFAULT_CONFIG.copy()
        self.logger = logger or setup_logging(verbose=False)
        
        # 子系統
        self.adb: Optional[ADBController] = None
        self.walk_simulator: Optional[WalkSimulator] = None
        self.phone_client: Optional[PhoneClient] = None
        
        # 機器人狀態
        self._running = False
        self._paused = False
        self._current_state = GameState.UNKNOWN
        self._planting_active = False
        self._last_plant_toggle = 0.0
        self._plant_interval = 300  # 每 5 分鐘檢查一次種花狀態
        self._last_collect_check = 0.0
        self._collect_interval = 180  # 每 3 分鐘檢查一次收集
        self._last_daily_check = 0.0
        self._daily_check_interval = 3600  # 每小時檢查每日獎勵
        self._state_check_interval = 60     # 每分鐘檢查一次遊戲狀態
        
        # 統計
        self._total_steps = 0
        self._total_taps = 0
        self._start_time: Optional[float] = None
        self._stats_lock = threading.Lock()
        
        # 背景執行緒
        self._bot_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()

    # ====================================================================
    # 初始連線
    # ====================================================================
    def connect_adb_usb(self) -> bool:
        """透過 USB 連線 ADB。"""
        self.adb = ADBController(
            adb_path=self.config.get("adb_path", "adb"),
            logger=self.logger,
        )
        if self.adb.connect_usb():
            info = self.adb.get_device_info()
            if info:
                self.config["phone_ip"] = info.serial
            return True
        return False

    def connect_adb_wifi(self, ip: str, port: int = 5555) -> bool:
        """透過 Wi-Fi 連線 ADB。
        
        注意：第一次使用前需透過 USB 執行：
            adb tcpip 5555
        """
        self.adb = ADBController(
            adb_path=self.config.get("adb_path", "adb"),
            logger=self.logger,
        )
        return self.adb.connect_wifi(ip, port)

    def connect_gps_server(self, ip: str, port: int = 8888) -> bool:
        """連線至手機端的 GPS 伺服器。"""
        self.phone_client = PhoneClient(self.config, self.logger)
        
        try:
            if self.phone_client.connect(ip, port):
                if self.phone_client.perform_handshake():
                    self.phone_client.start_heartbeat()
                    self.logger.info("✓ GPS 伺服器連線與交握成功")
                    return True
            return False
        except (ConnectionTimeoutError, ConnectionRefusedError, SocketError) as e:
            self.logger.error(f"GPS 伺服器連線失敗: {e}")
            return False

    # ====================================================================
    # 遊戲控制
    # ====================================================================
    def launch_game(self) -> bool:
        """啟動 Pikmin Bloom。"""
        if not self.adb:
            self.logger.error("ADB 未連線")
            return False
        
        self.logger.info("啟動 Pikmin Bloom ...")
        if self.adb.launch_app(self.PIKMIN_PACKAGE):
            self.logger.info("✓ Pikmin Bloom 已啟動")
            time.sleep(10)  # 等待遊戲載入
            return True
        return False

    def ensure_game_foreground(self) -> bool:
        """確保 Pikmin Bloom 在前景運行。"""
        if not self.adb:
            return False
        
        if not self.adb.is_app_running(self.PIKMIN_PACKAGE):
            self.logger.warning("Pikmin Bloom 未在運行，重新啟動")
            return self.launch_game()
        return True

    def toggle_flower_planting(self) -> bool:
        """切換種花模式開關。
        
        點擊種花按鈕。如果目前正在種花則停止，反之則開始。
        """
        if not self.adb:
            return False
        
        x_pct, y_pct = self.BUTTON_POSITIONS["plant_flower"]
        result = self.adb.tap_relative(x_pct, y_pct)
        
        with self._stats_lock:
            self._total_taps += 1
        
        if result.value == "success":
            self._planting_active = not self._planting_active
            self.logger.info(
                f"{'開始' if self._planting_active else '停止'}種花"
            )
            
            # 如果是啟動種花，可能需要第二次確認
            if self._planting_active:
                time.sleep(0.5)
                cx, cy = self.BUTTON_POSITIONS["plant_confirm"]
                self.adb.tap_relative(cx, cy)
            
            return True
        return False

    def tap_collect_ok(self) -> bool:
        """點擊收集/確認按鈕（用於收取果實、花苗等）。"""
        if not self.adb:
            return False
        
        x_pct, y_pct = self.BUTTON_POSITIONS["collect_ok"]
        result = self.adb.tap_relative(x_pct, y_pct)
        
        if result.value == "success":
            self._total_taps += 1
            self.logger.debug("點擊收集確認")
            return True
        return False

    def tap_close_dialog(self) -> bool:
        """關閉對話框（點擊右上角 X）。"""
        if not self.adb:
            return False
        
        x_pct, y_pct = self.BUTTON_POSITIONS["close_dialog"]
        result = self.adb.tap_relative(x_pct, y_pct)
        
        if result.value == "success":
            self._total_taps += 1
            return True
        return False

    def tap_safe_zone(self) -> bool:
        """點擊畫面安全區域（用於解除選單、關閉資訊卡等）。
        
        點擊畫面中央偏下的位置，通常可以關閉大多數彈出視窗。
        """
        if not self.adb:
            return False
        
        # 使用多個位置隨機選取，避免被偵測為機器行為
        zones = [
            (50.0, 60.0),   # 中央偏下
            (30.0, 65.0),   # 左中
            (70.0, 65.0),   # 右中
        ]
        x_pct, y_pct = random.choice(zones)
        result = self.adb.tap_relative(x_pct, y_pct)
        
        if result.value == "success":
            self._total_taps += 1
            return True
        return False

    # ====================================================================
    # 步行模擬
    # ====================================================================
    def start_walking(self, waypoints: Optional[List[GPSPoint]] = None):
        """開始步行模擬。
        
        Args:
            waypoints: 航點列表（可選）
        """
        self.walk_simulator = WalkSimulator(self.config, self.logger)
        
        if waypoints:
            self.walk_simulator.set_waypoints(waypoints)
        else:
            # 無航點時使用隨機漫步
            self.logger.info("未設定航點，使用隨機漫步模式")
        
        self.walk_simulator.start()
        self.logger.info("步行模擬已開始")

    def _send_gps_update(self):
        """計算並發送 GPS 更新至手機端。"""
        if not self.phone_client or not self.phone_client.is_connected():
            return False
        
        if not self.walk_simulator or not self.walk_simulator.is_running:
            return False
        
        point = self.walk_simulator.get_next_point()
        if point is None:
            return False
        
        if self.phone_client.send_gps(point):
            self._total_steps += 1
            return True
        return False

    # ====================================================================
    # Bot 主循環
    # ====================================================================
    def start(self):
        """啟動 Bot（背景執行緒）。"""
        if self._running:
            self.logger.warning("Bot 已在運行中")
            return
        
        self._running = True
        self._stop_event.clear()
        self._start_time = time.time()
        
        self._bot_thread = threading.Thread(
            target=self._bot_loop, daemon=True,
            name="PikminBot-Main",
        )
        self._bot_thread.start()
        self.logger.info("🤖 Pikmin Bloom Bot 已啟動")

    def stop(self):
        """停止 Bot。"""
        self.logger.info("正在停止 Bot ...")
        self._running = False
        self._stop_event.set()
        
        # 停止步行模擬
        if self.walk_simulator:
            self.walk_simulator.stop()
        
        # 斷開 GPS 連線
        if self.phone_client:
            self.phone_client.disconnect()
        
        # 斷開 ADB
        if self.adb:
            self.adb.disconnect()
        
        self._print_final_stats()
        self.logger.info("🤖 Bot 已停止")

    def _bot_loop(self):
        """Bot 主循環 — 運行在背景執行緒中。"""
        gps_last_update = 0
        state_last_check = 0
        plant_last_check = 0
        collect_last_check = 0
        
        while self._running and not self._stop_event.is_set():
            now = time.time()
            
            try:
                # --- GPS 更新（每秒） ---
                if now - gps_last_update >= 1.0:
                    if self.phone_client and self.phone_client.is_connected():
                        self._send_gps_update()
                    gps_last_update = now
                
                # --- 遊戲狀態檢查（每 60 秒） ---
                if now - state_last_check >= self._state_check_interval:
                    self._check_game_health()
                    state_last_check = now
                
                # --- 種花檢查（每 5 分鐘） ---
                if now - plant_last_check >= self._plant_interval:
                    self._do_plant_routine()
                    plant_last_check = now
                
                # --- 收集檢查（每 3 分鐘） ---
                if now - collect_last_check >= self._collect_interval:
                    self._do_collect_routine()
                    collect_last_check = now
                
                # --- 輸出狀態（每 30 秒） ---
                if int(now) % 30 == 0 and int(now) != int(gps_last_update):
                    self._print_status()
                
                time.sleep(0.1)
                
            except Exception as e:
                self.logger.exception(f"Bot 主循環錯誤: {e}")
                time.sleep(5)

    # ====================================================================
    # 任務例行程序
    # ====================================================================
    def _do_plant_routine(self):
        """種花例行程序。
        
        確保種花功能處於開啟狀態。
        如果已經在種花則跳過，否則嘗試開啟。
        """
        if not self.adb or not self.adb.is_connected():
            self.logger.warning("種花例行程序：ADB 未連線")
            return
        
        if not self.ensure_game_foreground():
            return
        
        self.logger.info("🌸 執行種花檢查 ...")
        
        # 每次點擊種花按鈕（無論狀態如何，確保種花中）
        # 實際行為取決於當前種花狀態
        self.toggle_flower_planting()
        
        # 等待 0.5 秒後再點一次確認
        time.sleep(random.uniform(0.3, 0.8))
        
        # 再次點擊收集確認，確保沒有彈出視窗擋住
        self.tap_safe_zone()
        
        self.logger.info(f"🌸 種花狀態: {'啟動' if self._planting_active else '已關閉'}")

    def _do_collect_routine(self):
        """收集例行程序。
        
        點擊收集按鈕以收取：
        - 果實／花蜜
        - 花苗
        - 遠征獎勵
        - 升級獎勵
        """
        if not self.adb or not self.adb.is_connected():
            return
        
        if not self.ensure_game_foreground():
            return
        
        self.logger.info("🎁 執行收集檢查 ...")
        
        # 連續點擊可能出現的確認按鈕（不同位置）
        for _ in range(3):
            self.tap_collect_ok()
            time.sleep(random.uniform(0.5, 1.0))
            self.tap_safe_zone()
            time.sleep(random.uniform(0.5, 1.0))
        
        # 關閉可能出現的對話框
        self.tap_close_dialog()
        time.sleep(0.3)

    def _check_game_health(self):
        """檢查遊戲健康狀態。
        
        確保遊戲仍在運行，必要時重新啟動。
        """
        if not self.adb:
            return
        
        try:
            if not self.adb.is_app_running(self.PIKMIN_PACKAGE):
                self.logger.warning("⚠ Pikmin Bloom 未在前景運行")
                self._current_state = GameState.DISCONNECTED
                
                # 嘗試重新啟動
                self.launch_game()
                time.sleep(15)
            else:
                if self._current_state == GameState.DISCONNECTED:
                    self._current_state = GameState.MAIN_MENU
                    self.logger.info("✓ 遊戲恢復運行")
        
        except Exception as e:
            self.logger.error(f"遊戲健康檢查錯誤: {e}")

    # ====================================================================
    # 狀態輸出
    # ====================================================================
    def _print_status(self):
        """輸出 Bot 狀態摘要。"""
        elapsed = time.time() - (self._start_time or time.time())
        
        pos = "?"
        if self.walk_simulator:
            p = self.walk_simulator.current_position
            pos = f"({p.lat:.5f}, {p.lon:.5f})"
        
        self.logger.info(
            f"🤖 [運行 {elapsed/60:.0f}分] "
            f"步數:{self._total_steps} "
            f"點擊:{self._total_taps} "
            f"種花:{'🌷' if self._planting_active else '✕'} "
            f"位置:{pos}"
        )

    def _print_final_stats(self):
        """輸出最終統計。"""
        elapsed = time.time() - (self._start_time or time.time())
        self.logger.info("=" * 50)
        self.logger.info("  Bot 最終統計")
        self.logger.info(f"  運行時間: {elapsed/60:.1f} 分鐘")
        self.logger.info(f"  總步數: {self._total_steps}")
        self.logger.info(f"  總點擊: {self._total_taps}")
        self.logger.info("=" * 50)

    def is_running(self) -> bool:
        return self._running


# ====================================================================
# 命令列介面
# ====================================================================
def create_bot_parser() -> argparse.ArgumentParser:
    """建立 Bot 命令列參數解析器。"""
    parser = argparse.ArgumentParser(
        description="Pikmin Bloom 自動掛機機器人",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用範例：

  # 完整模式：Wi-Fi ADB + GPS 伺服器
  python pikmin_bot.py --ip 192.168.1.100 --adb-wifi

  # USB 模式（手機用 USB 連接電腦）
  python pikmin_bot.py --ip 192.168.1.100 --adb-usb

  # 自訂路線
  python pikmin_bot.py --ip 192.168.1.100 --adb-wifi \\
    --random-route 20 --route-radius 300

  # 調整種花間隔
  python pikmin_bot.py --ip 192.168.1.100 \\
    --plant-interval 180

  # 詳細除錯
  python pikmin_bot.py --ip 192.168.1.100 --adb-wifi --verbose
        """,
    )
    
    # 連線設定
    conn = parser.add_argument_group("連線設定")
    conn.add_argument("--ip", required=True, help="手機 IP 位址")
    conn.add_argument("--port", type=int, default=8888, help="GPS 伺服器埠號")
    conn.add_argument("--adb-wifi", action="store_true", help="ADB Wi-Fi 模式")
    conn.add_argument("--adb-usb", action="store_true", help="ADB USB 模式")
    conn.add_argument("--adb-port", type=int, default=5555, help="ADB 埠號 (Wi-Fi)")
    
    # GPS 設定
    gps = parser.add_argument_group("GPS 設定")
    gps.add_argument("--lat", type=float, default=25.0478, help="起始緯度")
    gps.add_argument("--lon", type=float, default=121.5170, help="起始經度")
    gps.add_argument("--speed-min", type=float, default=1.1, help="最低步速")
    gps.add_argument("--speed-max", type=float, default=1.5, help="最高步速")
    gps.add_argument("--random-route", type=int, help="自動生成路線（航點數）")
    gps.add_argument("--route-radius", type=int, default=200, help="路線半徑")
    
    # Bot 設定
    bot = parser.add_argument_group("Bot 設定")
    bot.add_argument("--plant-interval", type=int, default=300,
                     help="種花檢查間隔 (秒, 預設: 300)")
    bot.add_argument("--collect-interval", type=int, default=180,
                     help="收集檢查間隔 (秒, 預設: 180)")
    bot.add_argument("--no-launch", action="store_true",
                     help="不自動啟動遊戲")
    
    # 其他
    parser.add_argument("--verbose", action="store_true", help="詳細除錯模式")
    
    return parser


def main():
    """Bot 主程式進入點。"""
    parser = create_bot_parser()
    args = parser.parse_args()
    
    # 建立設定
    config = DEFAULT_CONFIG.copy()
    config.update({
        "phone_ip": args.ip,
        "phone_port": args.port,
        "start_lat": args.lat,
        "start_lon": args.lon,
        "walk_speed_min": args.speed_min,
        "walk_speed_max": args.speed_max,
    })
    
    # 建立 Bot
    bot = PikminBloomBot(config)
    
    if args.verbose:
        bot.logger.setLevel(logging.DEBUG)
    
    bot.logger.info("=" * 55)
    bot.logger.info("  🌸 Pikmin Bloom 自動掛機機器人 v1.0")
    bot.logger.info(f"  手機 IP: {args.ip}")
    bot.logger.info(f"  ADB 模式: {'Wi-Fi' if args.adb_wifi else 'USB' if args.adb_usb else '無'}")
    bot.logger.info("=" * 55)
    
    # --- 步驟 1: ADB 連線 ---
    if args.adb_wifi:
        bot.logger.info("\n[1/4] 連線 ADB (Wi-Fi)...")
        if not bot.connect_adb_wifi(args.ip, args.adb_port):
            bot.logger.error("ADB Wi-Fi 連線失敗")
            bot.logger.error("請先用 USB 連線執行: adb tcpip 5555")
            sys.exit(1)
    elif args.adb_usb:
        bot.logger.info("\n[1/4] 連線 ADB (USB)...")
        if not bot.connect_adb_usb():
            bot.logger.error("ADB USB 連線失敗，請確認 USB 已連接")
            sys.exit(1)
    else:
        bot.logger.info("\n[1/4] 跳過 ADB 連線（僅 GPS 模式）")
    
    # 顯示裝置資訊
    if bot.adb:
        info = bot.adb.get_device_info()
        if info:
            bot.logger.info(f"  裝置: {info.model} ({info.screen_width}x{info.screen_height})")
    
    # --- 步驟 2: GPS 伺服器連線 ---
    bot.logger.info("\n[2/4] 連線 GPS 伺服器...")
    if not bot.connect_gps_server(args.ip, args.port):
        bot.logger.error(
            "GPS 伺服器連線失敗。\n"
            "請確認：\n"
            "  1. 手機端 App 已啟動並顯示「運行中」\n"
            "  2. IP 位址正確\n"
            "  3. 手機與電腦在同一 Wi-Fi 網路"
        )
        sys.exit(1)
    
    # --- 步驟 3: 啟動遊戲 ---
    if not args.no_launch and bot.adb:
        bot.logger.info("\n[3/4] 啟動 Pikmin Bloom ...")
        if not bot.launch_game():
            bot.logger.warning("啟動遊戲失敗，將嘗試繼續")
    
    # --- 步驟 4: 開始模擬 ---
    bot.logger.info("\n[4/4] 設定步行路線...")
    
    waypoints = None
    if args.random_route:
        waypoints = build_waypoint_route(
            config["start_lat"], config["start_lon"],
            num_points=args.random_route,
            radius_m=args.route_radius,
        )
        bot.logger.info(f"  自動生成 {len(waypoints)} 個航點的路線")
    
    bot.start_walking(waypoints)
    bot.start()
    
    # 主執行緒保持存活
    try:
        while bot.is_running():
            time.sleep(1)
    except KeyboardInterrupt:
        bot.logger.info("使用者中斷 (Ctrl+C)")
    finally:
        bot.stop()


if __name__ == "__main__":
    main()
