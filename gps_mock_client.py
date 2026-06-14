#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
╔══════════════════════════════════════════════════════════════════════════╗
║              Pikmin Bloom GPS 模擬器 — 桌面客戶端                        ║
║              GPS Mock Walker — Desktop Client                           ║
╠══════════════════════════════════════════════════════════════════════════╣
║  架構：客戶端 (桌機) ── TCP ──→ 伺服器端 (手機 APK)                     ║
║  功能：模擬人類步行 (1.1~1.5 m/s)，隨機飄移方位角，雙向心跳檢查          ║
║  版本：1.0.0                                                            ║
╚══════════════════════════════════════════════════════════════════════════╝
"""

import argparse
import json
import logging
import math
import os
import random
import signal
import socket
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from typing import Optional, List, Tuple, Callable

# ============================================================================
# 設定區塊 — 可依需求修改預設值
# ============================================================================
DEFAULT_CONFIG = {
    # --- 手機伺服器連線 ---
    "phone_ip": "192.168.1.100",         # 手機端 IP（請修改為實際 IP）
    "phone_port": 8888,                  # 手機端埠號
    "connection_timeout": 10,            # 連線超時 (秒)
    "heartbeat_interval": 5,             # 心跳間隔 (秒)
    "heartbeat_timeout": 15,             # 心跳超時 (秒，超過此時間未收到回應視為斷線)

    # --- 步行模擬參數 ---
    "walk_speed_min": 1.1,               # 最低步行速度 (m/s)
    "walk_speed_max": 1.5,               # 最高步行速度 (m/s)
    "update_interval": 1.0,              # GPS 更新間隔 (秒)
    "bearing_drift_max_deg": 8.0,        # 最大方位角飄移 (度)，避免直線移動
    "bearing_drift_interval_steps": 5,    # 每 N 步改變一次飄移方向

    # --- ADB 路徑 ---
    "adb_path": "adb",                   # adb 執行檔路徑（若不在 PATH 請寫完整路徑）

    # --- 起始 GPS 座標（範例：台北車站）---
    "start_lat": 25.0478,
    "start_lon": 121.5170,

    # --- iOS 支援 (實驗性) ---
    "enable_ios": False,                 # 是否啟用 iOS 偵測 (需安裝 pymobiledevice3)
}

# ============================================================================
# 地球幾何常數
# ============================================================================
EARTH_RADIUS_M = 6_371_000              # 地球平均半徑 (公尺)
METERS_PER_DEG_LAT = 111_320.0          # 1 度緯度 ≈ 111.32 公里


def meters_per_deg_lon(lat: float) -> float:
    """計算指定緯度下，1 度經度對應的實際公尺數。
    
    因為地球是球體，經線在極點匯聚，所以經度每度的實際距離隨緯度縮減。
    
    Args:
        lat: 緯度 (度)
    
    Returns:
        該緯度下 1 度經度對應的公尺數
    """
    return METERS_PER_DEG_LAT * math.cos(math.radians(lat))


def distance_between(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """使用 Haversine 公式計算兩 GPS 點之間的大圓距離 (公尺)。
    
    Args:
        lat1, lon1: 起點緯度/經度 (度)
        lat2, lon2: 終點緯度/經度 (度)
    
    Returns:
        兩點間距離 (公尺)
    """
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = (math.sin(dlat / 2) ** 2 +
         math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) *
         math.sin(dlon / 2) ** 2)
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return EARTH_RADIUS_M * c


def bearing_between(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """計算從點 A 到點 B 的初始方位角 (bearing)，單位為度 (0~360)。
    
    Args:
        lat1, lon1: 起點
        lat2, lon2: 終點
    
    Returns:
        方位角 (度)，0 = 正北，順時針增加
    """
    dlon = math.radians(lon2 - lon1)
    y = math.sin(dlon) * math.cos(math.radians(lat2))
    x = (math.cos(math.radians(lat1)) * math.sin(math.radians(lat2)) -
         math.sin(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.cos(dlon))
    bearing = math.degrees(math.atan2(y, x))
    return (bearing + 360) % 360


def offset_position(lat: float, lon: float, distance_m: float, bearing_deg: float
                    ) -> Tuple[float, float]:
    """從起點出發，沿指定方位角移動指定距離，回傳新座標。
    
    使用球面幾何計算，適合短距離移動（數百公尺內精度良好）。
    
    Args:
        lat, lon: 起點座標 (度)
        distance_m: 移動距離 (公尺)
        bearing_deg: 方位角 (度)
    
    Returns:
        (新緯度, 新經度) (度)
    """
    d = distance_m / EARTH_RADIUS_M          # 角距離 (弧度)
    brg = math.radians(bearing_deg)

    lat1 = math.radians(lat)
    lon1 = math.radians(lon)

    lat2 = math.asin(math.sin(lat1) * math.cos(d) +
                     math.cos(lat1) * math.sin(d) * math.cos(brg))
    lon2 = lon1 + math.atan2(math.sin(brg) * math.sin(d) * math.cos(lat1),
                              math.cos(d) - math.sin(lat1) * math.sin(lat2))

    return (math.degrees(lat2), math.degrees(lon2))


# ============================================================================
# 日誌設定
# ============================================================================
def setup_logging(verbose: bool = False) -> logging.Logger:
    """初始化日誌系統，輸出到終端機及檔案。
    
    Args:
        verbose: 是否輸出 DEBUG 層級
    
    Returns:
        設定好的 Logger 實例
    """
    log_format = (
        "[%(asctime)s] %(levelname)-8s %(name)s - %(message)s"
    )
    level = logging.DEBUG if verbose else logging.INFO

    # 終端 Handler
    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setFormatter(logging.Formatter(log_format))

    # 檔案 Handler — 寫入 logs/ 目錄
    log_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs")
    os.makedirs(log_dir, exist_ok=True)
    log_file = os.path.join(
        log_dir,
        f"gps_mock_{datetime.now().strftime('%Y%m%d_%H%M%S')}.log"
    )
    file_handler = logging.FileHandler(log_file, encoding="utf-8")
    file_handler.setFormatter(logging.Formatter(log_format))

    logger = logging.getLogger("GPSMock")
    logger.setLevel(level)
    logger.addHandler(console_handler)
    logger.addHandler(file_handler)

    return logger


# ============================================================================
# GPS 資料結構
# ============================================================================
@dataclass
class GPSPoint:
    """單一 GPS 座標點"""
    lat: float          # 緯度
    lon: float          # 經度
    altitude: float = 0.0      # 海拔 (公尺)
    accuracy: float = 5.0      # 精度 (公尺)
    speed: float = 0.0         # 速度 (m/s)
    bearing: float = 0.0       # 方位角 (度)
    timestamp: float = 0.0     # Unix 時間戳

    def __post_init__(self):
        if self.timestamp == 0.0:
            self.timestamp = time.time()

    def to_dict(self) -> dict:
        """轉為 JSON 可序列化的字典"""
        return {
            "lat": self.lat,
            "lon": self.lon,
            "altitude": self.altitude,
            "accuracy": self.accuracy,
            "speed": round(self.speed, 2),
            "bearing": round(self.bearing, 2),
            "timestamp": self.timestamp,
        }


# ============================================================================
# 連線狀態列舉
# ============================================================================
class ConnectionStatus(Enum):
    """連線狀態"""
    DISCONNECTED = "disconnected"        # 未連線
    CONNECTING = "connecting"            # 連線中
    CONNECTED = "connected"              # 已連線
    HANDSHAKE_OK = "handshake_ok"        # 雙向驗證通過
    TIMEOUT = "timeout"                  # 連線超時
    ERROR = "error"                      # 連線錯誤
    DEVICE_NOT_FOUND = "device_not_found"  # 找不到裝置


# ============================================================================
# 步行模擬器
# ============================================================================
class WalkSimulator:
    """模擬人類步行移動的 GPS 座標產生器。
    
    主要行為：
    - 以 1.1~1.5 m/s 的速度行走
    - 每 N 步隨機調整方位角，產生自然飄移
    - 可設定向固定點移動（如打卡點），或隨機漫步
    """
    
    def __init__(self, config: dict, logger: logging.Logger):
        """初始化步行模擬器。
        
        Args:
            config: 設定字典
            logger: 日誌實例
        """
        self.config = config
        self.logger = logger
        
        # 目前狀態
        self.current_lat = config["start_lat"]
        self.current_lon = config["start_lon"]
        self.current_bearing = random.uniform(0, 360)  # 隨機初始方向
        self._step_count = 0
        self._drift_counter = 0
        self._current_drift = 0.0
        self._running = False
        self._lock = threading.Lock()
        
        # 航點 (waypoints) 列表 — 若設定則依序前往
        self.waypoints: List[GPSPoint] = []
        self._current_wp_index = 0
        self._random_walk = True          # 若無航點則隨機漫步
    
    def set_waypoints(self, points: List[GPSPoint]):
        """設定固定路線航點。設定後模擬器會依序前往每個航點。
        
        Args:
            points: GPS 航點列表
        """
        with self._lock:
            self.waypoints = points
            self._current_wp_index = 0
            self._random_walk = len(points) == 0
            self.logger.info(f"已設定 {len(points)} 個航點")
    
    def set_start_position(self, lat: float, lon: float):
        """手動設定起始位置（覆蓋 config 中的預設值）。
        
        Args:
            lat: 起始緯度
            lon: 起始經度
        """
        with self._lock:
            self.current_lat = lat
            self.current_lon = lon
            self.logger.info(f"起始位置設為: ({lat:.6f}, {lon:.6f})")
    
    def _compute_next_bearing(self) -> float:
        """計算下一次移動的方位角。
        
        實現隨機飄移行為：
        1. 每 `bearing_drift_interval_steps` 步重新計算一次飄移量
        2. 飄移量在 ±bearing_drift_max_deg 度範圍內隨機
        3. 若航點模式啟用，則朝航點方向移動為主方向
        
        Returns:
            新的方位角 (度)
        """
        # 計算基礎方位角
        if not self._random_walk and self.waypoints:
            # 航點模式：朝下一個航點移動
            wp = self.waypoints[self._current_wp_index]
            base_bearing = bearing_between(
                self.current_lat, self.current_lon, wp.lat, wp.lon
            )
            # 如果快到了，切換到下一個航點
            dist = distance_between(
                self.current_lat, self.current_lon, wp.lat, wp.lon
            )
            if dist < 3.0:  # 3 公尺內視為到達
                self._current_wp_index += 1
                if self._current_wp_index >= len(self.waypoints):
                    self.logger.info("所有航點已到達，切換為隨機漫步模式")
                    self._random_walk = True
                    base_bearing = self.current_bearing
                else:
                    self.logger.info(
                        f"抵達航點 {self._current_wp_index - 1}，"
                        f"前往航點 {self._current_wp_index}"
                    )
                    wp = self.waypoints[self._current_wp_index]
                    base_bearing = bearing_between(
                        self.current_lat, self.current_lon, wp.lat, wp.lon
                    )
        else:
            # 隨機漫步模式：維持大致方向，但有較大隨機性
            base_bearing = self.current_bearing
            # 每 10~20 步有 30% 機率大幅改變方向
            if self._drift_counter > random.randint(10, 20):
                if random.random() < 0.3:
                    base_bearing = (base_bearing + random.uniform(-60, 60)) % 360
                    self.logger.debug(f"大幅改變方向至 {base_bearing:.1f}°")
                    self._drift_counter = 0
        
        # 加入隨機飄移
        if self._drift_counter % self.config["bearing_drift_interval_steps"] == 0:
            self._current_drift = random.uniform(
                -self.config["bearing_drift_max_deg"],
                self.config["bearing_drift_max_deg"]
            )
        
        self._drift_counter += 1
        return (base_bearing + self._current_drift) % 360
    
    def get_next_point(self) -> Optional[GPSPoint]:
        """計算下一個 GPS 位置。
        
        根據設定的步速範圍隨機選取速度，結合方位角計算新座標。
        此方法執行非阻塞計算，應由主循環定時呼叫。
        
        Returns:
            新的 GPSPoint，若已停止則回傳 None
        """
        if not self._running:
            return None
        
        with self._lock:
            # 隨機選取步速 (1.1 ~ 1.5 m/s)
            speed = random.uniform(
                self.config["walk_speed_min"],
                self.config["walk_speed_max"]
            )
            
            # 計算新的方位角
            bearing = self._compute_next_bearing()
            
            # 距離 = 速度 × 更新間隔
            distance = speed * self.config["update_interval"]
            
            # 計算新座標
            new_lat, new_lon = offset_position(
                self.current_lat, self.current_lon, distance, bearing
            )
            
            # 更新內部狀態
            self.current_lat = new_lat
            self.current_lon = new_lon
            self.current_bearing = bearing
            self._step_count += 1
            
            # 構建 GPS 點
            point = GPSPoint(
                lat=new_lat,
                lon=new_lon,
                speed=speed,
                bearing=bearing,
                timestamp=time.time(),
            )
            
            if self._step_count % 50 == 0:
                self.logger.debug(
                    f"第 {self._step_count} 步: ({new_lat:.6f}, {new_lon:.6f}) "
                    f"速度={speed:.2f} m/s 方向={bearing:.1f}°"
                )
            
            return point
    
    def start(self):
        """啟動模擬器"""
        self._running = True
        self._step_count = 0
        self.logger.info("步行模擬器已啟動")
    
    def stop(self):
        """停止模擬器"""
        self._running = False
        self.logger.info(f"步行模擬器已停止 (共走了 {self._step_count} 步)")
    
    @property
    def is_running(self) -> bool:
        return self._running
    
    @property
    def total_steps(self) -> int:
        return self._step_count
    
    @property
    def current_position(self) -> GPSPoint:
        with self._lock:
            return GPSPoint(
                lat=self.current_lat,
                lon=self.current_lon,
                bearing=self.current_bearing,
            )


# ============================================================================
# ADB 裝置檢查器
# ============================================================================
class ADBChecker:
    """透過 ADB (Android Debug Bridge) 偵測及檢查 Android 裝置連線。
    
    支援功能：
    - 檢查是否有 Android 裝置連線
    - 獲取裝置 IP 位址
    - 發送測試指令確認裝置正常回應
    """
    
    def __init__(self, adb_path: str = "adb", logger: Optional[logging.Logger] = None):
        """初始化 ADB 檢查器。
        
        Args:
            adb_path: adb 執行檔路徑（若在 PATH 中可用 "adb"）
            logger: 日誌實例
        """
        self.adb_path = adb_path
        self.logger = logger or logging.getLogger("ADBChecker")
    
    def _run_adb(self, args: List[str], timeout: int = 10) -> Tuple[bool, str]:
        """執行 ADB 指令並回傳結果。
        
        Args:
            args: ADB 參數列表 (不包含 "adb" 本身)
            timeout: 超時秒數
        
        Returns:
            (成功與否, stdout + stderr 合併輸出)
        
        Raises:
            ADBError: ADB 執行失敗時的包裝例外
        """
        cmd = [self.adb_path] + args
        try:
            self.logger.debug(f"執行 ADB: {' '.join(cmd)}")
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=timeout,
            )
            output = (result.stdout + result.stderr).strip()
            
            if result.returncode != 0:
                return False, output
            
            return True, output
            
        except FileNotFoundError:
            msg = f"找不到 ADB 執行檔: {self.adb_path}"
            self.logger.error(msg)
            raise ADBError(msg)
        except subprocess.TimeoutExpired:
            msg = f"ADB 指令超時 (>{timeout}秒): {' '.join(cmd)}"
            self.logger.error(msg)
            raise ADBTimeoutError(msg)
        except PermissionError:
            msg = f"ADB 執行檔權限不足: {self.adb_path}"
            self.logger.error(msg)
            raise ADBError(msg)
    
    def detect_device(self) -> Tuple[bool, str]:
        """檢查是否有 Android 裝置通過 ADB 連線。
        
        執行 `adb devices` 並解析結果。
        
        Returns:
            (是否有已授權裝置, 詳細訊息)
        """
        success, output = self._run_adb(["devices"])
        
        if not success:
            return False, f"ADB devices 指令失敗: {output}"
        
        lines = output.strip().split("\n")
        devices = []
        unauthorized = []
        
        for line in lines[1:]:  # 跳過第一行 "List of devices attached"
            line = line.strip()
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) == 2:
                device_id, status = parts
                if status == "device":
                    devices.append(device_id)
                elif status == "unauthorized":
                    unauthorized.append(device_id)
        
        if devices:
            msg = f"找到 {len(devices)} 個已授權裝置: {', '.join(devices)}"
            self.logger.info(msg)
            return True, msg
        elif unauthorized:
            msg = (
                f"找到 {len(unauthorized)} 個未授權裝置: "
                f"{', '.join(unauthorized)}。請在手機上允許 USB 偵錯授權。"
            )
            self.logger.warning(msg)
            return False, msg
        else:
            msg = "未找到任何 Android 裝置。請確認 USB 已連接且 USB 偵錯已開啟。"
            self.logger.warning(msg)
            return False, msg
    
    def get_device_ip(self, serial: Optional[str] = None) -> Optional[str]:
        """透過 ADB 獲取裝置的 IP 位址（通常為 Wi-Fi IP）。
        
        Args:
            serial: 裝置序號，若為 None 則使用第一個已連線裝置
        
        Returns:
            IP 位址字串，若失敗則回傳 None
        """
        try:
            if serial:
                success, output = self._run_adb(["-s", serial, "shell", "ip", "route"])
            else:
                success, output = self._run_adb(["shell", "ip", "route"])
            
            if not success:
                self.logger.warning(f"獲取 IP 失敗: {output}")
                return None
            
            # 解析 "ip route" 輸出，尋找預設閘道或 wlan0 的 IP
            for line in output.split("\n"):
                if "wlan0" in line or "eth0" in line:
                    parts = line.split()
                    for i, part in enumerate(parts):
                        if part == "src" and i + 1 < len(parts):
                            ip = parts[i + 1]
                            self.logger.info(f"裝置 IP: {ip}")
                            return ip
            
            # 備用方法：透過 ifconfig 或 property
            success, output = self._run_adb(
                ["shell", "getprop", "dhcp.wlan0.ipaddress"]
                if not serial else
                ["-s", serial, "shell", "getprop", "dhcp.wlan0.ipaddress"]
            )
            if success and output.strip():
                ip = output.strip()
                self.logger.info(f"裝置 IP (dhcp): {ip}")
                return ip
            
            return None
            
        except (ADBError, ADBTimeoutError) as e:
            self.logger.error(f"獲取裝置 IP 時發生錯誤: {e}")
            return None
    
    def test_device_connectivity(self, serial: Optional[str] = None) -> bool:
        """測試裝置是否可以正常回應 ADB 指令。
        
        執行一個簡單指令確認裝置運作正常。
        
        Args:
            serial: 裝置序號
        
        Returns:
            True 如果裝置正常回應
        """
        try:
            cmd = ["shell", "echo", "ADB_OK"]
            if serial:
                cmd = ["-s", serial] + cmd
            
            success, output = self._run_adb(cmd)
            if success and "ADB_OK" in output:
                self.logger.info("ADB 裝置連線測試通過")
                return True
            else:
                self.logger.warning(f"ADB 裝置回應異常: {output}")
                return False
        except (ADBError, ADBTimeoutError) as e:
            self.logger.error(f"ADB 連線測試失敗: {e}")
            return False
    
    def get_device_info(self, serial: Optional[str] = None) -> dict:
        """取得裝置基本資訊。
        
        Args:
            serial: 裝置序號
        
        Returns:
            包含裝置資訊的字典
        """
        info = {}
        props = [
            ("model", "ro.product.model"),
            ("android_version", "ro.build.version.release"),
            ("sdk_level", "ro.build.version.sdk"),
            ("manufacturer", "ro.product.manufacturer"),
        ]
        
        for key, prop in props:
            try:
                cmd = ["shell", "getprop", prop]
                if serial:
                    cmd = ["-s", serial] + cmd
                _, val = self._run_adb(cmd)
                info[key] = val.strip()
            except Exception:
                info[key] = "unknown"
        
        self.logger.info(f"裝置資訊: {info.get('model', 'unknown')} "
                         f"Android {info.get('android_version', '?')}")
        return info
    
    def wait_for_device(self, timeout: int = 30) -> bool:
        """等待裝置連線（最多等待 timeout 秒）。
        
        Args:
            timeout: 最大等待時間 (秒)
        
        Returns:
            True 如果在 timeout 內找到裝置
        """
        self.logger.info(f"等待裝置連線 (最長 {timeout} 秒)...")
        start = time.time()
        while time.time() - start < timeout:
            found, msg = self.detect_device()
            if found:
                return True
            time.sleep(2)
        self.logger.error(f"等待裝置超時 (>{timeout}秒)")
        return False


class ADBError(Exception):
    """ADB 相關錯誤"""
    pass


class ADBTimeoutError(ADBError):
    """ADB 指令超時"""
    pass


# ============================================================================
# iOS 裝置檢查器 (實驗性)
# ============================================================================
class iOSChecker:
    """透過 pymobiledevice3 偵測 iOS 裝置連線（實驗性功能）。
    
    注意：此功能需要安裝 pymobiledevice3 套件，且僅支援 macOS/Linux。
    Windows 上請考慮使用其他工具或 Docker。
    """
    
    def __init__(self, logger: Optional[logging.Logger] = None):
        self.logger = logger or logging.getLogger("iOSChecker")
        self._available = self._check_dependency()
    
    def _check_dependency(self) -> bool:
        """檢查是否已安裝 pymobiledevice3。
        
        Returns:
            True 如果 pymobiledevice3 可用
        """
        try:
            import pymobiledevice3  # noqa: F401
            return True
        except ImportError:
            self.logger.warning(
                "pymobiledevice3 未安裝。iOS 偵測功能將被停用。"
            )
            return False
    
    def detect_device(self) -> Tuple[bool, str]:
        """檢查是否有 iOS 裝置連線。
        
        Returns:
            (是否找到裝置, 詳細訊息)
        """
        if not self._available:
            return False, "pymobiledevice3 未安裝"
        
        try:
            from pymobiledevice3 import usbmux
            devices = usbmux.list_devices()
            if devices:
                return True, f"找到 {len(devices)} 個 iOS 裝置"
            else:
                return False, "未找到 iOS 裝置"
        except Exception as e:
            return False, f"iOS 偵測失敗: {e}"


# ============================================================================
# 手機 TCP 客戶端
# ============================================================================
class PhoneClient:
    """透過 TCP Socket 與手機端伺服器通訊。
    
    負責：
    - 建立 TCP 連線
    - 發送 GPS 座標（JSON 格式）
    - 雙向心跳驗證
    - 斷線重連
    """
    
    # 協定協定
    PROTOCOL_VERSION = 1
    
    class MessageType:
        HANDSHAKE = "handshake"
        HANDSHAKE_ACK = "handshake_ack"
        GPS_UPDATE = "gps_update"
        HEARTBEAT = "heartbeat"
        HEARTBEAT_ACK = "heartbeat_ack"
        COMMAND = "command"
        ERROR = "error"
        DISCONNECT = "disconnect"
    
    def __init__(self, config: dict, logger: logging.Logger):
        """初始化手機客戶端。
        
        Args:
            config: 設定字典
            logger: 日誌實例
        """
        self.config = config
        self.logger = logger
        
        self._sock: Optional[socket.socket] = None
        self._recv_thread: Optional[threading.Thread] = None
        self._heartbeat_thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()
        self._running = False
        self._connected = False
        self._handshake_done = False
        self._last_heartbeat_ack = 0.0
        self._on_message: Optional[Callable[[dict], None]] = None
        self._on_disconnect: Optional[Callable[[], None]] = None
        self._recv_buffer = b""
    
    def set_callbacks(self,
                      on_message: Optional[Callable[[dict], None]] = None,
                      on_disconnect: Optional[Callable[[], None]] = None):
        """設定訊息回呼函數。
        
        Args:
            on_message: 收到伺服器訊息時呼叫 (接收 dict)
            on_disconnect: 斷線時呼叫
        """
        self._on_message = on_message
        self._on_disconnect = on_disconnect
    
    def connect(self, host: str, port: int) -> bool:
        """建立對手機伺服器的 TCP 連線。
        
        Args:
            host: 手機 IP 位址
            port: 手機埠號
        
        Returns:
            True 如果連線成功
        
        Raises:
            ConnectionTimeoutError: 連線超時
            ConnectionRefusedError: 連線被拒絕
            SocketError: 其他 Socket 錯誤
        """
        timeout = self.config["connection_timeout"]
        self.logger.info(f"正在連線至 {host}:{port} (超時 {timeout} 秒)...")
        
        try:
            self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self._sock.settimeout(timeout)
            self._sock.connect((host, port))
            self._sock.settimeout(None)  # 連線後取消阻塞模式，用 select 或專屬執行緒
            self._connected = True
            self._running = True
            
            self.logger.info(f"TCP 連線成功: {host}:{port}")
            
            # 啟動接收執行緒
            self._recv_thread = threading.Thread(
                target=self._recv_loop, daemon=True, name="PhoneClient-Recv"
            )
            self._recv_thread.start()
            
            return True
            
        except socket.timeout:
            msg = f"連線超時 (>{timeout}秒): {host}:{port}"
            self.logger.error(msg)
            self._cleanup_socket()
            raise ConnectionTimeoutError(msg)
        
        except socket.error as e:
            msg = f"Socket 連線失敗: {e}"
            self.logger.error(msg)
            self._cleanup_socket()
            if "refused" in str(e).lower():
                raise ConnectionRefusedError(
                    f"連線被拒絕。請確認手機端的伺服器應用程式已在運行。"
                )
            raise SocketError(msg)
    
    def _recv_loop(self):
        """接收資料的執行緒主循環。
        
        持續從 Socket 讀取資料，解析 JSON 訊息，並觸發回呼。
        處理粘包 (TCP 封包黏合) 與拆包問題。
        """
        self.logger.debug("接收執行緒已啟動")
        
        while self._running and self._connected:
            try:
                data = self._sock.recv(4096)
                if not data:
                    self.logger.warning("伺服器關閉連線 (收到空資料)")
                    self._handle_disconnect()
                    break
                
                self._recv_buffer += data
                
                # 嘗試解析完整的 JSON 訊息（以換行分隔）
                while b"\n" in self._recv_buffer:
                    line, self._recv_buffer = self._recv_buffer.split(b"\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    
                    try:
                        message = json.loads(line.decode("utf-8"))
                        self._process_message(message)
                    except json.JSONDecodeError as e:
                        self.logger.warning(f"收到無效 JSON: {e} | 原始: {line[:200]}")
                
            except (socket.timeout, BlockingIOError):
                # 非阻塞模式下無資料可讀，正常行為
                time.sleep(0.05)
                continue
            except socket.error as e:
                self.logger.error(f"Socket 接收錯誤: {e}")
                self._handle_disconnect()
                break
            except Exception as e:
                self.logger.exception(f"接收執行緒未預期錯誤: {e}")
                self._handle_disconnect()
                break
        
        self.logger.debug("接收執行緒已結束")
    
    def _process_message(self, message: dict):
        """處理從伺服器收到的訊息。
        
        Args:
            message: 解析後的 JSON 字典
        """
        msg_type = message.get("type", "")
        self.logger.debug(f"收到伺服器訊息: type={msg_type}")
        
        if msg_type == self.MessageType.HANDSHAKE_ACK:
            self._handshake_done = True
            self.logger.info("✓ 雙向交握驗證成功 (伺服器已確認連線)")
        
        elif msg_type == self.MessageType.HEARTBEAT:
            # 回傳心跳確認
            self._send_json({
                "type": self.MessageType.HEARTBEAT_ACK,
                "timestamp": time.time(),
            })
        
        elif msg_type == self.MessageType.HEARTBEAT_ACK:
            self._last_heartbeat_ack = time.time()
        
        elif msg_type == self.MessageType.ERROR:
            error_msg = message.get("message", "未知錯誤")
            self.logger.error(f"伺服器回報錯誤: {error_msg}")
        
        elif msg_type == self.MessageType.COMMAND:
            cmd = message.get("command", "")
            params = message.get("params", {})
            self.logger.info(f"收到伺服器指令: {cmd} {params}")
            # 可在此擴充指令處理邏輯
        
        elif msg_type == self.MessageType.DISCONNECT:
            self.logger.info("伺服器要求斷線")
            self._handle_disconnect()
        
        # 觸發通用訊息回呼
        if self._on_message:
            try:
                self._on_message(message)
            except Exception as e:
                self.logger.error(f"訊息回呼錯誤: {e}")
    
    def _handle_disconnect(self):
        """處理斷線事件。"""
        if not self._connected:
            return
        self._connected = False
        self._handshake_done = False
        self.logger.warning("與伺服器的連線已中斷")
        
        if self._on_disconnect:
            try:
                self._on_disconnect()
            except Exception as e:
                self.logger.error(f"斷線回呼錯誤: {e}")
        
        self._cleanup_socket()
    
    def _cleanup_socket(self):
        """安全關閉 Socket。"""
        with self._lock:
            if self._sock:
                try:
                    self._sock.close()
                except Exception:
                    pass
                self._sock = None
    
    def _send_json(self, data: dict) -> bool:
        """發送 JSON 訊息（以換行為分隔）。
        
        Args:
            data: 要發送的資料字典
        
        Returns:
            True 如果發送成功
        """
        if not self._connected or not self._sock:
            return False
        
        try:
            payload = (json.dumps(data, ensure_ascii=False) + "\n").encode("utf-8")
            with self._lock:
                self._sock.sendall(payload)
            return True
        except socket.error as e:
            self.logger.error(f"發送資料失敗: {e}")
            self._handle_disconnect()
            return False
    
    def perform_handshake(self) -> bool:
        """與手機伺服器進行雙向交握驗證。
        
        傳送交握訊息並等待伺服器確認。
        
        Returns:
            True 如果交握成功
        """
        self.logger.info("正在進行雙向交握驗證...")
        
        # 發送交握訊息
        success = self._send_json({
            "type": self.MessageType.HANDSHAKE,
            "protocol_version": self.PROTOCOL_VERSION,
            "client_name": "GPSMock Desktop Client",
            "timestamp": time.time(),
        })
        
        if not success:
            self.logger.error("交握訊息發送失敗")
            return False
        
        # 等待伺服器回應 (最多 5 秒)
        wait_start = time.time()
        while time.time() - wait_start < 5.0:
            if self._handshake_done:
                return True
            time.sleep(0.1)
        
        self.logger.error("交握驗證超時 (未收到伺服器確認)")
        return False
    
    def send_gps(self, point: GPSPoint) -> bool:
        """發送 GPS 座標更新至伺服器。
        
        Args:
            point: GPS 座標點
        
        Returns:
            True 如果發送成功
        """
        return self._send_json({
            "type": self.MessageType.GPS_UPDATE,
            "data": point.to_dict(),
        })
    
    def start_heartbeat(self):
        """啟動心跳檢查執行緒。
        
        定時發送心跳封包並檢查最後一次心跳確認的時間。
        若超過 heartbeat_timeout 未收到確認，視為斷線。
        """
        if self._heartbeat_thread and self._heartbeat_thread.is_alive():
            return
        
        self._last_heartbeat_ack = time.time()
        self._heartbeat_thread = threading.Thread(
            target=self._heartbeat_loop, daemon=True, name="PhoneClient-Heartbeat"
        )
        self._heartbeat_thread.start()
        self.logger.debug("心跳檢查已啟動")
    
    def _heartbeat_loop(self):
        """心跳檢查主循環。"""
        interval = self.config["heartbeat_interval"]
        timeout = self.config["heartbeat_timeout"]
        
        while self._running and self._connected:
            time.sleep(interval)
            
            if not self._connected:
                break
            
            # 發送心跳
            self._send_json({
                "type": self.MessageType.HEARTBEAT,
                "timestamp": time.time(),
            })
            
            # 檢查上次心跳確認時間
            elapsed = time.time() - self._last_heartbeat_ack
            if elapsed > timeout:
                self.logger.error(
                    f"心跳超時 (已 {elapsed:.1f} 秒未收到確認)，視為斷線"
                )
                self._handle_disconnect()
                break
    
    def is_connected(self) -> bool:
        return self._connected and self._handshake_done
    
    def disconnect(self):
        """安全斷線。"""
        self.logger.info("正在安全關閉連線...")
        self._running = False
        
        if self._connected:
            self._send_json({
                "type": self.MessageType.DISCONNECT,
                "timestamp": time.time(),
            })
        
        self._connected = False
        self._handshake_done = False
        self._cleanup_socket()


class ConnectionTimeoutError(ConnectionError):
    """連線超時錯誤"""
    pass


class SocketError(ConnectionError):
    """Socket 相關錯誤"""
    pass


# ============================================================================
# 主控制器
# ============================================================================
class MockController:
    """主要控制器，整合所有元件。
    
    負責：
    - 運行前檢查清單
    - 協調步行模擬器與手機客戶端
    - 處理異常與安全關閉
    - 提供即時狀態資訊
    """
    
    def __init__(self, config: dict = None):
        """初始化控制器。
        
        Args:
            config: 設定字典，若為 None 則使用預設值
        """
        self.config = config or DEFAULT_CONFIG.copy()
        
        # 初始化日誌
        self.logger = setup_logging(verbose=False)
        
        # 初始化子元件
        self.adb_checker = ADBChecker(
            adb_path=self.config["adb_path"], logger=self.logger
        )
        self.ios_checker = iOSChecker(logger=self.logger)
        self.walk_simulator = WalkSimulator(self.config, self.logger)
        self.phone_client = PhoneClient(self.config, self.logger)
        
        # 控制器狀態
        self._running = False
        self._status = ConnectionStatus.DISCONNECTED
        self._status_lock = threading.Lock()
        self._main_thread: Optional[threading.Thread] = None
        
        # 設定手機客戶端回呼
        self.phone_client.set_callbacks(
            on_message=self._on_phone_message,
            on_disconnect=self._on_phone_disconnect,
        )
        
        # 註冊訊號處理器 (Ctrl+C 優雅關閉)
        signal.signal(signal.SIGINT, self._signal_handler)
        signal.signal(signal.SIGTERM, self._signal_handler)
    
    def set_verbose(self, verbose: bool):
        """設定詳細日誌模式。
        
        Args:
            verbose: True 為除錯模式
        """
        self.logger.setLevel(logging.DEBUG if verbose else logging.INFO)
        for handler in self.logger.handlers:
            handler.setLevel(logging.DEBUG if verbose else logging.INFO)
    
    def _signal_handler(self, signum, frame):
        """處理系統訊號 (Ctrl+C / SIGTERM)，實現優雅關閉。"""
        self.logger.info(f"收到訊號 {signum}，正在安全關閉...")
        self.stop()
        sys.exit(0)
    
    def _on_phone_message(self, message: dict):
        """手機客戶端收到訊息時的回呼。"""
        msg_type = message.get("type", "")
        if msg_type == "error":
            self.logger.error(f"手機端錯誤: {message.get('message', '未知')}")
    
    def _on_phone_disconnect(self):
        """手機客戶端斷線時的回呼。"""
        self.logger.warning("手機連線中斷！自動停止模擬...")
        self._set_status(ConnectionStatus.DISCONNECTED)
        self.walk_simulator.stop()
        self._running = False
    
    def _set_status(self, status: ConnectionStatus):
        """設定並記錄當前連線狀態。"""
        with self._status_lock:
            self._status = status
        self.logger.info(f"狀態變更: {status.value}")
    
    def get_status(self) -> ConnectionStatus:
        """獲取當前連線狀態。"""
        with self._status_lock:
            return self._status
    
    # ========================================================================
    # 運行前檢查 (Preflight Checklist)
    # ========================================================================
    def preflight_check(self, skip_adb: bool = False, skip_ios: bool = True) -> bool:
        """執行運行前完整檢查。
        
        檢查項目：
        1. ADB / iOS 裝置連線
        2. 裝置授權狀態
        3. 網路連線與埠號可達性 (ICMP / TCP)
        4. TCP 連線至手機伺服器
        5. 雙向交握驗證
        
        Args:
            skip_adb: 跳過 ADB 裝置檢查
            skip_ios: 跳過 iOS 裝置檢查
        
        Returns:
            True 如果所有檢查通過
        """
        self.logger.info("=" * 60)
        self.logger.info("  運行前檢查 (Preflight Checklist)")
        self.logger.info("=" * 60)
        
        checks_passed = True
        
        # ----- 檢查 1: ADB 連線 (Android) -----
        if not skip_adb:
            self.logger.info("\n[1/5] 檢查 ADB 裝置連線...")
            try:
                device_found, device_msg = self.adb_checker.detect_device()
                if device_found:
                    self.logger.info(f"  ✓ {device_msg}")
                    
                    # 檢查裝置授權
                    self.logger.info("[1a] 測試裝置回應...")
                    if self.adb_checker.test_device_connectivity():
                        self.logger.info("  ✓ 裝置正常回應")
                    else:
                        self.logger.error("  ✗ 裝置回應異常")
                        checks_passed = False
                    
                    # 獲取裝置資訊
                    info = self.adb_checker.get_device_info()
                    self.logger.info(f"  裝置: {info.get('manufacturer', '?')} "
                                     f"{info.get('model', '?')} "
                                     f"(Android {info.get('android_version', '?')})")
                    
                    # 自動偵測 IP
                    self.logger.info("[1b] 嘗試自動偵測裝置 IP...")
                    auto_ip = self.adb_checker.get_device_ip()
                    if auto_ip:
                        self.config["phone_ip"] = auto_ip
                        self.logger.info(f"  ✓ 自動偵測 IP: {auto_ip}")
                    else:
                        self.logger.warning(
                            f"  ⚠ 無法自動偵測 IP，"
                            f"將使用設定中的 {self.config['phone_ip']}"
                        )
                else:
                    self.logger.error(f"  ✗ {device_msg}")
                    checks_passed = False
            except (ADBError, ADBTimeoutError) as e:
                self.logger.error(f"  ✗ ADB 檢查失敗: {e}")
                checks_passed = False
        else:
            self.logger.info("\n[1/5] ADB 檢查已跳過")
        
        # ----- 檢查 2: iOS 連線 (選擇性) -----
        if not skip_ios and self.config.get("enable_ios", False):
            self.logger.info("\n[2/5] 檢查 iOS 裝置連線...")
            try:
                found, msg = self.ios_checker.detect_device()
                if found:
                    self.logger.info(f"  ✓ {msg}")
                else:
                    self.logger.warning(f"  ⚠ {msg}")
            except Exception as e:
                self.logger.warning(f"  ⚠ iOS 檢查失敗: {e}")
        else:
            self.logger.info("\n[2/5] iOS 檢查已跳過")
        
        # ----- 檢查 3: 網路連線 (ICMP Ping) -----
        self.logger.info(f"\n[3/5] 檢查網路連線至 {self.config['phone_ip']}...")
        phone_ip = self.config["phone_ip"]
        try:
            # 使用系統 ping（1 個封包，超時 3 秒）
            ping_cmd = ["ping", "-n", "1", "-w", "3000", phone_ip] \
                if sys.platform == "win32" else \
                ["ping", "-c", "1", "-W", "3", phone_ip]
            
            ping_result = subprocess.run(
                ping_cmd, capture_output=True, text=True, timeout=5
            )
            if ping_result.returncode == 0:
                self.logger.info(f"  ✓ {phone_ip} 可 ping 通")
            else:
                self.logger.warning(
                    f"  ⚠ {phone_ip} ping 失敗 (可能防火牆阻擋 ICMP)"
                )
        except Exception as e:
            self.logger.warning(f"  ⚠ Ping 檢查失敗: {e}")
        
        # ----- 檢查 4: TCP 埠號檢查 -----
        port = self.config["phone_port"]
        self.logger.info(f"\n[4/5] 檢查 TCP 埠號 {port} 是否可連線...")
        try:
            test_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            test_sock.settimeout(3)
            result = test_sock.connect_ex((phone_ip, port))
            test_sock.close()
            
            if result == 0:
                self.logger.info(f"  ✓ 埠號 {port} 開啟中")
            elif result == 10061:  # Connection refused (Windows)
                self.logger.warning(
                    f"  ⚠ 埠號 {port} 連線被拒絕，"
                    f"請確認手機端伺服器已啟動"
                )
            else:
                self.logger.warning(
                    f"  ⚠ 埠號 {port} 無法連線 (錯誤碼: {result})"
                )
        except Exception as e:
            self.logger.warning(f"  ⚠ 埠號檢查失敗: {e}")
        
        # ----- 檢查 5: TCP 連線 + 雙向交握 -----
        self.logger.info(f"\n[5/5] TCP 連線與雙向交握驗證...")
        try:
            if self.phone_client.connect(phone_ip, port):
                self.logger.info("  ✓ TCP 連線成功")
                self._set_status(ConnectionStatus.CONNECTED)
                
                if self.phone_client.perform_handshake():
                    self._set_status(ConnectionStatus.HANDSHAKE_OK)
                    self.logger.info("  ✓ 雙向交握驗證通過！")
                    self.phone_client.start_heartbeat()
                else:
                    self.logger.error("  ✗ 雙向交握驗證失敗")
                    self.phone_client.disconnect()
                    checks_passed = False
            else:
                self.logger.error("  ✗ TCP 連線失敗")
                checks_passed = False
        except ConnectionTimeoutError as e:
            self.logger.error(f"  ✗ {e}")
            checks_passed = False
        except ConnectionRefusedError as e:
            self.logger.error(f"  ✗ {e}")
            checks_passed = False
        except SocketError as e:
            self.logger.error(f"  ✗ {e}")
            checks_passed = False
        
        # ----- 總結 -----
        self.logger.info("\n" + "=" * 60)
        if checks_passed:
            self.logger.info("  ✓ 所有檢查通過！準備開始模擬。")
        else:
            self.logger.warning("  ⚠ 部分檢查未通過，請修正後重新運行。")
            self.logger.warning("  可以透過 --skip-adb 跳過 ADB 檢查。")
        self.logger.info("=" * 60)
        
        return checks_passed
    
    # ========================================================================
    # 主模擬循環
    # ========================================================================
    def run(self, waypoints: Optional[List[GPSPoint]] = None):
        """開始模擬循環。
        
        Args:
            waypoints: 航點列表（可選），若提供則依序前往
        
        Raises:
            RuntimeError: 如果運行前檢查未通過
        """
        # 設定航點
        if waypoints:
            self.walk_simulator.set_waypoints(waypoints)
        else:
            self.logger.info("未設定航點，將使用隨機漫步模式")
        
        # 啟動步行模擬器
        self.walk_simulator.start()
        self._running = True
        
        self.logger.info("=" * 60)
        self.logger.info("  模擬開始")
        self.logger.info(f"  初始位置: ({self.config['start_lat']:.6f}, "
                         f"{self.config['start_lon']:.6f})")
        self.logger.info(f"  步行速度: {self.config['walk_speed_min']} ~ "
                         f"{self.config['walk_speed_max']} m/s")
        self.logger.info(f"  更新間隔: {self.config['update_interval']} 秒")
        self.logger.info("=" * 60)
        
        # 主循環
        last_update = 0
        total_distance = 0.0
        start_time = time.time()
        
        try:
            while self._running and self.phone_client.is_connected():
                now = time.time()
                
                if now - last_update >= self.config["update_interval"]:
                    # 獲取下一個 GPS 點
                    point = self.walk_simulator.get_next_point()
                    if point is None:
                        break
                    
                    # 發送至手機
                    if self.phone_client.is_connected():
                        sent = self.phone_client.send_gps(point)
                        if not sent:
                            self.logger.error("GPS 資料發送失敗")
                            break
                        
                        total_distance += point.speed * self.config["update_interval"]
                    
                    last_update = now
                    
                    # 每 30 秒輸出一次摘要
                    elapsed = now - start_time
                    if int(elapsed) % 30 == 0 and int(elapsed) > 0:
                        self._print_status(elapsed, total_distance)
                
                time.sleep(0.05)  # 避免 busy-wait
                
        except KeyboardInterrupt:
            self.logger.info("使用者中斷")
        except Exception as e:
            self.logger.exception(f"模擬循環發生未預期錯誤: {e}")
        finally:
            self.stop()
    
    def _print_status(self, elapsed: float, total_distance: float):
        """輸出當前狀態摘要。"""
        steps = self.walk_simulator.total_steps
        avg_speed = total_distance / elapsed if elapsed > 0 else 0
        pos = self.walk_simulator.current_position
        
        self.logger.info(
            f"[狀態] 已運行 {elapsed:.0f}s | "
            f"步數 {steps} | "
            f"距離 {total_distance:.0f}m | "
            f"均速 {avg_speed:.2f} m/s | "
            f"位置 ({pos.lat:.6f}, {pos.lon:.6f})"
        )
    
    def stop(self):
        """安全停止所有元件。"""
        self.logger.info("正在停止所有元件...")
        self._running = False
        
        self.walk_simulator.stop()
        self.phone_client.disconnect()
        
        self._set_status(ConnectionStatus.DISCONNECTED)
        self.logger.info("所有元件已停止")


# ============================================================================
# 命令列介面 (CLI)
# ============================================================================
def parse_waypoints(waypoint_str: str) -> List[GPSPoint]:
    """解析命令列航點參數。
    
    支援格式: "lat1,lon1;lat2,lon2;..."
    
    Args:
        waypoint_str: 航點字串
    
    Returns:
        GPSPoint 列表
    """
    points = []
    for wp in waypoint_str.split(";"):
        wp = wp.strip()
        if not wp:
            continue
        try:
            parts = wp.split(",")
            if len(parts) != 2:
                raise ValueError(f"無效航點格式: {wp}")
            lat, lon = float(parts[0]), float(parts[1])
            points.append(GPSPoint(lat=lat, lon=lon))
        except ValueError as e:
            print(f"⚠ 航點解析錯誤: {e}")
    return points


def build_waypoint_route(start_lat: float, start_lon: float,
                         num_points: int = 10, radius_m: int = 200
                         ) -> List[GPSPoint]:
    """隨機生成一條環形路線（用於測試）。
    
    Args:
        start_lat, start_lon: 起點座標
        num_points: 航點數
        radius_m: 半徑 (公尺)
    
    Returns:
        GPSPoint 列表
    """
    points = []
    for i in range(num_points):
        angle = (360.0 / num_points) * i
        dist = radius_m * (0.5 + random.random() * 0.5)
        lat, lon = offset_position(start_lat, start_lon, dist, angle)
        points.append(GPSPoint(lat=lat, lon=lon))
    return points


def build_config_from_args(args: argparse.Namespace) -> dict:
    """從命令列參數構建設定字典。"""
    config = DEFAULT_CONFIG.copy()
    
    if args.ip:
        config["phone_ip"] = args.ip
    if args.port:
        config["phone_port"] = args.port
    
    config["start_lat"] = args.lat
    config["start_lon"] = args.lon
    
    if args.speed_min:
        config["walk_speed_min"] = args.speed_min
    if args.speed_max:
        config["walk_speed_max"] = args.speed_max
    
    config["bearing_drift_max_deg"] = args.drift
    config["adb_path"] = args.adb
    config["enable_ios"] = args.ios
    
    return config


def create_argparser() -> argparse.ArgumentParser:
    """建立命令列參數解析器。"""
    parser = argparse.ArgumentParser(
        description="Pikmin Bloom GPS 模擬器 — 桌面客戶端",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用範例：
  # 基本運行 (使用預設設定)
  python gps_mock_client.py --ip 192.168.1.100

  # 自訂起始位置與速度
  python gps_mock_client.py --ip 192.168.1.100 --lat 25.0343 --lon 121.5645

  # 指定航點路線 (用分號分隔)
  python gps_mock_client.py --ip 192.168.1.100 \\
    --waypoints "25.0478,121.5170;25.0500,121.5200;25.0520,121.5180"

  # 跳過 ADB 檢查 (已知手機 IP)
  python gps_mock_client.py --ip 192.168.1.100 --skip-adb

  # 詳細除錯模式
  python gps_mock_client.py --ip 192.168.1.100 --verbose
        """,
    )
    
    # === 連線設定 ===
    conn_group = parser.add_argument_group("連線設定")
    conn_group.add_argument(
        "--ip", type=str, default=DEFAULT_CONFIG["phone_ip"],
        help=f"手機 IP 位址 (預設: {DEFAULT_CONFIG['phone_ip']})"
    )
    conn_group.add_argument(
        "--port", type=int, default=DEFAULT_CONFIG["phone_port"],
        help=f"手機埠號 (預設: {DEFAULT_CONFIG['phone_port']})"
    )
    conn_group.add_argument(
        "--skip-adb", action="store_true",
        help="跳過 ADB 裝置檢查"
    )
    conn_group.add_argument(
        "--adb", type=str, default=DEFAULT_CONFIG["adb_path"],
        help=f"ADB 執行檔路徑 (預設: {DEFAULT_CONFIG['adb_path']})"
    )
    conn_group.add_argument(
        "--ios", action="store_true",
        help="啟用 iOS 裝置偵測 (需安裝 pymobiledevice3)"
    )
    
    # === GPS 模擬設定 ===
    gps_group = parser.add_argument_group("GPS 模擬設定")
    gps_group.add_argument(
        "--lat", type=float, default=DEFAULT_CONFIG["start_lat"],
        help=f"起始緯度 (預設: {DEFAULT_CONFIG['start_lat']})"
    )
    gps_group.add_argument(
        "--lon", type=float, default=DEFAULT_CONFIG["start_lon"],
        help=f"起始經度 (預設: {DEFAULT_CONFIG['start_lon']})"
    )
    gps_group.add_argument(
        "--speed-min", type=float,
        help=f"最低步行速度 m/s (預設: {DEFAULT_CONFIG['walk_speed_min']})"
    )
    gps_group.add_argument(
        "--speed-max", type=float,
        help=f"最高步行速度 m/s (預設: {DEFAULT_CONFIG['walk_speed_max']})"
    )
    gps_group.add_argument(
        "--drift", type=float, default=DEFAULT_CONFIG["bearing_drift_max_deg"],
        help=f"最大方位角飄移 (度, 預設: {DEFAULT_CONFIG['bearing_drift_max_deg']})"
    )
    gps_group.add_argument(
        "--waypoints", type=str,
        help="航點路線，格式: lat1,lon1;lat2,lon2;..."
    )
    gps_group.add_argument(
        "--random-route", type=int, metavar="POINTS",
        help="自動生成環形路線 (指定航點數)"
    )
    gps_group.add_argument(
        "--route-radius", type=int, default=200,
        help="環形路線半徑 (公尺, 預設: 200)"
    )
    
    # === 其他 ===
    parser.add_argument(
        "--verbose", action="store_true",
        help="詳細除錯模式"
    )
    parser.add_argument(
        "--check-only", action="store_true",
        help="只執行運行前檢查，不開始模擬"
    )
    
    return parser


