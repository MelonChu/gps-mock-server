#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ADB 控制器 — 透過 ADB over Wi-Fi 控制 Android 裝置
====================================================

提供螢幕截圖、觸控點擊、滑動手勢等功能，
讓桌面 Python 腳本可以像人類一樣操作手機遊戲。

支援兩種連線模式：
1. USB 模式：手機透過 USB 連接電腦
2. Wi-Fi 模式：`adb connect <手機 IP>:5555` 後斷開 USB

注意事項：
- ADB over Wi-Fi 需要手機與電腦在同一區域網路
- 第一次設定需要 USB 連線執行 `adb tcpip 5555`
- 部分中國品牌手機可能限制 ADB over Wi-Fi
"""

import subprocess
import time
import os
import io
import logging
import struct
from typing import Optional, Tuple, List
from dataclasses import dataclass
from enum import Enum


class ScreenTapResult(Enum):
    """螢幕點擊結果"""
    SUCCESS = "success"
    FAILED = "failed"
    DEVICE_NOT_FOUND = "device_not_found"
    PERMISSION_DENIED = "permission_denied"


@dataclass
class DeviceInfo:
    """Android 裝置資訊"""
    serial: str
    model: str = "unknown"
    android_version: str = "unknown"
    screen_width: int = 0
    screen_height: int = 0
    is_wifi: bool = False


class ADBError(Exception):
    """ADB 操作錯誤"""
    pass


class ADBController:
    """ADB 控制器，封裝所有 ADB 操作。
    
    使用方式：
    >>> adb = ADBController(adb_path="adb")
    >>> adb.connect_wifi("192.168.1.100")  # Wi-Fi 連線
    >>> adb.tap(500, 1000)                  # 點擊座標
    >>> screenshot = adb.screenshot()       # 截圖
    """

    def __init__(self, adb_path: str = "adb", logger: Optional[logging.Logger] = None):
        """初始化 ADB 控制器。
        
        Args:
            adb_path: adb 執行檔路徑
            logger: 日誌實例
        """
        self.adb_path = adb_path
        self.logger = logger or logging.getLogger("ADBController")
        self._device_serial: Optional[str] = None
        self._device_info: Optional[DeviceInfo] = None

    # ====================================================================
    # 裝置連線管理
    # ====================================================================
    def connect_wifi(self, ip: str, port: int = 5555) -> bool:
        """透過 Wi-Fi 連線至 Android 裝置。
        
        注意：第一次使用前，需要透過 USB 執行一次：
            adb tcpip 5555
            然後才能使用 adb connect <IP>:5555
        
        Args:
            ip: 手機 IP 位址
            port: ADB 埠號 (預設 5555)
        
        Returns:
            True 如果連線成功
        
        Raises:
            ADBError: 連線失敗
        """
        addr = f"{ip}:{port}"
        self.logger.info(f"正在透過 Wi-Fi 連線至 {addr} ...")
        
        try:
            success, output = self._run_adb(["connect", addr])
            
            if "connected" in output.lower():
                self._device_serial = addr
                self.logger.info(f"✓ Wi-Fi 連線成功: {addr}")
                self._detect_device_info()
                return True
            elif "already connected" in output.lower():
                self._device_serial = addr
                self.logger.info(f"✓ 已連線至 {addr}")
                return True
            else:
                self.logger.error(f"✗ 連線失敗: {output}")
                raise ADBError(f"無法連線至 {addr}: {output}")
                
        except subprocess.TimeoutExpired:
            raise ADBError(f"連線超時: {addr}")
        except FileNotFoundError:
            raise ADBError(f"找不到 ADB: {self.adb_path}")

    def connect_usb(self) -> bool:
        """連線至 USB 裝置（自動選取第一個已授權裝置）。
        
        Returns:
            True 如果找到 USB 裝置
        """
        success, output = self._run_adb(["devices"])
        
        for line in output.split("\n")[1:]:
            parts = line.strip().split("\t")
            if len(parts) == 2 and parts[1] == "device" and ":" not in parts[0]:
                self._device_serial = parts[0]
                self.logger.info(f"✓ USB 裝置: {parts[0]}")
                self._detect_device_info()
                return True
        
        self.logger.error("✗ 未找到 USB 裝置")
        return False

    def disconnect(self):
        """中斷 ADB 連線。"""
        if self._device_serial:
            self._run_adb(["disconnect", self._device_serial])
            self.logger.info("ADB 連線已中斷")
            self._device_serial = None
            self._device_info = None

    def is_connected(self) -> bool:
        """檢查 ADB 裝置是否仍在線。"""
        if not self._device_serial:
            return False
        try:
            success, output = self._run_adb(["devices"])
            return self._device_serial in output and "device" in output
        except Exception:
            return False

    # ====================================================================
    # 裝置資訊
    # ====================================================================
    def _detect_device_info(self):
        """偵測連線裝置的資訊。"""
        if not self._device_serial:
            return
        
        info = DeviceInfo(serial=self._device_serial)
        
        # 型號
        _, model = self._adb_shell("getprop ro.product.model")
        info.model = model.strip() or "unknown"
        
        # Android 版本
        _, ver = self._adb_shell("getprop ro.build.version.release")
        info.android_version = ver.strip() or "unknown"
        
        # 螢幕解析度
        _, res = self._adb_shell("wm size")
        if res:
            try:
                parts = res.strip().split(":")[-1].strip().split("x")
                info.screen_width = int(parts[0])
                info.screen_height = int(parts[1])
            except (ValueError, IndexError):
                pass
        
        info.is_wifi = ":" in self._device_serial
        self._device_info = info
        
        self.logger.info(
            f"裝置: {info.model} | Android {info.android_version} | "
            f"螢幕: {info.screen_width}x{info.screen_height} | "
            f"{'Wi-Fi' if info.is_wifi else 'USB'}"
        )

    def get_device_info(self) -> Optional[DeviceInfo]:
        """取得裝置資訊。"""
        return self._device_info

    # ====================================================================
    # 螢幕互動
    # ====================================================================
    def tap(self, x: int, y: int) -> ScreenTapResult:
        """在指定座標模擬手指點擊。
        
        Args:
            x: X 座標 (像素)
            y: Y 座標 (像素)
        
        Returns:
            點擊結果
        """
        try:
            success, output = self._adb_shell(f"input tap {x} {y}")
            if success:
                self.logger.debug(f"點擊 ({x}, {y})")
                return ScreenTapResult.SUCCESS
            else:
                self.logger.error(f"點擊失敗: {output}")
                return ScreenTapResult.FAILED
        except ADBError as e:
            self.logger.error(f"點擊錯誤: {e}")
            if "not found" in str(e).lower():
                return ScreenTapResult.DEVICE_NOT_FOUND
            return ScreenTapResult.FAILED

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> bool:
        """模擬手指滑動。
        
        Args:
            x1, y1: 起點座標
            x2, y2: 終點座標
            duration_ms: 滑動持續時間 (毫秒)
        
        Returns:
            True 如果滑動成功
        """
        try:
            success, _ = self._adb_shell(
                f"input swipe {x1} {y1} {x2} {y2} {duration_ms}"
            )
            return success
        except ADBError:
            return False

    def long_press(self, x: int, y: int, duration_ms: int = 1000) -> bool:
        """模擬長按。
        
        Args:
            x, y: 座標
            duration_ms: 按壓持續時間 (毫秒)
        
        Returns:
            True 如果長按成功
        """
        return self.swipe(x, y, x, y, duration_ms)

    def type_text(self, text: str) -> bool:
        """輸入文字（需焦點在文字輸入框）。
        
        Args:
            text: 要輸入的文字
        
        Returns:
            True 如果輸入成功
        """
        try:
            # 跳過特殊字元以避免 ADB 錯誤
            safe_text = text.replace(" ", "%s").replace("'", "")
            success, _ = self._adb_shell(f"input text '{safe_text}'")
            return success
        except ADBError:
            return False

    def press_key(self, keycode: int) -> bool:
        """模擬按鍵。
        
        常用按鍵代碼：
        - KEYCODE_HOME = 3
        - KEYCODE_BACK = 4
        - KEYCODE_ENTER = 66
        - KEYCODE_MENU = 82
        
        Args:
            keycode: Android KeyEvent 代碼
        
        Returns:
            True 如果按鍵成功
        """
        try:
            success, _ = self._adb_shell(f"input keyevent {keycode}")
            return success
        except ADBError:
            return False

    def press_back(self) -> bool:
        """按下返回鍵。"""
        return self.press_key(4)

    def press_home(self) -> bool:
        """按下 Home 鍵。"""
        return self.press_key(3)

    # ====================================================================
    # 螢幕截圖
    # ====================================================================
    def screenshot(self, save_path: Optional[str] = None) -> Optional[bytes]:
        """擷取 Android 裝置螢幕畫面。
        
        Args:
            save_path: 若指定，將圖片存檔至該路徑
        
        Returns:
            PNG 格式的圖片 bytes，若失敗則回傳 None
        """
        try:
            success, output = self._adb_shell("screencap -p", raw_output=True)
            
            if not success or not output:
                self.logger.error("截圖失敗")
                return None
            
            if save_path:
                with open(save_path, "wb") as f:
                    f.write(output)
                self.logger.debug(f"截圖已存檔: {save_path}")
            
            return output
            
        except ADBError as e:
            self.logger.error(f"截圖錯誤: {e}")
            return None

    # ====================================================================
    # 螢幕解析度感知的座標輔助
    # ====================================================================
    def get_relative_coords(self, x_pct: float, y_pct: float) -> Tuple[int, int]:
        """將百分比座標轉換為像素座標。
        
        這對於支援不同螢幕解析度的手機非常有用。
        例如手機螢幕為 1080x2400，呼叫 get_relative_coords(50, 50)
        會回傳 (540, 1200)。
        
        Args:
            x_pct: X 百分比 (0~100)
            y_pct: Y 百分比 (0~100)
        
        Returns:
            (像素 X, 像素 Y)
        """
        info = self._device_info
        if not info or info.screen_width == 0:
            self.logger.warning("無法取得螢幕解析度，使用原始百分比")
            return (int(x_pct), int(y_pct))
        
        x = int(info.screen_width * x_pct / 100)
        y = int(info.screen_height * y_pct / 100)
        return (x, y)

    def tap_relative(self, x_pct: float, y_pct: float) -> ScreenTapResult:
        """點擊螢幕上的百分比位置。
        
        Args:
            x_pct: X 百分比 (0~100)
            y_pct: Y 百分比 (0~100)
        
        Returns:
            點擊結果
        """
        x, y = self.get_relative_coords(x_pct, y_pct)
        return self.tap(x, y)

    # ====================================================================
    # 應用程式管理
    # ====================================================================
    def launch_app(self, package_name: str) -> bool:
        """啟動指定 Android 應用程式。
        
        Args:
            package_name: Android Package Name
        
        Returns:
            True 如果啟動成功
        
        常用 Package Name:
        - Pikmin Bloom: com.nianticlabs.pikmin
        """
        try:
            success, output = self._adb_shell(f"monkey -p {package_name} -c android.intent.category.LAUNCHER 1")
            return success
        except ADBError:
            return False

    def force_stop_app(self, package_name: str) -> bool:
        """強制停止指定應用程式。"""
        try:
            success, _ = self._adb_shell(f"am force-stop {package_name}")
            return success
        except ADBError:
            return False

    def is_app_running(self, package_name: str) -> bool:
        """檢查應用程式是否正在運行。"""
        try:
            success, output = self._adb_shell(f"pidof {package_name}")
            return success and output.strip().isdigit()
        except ADBError:
            return False

    # ====================================================================
    # 內部方法
    # ====================================================================
    def _run_adb(self, args: List[str], timeout: int = 15, raw_output: bool = False
                 ) -> Tuple[bool, any]:
        """執行 ADB 指令（低階方法）。
        
        Args:
            args: ADB 參數
            timeout: 超時秒數
            raw_output: True 時回傳原始 bytes（用於二進位輸出如截圖）
        
        Returns:
            (成功與否, 輸出字串或 bytes)
        """
        cmd = [self.adb_path] + args
        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                timeout=timeout,
            )
            
            if raw_output:
                output = result.stdout
            else:
                output = (result.stdout + result.stderr).decode("utf-8", errors="replace").strip()
            
            if result.returncode != 0:
                error_msg = (result.stderr.decode("utf-8", errors="replace").strip()
                             if not raw_output else str(result.stderr))
                self.logger.debug(f"ADB 回傳非零: {error_msg}")
                return False, output
            
            return True, output
            
        except FileNotFoundError:
            raise ADBError(f"找不到 ADB 執行檔: {self.adb_path}")
        except subprocess.TimeoutExpired:
            raise ADBError(f"ADB 指令超時")
        except PermissionError:
            raise ADBError(f"ADB 權限不足")

    def _adb_shell(self, command: str, raw_output: bool = False) -> Tuple[bool, any]:
        """執行 ADB shell 指令。
        
        Args:
            command: shell 指令
            raw_output: 是否回傳原始 bytes
        
        Returns:
            (成功與否, 輸出)
        """
        if not self._device_serial:
            raise ADBError("未連線至裝置")
        
        args = ["-s", self._device_serial, "shell", command]
        return self._run_adb(args, raw_output=raw_output)


# ====================================================================
# 常用按鍵代碼
# ====================================================================
class KeyCode:
    """Android KeyEvent 常數"""
    HOME = 3
    BACK = 4
    DIAL = 5
    VOLUME_UP = 24
    VOLUME_DOWN = 25
    POWER = 26
    CAMERA = 27
    ENTER = 66
    MENU = 82
    SEARCH = 84
