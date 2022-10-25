package com.kroko.bmsmonitor

import android.bluetooth.*
import android.bluetooth.BluetoothAdapter.STATE_CONNECTED
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import kotlin.math.min

private fun log(tag: String, msg: String) {
    //Log.d(tag, msg)
}

@ExperimentalUnsignedTypes
class Em3evBms(private val ctx: Context, private val device: BluetoothDevice, val updateHandler: DataUpdateHandler) : BluetoothGattCallback() {
    private val commandBuilder = CommandBuilder()
    private val bmsData = BmsData()

    var gatt: BluetoothGatt? = null
    lateinit var queuedSender: QueuedSender
    lateinit var commandReceiver: CommandReceiver

    private val UUID_BMS_SERVICE : UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val UUID_BMS_NOTIFY_CHARACTERISTIC : UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
    private val UUID_BMS_NOTIFY_DESCRIPTOR : UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val UUID_BMS_WRITE_CHARACTERISTIC : UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")

    enum class State {
        WAITING_FOR_CONNECTION,
        SERVICE_DISCOVERY,
        ENABLING_NOTIFICATIONS,
        UNLOCKING_BMS,
        READY
    }
    var state = State.WAITING_FOR_CONNECTION
    var refreshIndex = 0

    interface DataUpdateHandler {
        fun onDataChanged(data : BmsData)
        fun debug(str: String)
    }

    fun init() {
        if (state != State.WAITING_FOR_CONNECTION) {
            return;
        }
        val g = device.connectGatt(ctx, true, this)
        updateHandler.debug("connectGatt done. Services: ${g.services} -- Device ${g.device} name=${g.device.name}")
        gatt = g
        object: Runnable {
            override fun run () {
                if (gatt == null) {
                    return
                }
                nextRefresh()
                Handler(Looper.getMainLooper()).postDelayed(this, 1000)
            }
        }.run()
    }

    fun stop() {
        Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
        gatt?.close()
        gatt = null
    }

    private fun nextRefresh() {
        if (state != State.READY || queuedSender.isBusy() || commandReceiver.isBusy()) {
            return
        }
        log("Em3evBms", "nextRefresh() idx=$refreshIndex")
        when (refreshIndex++) {
            0 -> enqueueCommand(commandBuilder.requestStats1())
            1 -> enqueueCommand(commandBuilder.requestStats2())
            2 -> enqueueCommand(commandBuilder.requestStats3())
            else -> refreshIndex = 0 // don't do anything this cycle
        }
    }

