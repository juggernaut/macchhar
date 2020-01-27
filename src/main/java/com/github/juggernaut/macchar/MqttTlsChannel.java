package com.github.juggernaut.macchar;

import com.github.juggernaut.macchar.packet.MqttPacket;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * @author ameya
 */
public class MqttTlsChannel extends MqttChannel {

    private final SSLEngine sslEngine;
    private ByteBuffer cipherData = ByteBuffer.allocate(2 * 1024); // these will be adjusted to the correct size dynamically
    private ByteBuffer applicationData = ByteBuffer.allocate(2 * 1024);
    private ByteBuffer outboundData = ByteBuffer.allocate(2 * 1024);

    protected MqttTlsChannel(SocketChannel socketChannel, MqttDecoder mqttDecoder, Actor mqttChannelActor, SSLEngine sslEngine) {
        super(socketChannel, mqttDecoder, mqttChannelActor);
        assert !sslEngine.getUseClientMode();
        this.sslEngine = sslEngine;
    }

    public static MqttTlsChannel create(SocketChannel socketChannel, Actor mqttChannelActor, SSLEngine sslEngine) {
        final var mqttDecoder = new MqttDecoder();
        final var mqttTlsChannel = new MqttTlsChannel(socketChannel, mqttDecoder, mqttChannelActor, sslEngine);
        mqttDecoder.setPacketConsumer(mqttTlsChannel);
        return mqttTlsChannel;
    }

    @Override
    public void onRead(ByteBuffer src) {
        System.out.println("onRead TLS");
        // TODO: It's unlikely that network buffer is larger if we size both correctly, but handle this case for now...
        if (src.remaining() > (cipherData.capacity() - cipherData.position())) {
            System.out.println("Enlarging cipherData");
            ByteBuffer b = ByteBuffer.allocate(src.remaining() + cipherData.position());
            cipherData.flip();
            b.put(cipherData);
            cipherData = b;
        }
        cipherData.put(src);
        cipherData.flip();
        System.out.println("cipher data has " + cipherData.remaining() + " bytes");
        doUnwrap();
    }

    private void doUnwrap() {
        try {
            final SSLEngineResult result = sslEngine.unwrap(cipherData, applicationData);
            if (result.bytesConsumed() == 0 && result.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                System.out.println("Not enough bytes to make a TLS record during handshake");
                return;
            }
            handleHandshake(result.getHandshakeStatus());
            switch(result.getStatus()) {
                case BUFFER_OVERFLOW:
                    int appSize = sslEngine.getSession().getApplicationBufferSize();
                    ByteBuffer b = ByteBuffer.allocate(appSize + applicationData.position());
                    applicationData.flip();
                    b.put(applicationData);
                    applicationData = b;
                    doUnwrap();
                    break;
                case BUFFER_UNDERFLOW:
                    int netSize = sslEngine.getSession().getPacketBufferSize();
                    if (netSize > cipherData.capacity()) {
                        // This happens if the incoming TLS record is greater than our currently allocated byte buffer
                        ByteBuffer tmp = ByteBuffer.allocate(netSize);
                        cipherData.flip();
                        tmp.put(cipherData);
                        cipherData = tmp;
                    } else {
                        // We don't have enough data in cipherdata to make up a tls record; prepare for more incoming data
                        cipherData.compact();
                    }
                    break;
                case OK:
                    if (result.bytesProduced() > 0) {
                        applicationData.flip();
                        super.onRead(applicationData);
                        if (applicationData.hasRemaining()) {
                            System.out.println("Application didn't read all data");
                        }
                        applicationData.clear();
                    }
                    cipherData.compact();
                    break;
                case CLOSED:
                    System.out.println("Channel already closed..");
                    break;
            }
        } catch (SSLException e) {
            e.printStackTrace();
        }

    }

    private void handleHandshake(final SSLEngineResult.HandshakeStatus handshakeStatus) {
        switch(handshakeStatus) {
            case NEED_TASK:
                runDelegatedTasks();
                break;
            case NEED_UNWRAP:
                // TODO: this is debug
                System.out.println("Cipherdata has " + cipherData.remaining() + " bytes");
                //System.out.println("Will wait for more TLS data");
                if (cipherData.hasRemaining()) {
                    doUnwrap();
                }
                break;
            case NEED_WRAP:
                try {
                    sendOutboundHandshakeData();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                break;
            case FINISHED:
                System.out.println("Finished TLS handshake");
                break;
            case NOT_HANDSHAKING:
                break;
            default:
                System.out.println("Unknown handshake status " + handshakeStatus);
        }
    }

    private void sendOutboundHandshakeData() throws IOException {
        SSLEngineResult result;
        do {
            result = doWrap(new ByteBuffer[] { ByteBuffer.allocate(0)});
            if (result.bytesProduced() > 0) {
                outboundData.flip();
                // TODO: handle partial writes
                socketChannel.write(outboundData);
                // TODO: debug
                System.out.println("Wrote " + result.bytesProduced() + " bytes of TLS data to socket");
                outboundData.compact();
            }
        } while (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP);
        handleHandshake(result.getHandshakeStatus());
    }

    private void runDelegatedTasks() {
        Runnable runnable;
        // TODO: do this in a separate threadpool?
        while ((runnable = sslEngine.getDelegatedTask()) != null) {
            runnable.run();
        }
        // TODO: debug
        System.out.println("finished running delegated tasks");
        handleHandshake(sslEngine.getHandshakeStatus());
    }

    private ByteBuffer enlargeBuffer(final ByteBuffer inputBuffer, final int newSize) {
        ByteBuffer tmp = ByteBuffer.allocate(newSize);
        inputBuffer.flip();
        tmp.put(inputBuffer);
        return tmp;
    }

    @Override
    public void sendPacket(MqttPacket packet) {
        final ByteBuffer[] encoded = packet.encode();
        doWrap(encoded);
        outboundData.flip();
        try {
            // TODO: handle partial writes
            socketChannel.write(outboundData);
            outboundData.compact();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void sendPacketAndDisconnect(MqttPacket packet) {
        sendPacket(packet);
        sslEngine.closeOutbound();
        try {
            while (!sslEngine.isOutboundDone()) {
                handleNonApplicationData();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleNonApplicationData() throws IOException {
        // TODO: this is only generating handshake data now, so what should be the source buffer? just using a dummy buffer for now
        sslEngine.wrap(ByteBuffer.allocate(0), outboundData);
        outboundData.flip();
        // TODO: handle partial writes
        socketChannel.write(outboundData);
        outboundData.compact();
    }

    private SSLEngineResult doWrap(final ByteBuffer[] encoded) {
        try {
            final SSLEngineResult result = sslEngine.wrap(encoded, outboundData);
            switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    // TODO: debug
                    System.out.println("Enlarging outbound data buffer");
                    outboundData = enlargeBuffer(outboundData, sslEngine.getSession().getPacketBufferSize());
                    return doWrap(encoded);
                case CLOSED:
                    System.out.println("Channel already closed, can't write to it");
            }
            return result;
        } catch (SSLException e) {
            e.printStackTrace();
            // TODO: how to handle this?
            throw new RuntimeException(e);
        }
    }
}
