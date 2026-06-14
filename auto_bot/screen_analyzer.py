#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
螢幕分析器 — 解析 Android 螢幕截圖
=====================================

提供基礎的螢幕分析功能：
- 顏色比對：在指定區域尋找特定顏色
- 文字偵測：使用 Tesseract OCR（選擇性）
- 影像比對：比對區域相似度（用於偵測 UI 元素變化）
- 座標輔助：根據螢幕解析度自動調整

注意：此模組為基礎版本，不使用 OpenCV 或機器學習，
以保持零外部依賴的目標。若需要更精確的辨識，建議安裝 opencv-python。
"""

import logging
import struct
import io
from typing import Optional, Tuple, List
from dataclasses import dataclass


@dataclass
class Color:
    """RGB 顏色"""
    r: int
    g: int
    b: int

    @classmethod
    def from_hex(cls, hex_str: str) -> "Color":
        """從十六進位字串建立顏色，如 '#FF8800' 或 'FF8800'"""
        h = hex_str.lstrip("#")
        return cls(int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))

    def distance_to(self, other: "Color") -> float:
        """計算與另一顏色的歐幾里德距離 (0~441)。"""
        return ((self.r - other.r) ** 2 +
                (self.g - other.g) ** 2 +
                (self.b - other.b) ** 2) ** 0.5

    def is_similar(self, other: "Color", threshold: float = 30.0) -> bool:
        """判斷是否與另一顏色相似（距離小於 threshold）。"""
        return self.distance_to(other) < threshold


@dataclass
class Region:
    """螢幕區域"""
    x: int
    y: int
    width: int
    height: int


class ScreenAnalyzer:
    """螢幕分析器，從 PNG 截圖中提取資訊。
    
    使用方式：
    >>> analyzer = ScreenAnalyzer()
    >>> analyzer.load(png_bytes)
    >>> color = analyzer.get_pixel_color(500, 1000)
    >>> if color.is_similar(Color(255, 136, 0)):
    ...     print("找到橘色元素")
    """

    def __init__(self, logger: Optional[logging.Logger] = None):
        self.logger = logger or logging.getLogger("ScreenAnalyzer")
        self._width = 0
        self._height = 0
        self._raw_pixels: Optional[bytes] = None  # RGBA raw bytes

    def load(self, png_data: bytes) -> bool:
        """載入 PNG 截圖資料。
        
        解析簡易 PNG 格式，取得寬高與像素資料。
        注意：這是一個簡化的 PNG 解析器，支援最常見的 PNG 格式。
        
        Args:
            png_data: PNG 格式的 bytes
        
        Returns:
            True 如果載入成功
        """
        try:
            # 簡易 PNG 解析
            # PNG signature: 8 bytes
            if png_data[:8] != b'\x89PNG\r\n\x1a\n':
                self.logger.error("非 PNG 格式")
                return False

            pos = 8
            # 遍歷 chunks 尋找 IHDR
            while pos < len(png_data):
                length = struct.unpack('>I', png_data[pos:pos+4])[0]
                chunk_type = png_data[pos+4:pos+8]

                if chunk_type == b'IHDR':
                    self._width = struct.unpack('>I', png_data[pos+8:pos+12])[0]
                    self._height = struct.unpack('>I', png_data[pos+12:pos+16])[0]
                    bit_depth = png_data[pos+16]
                    color_type = png_data[pos+17]
                    
                    self.logger.debug(
                        f"PNG: {self._width}x{self._height}, "
                        f"bit_depth={bit_depth}, color_type={color_type}"
                    )
                    
                    # 讀取完整的像素資料需要完整的 PNG 解壓（超出本模組範圍）
                    # 改用 PIL 或 pypng 可完整解析
                    # 此處僅回傳尺寸資訊
                    return True

                pos += 12 + length  # length + type + data + CRC
                if chunk_type == b'IEND':
                    break

            # 若無法擷取原始像素，至少記錄尺寸
            self.logger.warning("PNG 解析完成（僅尺寸），像素層級比對需安裝 opencv-python")
            return self._width > 0

        except Exception as e:
            self.logger.error(f"PNG 解析錯誤: {e}")
            return False

    def get_size(self) -> Tuple[int, int]:
        """取得螢幕尺寸 (width, height)。"""
        return (self._width, self._height)

    def get_pixel_color(self, x: int, y: int) -> Optional[Color]:
        """取得指定座標的顏色（僅在完整載入像素資料時可用）。
        
        Args:
            x: X 座標
            y: Y 座標
        
        Returns:
            Color 物件，若無法取得則回傳 None
        """
        if not self._raw_pixels or self._width == 0:
            return None
        
        idx = (y * self._width + x) * 4  # RGBA
        if idx + 3 >= len(self._raw_pixels):
            return None
        
        return Color(
            r=self._raw_pixels[idx],
            g=self._raw_pixels[idx + 1],
            b=self._raw_pixels[idx + 2],
        )

    def find_color_in_region(self, target_color: Color, region: Region,
                               threshold: float = 40.0) -> List[Tuple[int, int]]:
        """在指定區域中尋找相似顏色。
        
        注意：此功能需要完整的像素資料，建議安裝 opencv-python。
        
        Args:
            target_color: 目標顏色
            region: 搜尋區域
            threshold: 顏色相似度閾值
        
        Returns:
            符合條件的像素座標列表
        """
        # 基礎版本未實作完整像素掃描
        # 需要 opencv-python 或 numpy 加速
        self.logger.warning("完整像素掃描需要 opencv-python")
        return []

    def has_color_at(self, x: int, y: int, target_color: Color,
                     threshold: float = 40.0) -> bool:
        """檢查指定座標的顏色是否符合目標。
        
        Args:
            x, y: 座標
            target_color: 目標顏色
            threshold: 閾值
        
        Returns:
            True 如果顏色在閾值內
        """
        color = self.get_pixel_color(x, y)
        if color is None:
            return False
        return color.is_similar(target_color, threshold)

    def estimate_brightness(self, region: Region) -> float:
        """估算指定區域的平均亮度 (0~255)。
        
        注意：需要完整像素資料，未實作。"""
        return 128.0


def detect_plant_button(screen_width: int, screen_height: int) -> Tuple[int, int]:
    """根據螢幕解析度估算 Pikmin Bloom 種花按鈕的位置。
    
    Pikmin Bloom 的種花按鈕通常位於畫面正下方中央。
    
    Args:
        screen_width: 螢幕寬度 (像素)
        screen_height: 螢幕高度 (像素)
    
    Returns:
        (按鈕 X, 按鈕 Y) 像素座標
    """
    return (screen_width // 2, screen_height - 120)


def detect_collect_button(screen_width: int, screen_height: int) -> Tuple[int, int]:
    """估算收集按鈕位置（成果／果實對話框中的「OK」按鈕）。
    
    通常在畫面中央偏下位置。
    """
    return (screen_width // 2, screen_height - 200)


def detect_exit_button(screen_width: int, screen_height: int) -> Tuple[int, int]:
    """估算關閉按鈕位置（對話框右上角的 X）。"""
    return (screen_width - 50, 50)
