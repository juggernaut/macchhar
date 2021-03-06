package com.github.juggernaut.muqtti;

import com.github.juggernaut.muqtti.exception.DecodingException;
import com.github.juggernaut.muqtti.exception.MqttException;
import com.github.juggernaut.muqtti.fsm.events.ChannelDisconnectedEvent;
import com.github.juggernaut.muqtti.fsm.events.ChannelWriteReadyEvent;
import com.github.juggernaut.muqtti.fsm.events.ExceptionEvent;
import com.github.juggernaut.muqtti.fsm.events.PacketReceivedEvent;
import com.github.juggernaut.muqtti.packet.MqttPacket;
import com.github.juggernaut.muqtti.packet.Utils;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author ameya
 */
public class MqttChannel implements ChannelListener, Consumer<MqttPacket> {

    private final SocketChannel socketChannel;
    private final SelectionKey selectionKey;
    private final MqttDecoder mqttDecoder;
    private final Actor mqttChannelActor;

    private static final Logger LOGGER = Logger.getLogger(MqttChannel.class.getName());

    // Buffer used when channel is not writeable
    private ByteBuffer writeBuffer;
    private volatile boolean channelWriteable = true;

    // TODO: better timeouts using a timer wheel
    private static final ScheduledExecutorService timerService = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> timerFuture;
    private volatile long keepAliveTimeout = -1;
    private volatile boolean shouldDisconnect = false;

    protected MqttChannel(final SelectionKey selectionKey, MqttDecoder mqttDecoder, Actor mqttChannelActor) {
        this.selectionKey = Objects.requireNonNull(selectionKey);
        this.socketChannel = (SocketChannel) selectionKey.channel();
        this.mqttDecoder = Objects.requireNonNull(mqttDecoder);
        this.mqttChannelActor = Objects.requireNonNull(mqttChannelActor);
    }

    public static MqttChannel create(final SelectionKey selectionKey, final Actor mqttChannelActor) {
        final var mqttDecoder = new MqttDecoder();
        final var mqttChannel = new MqttChannel(selectionKey, mqttDecoder, mqttChannelActor);
        mqttDecoder.setPacketConsumer(mqttChannel);
        return mqttChannel;
    }

    @Override
    public void onRead(ByteBuffer buffer) {
        updateTimer();
        try {
            mqttDecoder.onRead(buffer);
        } catch (final MqttException e) {
            mqttChannelActor.sendMessage(new ExceptionEvent(e));
        } catch (final BufferUnderflowException e) {
            log(Level.FINE, e, () -> "Buffer underflow (malformed packet) occurred during decoding");
            mqttChannelActor.sendMessage(new ExceptionEvent(new DecodingException("Protocol decode error")));
        } catch (final Exception e) {
            log(Level.WARNING, e, () -> "Unhandled exception occurred during decoding");
            // TODO: disconnect here, send message to actor
        }
    }

    private void updateTimer() {
        if (timerFuture != null) {
            timerFuture.cancel(false);
            scheduleKeepAliveTimeout();
        }
    }

    private void cancelTimer() {
        if (timerFuture != null) {
            timerFuture.cancel(false);
        }
    }

    private void scheduleKeepAliveTimeout() {
        timerFuture = timerService.schedule(() -> {
            log(Level.INFO, () -> "Disconnecting channel because keepalive timeout expired");
            try {
                // TODO: should be different for TLS channel
                socketChannel.close();
            } catch (IOException e) {
                log(Level.INFO, e, () -> "Failed to close channel");
            }
        }, keepAliveTimeout, TimeUnit.SECONDS);
    }

    public SocketAddress getRemoteAddress() {
        return Utils.getRemoteAddressUnchecked(socketChannel);
    }

    public void setKeepAliveTimeout(final long timeout) {
        assert timeout > 0 && timeout <= 600;
        // Can only be set once (during CONNECT handshake)
        assert keepAliveTimeout == -1 && timerFuture == null;
        keepAliveTimeout = timeout;
        scheduleKeepAliveTimeout();
    }

