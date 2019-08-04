package com.snail.easyble.core

import android.bluetooth.*
import android.os.Build
import android.os.Message
import android.util.Log
import androidx.annotation.UiThread
import com.snail.easyble.callback.ConnectionStateChangeListener
import com.snail.easyble.util.BleLogger
import com.snail.easyble.util.BleUtils
import java.util.*

/**
 * 管理一个设备的连接，数据收发，重连之类的
 * 
 * date: 2018/4/11 15:29
 * author: zengfansheng
 */
class Connection private constructor(device: Device, bluetoothDevice: BluetoothDevice, config: ConnectionConfig) : BaseConnection(device, bluetoothDevice, config) {
    private var stateChangeListener: ConnectionStateChangeListener? = null
    private var connStartTime = 0L //用于连接超时计时
    private var refreshTimes = 0 //刷新计数，在发现服务后清零
    private var tryReconnectTimes = 0 //尝试重连计数
    private var lastConnectState = -1 
    private var reconnectImmediatelyCount = 0 //不搜索直接重连计数
    private var refreshing = false
    private var isActiveDisconnect = false
    private var lastScanStopTime = 0L

    internal val isAutoReconnectEnabled: Boolean
        get() = config.isAutoReconnect

    val connctionState: Int
        get() = device.connectionState

    @Synchronized
    internal fun onScanStop() {
        lastScanStopTime = System.currentTimeMillis()
    }
    
    @Synchronized
    internal fun onScanResult(addr: String) {
        if (!isReleased && device.addr == addr && device.connectionState == IConnection.STATE_SCANNING) {
            connHandler.sendEmptyMessage(MSG_CONNECT)
        }
    }

    @UiThread
    override fun handleMsg(msg: Message) {
        if (isReleased && msg.what != MSG_RELEASE) {
            return
        }
        when (msg.what) {
            MSG_CONNECT //连接
            -> if (bluetoothAdapter!!.isEnabled) {
                doConnect()
            }
            MSG_DISCONNECT //断开
            -> doDisconnect(msg.arg1 == MSG_ARG_RECONNECT && bluetoothAdapter!!.isEnabled, true)
            MSG_REFRESH //手动刷新
            -> doRefresh(false)
            MSG_AUTO_REFRESH //自动刷新
            -> doRefresh(true)
            MSG_RELEASE //释放连接
            -> {
                config.setAutoReconnect(false) //停止自动重连
                doDisconnect(false, msg.arg1 == MSG_ARG_NOTIFY, msg.arg2 == MSG_ARG_RELEASE)
            }
            MSG_TIMER
            -> doTimer()
            MSG_DISCOVER_SERVICES, //执行发现服务
            MSG_ON_CONNECTION_STATE_CHANGE, //连接状态变化
            MSG_ON_SERVICES_DISCOVERED //服务已发现
            -> if (bluetoothAdapter!!.isEnabled) {
                if (msg.what == MSG_DISCOVER_SERVICES) {
                    doDiscoverServices()
                } else {
                    if (msg.what == MSG_ON_SERVICES_DISCOVERED) {
                        doOnServicesDiscovered(msg.arg1)
                    } else {
                        doOnConnectionStateChange(msg.arg1, msg.arg2)
                    }
                }
            }
        }
    }

    private fun notifyDisconnected() {
        device.connectionState = IConnection.STATE_DISCONNECTED
        sendConnectionCallback()
    }

