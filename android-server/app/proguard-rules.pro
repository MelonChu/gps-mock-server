# GPS Mock Server ProGuard Rules
# 保留序列化相關類別
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class com.gpsmock.server.GPSServerService$Message { *; }
-keepclassmembers class com.gpsmock.server.GPSServerService$GpsData { *; }

# 保留 Service 類別
-keep class com.gpsmock.server.GPSServerService { *; }
-keep class com.gpsmock.server.GPSMockLocationProvider { *; }