# ============================================================================
# 主程式進入點
# ============================================================================
def main():
    """主程式進入點。"""
    parser = create_argparser()
    args = parser.parse_args()
    
    # 從命令列參數建構設定
    config = build_config_from_args(args)
    
    # 初始化控制器
    controller = MockController(config)
    
    if args.verbose:
        controller.set_verbose(True)
        # 也設定 ADB 檢查器的日誌等級
        controller.adb_checker.logger.setLevel(logging.DEBUG)
    
    # 顯示啟動資訊
    controller.logger.info("=" * 60)
    controller.logger.info("  Pikmin Bloom GPS 模擬器 v1.0.0")
    controller.logger.info(f"  目標手機: {config['phone_ip']}:{config['phone_port']}")
    controller.logger.info(f"  起始位置: ({config['start_lat']:.6f}, "
                           f"{config['start_lon']:.6f})")
    controller.logger.info("=" * 60)
    
    # 運行前檢查
    check_passed = controller.preflight_check(
        skip_adb=args.skip_adb,
        skip_ios=not args.ios,
    )
    
    if args.check_only:
        controller.logger.info("僅執行檢查模式，結束。")
        return
    
    if not check_passed:
        controller.logger.error(
            "運行前檢查未通過。請修正問題後重試。\n"
            "提示: 使用 --skip-adb 可跳過 ADB 檢查（需已知手機 IP）"
        )
        sys.exit(1)
    
    # 準備航點
    waypoints = None
    if args.waypoints:
        waypoints = parse_waypoints(args.waypoints)
        if not waypoints:
            controller.logger.warning("航點解析失敗，將使用隨機漫步")
            waypoints = None
    elif args.random_route:
        waypoints = build_waypoint_route(
            config["start_lat"], config["start_lon"],
            num_points=args.random_route,
            radius_m=args.route_radius,
        )
        controller.logger.info(
            f"已自動生成 {len(waypoints)} 個航點的環形路線 "
            f"(半徑 {args.route_radius}m)"
        )
    
    # 開始模擬
    try:
        controller.run(waypoints)
    except KeyboardInterrupt:
        controller.logger.info("使用者中斷 (Ctrl+C)")
    except Exception as e:
        controller.logger.exception(f"執行時發生未預期錯誤: {e}")
    finally:
        controller.stop()
        controller.logger.info("程式結束。")


if __name__ == "__main__":
    main()
