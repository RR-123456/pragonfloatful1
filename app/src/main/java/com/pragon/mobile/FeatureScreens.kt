package com.pragon.mobile

/** v18: one screen per feature. Each opens only its own tool (see tools.html only()). */
class DrafterScreen : ToolsActivity() { override fun startPanel(): String = "draft" }
class CalculatorScreen : ToolsActivity() { override fun startPanel(): String = "calc" }
class MessagesScreen : ToolsActivity() { override fun startPanel(): String = "msg" }
class NotificationsScreen : ToolsActivity() { override fun startPanel(): String = "mon" }
class MapScreen : ToolsActivity() { override fun startPanel(): String = "map" }
class DirectionsScreen : ToolsActivity() { override fun startPanel(): String = "dir" }
class RideScreen : ToolsActivity() { override fun startPanel(): String = "ride" }
class CalendarScreen : ToolsActivity() { override fun startPanel(): String = "cal" }
class ClockScreen : ToolsActivity() { override fun startPanel(): String = "clock" }
class CameraScreen : ToolsActivity() { override fun startAction(): String = "camera" }
class LensScreen : ToolsActivity() { override fun startAction(): String = "lens" }