    override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
        updateHandler.debug("onConnectionStateChanged. status=$status newState=$newState")
        if (newState == STATE_CONNECTED && state == State.WAITING_FOR_CONNECTION) {
            state = State.SERVICE_DISCOVERY
            g!!.discoverServices()
        }
    }
    override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
        updateHandler.debug("Services discovered status=$status: ${g?.services?.size} for device = ${g?.device}")
        if (g?.getService(UUID_BMS_SERVICE) != null) {
            enableNotifications()
        }
    }

    private fun createQueueSender() : QueuedSender {
        val writeCharacteristic = gatt!!.getService(UUID_BMS_SERVICE).getCharacteristic(UUID_BMS_WRITE_CHARACTERISTIC)
        val sendRawBytes : (UByteArray) -> Unit = {
            writeCharacteristic.value = it.toByteArray()
            writeCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            object : Runnable {
                override fun run() {
                    val res = gatt!!.writeCharacteristic(writeCharacteristic)
                    updateHandler.debug("sendRawBytes() res=$res data=${it.toHexString()}")
                    if (!res) {
                        Handler(Looper.getMainLooper()).postDelayed(this, 250)
                    }
                }
            }.run()
        }
        return QueuedSender(sendRawBytes)
    }

    private fun enableNotifications() {
        queuedSender = createQueueSender()
        commandReceiver = CommandReceiver(queuedSender, ::onPacketReceived)

        val g = gatt!!
        val notifyCharacteristic = g.getService(UUID_BMS_SERVICE).getCharacteristic(UUID_BMS_NOTIFY_CHARACTERISTIC)
        val charWriteSuccess = g.setCharacteristicNotification(notifyCharacteristic, true)

        val notifyDescriptor = g.getService(UUID_BMS_SERVICE).getCharacteristic(UUID_BMS_NOTIFY_CHARACTERISTIC).getDescriptor(UUID_BMS_NOTIFY_DESCRIPTOR)
        notifyDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val descWriteSuccess = g.writeDescriptor(notifyDescriptor)

        state = State.ENABLING_NOTIFICATIONS
        updateHandler.debug("enableNotifications enableNotifications charWriteSuccess=$charWriteSuccess descWriteSuccess=$descWriteSuccess")
    }

    override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
    ) {
        updateHandler.debug("onDescriptorWrite: ${descriptor?.uuid} status=$status")
        when (state) {
            State.ENABLING_NOTIFICATIONS -> {
                enqueueCommand(commandBuilder.unlockCommand(device))
                state = State.UNLOCKING_BMS
            }
        }
    }

    override fun onCharacteristicChanged(
        g: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        when (characteristic?.uuid) {
            UUID_BMS_NOTIFY_CHARACTERISTIC -> onDataReceived(characteristic.value.toUByteArray())
            else -> updateHandler.debug("onCharacteristicChanged: uuid=${characteristic!!.uuid} inst=${characteristic!!.instanceId} value=${characteristic!!.value.toUByteArray().toHexString()}")
        }
    }

    private fun enqueueCommand(data: UByteArray) {
        log("Em3evBms", "Enqueuing bytes: ${data.toHexString()}")
        queuedSender.enqueue(data)
    }

    private fun onDataReceived(data: UByteArray) {
        log("Em3evBms", "Data received: ${data.toHexString()}")
        when (data[0].and(0x80u)) {
            0x80u.toUByte() -> queuedSender.handleAck(data)
            else -> commandReceiver.onDataReceived(data)
        }
    }

    private fun onPacketReceived(cmd: CommandReceiver.BmsToPhoneCmd, rx: UByteArray) {
        when (cmd) {
            CommandReceiver.BmsToPhoneCmd.TokenCorrect -> state = State.READY
            CommandReceiver.BmsToPhoneCmd.AlreadyUnlocked -> state = State.READY
            CommandReceiver.BmsToPhoneCmd.Stats1 -> BinaryReader(rx)
                .skip(2)
                .readUShort { bmsData.voltage = it / 10.0 }
                .readUShort { bmsData.current = it / 10.0 }
                .readUShort { bmsData.fullChargeCapacity = it }
                .readUShort { bmsData.remainingChargeCapacity = it }
                .readUByte { bmsData.remainingChargePercent = it }
            CommandReceiver.BmsToPhoneCmd.Stats2 -> BinaryReader(rx)
                .skip(2)
                .readUShort { bmsData.noChargeTime = it }
                .readUShort { bmsData.cycleCount = it }
                .readUShort { bmsData.factoryCapacity = it }
                .readUShort { bmsData.temperature = (it / 10.0) - 273.15 /* Kelvin to Celsius */}
            CommandReceiver.BmsToPhoneCmd.Stats3 -> {
                val reader = BinaryReader(rx).skip(2)
                bmsData.numCells = reader.readUByte()
                bmsData.cellVoltages = (1..bmsData.numCells).map { reader.readUShort() }.toIntArray()
            }
        }
        updateHandler.onDataChanged(bmsData)
    }

    class BmsData {
        // Stats1 (0x60):
        var voltage = 0.0 // V
        var current = 0.0 // in A
        var fullChargeCapacity = 0 // mA
        var remainingChargeCapacity = 0 // mA
        var remainingChargePercent = 0 // mA

        // Stats2 (0x61):
        var noChargeTime = 0 // ???
        var cycleCount = 0
        var factoryCapacity = 0 // mA
        var temperature = 0.0 // C

        // Stats3 (0x63):
        var numCells = 0
        var cellVoltages : IntArray = intArrayOf()

        override fun toString(): String {
            return "BmsData(voltage=$voltage, current=$current, fullChargeCapacity=$fullChargeCapacity, remainingChargeCapacity=$remainingChargeCapacity, remainingChargePercent=$remainingChargePercent, noChargeTime=$noChargeTime, cycleCount=$cycleCount, factoryCapacity=$factoryCapacity, temperature=$temperature, numCells=$numCells, cellVoltages=${cellVoltages.contentToString()})"
        }

    }
    class CommandBuilder {
        enum class PhoneToBmsCmd(val cmd1: UByte, val cmd2: UByte){
            UnlockBms(0x05u, 0x65u),
            BmsStats1(0x05u, 0x60u),
            BmsStats2(0x05u, 0x61u),
            BmsStats3(0x05u, 0x62u),
            flashRead(0x05u, 0x14u),
            i2cRead(0x06u, 0x18u),
        }

        fun unlockCommand(device: BluetoothDevice) : UByteArray {
            //   881 Phone -> BMS @ 0xFFF3: 0c 11 3a 03 05 65 00 02[01 94 01 04]0d 0a 00 00 f0 76 3d 0c
            check(device.name.isNotEmpty())
            val lastFourDigits = device.name.substring(device.name.length - 4); // e.g. 0194
            val id = lastFourDigits.toUInt(16)
            val pw1 = (id shr 8).toUByte()
            val pw2 = id.toUByte()
            val checksum : UInt = (pw1 + pw2 + 111u)
            return buildCommand(
                    PhoneToBmsCmd.UnlockBms,
                    0x00u, 0x02u,
                    pw1, pw2, (checksum shr 8).toUByte(), checksum.toUByte())
        }

        fun requestStats1() : UByteArray {
            //   889 Phone -> BMS @ 0xFFF3 -- GetStats:   0a 11 3a 03 05 60 00 00 00 68 0d 0a 00 00 00 00 00 00 00 00
            return buildCommand(PhoneToBmsCmd.BmsStats1, 0x00u, 0x00u, 0x00u, 0x68u)
        }

        fun requestStats2() : UByteArray {
            // 900 Phone -> BMS        --  ExtStats     0a 11 3a 03 05 61 00 00 00 69 0d 0a 75 00 00 00 90 12 3c 0c
            return buildCommand(PhoneToBmsCmd.BmsStats2, 0x00u, 0x00u, 0x00u, 0x69u)
        }

        fun requestStats3() : UByteArray {
            // 910 Phone -> BMS        -- ReqMoarStats  0a 11 3a 03 05 62 00 00 00 6a 0d 0a 00 00 00 00 00 00 00 00
            return buildCommand(PhoneToBmsCmd.BmsStats3, 0x00u, 0x00u, 0x00u, 0x6Au)
        }

        private fun buildCommand(cmd: PhoneToBmsCmd, vararg data: UByte) : UByteArray =
           buildBytes(0x3Au, 0x03u, cmd.cmd1, cmd.cmd2, *data, 0x0Du, 0x0Au)

        private fun buildBytes(vararg data: UByte) : UByteArray = data
    }
    class QueuedSender(val sendRawBytes: (UByteArray) -> Unit) {
        private val queuedPages: MutableList<UByteArray> = mutableListOf()
        private var isWaitingForAck: Boolean = false
        private var lastSentPage: UByteArray? = null
        private var resendHandler = Handler(Looper.getMainLooper())

        fun enqueue(data: UByteArray) {
            for (i in 1..numPages(data)) {
                queuedPages.add(buildPage(data, i))
            }
            trySend()
        }
        fun enqueueAck(rx: UByteArray) {
            check(rx.size == 20) {"Expected 20 byte packet, was ${rx.size}" }
            rx[0] = rx[0].or(0x80u)
            queuedPages.add(0, rx)
            trySend()
        }

        fun isBusy() : Boolean {
            return isWaitingForAck || queuedPages.isNotEmpty()
        }

        fun handleAck(buf: UByteArray) {
            if (!isWaitingForAck) {
                log("QueuedSender", "Received unexpected ack: ${buf.toHexString()}")
                return
            }
            val expectedPage = lastSentPage!!
            val len = buf[0].and(0x80u.toUByte().inv())
            check(expectedPage[0] == len) { "ACK Length mismatch: expected=$expectedPage[0] got=$len"}
            check(expectedPage[1] == buf[1]) { "ACK Pages mismatch: expected=$expectedPage[0] got=$len"}
            log("QueueSender", "Received valid ACK")
            isWaitingForAck = false
            trySend();
        }

        private fun trySend() {
            if (isWaitingForAck) return
            if (queuedPages.isEmpty()) return
            val page = queuedPages.removeAt(0)
            if (page[0].and(0x80u) == 0x00u.toUByte()) isWaitingForAck = true
            lastSentPage = page
            sendRawPage(page)
        }

        private fun sendRawPage(page: UByteArray) {
            // Resending pages doesn't seem to actually work
            /*resendHandler.removeCallbacksAndMessages(null)
            resendHandler.postDelayed({
                log("QueueSender", "Resending page: ${page.toHexString()}")
                sendRawPage(page)
            }, 5000)*/
            sendRawBytes(page)
        }

        private fun numPages(data : UByteArray) : Int {
            val numPages = (data.size) / 18
            return when (data.size % 18 != 0) {
                true -> numPages + 1
                else -> numPages
            }
        }

        private fun buildPage(data : UByteArray, page: Int) : UByteArray {
            val start = (page-1) * 18
            val pageLen = min(18, data.size - start)

            // tx[0] = numBytesWritten (bufferLen % 18, but explicitly set to "18" if len==18)
            // tx[1] = (currentPage << 4) | (maxPages & 0xF)
            // tx[2..X] = content of txBuffer (see below)
            var tx : UByteArray = UByteArray(20)
            tx[0] = pageLen.toUByte()
            tx[1] = (page.shl(4)).or(numPages(data)).toUByte()
            for (i in 0 until pageLen) {
                tx[2 + i] = data[start + i]
            }
            log("QueuedSender", "buildPage($page): ${tx.toHexString()}")
            return tx
        }
    }
    class BinaryReader(private val buf: UByteArray) {
        var i = 0

        fun skip(n : Int) : BinaryReader {
            i += n
            return this
        }

        fun readUByte() : Int {
            return buf[i++].toInt()
        }

        fun readUShort() : Int {
            val high = readUByte()
            val low = readUByte()
            return high.shl(8).or(low)
        }

        fun readUByte(fn : (Int) -> Unit) : BinaryReader {
            fn(readUByte())
            return this
        }

        fun readUShort(fn : (Int) -> Unit) : BinaryReader {
            fn(readUShort())
            return this
        }
    }
    class CommandReceiver(
            private val queuedSender: QueuedSender,
            val onPacketReceived: (BmsToPhoneCmd, UByteArray) -> Unit) {
        enum class BmsToPhoneCmd(val cmd: UByte){
            RamReadAck(0x11u),
            FlashReadAck(0x15u),
            I2cReadAck(0x19u),
            I2cWriteAck(0x21u),
            TokenCorrect(0x32u),
            TokenWrong(0x33u),
            Stats1(0x60u),
            Stats2(0x61u),
            Stats3(0x62u),
            AlreadyUnlocked(0x65u),
        }

        private val currentPacket = mutableListOf<UByte>()
        private var lastPage = 0
        private val timeoutHandler = Handler(Looper.getMainLooper())

        fun isBusy() : Boolean {
            return lastPage != 0
        }

        fun onDataReceived(data: UByteArray) {
            val len = data[0].toInt()
            val curPage = data[1].toUInt().shr(4).toInt()
            val maxPage = data[1].and(0x0Fu).toInt()
            val actualData = data.copyOfRange(2, 2 + len)

            if (lastPage + 1 != curPage) {
                log("CommandReceiver", "Received out of band page: got=$curPage lastPage=$lastPage data=${data.toHexString()}")
                sendAck(data)
                return
            }

            timeoutHandler.removeCallbacksAndMessages(null)
            log("CommandReceiver", "len=$len curPage=$curPage maxPage=$maxPage data=${actualData.toHexString()}")
            currentPacket.addAll(actualData)
            lastPage = curPage
            if (curPage >= maxPage) {
                onPacketFinished(currentPacket.toUByteArray())
                currentPacket.clear()
                lastPage = 0
            } else {
                timeoutHandler.postDelayed({
                    log("CommandReceiver", "No response received within timeout; abandoning packet")
                    currentPacket.clear()
                    lastPage = 0
                }, 5000)
            }
            sendAck(data)
        }

        private fun sendAck(data: UByteArray) {
            log("CommandReceiver", "sending ACK")
            queuedSender.enqueueAck(data)
        }

        private fun onPacketFinished(rx: UByteArray) {
            check(rx[0] == 0x3Au.toUByte()) {"rx mismatch: ${rx.toHexString()}"}
            check(rx[1] == 0x05u.toUByte()) {"rx mismatch: ${rx.toHexString()}"}
            check(rx[2] == 0x03u.toUByte()) {"rx mismatch: ${rx.toHexString()}"}
            val cmd = BmsToPhoneCmd.values().singleOrNull() { it.cmd == rx[3] }
            log("CommandReceiver", "OnPacketReceived: cmd=$cmd pak=${rx.toHexString()}")
            if (cmd != null)  onPacketReceived(cmd, rx.drop(4).toUByteArray())
        }
    }
}

fun UByteArray.toHexString() = joinToString(" ") {
    it.toString(16).padStart(2, '0')
}
