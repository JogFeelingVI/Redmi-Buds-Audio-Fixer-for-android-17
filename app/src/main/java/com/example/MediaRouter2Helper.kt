package com.example

import android.content.Context
import android.media.MediaRoute2Info
import android.media.MediaRouter
import android.media.MediaRouter2
import android.os.Build
import android.util.Log

data class RouteTransferResult(
    val isSuccess: Boolean,
    val targetRouteName: String?,
    val routeType: Int?,
    val message: String,
    val availableRoutes: List<String>
)

object MediaRouter2Helper {

    private const val TAG = "MediaRouter2Helper"

    /**
     * Programmatically transfers Android's system-wide media playback route to the connected Bluetooth A2DP device.
     * Uses MediaRouter2 on Android 11+ (API 30+), which requires MEDIA_CONTENT_CONTROL or MEDIA_ROUTING_CONTROL.
     */
    fun transferToBluetooth(context: Context, preferredDeviceName: String? = null): RouteTransferResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val router = MediaRouter2.getInstance(context)
                val routes = router.routes
                val routeSummaries = routes.map { "${it.name} (type: ${getRouteTypeName(it.type)}, id: ${it.id})" }

                Log.d(TAG, "MediaRouter2 total routes found: ${routes.size}. Routes: $routeSummaries")

                // Look for a Bluetooth A2DP or BLE route
                val btRoute = routes.firstOrNull { route ->
                    val name = route.name?.toString() ?: ""
                    val isBtType = route.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP ||
                            route.type == MediaRoute2Info.TYPE_BLE_HEADSET ||
                            route.type == MediaRoute2Info.TYPE_HEARING_AID ||
                            route.type == 26 || // AudioDeviceInfo.TYPE_BLE_HEADSET
                            route.type == 27    // AudioDeviceInfo.TYPE_BLE_SPEAKER

                    val matchesName = preferredDeviceName != null && name.contains(preferredDeviceName, ignoreCase = true)
                    val matchesKeywords = name.contains("Redmi", ignoreCase = true) ||
                            name.contains("Buds", ignoreCase = true) ||
                            name.contains("Bluetooth", ignoreCase = true) ||
                            name.contains("耳机", ignoreCase = true) ||
                            name.contains("蓝牙", ignoreCase = true)

                    isBtType || matchesName || matchesKeywords
                }

                if (btRoute != null) {
                    Log.d(TAG, "Transferring to route: ${btRoute.name} (type: ${btRoute.type})")
                    router.transferTo(btRoute)
                    return RouteTransferResult(
                        isSuccess = true,
                        targetRouteName = btRoute.name?.toString() ?: "Bluetooth Audio",
                        routeType = btRoute.type,
                        message = "Media output successfully routed to ${btRoute.name}",
                        availableRoutes = routeSummaries
                    )
                } else {
                    Log.w(TAG, "No Bluetooth A2DP route found in MediaRouter2")
                    return RouteTransferResult(
                        isSuccess = false,
                        targetRouteName = null,
                        routeType = null,
                        message = "No Bluetooth A2DP route found in system MediaRouter2. Please check if 'Media audio' is enabled in Bluetooth settings.",
                        availableRoutes = routeSummaries
                    )
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for MediaRouter2.transferTo: MEDIA_CONTENT_CONTROL required", e)
                return RouteTransferResult(
                    isSuccess = false,
                    targetRouteName = null,
                    routeType = null,
                    message = "System permission error: MediaRouter2 requires MEDIA_CONTENT_CONTROL. Please grant permission via Shizuku.",
                    availableRoutes = emptyList()
                )
            } catch (e: Exception) {
                Log.e(TAG, "MediaRouter2 error", e)
                return RouteTransferResult(
                    isSuccess = false,
                    targetRouteName = null,
                    routeType = null,
                    message = "MediaRouter2 transfer failed: ${e.localizedMessage}",
                    availableRoutes = emptyList()
                )
            }
        } else {
            // Android < 11 legacy MediaRouter fallback
            return try {
                val mediaRouter = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
                if (mediaRouter != null) {
                    var selected = false
                    var matchedName: String? = null
                    for (i in 0 until mediaRouter.routeCount) {
                        val route = mediaRouter.getRouteAt(i)
                        val rName = route.name?.toString() ?: ""
                        if (route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH ||
                            rName.contains("Redmi", ignoreCase = true) ||
                            rName.contains("Buds", ignoreCase = true) ||
                            rName.contains("Bluetooth", ignoreCase = true)
                        ) {
                            mediaRouter.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, route)
                            selected = true
                            matchedName = rName
                            break
                        }
                    }
                    if (selected) {
                        RouteTransferResult(
                            isSuccess = true,
                            targetRouteName = matchedName,
                            routeType = null,
                            message = "Selected legacy MediaRouter route: $matchedName",
                            availableRoutes = listOfNotNull(matchedName)
                        )
                    } else {
                        RouteTransferResult(
                            isSuccess = false,
                            targetRouteName = null,
                            routeType = null,
                            message = "No Bluetooth route found in legacy MediaRouter",
                            availableRoutes = emptyList()
                        )
                    }
                } else {
                    RouteTransferResult(
                        isSuccess = false,
                        targetRouteName = null,
                        routeType = null,
                        message = "MediaRouter service unavailable",
                        availableRoutes = emptyList()
                    )
                }
            } catch (e: Exception) {
                RouteTransferResult(
                    isSuccess = false,
                    targetRouteName = null,
                    routeType = null,
                    message = "Legacy MediaRouter error: ${e.localizedMessage}",
                    availableRoutes = emptyList()
                )
            }
        }
    }

    private fun getRouteTypeName(type: Int): String {
        return when (type) {
            MediaRoute2Info.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
            MediaRoute2Info.TYPE_WIRED_HEADSET -> "Wired Headset"
            MediaRoute2Info.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            MediaRoute2Info.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP (High Definition)"
            MediaRoute2Info.TYPE_HDMI -> "HDMI"
            MediaRoute2Info.TYPE_USB_DEVICE -> "USB Audio"
            MediaRoute2Info.TYPE_USB_ACCESSORY -> "USB Accessory"
            MediaRoute2Info.TYPE_DOCK -> "Dock"
            MediaRoute2Info.TYPE_USB_HEADSET -> "USB Headset"
            MediaRoute2Info.TYPE_HEARING_AID -> "Hearing Aid"
            MediaRoute2Info.TYPE_BLE_HEADSET -> "BLE Audio Headset"
            27 -> "BLE Audio Speaker"
            28 -> "BLE Broadcast"
            else -> "Audio Route ($type)"
        }
    }
}
