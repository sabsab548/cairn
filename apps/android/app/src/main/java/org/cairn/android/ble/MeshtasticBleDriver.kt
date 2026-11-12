package org.cairn.android.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.cairn.protocol.Codec
import org.cairn.sync.RadioTransport
import org.cairn.protocol.Packet
import java.util.UUID

/**
 * Raw GATT driver for a Meshtastic node.
 *
 * This is intentionally a minimal skeleton: it scans for the Meshtastic service,
 * connects, discovers characteristics, and provides hooks to send/receive
 * Cairn packets. The Meshtastic protobuf layer (ToRadio/FromRadio, node info,
 * channel config, …) lives platform-independently in `packages/meshtastic`;
 * this class only has to move bytes between that codec and the GATT
 * characteristics.
 */
class MeshtasticBleDriver(
    private val context: Context,
) : RadioTransport {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow<RadioTransport.State>(RadioTransport.State.Disconnected)
    override val state: StateFlow<RadioTransport.State> = _state

    // Meshtastic BLE service UUIDs (from meshtastic/protobufs)
    private val serviceUuid = UUID.fromString("6ba1b218-15a8-461f-9269-48f14e47f3a8")
    private val fromRadioUuid = UUID.fromString("8ba2cc2d-6200-4875-919e-b7249cc5619a")
    private val toRadioUuid = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    private val fromNumUuid = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

    private var scanner = adapter?.bluetoothLeScanner
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var channelKey: ByteArray? = null

    override var onReceive: (Packet) -> Unit = {}
    override var onStateChanged: (RadioTransport.State) -> Unit = {}

    init {
        // Forward state changes to the callback.
        // In a real app this would be tied to a lifecycle-aware collector.
    }

    override fun connect(channelKey: ByteArray) {
        this.channelKey = channelKey

        if (!hasPermissions()) {
            emitState(RadioTransport.State.Error("Missing BLUETOOTH_SCAN / BLUETOOTH_CONNECT permission"))
            return
        }

        val adapter = adapter ?: run {
            emitState(RadioTransport.State.Error("Bluetooth not available"))
            return
        }

        if (!adapter.isEnabled) {
            emitState(RadioTransport.State.Error("Bluetooth is off"))
            return
        }

        emitState(RadioTransport.State.Scanning)
        scanner = adapter.bluetoothLeScanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    stopScan()
                    connectGatt(device)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.firstOrNull()?.device?.let { device ->
                    stopScan()
                    connectGatt(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                emitState(RadioTransport.State.Error("BLE scan failed: $errorCode"))
            }
        }
        scanCallback = callback
        scanner?.startScan(listOf(filter), settings, callback)
    }

    override fun disconnect() {
        stopScan()
        gatt?.close()
        gatt = null
        toRadio = null
        emitState(RadioTransport.State.Disconnected)
    }

    override fun send(packet: Packet) {
        val bytes = Codec.encode(packet)
        // TODO: chunk and send via Meshtastic ToRadio protobuf once the
        //       Meshtastic app-port API is wired up. For now this is a hook.
    }

    private fun hasPermissions(): Boolean {
        val scan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.BLUETOOTH
        }
        val connect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(context, scan) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, connect) == PackageManager.PERMISSION_GRANTED
    }

    private fun stopScan() {
        scanCallback?.let { scanner?.stopScan(it) }
        scanCallback = null
    }

    private fun connectGatt(device: BluetoothDevice) {
        emitState(RadioTransport.State.Connecting)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            emitState(RadioTransport.State.Error("Missing BLUETOOTH_CONNECT permission"))
            return
        }

        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        gatt.discoverServices()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        this@MeshtasticBleDriver.gatt?.close()
                        this@MeshtasticBleDriver.gatt = null
                        emitState(RadioTransport.State.Disconnected)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitState(RadioTransport.State.Error("Service discovery failed: $status"))
                    return
                }

                val service = gatt.getService(serviceUuid)
                if (service == null) {
                    emitState(RadioTransport.State.Error("Meshtastic service not found"))
                    return
                }

                toRadio = service.getCharacteristic(toRadioUuid)
                val fromRadio = service.getCharacteristic(fromRadioUuid)
                val fromNum = service.getCharacteristic(fromNumUuid)

                // Request notifications on FromNum to learn when FromRadio has data.
                // TODO(ble): also write the CCCD descriptor, request an MTU, and
                //       read FromRadio when FromNum notifies. Until then no data
                //       arrives on a real node.
                if (fromNum != null) {
                    gatt.setCharacteristicNotification(fromNum, true)
                }

                emitState(RadioTransport.State.Connected(gatt.device?.name))
            }

            // API < 33 delivers values through this overload only.
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                onFromRadioChanged(characteristic, characteristic.value ?: ByteArray(0))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                onFromRadioChanged(characteristic, value)
            }
        })
    }

    private fun onFromRadioChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == fromRadioUuid) {
            // TODO: decode Meshtastic FromRadio protobuf, extract app
            //       port payload, Codec.decode(bytes), onReceive(packet)
        }
    }

    private fun emitState(newState: RadioTransport.State) {
        _state.value = newState
        onStateChanged(newState)
    }
}
