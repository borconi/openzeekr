package io.aawireless.wirelessaamanipulator.helpers.ssl;

import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_ALL;
import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_FIRST;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import io.aawireless.wirelessaamanipulator.helpers.streams.InOutStream;

public class NioSSL extends NioSslPeer {

    private String TAG = "AAGateWay";
    private final ExecutorService decryptThread = Executors.newSingleThreadExecutor();

    /**
     * Declares if the server is active to serve and create new connections.
     */
    private boolean active;

    /**
     * The context will be initialized with a specific SSL/TLS protocol and will then be used
     * to create {@link SSLEngine} classes for each new connection that arrives to the server.
     */
    private SSLContext context;

    /**
     * A part of Java NIO that will be used to serve all connections to the server in one thread.
     */
    private Selector selector;
    private final SSLEngine engine;


    /**
     * Server is designed to apply an SSL/TLS protocol and listen to an IP address and port.
     *
     * @throws Exception
     */


    public NioSSL(SSLEngine engine, InOutStream stream, boolean client, String tag) throws Exception {
        this.engine = engine;
        this.inoutStram = stream;
        this.engine.setUseClientMode(client);
        this.engine.beginHandshake();
        this.TAG=tag;
        Log.d(TAG, "Begin handshake");

        doHandshake(this.engine,tag);
    }

    /**
     * Release this TLS engine. Marks the {@link SSLEngine} closed (freeing its native SSL
     * context promptly instead of waiting for GC) and closes the NIO selector if one was ever
     * opened. The underlying transport ({@link InOutStream}) is owned and closed by the
     * simulator, not here. Safe to call more than once and from any thread.
     */
    public void close() {
        try { engine.closeOutbound(); } catch (Exception ignored) {}
        try { if (selector != null) selector.close(); } catch (Exception ignored) {}
    }


    public synchronized byte[] decrypt(byte[] encoded_data, int pos, int i) throws IOException {

        ByteBuffer inData=ByteBuffer.allocate(i);
        inData.put(encoded_data, pos, i);
        inData.flip();

        //peerNetData.clear();
        // peerNetData.put(encoded_data, pos, i);
        // peerNetData.flip();

        byte[] response = null;
        while (inData.hasRemaining()) {
            peerAppData.clear();
            SSLEngineResult result = engine.unwrap(inData, peerAppData);

            switch (result.getStatus()) {
                case OK:
                    peerAppData.flip();
                    response = new byte[peerAppData.remaining()];
                    peerAppData.get(response);

                    break;
                case BUFFER_OVERFLOW:
                    Log.d(TAG, "Buffer overflow, we need more network data");
                    peerAppData = enlargeApplicationBuffer(engine, peerAppData);
                    break;
                case BUFFER_UNDERFLOW:
                    inData = handleBufferUnderflow(engine, inData);
                    break;
                case CLOSED:
                    Log.d("AA_GATEWAY_SERVER", "Client wants to close connection...");
                    // closeConnection(socketChannel, engine);
                    Log.d("AA_GATEWAY_SERVER", "Goodbye client!");
                    return response;
                default:
                    throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
            }
        }

        return response;
    }


    //Must be synchronized, otherwise SSLENgine might fail on multi-thread.
    public synchronized byte[] encrypt(byte[] message, short messageID, byte flag) throws Exception {
        myAppData.clear();

        if (flag == FLAG_FIRST || flag == FLAG_ALL) {
            myAppData.putShort(messageID);
        }
        if (message != null && message.length > 0) {
            myAppData.put(message);
        }
        myAppData.flip();

        while (true) {
            myNetData.clear();

            SSLEngineResult result = engine.wrap(myAppData, myNetData);

            switch (result.getStatus()) {
                case OK: {
                    myNetData.flip();
                    byte[] out = new byte[myNetData.remaining()];
                    myNetData.get(out);
                    return out; // ✅ return TLS ciphertext bytes
                }

                case BUFFER_OVERFLOW:
                    myNetData = enlargePacketBuffer(engine, myNetData);
                    continue; // retry wrap

                case BUFFER_UNDERFLOW:
                    throw new SSLException("Buffer underflow after wrap (unexpected).");

                case CLOSED:
                    throw new SSLException("SSLEngine closed during wrap.");

                default:
                    throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
            }
        }
    }


}
