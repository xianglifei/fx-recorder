package com.fx.recorder.recorder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 局域网传输服务信息（状态页显示用）。 */
data class TransferInfo(val port: Int, val pairCode: String)

/** 引擎运行时状态的进程内广播桥：引擎写入，前台服务（通知）与 UI 读取。 */
object RecorderBus {
    private val _runtime = MutableStateFlow(EngineRuntime())
    val runtime: StateFlow<EngineRuntime> = _runtime

    private val _transfer = MutableStateFlow<TransferInfo?>(null)
    val transfer: StateFlow<TransferInfo?> = _transfer

    fun publish(r: EngineRuntime) {
        _runtime.value = r
    }

    fun publishTransfer(t: TransferInfo?) {
        _transfer.value = t
    }
}