    @Override
    public void accept(MqttPacket mqttPacket) {
        log(Level.FINEST, () -> "Successfully decoded packet of type " + mqttPacket.getPacketType());
        mqttChannelActor.sendMessage(new PacketReceivedEvent(mqttPacket));
    }

    public void sendPacket(MqttPacket packet) {
        final ByteBuffer[] encoded = packet.encode();
        write(encoded);
    }

    public void disconnect() {
        if (channelWriteable) {
            closeChannel();
        } else {
            shouldDisconnect = true;
        }
    }

    @Override
    public void onDisconnect() {
        mqttChannelActor.sendMessage(new ChannelDisconnectedEvent());
    }

    public void flushWriteBuffer() {
        assert channelWriteable;
        assert writeBuffer != null;
        writeBuffer.flip();
        write(writeBuffer);
        if (channelWriteable) {
            // this means all of write buffer was written out, allow GC to collect it ASAP
            writeBuffer = null;
            if (shouldDisconnect) {
                closeChannel();
            }
        }
    }

    protected void closeChannel() {
        try {
            cancelTimer();
            socketChannel.close();
        } catch (IOException e) {
            log(Level.WARNING, e, () -> "Failed to close socket channel");
        }
    }

    protected void write(final ByteBuffer buffer) {
        write(new ByteBuffer[]{ buffer });
    }

    protected void write(final ByteBuffer[] buffers) {
        if (channelWriteable) {
            final int expectedWrittenBytes = Arrays.stream(buffers).mapToInt(ByteBuffer::remaining).sum();
            try {
                final long actuallyWrittenBytes = socketChannel.write(buffers);
                if (actuallyWrittenBytes < expectedWrittenBytes) {
                    // uh-oh, reader is slow, we need to buffer..
                    channelWriteable = false;
                    // NOTE: tmp is needed here because writeBuffer itself may be the input buffer
                    final ByteBuffer tmp = ByteBuffer.allocate((int) (expectedWrittenBytes - actuallyWrittenBytes));
                    Arrays.stream(buffers).forEach(tmp::put);
                    writeBuffer = tmp;
                    // Signal our interest in knowing when the channel will be writeable
                    selectionKey.interestOpsOr(SelectionKey.OP_WRITE);
                }
            } catch (IOException e) {
                log(Level.SEVERE, e, () -> "I/O error writing to socket");
            }
        } else {
            enlargeWriteBuffer(buffers);
        }
    }

    private void enlargeWriteBuffer(ByteBuffer[] buffers) {
        assert writeBuffer != null;
        final int totalBuffersLength = Arrays.stream(buffers).mapToInt(ByteBuffer::remaining).sum();
        final ByteBuffer tmp = ByteBuffer.allocate(writeBuffer.position() + totalBuffersLength);
        writeBuffer.flip();
        tmp.put(writeBuffer);
        Arrays.stream(buffers).forEach(tmp::put);
        writeBuffer = tmp;
    }

    @Override
    public void onWriteReady() {
        // First, deregister our interest in OP_WRITE
        selectionKey.interestOpsAnd(~SelectionKey.OP_WRITE);
        channelWriteable = true;
        // This is a little roundabout but we essentially want to funnel all writes through the actor so that
        // we get serialized writes
        mqttChannelActor.sendMessage(new ChannelWriteReadyEvent());
    }

    protected void log(Level level, Throwable t, Supplier<String> messageSupplier) {
        LOGGER.log(level, t, () -> getLogPrefix() + " " + messageSupplier.get());
    }

    protected void log(Level level, Supplier<String> messageSupplier) {
        LOGGER.log(level, () -> getLogPrefix() + " " + messageSupplier.get());
    }

    private String getLogPrefix() {
        return "[remote-address="+ getRemoteAddress() + "]";
    }
}
