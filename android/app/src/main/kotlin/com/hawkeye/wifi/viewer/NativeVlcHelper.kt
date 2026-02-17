package com.hawkeye.wifi.viewer

/** Player event callback interface used by MethodChannel bridge in MainActivity. */
interface VlcEventCallback {
    fun onPlaying()
    fun onError()
    fun onEndReached()
    fun onStopped()
    fun onVideoSizeChanged(width: Int, height: Int)
}