    private fun doOnConnectionStateChange(status: Int, newState: Int) {
        if (bluetoothGatt != null) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Ble.instance.logger.handleLog(Log.DEBUG, "connected! [name: ${device.name}, addr: ${device.addr}]", BleLogger.TYPE_CONNECTION_STATE)
                    device.connectionState = IConnection.STATE_CONNECTED
                    sendConnectionCallback()
                    // 延时一会再去发现服务
                    connHandler.sendEmptyMessageDelayed(MSG_DISCOVER_SERVICES, config.discoverServicesDelayMillis.toLong())
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Ble.instance.logger.handleLog(Log.DEBUG, "disconnected! [name: ${device.name}, addr: ${device.addr}, autoReconnEnable: ${config.isAutoReconnect}]", 
                        BleLogger.TYPE_CONNECTION_STATE)
                    clearRequestQueueAndNotify()
                    notifyDisconnected()
                }
            } else {
                Ble.instance.logger.handleLog(Log.ERROR, "GATT error! [name: ${device.name}, addr: ${device.addr}, status: $status]", BleLogger.TYPE_CONNECTION_STATE)
                if (status == 133) {
                    doClearTaskAndRefresh()
                } else {
                    clearRequestQueueAndNotify()
                    notifyDisconnected()
                }
            }
        }
    }

    private fun doOnServicesDiscovered(status: Int) {
        if (bluetoothGatt != null) {
            val services = bluetoothGatt!!.services
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Ble.instance.logger.handleLog(Log.DEBUG, "services discovered! [name: ${device.name}, addr: ${device.addr}, size: ${bluetoothGatt!!.services.size}]",
                    BleLogger.TYPE_CONNECTION_STATE)
                if (services.isEmpty()) {
                    doClearTaskAndRefresh()
                } else {
                    refreshTimes = 0
                    tryReconnectTimes = 0
                    reconnectImmediatelyCount = 0
                    device.connectionState = IConnection.STATE_SERVICE_DISCOVERED
                    sendConnectionCallback()
                }
            } else {
                doClearTaskAndRefresh()
                Ble.instance.logger.handleLog(Log.ERROR, "GATT error! [status: $status, name: ${device.name}, addr: ${device.addr}]", BleLogger.TYPE_CONNECTION_STATE)
            }
        }
    }

    private fun doDiscoverServices() {
        if (bluetoothGatt != null) {
            bluetoothGatt!!.discoverServices()
            device.connectionState = IConnection.STATE_SERVICE_DISCOVERING
            sendConnectionCallback()
        } else {
            notifyDisconnected()
        }
    }

    private fun doTimer() {
        if (!isReleased) {
            //只处理不是已发现服务并且不在刷新也不是主动断开连接的
            if (device.connectionState != IConnection.STATE_SERVICE_DISCOVERED && !refreshing && !isActiveDisconnect) {
                if (device.connectionState != IConnection.STATE_DISCONNECTED) {
                    //超时
                    if (System.currentTimeMillis() - connStartTime > config.connectTimeoutMillis) {
                        connStartTime = System.currentTimeMillis()
                        Ble.instance.logger.handleLog(Log.ERROR, "connect timeout! [name: ${device.name}, addr: ${device.addr}]", BleLogger.TYPE_CONNECTION_STATE)
                        val type = when {
                            device.connectionState == IConnection.STATE_SCANNING -> IConnection.TIMEOUT_TYPE_CANNOT_DISCOVER_DEVICE
                            device.connectionState == IConnection.STATE_CONNECTING -> IConnection.TIMEOUT_TYPE_CANNOT_CONNECT
                            else -> IConnection.TIMEOUT_TYPE_CANNOT_DISCOVER_SERVICES
                        }
                        Ble.instance.getObservable().notifyConnectTimeout(device, type)
                        stateChangeListener?.onConnectTimeout(device, type)
                        if (config.isAutoReconnect && (config.tryReconnectTimes == ConnectionConfig.TRY_RECONNECT_TIMES_INFINITE || tryReconnectTimes < config.tryReconnectTimes)) {
                            doDisconnect(true)
                        } else {
                            doDisconnect(false)
                            notifyConnectFailed(device, IConnection.CONNECT_FAIL_TYPE_MAXIMUM_RECONNECTION, stateChangeListener)
                            Ble.instance.logger.handleLog(Log.ERROR, "connect failed! [type: maximun reconnection, name: ${device.name}, addr: ${device.addr}]",
                                BleLogger.TYPE_CONNECTION_STATE)
                        }
                    }
                } else if (config.isAutoReconnect) {
                    doDisconnect(true)
                }
            }
            connHandler.sendEmptyMessageDelayed(MSG_TIMER, 500)
        }
    }

    //处理刷新
    private fun doRefresh(isAuto: Boolean) {
        Ble.instance.logger.handleLog(Log.DEBUG, "refresh GATT! [name: ${device.name}, addr: ${device.addr}]", BleLogger.TYPE_CONNECTION_STATE)
        connStartTime = System.currentTimeMillis()
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt!!.disconnect()
            } catch (ignored: Exception) {}

            if (isAuto) {
                if (refreshTimes <= 5) {
                    refreshing = refresh(bluetoothGatt!!)
                }
                refreshTimes++
            } else {
                refreshing = refresh(bluetoothGatt!!)
            }
            if (refreshing) {
                connHandler.postDelayed({ cancelRefreshState() }, 2000)
            } else if (bluetoothGatt != null) {
                closeGatt(bluetoothGatt)
                bluetoothGatt = null
            }
        }
        notifyDisconnected()
    }

    private fun cancelRefreshState() {
        if (refreshing) {
            refreshing = false
            if (bluetoothGatt != null) {
                closeGatt(bluetoothGatt)
                bluetoothGatt = null
            }
        }
    }

    private fun doConnect() {
        cancelRefreshState()        
        device.connectionState = IConnection.STATE_CONNECTING
        sendConnectionCallback()
        Ble.instance.logger.handleLog(Log.DEBUG, "connecting [name: ${device.name}, addr: ${device.addr}]", BleLogger.TYPE_CONNECTION_STATE)        
        connHandler.postDelayed(connectRunnable, 500)
    }
    
    private val connectRunnable = {
        if (!isReleased) {
            //连接之前必须先停止搜索
            Ble.instance.stopScan()
            bluetoothGatt = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    bluetoothDevice.connectGatt(Ble.instance.context, false, this, config.transport, config.phy)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    bluetoothDevice.connectGatt(Ble.instance.context, false, this, config.transport)
                }
                else -> bluetoothDevice.connectGatt(Ble.instance.context, false, this)
            }
        }
    }

    private fun doDisconnect(reconnect: Boolean, notify: Boolean = true, release: Boolean = false) {
        clearRequestQueueAndNotify()
        connHandler.removeCallbacks(connectRunnable)
        connHandler.removeMessages(MSG_DISCOVER_SERVICES)
        if (bluetoothGatt != null) {
            closeGatt(bluetoothGatt)
            bluetoothGatt = null
        }
        device.connectionState = IConnection.STATE_DISCONNECTED
        if (release) {
            device.connectionState = IConnection.STATE_RELEASED
            super.release()
            Ble.instance.logger.handleLog(Log.DEBUG, "connection released! [name: ${device.name}, addr: ${device.addr}]", BleLogger.TYPE_CONNECTION_STATE)
        } else if (reconnect) {
            if (reconnectImmediatelyCount < config.reconnectImmediatelyTimes) {
                tryReconnectTimes++
                reconnectImmediatelyCount++
                connStartTime = System.currentTimeMillis()
                doConnect()
            } else if (canScanReconnect()) {
                tryScanReconnect()
            }
        }
        if (notify) {
            sendConnectionCallback()
        }
    }

    private fun closeGatt(gatt: BluetoothGatt?) {
        try {
            gatt?.disconnect()
        } catch (ignored: Exception) {}

        try {
            gatt?.close()
        } catch (ignored: Exception) {}
    }

    private fun tryScanReconnect() {
        if (!isReleased) {
            connStartTime = System.currentTimeMillis()            
            Ble.instance.stopScan()
            //搜索设备，搜索到才执行连接
            device.connectionState = IConnection.STATE_SCANNING
            Ble.instance.logger.handleLog(Log.DEBUG, "scanning [name: ${device.name}, addr: ${device.addr}]", BleLogger.TYPE_CONNECTION_STATE)
            Ble.instance.startScan()
        }
    }
    
    private fun canScanReconnect(): Boolean {
        val duration = System.currentTimeMillis() - lastScanStopTime
        val pairs = Ble.instance.bleConfig.scanIntervalPairsInAutoReonnection
        pairs.sortBy { it.first }
        pairs.reverse()
        pairs.forEach { 
            if (tryReconnectTimes >= it.first && duration >= it.second) {
                return true
            }
        }
        return false
    }
    
    private fun doClearTaskAndRefresh() {
        clearRequestQueueAndNotify()
        doRefresh(true)
    }

    private fun sendConnectionCallback() {
        if (lastConnectState != device.connectionState) {
            lastConnectState = device.connectionState
            stateChangeListener?.onConnectionStateChanged(device)
            Ble.instance.getObservable().notifyConnectionStateChanged(device)
        }
    }

    internal fun setAutoReconnectEnable(enable: Boolean) {
        config.setAutoReconnect(enable)
    }

    fun reconnect() {
        if (!isReleased) {
            isActiveDisconnect = false
            tryReconnectTimes = 0
            reconnectImmediatelyCount = 0
            Message.obtain(connHandler, MSG_DISCONNECT, MSG_ARG_RECONNECT, 0).sendToTarget()
        }
    }

    fun disconnect() {
        if (!isReleased) {
            isActiveDisconnect = true
            Message.obtain(connHandler, MSG_DISCONNECT, MSG_ARG_NONE, 0).sendToTarget()
        }
    }

    /**
     * Clears the internal cache and forces a refresh of the services from the remote device.
     */
    fun refresh() {
        connHandler.sendEmptyMessage(MSG_REFRESH)
    }

    /**
     * 销毁连接，停止定时器
     */
    override fun release() {
        Message.obtain(connHandler, MSG_RELEASE, MSG_ARG_NOTIFY, MSG_ARG_RELEASE).sendToTarget()
        connHandler.post { super.release() }
    }

    /**
     * 销毁连接，停止定时器，不通过观察者
     */
    fun releaseNoEvnet() {
        Message.obtain(connHandler, MSG_RELEASE, MSG_ARG_NONE, MSG_ARG_RELEASE).sendToTarget()
        connHandler.post { super.release() }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (!isReleased) {
            connHandler.sendMessage(Message.obtain(connHandler, MSG_ON_CONNECTION_STATE_CHANGE, status, newState))
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (!isReleased) {
            connHandler.sendMessage(Message.obtain(connHandler, MSG_ON_SERVICES_DISCOVERED, status, 0))
        }
    }

    private fun getHex(value: ByteArray): String {
        return BleUtils.bytesToHexString(value).trim { it <= ' ' }
    }

    override fun onCharacteristicRead(tag: String, characteristic: BluetoothGattCharacteristic) {
        Ble.instance.getObservable().notifyCharacteristicRead(device, tag, characteristic.service.uuid, characteristic.uuid, characteristic.value)
        Ble.instance.logger.handleLog(Log.DEBUG, "(${characteristic.uuid})characteristic read! [addr: ${device.addr}, " +
                "value: ${getHex(characteristic.value)}]", BleLogger.TYPE_CHARACTERISTIC_READ)
    }

    override fun onCharacteristicChanged(characteristic: BluetoothGattCharacteristic) {
        Ble.instance.getObservable().notifyCharacteristicChanged(device, characteristic.service.uuid, characteristic.uuid, characteristic.value)
        Ble.instance.logger.handleLog(Log.INFO, "(${characteristic.uuid})characteristic change! [addr: ${device.addr}, " +
                "value: ${getHex(characteristic.value)}]", BleLogger.TYPE_CHARACTERISTIC_CHANGED)
    }

    override fun onReadRemoteRssi(tag: String, rssi: Int) {
        Ble.instance.getObservable().notifyRemoteRssiRead(device, tag, rssi)
        Ble.instance.logger.handleLog(Log.DEBUG, "rssi read! [addr: ${device.addr}, rssi: $rssi]", BleLogger.TYPE_READ_REMOTE_RSSI)
    }

    override fun onMtuChanged(tag: String, mtu: Int) {
        Ble.instance.getObservable().notifyMtuChanged(device, tag, mtu)
        Ble.instance.logger.handleLog(Log.DEBUG, "mtu change! [addr: ${device.addr}, mtu: $mtu]", BleLogger.TYPE_MTU_CHANGED)
    }

    override fun onRequestFialed(tag: String, requestType: Request.RequestType, failType: Int, value: ByteArray?) {
        Ble.instance.getObservable().notifyRequestFailed(device, tag, requestType, failType, value)
        Ble.instance.logger.handleLog(Log.DEBUG, "request failed! [addr: ${device.addr}, tag: $tag, failType: $failType]", BleLogger.TYPE_REQUEST_FIALED)
    }

    override fun onDescriptorRead(tag: String, descriptor: BluetoothGattDescriptor) {
        Ble.instance.getObservable().notifyDescriptorRead(device, tag, descriptor.characteristic.service.uuid, descriptor.characteristic.uuid, descriptor.uuid, descriptor.value)
        Ble.instance.logger.handleLog(Log.DEBUG, "(${descriptor.characteristic.uuid})descriptor read! [addr: ${device.addr}, value: ${getHex(descriptor.value)}]",
            BleLogger.TYPE_DESCRIPTOR_READ)
    }

    override fun onNotificationChanged(tag: String, descriptor: BluetoothGattDescriptor, isEnabled: Boolean) {
        Ble.instance.getObservable().notifyNotificationChanged(device, tag, descriptor.characteristic.service.uuid, descriptor.characteristic.uuid, descriptor.uuid, isEnabled)
        Ble.instance.logger.handleLog(Log.DEBUG, "(${descriptor.characteristic.uuid})${if (isEnabled) "notification enabled!" else "notification disabled!"} " +
                "[addr: ${device.addr}]", BleLogger.TYPE_NOTIFICATION_CHANGED)
    }

    override fun onIndicationChanged(tag: String, descriptor: BluetoothGattDescriptor, isEnabled: Boolean) {
        Ble.instance.getObservable().notifyIndicationChanged(device, tag, descriptor.characteristic.service.uuid, descriptor.characteristic.uuid, descriptor.uuid, isEnabled)
        Ble.instance.logger.handleLog(Log.DEBUG, "(${descriptor.characteristic.uuid})${if (isEnabled) "indication enabled!" else "indication disabled"} [addr: ${device.addr}]",
            BleLogger.TYPE_INDICATION_CHANGED)
    }

    override fun onCharacteristicWrite(tag: String, serviceUuid: UUID, characteristicUuid: UUID, value: ByteArray) {
        Ble.instance.getObservable().notifyCharacteristicWrite(device, tag, serviceUuid, characteristicUuid, value)
        Ble.instance.logger.handleLog(Log.DEBUG, "($characteristicUuid)write success! [addr: ${device.addr}, value: ${getHex(value)}]", BleLogger.TYPE_CHARACTERISTIC_WRITE)
    }

    override fun onPhyReadOrUpdate(tag: String, read: Boolean, txPhy: Int, rxPhy: Int) {
        if (read) {
            Ble.instance.logger.handleLog(Log.DEBUG, "phy read! [addr: ${device.addr}, tvPhy: $txPhy, rxPhy: $rxPhy]", BleLogger.TYPE_PHY_READ)
            Ble.instance.getObservable().notifyPhyRead(device, tag, txPhy, rxPhy)
        } else {
            Ble.instance.logger.handleLog(Log.DEBUG, "phy update! [addr: ${device.addr}, tvPhy: $txPhy, rxPhy: $rxPhy]", BleLogger.TYPE_PHY_UPDATE)
            Ble.instance.getObservable().notifyPhyUpdate(device, tag, txPhy, rxPhy)
        }      
    }

    companion object {
        private const val MSG_ARG_NONE = 0
        private const val MSG_ARG_RECONNECT = 1
        private const val MSG_ARG_NOTIFY = 2
        private const val MSG_ARG_RELEASE = 3

        /**
         * 创建新的连接
         */
        @Synchronized
        internal fun newInstance(bluetoothAdapter: BluetoothAdapter, device: Device, config: ConnectionConfig?, connectDelay: Long, 
                                 stateChangeListener: ConnectionStateChangeListener?): Connection? {
            var connectionConfig = config
            if (!device.addr.matches("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$".toRegex())) {
                Ble.instance.logger.handleLog(Log.ERROR, "connect failed! [type: unspecified mac address, name: ${device.name}, addr: ${device.addr}]", 
                    BleLogger.TYPE_CONNECTION_STATE)
                notifyConnectFailed(device, IConnection.CONNECT_FAIL_TYPE_UNSPECIFIED_ADDRESS, stateChangeListener)
                return null
            }
            if (connectionConfig == null) {
                connectionConfig = ConnectionConfig()
            }
            val conn = Connection(device, bluetoothAdapter.getRemoteDevice(device.addr), connectionConfig)
            conn.bluetoothAdapter = bluetoothAdapter
            conn.stateChangeListener = stateChangeListener
            conn.connStartTime = System.currentTimeMillis()
            conn.connHandler.sendEmptyMessageDelayed(MSG_CONNECT, connectDelay) //执行连接
            conn.connHandler.sendEmptyMessageDelayed(MSG_TIMER, connectDelay) //启动定时器
            return conn
        }

        internal fun notifyConnectFailed(device: Device?, type: Int, listener: ConnectionStateChangeListener?) {
            listener?.onConnectFailed(device, type)
            Ble.instance.getObservable().notifyConnectFailed(device, type)
        }
    }
}
