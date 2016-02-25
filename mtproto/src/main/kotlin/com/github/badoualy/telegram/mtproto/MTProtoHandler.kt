package com.github.badoualy.telegram.mtproto

import com.github.badoualy.telegram.mtproto.auth.AuthKey
import com.github.badoualy.telegram.mtproto.auth.AuthResult
import com.github.badoualy.telegram.mtproto.exception.RpcErrorException
import com.github.badoualy.telegram.mtproto.secure.MTProtoMessageEncryption
import com.github.badoualy.telegram.mtproto.secure.RandomUtils
import com.github.badoualy.telegram.mtproto.time.MTProtoTimer
import com.github.badoualy.telegram.mtproto.time.TimeOverlord
import com.github.badoualy.telegram.mtproto.tl.*
import com.github.badoualy.telegram.mtproto.transport.MTProtoConnection
import com.github.badoualy.telegram.mtproto.transport.MTProtoTcpConnection
import com.github.badoualy.telegram.mtproto.util.Log
import com.github.badoualy.telegram.tl.StreamUtils
import com.github.badoualy.telegram.tl.api.TLAbsUpdates
import com.github.badoualy.telegram.tl.api.TLApiContext
import com.github.badoualy.telegram.tl.core.TLMethod
import com.github.badoualy.telegram.tl.core.TLObject
import com.github.badoualy.telegram.tl.exception.DeserializationException
import org.apache.commons.lang3.StringUtils
import rx.Observable
import rx.Subscriber
import rx.schedulers.Schedulers
import java.io.IOException
import java.math.BigInteger
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MTProtoHandler {

    private var TAG = "MTProtoHandler"
    private val ACK_BUFFER_SIZE = 15
    private val ACK_BUFFER_TIMEOUT: Long = 60 * 1000

    private val mtProtoContext = MTProtoContext
    private val apiContext = TLApiContext.getInstance()

    private var connection: MTProtoConnection? = null
    var authKey: AuthKey? = null
        private set
    var sessionId: ByteArray? = null
        private set
    var salt: Long = 0
        private set

    private val subscriberMap = Hashtable<Long, Subscriber<TLObject>>(10)
    private val sentMessageList = ArrayList<MTMessage>(10)
    private var messageToAckList = ArrayList<Long>(ACK_BUFFER_SIZE)

    private var crMessageSent = 0 // Number of content related message sent
    private var lastMessageId: Long = 0

    private var bufferTimeoutTask: TimerTask? = null
    private var bufferId = 0

    private val apiCallback: ApiCallback?

    constructor(authResult: AuthResult, apiCallback: ApiCallback?) {
        connection = authResult.connection
        authKey = authResult.authKey
        this.salt = authResult.serverSalt
        this.apiCallback = apiCallback

        init()
    }

    constructor(dataCenter: DataCenter, authKey: AuthKey, salt: Long?, apiCallback: ApiCallback?) {
        connection = MTProtoTcpConnection(dataCenter.ip, dataCenter.port)
        this.authKey = authKey
        this.salt = salt ?: 0
        this.apiCallback = apiCallback

        init()
    }

    private fun init() {
        // see https://core.telegram.org/mtproto/description#session
        // a (random) 64-bit number generated by the client
        sessionId = RandomUtils.randomSessionId()
        TAG = "MTProtoHandler#${BigInteger(sessionId).toLong()}"
        Log.d(TAG, "New session id created")
        lastMessageId = 0
        crMessageSent = 0
    }

    fun startWatchdog() {
        MTProtoWatchdog.start(connection!!)
                .observeOn(Schedulers.computation())
                .doOnError {
                    // TODO: handle
                    Log.e(TAG, "FIX ME PLEASE")
                    it.printStackTrace()
                }
                .doOnNext { onMessageReceived(it) }
                .subscribe()
    }

    private fun stopWatchdog() = MTProtoWatchdog.stop(connection!!)

    /** Close the connection and re-open another one with a new session id */
    fun resetConnection() {
        Log.e(TAG, "Reset connection...")
        try {
            stopWatchdog()
            connection!!.close()
            subscriberMap.clear()
            sentMessageList.clear()
            messageToAckList.clear()
        } catch (e: IOException) {
        }

        connection = MTProtoTcpConnection(connection!!.ip, connection!!.port)
        init()
        startWatchdog()
    }

    /** Properly close the connection to Telegram's server after sending ACK for messages if any to send */
    fun close() {
        bufferTimeoutTask?.cancel()
        onBufferTimeout(bufferId)
        stopWatchdog()
        try {
            connection!!.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class)
    fun <T : TLObject> executeMethodSync(method: TLMethod<T>, timeout: Long) = executeMethod(method, timeout).toBlocking().first()

    /**
     * Execute the given method, generates a message id, serialize the method, encrypt it then send it
     * @param method method to execute
     * @param timeout timeout before returning an error
     * @param T response type
     * @return an observable that will receive one unique item being the response
     * @throws IOException
     */
    @Throws(IOException::class)
    fun <T : TLObject> executeMethod(method: TLMethod<T>, timeout: Long): Observable<T> {
        val observable = Observable.create<T> { subscriber ->
            Log.d(TAG, "executeMethod ${method.toString()}")
            try {
                val extra = getExtraToSend()

                val msgId = generateMessageId()
                val methodMessage = MTMessage(msgId, generateSeqNo(method), method.serialize())
                Log.d(TAG, "Sending method with msgId ${methodMessage.messageId} and seqNo ${methodMessage.seqNo}")

                if (extra.isNotEmpty()) {
                    val container = MTMessagesContainer()
                    container.messages.addAll(extra)
                    container.messages.add(methodMessage)
                    sendMessage(MTMessage(generateMessageId(), generateSeqNo(container), container.serialize()))
                } else
                    sendMessage(methodMessage)

                // Everything went OK, save subscriber for later retrieval
                @Suppress("UNCHECKED_CAST")
                val s = subscriber as Subscriber<TLObject>
                subscriberMap.put(msgId, s)
            } catch (e: IOException) {
                subscriber.onError(e)
            }
        }
        return observable.timeout(timeout, TimeUnit.MILLISECONDS)
    }

    /**
     * Acknowledge the given message id to the server. The request may be sent later, it is added to a queue, the queue of messages
     * will be sent when a method is executed, or when a timeout value has passed since the first element of the queue was added,
     * or if the queue is full
     *
     * @param messageId message id to acknowledge
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun sendMessageAck(messageId: Long) {
        var flush = false
        var startTimer = false
        var list: ArrayList<Long>? = null
        var id: Int = -1

        synchronized(messageToAckList) {
            list = messageToAckList
            list!!.add(messageId)
            Log.d(TAG, "Adding msgId $messageId to bufferId $bufferId")
            id = bufferId

            if (list!!.size == 1)
                startTimer = true
            else if (list!!.size < ACK_BUFFER_SIZE)
                return
            else {
                messageToAckList = ArrayList<Long>(ACK_BUFFER_SIZE)
                bufferId++
                flush = true
            }
        }

        if (startTimer) {
            try {
                bufferTimeoutTask = MTProtoTimer.schedule(ACK_BUFFER_TIMEOUT, { onBufferTimeout(id) })
            } catch(e: IllegalStateException) {
                // TODO: remove Timer use
                // Timer already cancelled.
            }
        }
        if (flush) {
            bufferTimeoutTask?.cancel()
            bufferTimeoutTask = null
            sendMessagesAck(list!!.toLongArray())
        }
    }

    /** If buffer timed out, check that the relevant buffer wasn't already flushed, and if not, flush it */
    private fun onBufferTimeout(id: Int) {
        if (!(connection?.isOpen() ?: false))
            return

        var list: ArrayList<Long>? = null

        synchronized(messageToAckList) {
            if (id != bufferId) {
                // Already flushed
                return
            }

            list = messageToAckList
            messageToAckList = ArrayList<Long>(ACK_BUFFER_SIZE)
            bufferId++
        }

        sendMessagesAck(list!!.toLongArray())
    }

    /**
     * Send acknowledgment request to server for the given messages
     *
     * @param messagesId message id to acknowledge
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun sendMessagesAck(messagesId: LongArray) {
        if (messagesId.isEmpty())
            return

        val ackMessage = MTMsgsAck(messagesId)
        val ackMessageId = generateMessageId()
        Log.d(TAG, "Send ack for messages " + messagesId.joinToString(", ") + " with ackMsgId " + ackMessageId)
        sendMessage(MTMessage(ackMessageId, generateSeqNo(ackMessage), ackMessage.serialize()))
    }

    /**
     * Send a message after encrypting it
     * @param message message to encrypt then send
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun sendMessage(message: MTMessage) {
        Log.d(TAG, "Sending message with msgId " + message.messageId + " and seqNo " + message.seqNo)
        val encryptedMessage = MTProtoMessageEncryption.encrypt(authKey!!, sessionId!!, salt, message)
        sendData(encryptedMessage.data)
        sentMessageList.add(message)
    }

    /**
     * Send data using the connection
     * @param data data to send
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun sendData(data: ByteArray) = connection!!.writeMessage(data)

    /** Build a container with all the extras to send with a method invocation called */
    private fun getExtraToSend(): Array<MTMessage> {
        // Collect messages to ack
        var toAckList: ArrayList<Long>? = null
        synchronized(messageToAckList) {
            toAckList = messageToAckList
            if (messageToAckList.isNotEmpty()) {
                messageToAckList = ArrayList<Long>(ACK_BUFFER_SIZE)
                bufferId++
                bufferTimeoutTask?.cancel()
                bufferTimeoutTask = null
            }
        }

        if (toAckList?.size ?: 0 > 0) {
            val ack = MTMsgsAck(toAckList!!.toLongArray())
            val ackMessage = MTMessage(generateMessageId(), generateSeqNo(ack), ack.serialize())
            Log.d(TAG, "Adding extra: ack for messages ${toAckList!!.joinToString(", ")} with msgId ${ackMessage.messageId} and seqNo ${ackMessage.seqNo}")
            return arrayOf(ackMessage)
        }

        return emptyArray()
    }

    /**
     * Checks if the given object is content-related (useful for seqNo generation)
     * @param clazz object type to check
     * @return true if the object is content related, else false
     */
    private fun isContentRelated(clazz: Class<out TLObject>) = !clazz.simpleName.startsWith("MT")

    private fun isContentRelated(message: TLObject) = isContentRelated(message.javaClass)

    /**
     * Generate a valid seqNo value for the given message type
     * @param clazz message type
     * @return a valid seqNo value to send
     * @see <a href="https://core.telegram.org/mtproto/description#message-sequence-number-msg-seqno">MTProto description</a>
     */
    private fun generateSeqNo(clazz: Class<out TLObject>) =
            if (isContentRelated(clazz)) {
                val seqNo = crMessageSent * 2 + 1
                crMessageSent++
                seqNo
            } else {
                crMessageSent * 2
            }

    private fun generateSeqNo(message: TLObject) = generateSeqNo(message.javaClass)

    private fun generateMessageId(): Long {
        lastMessageId = Math.max(TimeOverlord.generateMessageId(connection!!.dataCenter), lastMessageId + 4)
        return lastMessageId
    }

    private fun onMessageReceived(bytes: ByteArray) {
        var message: MTMessage = MTMessage()
        try {
            if (bytes.size == 4)
                throw RpcErrorException(MTRpcError(StreamUtils.readInt(bytes)))

            message = MTProtoMessageEncryption.decrypt(authKey!!, sessionId!!, bytes)
            Log.d(TAG, "Received msg ${message.messageId} with seqNo ${message.seqNo}")

            // Check if is a container
            when (StreamUtils.readInt(message.payload)) {
                MTMessagesContainer.CONSTRUCTOR_ID -> {
                    Log.d(TAG, "Message is a container")
                    val container = mtProtoContext.deserializeMessage(message.payload, MTMessagesContainer::class.java, MTMessagesContainer.CONSTRUCTOR_ID)
                    Log.d(TAG, "Container has ${container.messages.size} items")
                    if (container.messages.firstOrNull() { m -> m.messageId >= message.messageId } != null)
                        throw SecurityException("Message contained in container has a same or greater msgId than container, ignoring whole container")

                    for (msg in container.messages)
                        handleMessage(msg)
                }
                else -> handleMessage(message)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Dump");
            Log.e(TAG, StreamUtils.toHexString(message.payload))
            if (subscriberMap.size == 1) {
                // We only have 1 method executing, it means that this is the one that failed
                subscriberMap[subscriberMap.keys.first()]?.onError(e)
            } else {
                // TODO: cleaner way ?
                throw RuntimeException(e)
            }
        }
    }

    @Throws(DeserializationException::class, IOException::class)
    private fun deserializeMessageContent(message: MTMessage): TLObject {
        // Default container, handle content
        val classId = StreamUtils.readInt(message.payload)
        if (mtProtoContext.isSupportedObject(classId))
            return mtProtoContext.deserializeMessage(message.payload)

        return apiContext.deserializeMessage(message.payload)
    }

    @Throws(IOException::class)
    private fun handleMessage(message: MTMessage) {
        val messageContent = deserializeMessageContent(message)
        Log.d(TAG, "handle ${messageContent.toString()}")

        when (messageContent) {
            is MTMsgsAck -> {
                Log.d(TAG, "Received ack for ${StringUtils.join(messageContent.messages, ", ")}")
                // TODO check missing ack ?
            }
            is MTRpcResult -> {
                handleResult(messageContent)
                sendMessageAck(message.messageId)
            }
            is MTRpcError -> {
                Log.e(TAG, "RpcError ${messageContent.errorCode}: ${messageContent.message}")
                throw IllegalStateException("RpcError handled in handleMessage()")
                // This should never happen, it should always be contained in MTRpcResult
            }
            is TLAbsUpdates -> updatePool.execute { apiCallback?.onUpdates(messageContent) }
            is MTNewSessionCreated -> {
                //salt = message.serverSalt
                sendMessageAck(message.messageId)
            }
            is MTBadMessageNotification -> handleBadMessage(messageContent, message)
            is MTBadServerSalt -> {
                Log.e(TAG, messageContent.toPrettyString())

                // Message contains a good salt to use
                salt = messageContent.newSalt
                apiCallback?.onSalt(salt)

                // Resend message with good salt
                val sentMessage = sentMessageList.filter { it.messageId == messageContent.badMsgId }.firstOrNull()
                if (sentMessage != null) {
                    Log.d(TAG, "Re-sending message ${messageContent.badMsgId} with new salt")
                    sendMessage(sentMessage)
                } else {
                    Log.e(TAG, "Couldn't find sentMessage in history with msgId ${messageContent.badMsgId}, can't re-send with good salt")
                }
            }
            is MTNeedResendMessage -> {
                // TODO
            }
            is MTNewMessageDetailedInfo -> {
                // TODO
            }
            is MTMessageDetailedInfo -> {
                // TODO
            }
            is MTFutureSalts -> {
                // TODO
            }
            is MTFutureSalt -> {
                // TODO
            }
            else -> throw RuntimeException("Unsupported case ${messageContent.javaClass.simpleName} ${messageContent.toString()}")
        }
    }

    @Throws(IOException::class)
    private fun handleBadMessage(badMessage: MTBadMessageNotification, container: MTMessage) {
        Log.e(TAG, badMessage.toPrettyString())

        when (badMessage.errorCode) {
            MTBadMessage.ERROR_MSG_ID_TOO_LOW, MTBadMessage.ERROR_MSG_ID_TOO_HIGH -> {
                lastMessageId = 0
                TimeOverlord.synchronizeTime(connection!!.dataCenter, container.messageId)

                // Resend message with good salt
                val sentMessage = sentMessageList.filter { it.messageId == badMessage.badMsgId }.firstOrNull()
                if (sentMessage != null) {
                    // Update map and generate new msgId
                    val subscriber = subscriberMap.remove(sentMessage.messageId)
                    sentMessage.messageId = generateMessageId()
                    subscriberMap.put(sentMessage.messageId, subscriber)

                    Log.d(TAG, "Re-sending message ${badMessage.badMsgId} with new msgId ${sentMessage.messageId}")
                    sendMessage(sentMessage)
                } else {
                    Log.e(TAG, "Couldn't find sentMessage in history with msgId ${badMessage.badMsgId}, can't re-send with good msgid")
                }
            }
            MTBadMessage.ERROR_MSG_ID_MODULO -> {

            }
            MTBadMessage.ERROR_SEQNO_TOO_LOW -> {

            }
            MTBadMessage.ERROR_SEQNO_TOO_HIGH -> {

            }
            MTBadMessage.ERROR_SEQNO_EXPECTED_EVEN -> {

            }
            MTBadMessage.ERROR_SEQNO_EXPECTED_ODD -> {

            }
            else -> Log.e(TAG, "Unknown error code: " + badMessage.toPrettyString())
        }
    }

    @Throws(IOException::class)
    private fun handleResult(result: MTRpcResult) {
        Log.d(TAG, "Got result for msgId " + result.messageId)

        val subscriber =
                if (subscriberMap.containsKey(result.messageId))
                    subscriberMap.remove(result.messageId)!!
                else {
                    Log.e(TAG, "No subscriber found for msgId ${result.messageId}")
                    null
                }

        val classId = StreamUtils.readInt(result.content)
        Log.d(TAG, "Response is a " + classId)
        if (mtProtoContext.isSupportedObject(classId)) {
            val resultContent = mtProtoContext.deserializeMessage(result.content)
            if (resultContent is MTRpcError) {
                Log.e(TAG, "rpcError ${resultContent.errorCode}: ${resultContent.message}")
                subscriber?.onError(RpcErrorException(resultContent))
            }
        } else {
            subscriber?.onNext(apiContext.deserializeMessage(result.content))
        }

        subscriber?.onCompleted()
    }

    companion object {

        /** Thread pool to forward update callback */
        val updatePool = Executors.newFixedThreadPool(8)

        /** Cleanup all the threads and common resources associated to this instance */
        @JvmStatic
        fun cleanUp() {
            MTProtoWatchdog.cleanUp()
            MTProtoTimer.shutdown()
        }
    }
}
