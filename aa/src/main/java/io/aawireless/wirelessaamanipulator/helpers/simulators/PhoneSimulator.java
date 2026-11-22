package io.aawireless.wirelessaamanipulator.helpers.simulators;


import static io.aawireless.wirelessaamanipulator.helpers.messageprocessors.EnergyModelTranslator.sensorChannel;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.net.ssl.SSLEngine;

import io.aawireless.wirelessaamanipulator.helpers.AAMessage;
import io.aawireless.wirelessaamanipulator.helpers.AAStarter;
import io.aawireless.wirelessaamanipulator.helpers.Util;
import io.aawireless.wirelessaamanipulator.helpers.messageprocessors.EnergyModelTranslator;
import io.aawireless.wirelessaamanipulator.helpers.ssl.CertProvider;
import io.aawireless.wirelessaamanipulator.helpers.ssl.NioSSL;
import io.aawireless.wirelessaamanipulator.helpers.ssl.sslEngineBuilder;
import io.aawireless.wirelessaamanipulator.helpers.streams.InOutStream;
import io.aawireless.wirelessaamanipulator.proto.MessageStatus;
import io.aawireless.wirelessaamanipulator.proto.SensorMessageId;
import io.aawireless.wirelessaamanipulator.proto.SensorResponse;
import io.aawireless.wirelessaamanipulator.services.MainTaskService;

public class PhoneSimulator extends AASimulator{


    private static final String TAG = "PhoneSimulator";
    /** Listening socket (6299), held so stopself() can unblock a thread parked in accept(). */
    private volatile ServerSocket serverSocket;

    public PhoneSimulator(MainTaskService other, Socket socket, EnergyModelTranslator t) {
        super(other,  "PhoneToCar",t);
        inoutstream=new InOutStream(socket);
        inoutstream.setNamedstring(TAG);
    }

    public PhoneSimulator(MainTaskService other, ParcelFileDescriptor mFileDescriptor, EnergyModelTranslator t) {
        super(other,  "PhoneToCar",t);
        inoutstream=new InOutStream(mFileDescriptor);
        inoutstream.setNamedstring(TAG);
    }

    public PhoneSimulator(MainTaskService other,  EnergyModelTranslator t) {
        super(other,  "PhoneToCar",t);
    }

    @Override
    public void stopself() {
        // accept() ignores interrupt(): close the 6299 listening socket so a thread waiting for the
        // car to connect (Stop pressed before a session starts) unblocks. run()'s finally
        // double-closes harmlessly.
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        super.stopself();
    }

    @Override
    public void run() {
        super.run();
        serverSocket = null;
        try {

            if (inoutstream==null)
            {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                Log.d(TAG,"PhoneSimulator listening on port 6299");
                serverSocket.bind(new InetSocketAddress(6299));
                java.net.Socket socket = serverSocket.accept();




                Log.d(TAG,"phoneSimulator accepted connection, socket is: "+socket);
                inoutstream=new InOutStream(socket);
                inoutstream.setNamedstring(TAG);

            }
            //Need to do phone handshake and polling.



            byte[] data = inoutstream.readraw();
            Log.w(TAG, "Read car hello: " + Util.bytesToHex(data));



            /*
             * Expected HU → PHONE VersionRequest frame:
             * 00 03 00 06 00 01 MM MM mm mm
             *  ch fl  len  msgId   major  minor     (major/minor are uint16 BE)
             */
            if (data.length < 10) {
                throw new IllegalStateException("HU VersionRequest too short: " + data.length);
            }
            int huPayloadLen = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            int huMsgId      = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            if (huMsgId != 1 /* MESSAGE_VERSION_REQUEST */ || huPayloadLen != 6) {
                throw new IllegalStateException("Invalid HU VersionRequest: len=" + huPayloadLen + " msgId=" + huMsgId);
            }

            int huMajor = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            int huMinor = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);

            Log.w(TAG, "Car AA version: " + huMajor + "." + huMinor);

            /*
             * PHONE → HU VersionResponse (matches C++):
             * payload = uint16 major + uint16 minor + uint16 status
             * AA frame payloadLen = msgId(2) + 6 = 8
             *
             * Frame:
             * 00 03 00 08 00 02 MM MM mm mm 00 00
             *              ^^^^ MESSAGE_VERSION_RESPONSE
             *                               ^^^^ STATUS_SUCCESS (uint16 = 0)
             */
            int phoneMajor = huMajor; // or your saved phone version major
            int phoneMinor = huMinor; // or your saved phone version minor
            short statusSuccess = 0;

            ByteBuffer vr = ByteBuffer.allocate(4 + 8).order(ByteOrder.BIG_ENDIAN);
            vr.put((byte) 0x00);          // channel 0
            vr.put((byte) 0x03);          // flags (plain + control) - keep consistent with HU
            vr.putShort((short) 8);       // payload length (bytes after len): msgId + payload = 8
            vr.putShort((short) 2);       // MESSAGE_VERSION_RESPONSE
            vr.putShort((short) phoneMajor);
            vr.putShort((short) phoneMinor);
            vr.putShort(statusSuccess);

            byte[] versionResponse = vr.array();
            Log.w(TAG, "Send phone hello (VersionResponse): " + Util.bytesToHex(versionResponse));
            inoutstream.write(versionResponse);


            // Use the REAL Android Auto client certificate, extracted on-device from the
            // installed gearhead APK and cached (see CertProvider / AACertExtractor).
            // The car rejects the static bundled keystore; it expects the genuine AA cert.
            // Falls back to res/raw/keystore.pkcs12 only if extraction is impossible.
            java.security.KeyStore aaKeyStore = CertProvider.getKeyStore(other.getContext());
            SSLEngine engine = sslEngineBuilder.Builder(other.getContext(), aaKeyStore);
            /*
             * Start TLS (PHONE is client) - matches the C++ "huSslEngine->doHandshake()"
             *
             * IMPORTANT:
             * Your NioSslPeer MUST only feed MESSAGE_ENCAPSULATED_SSL payloads (msgId=3) to SSLEngine.unwrap(),
             * and when it wraps, it must send them as msgId=3 frames. If you unwrap anything else, you’ll get -3.
             */
            Log.w(TAG, "Init TLS (phone as client)");
            NioSSL ssl = new NioSSL(engine, inoutstream, false, TAG);
            other.setcarSSL(ssl);
            linkSsl = ssl;   // enables a proper ByeBye to the car on shutdown
            /*
             * Do NOT send AuthComplete manually here.
             * In the C++ code, after handshake it does:
             *   huSslEngine->read(tmp);
             *   if (!tmp.isEmpty()) send MESSAGE_ENCAPSULATED_SSL(tmp)
             *
             * AuthComplete comes later as part of the proper AA control flow.
             */
            // remove the authComplete write entirely
            ready=true;

            while (!other.isCarready())
            {
                Thread.sleep(50);
            }
            other.handshakeready();
            Log.d(TAG,"Phone connected to CAR, starting reading data from car");
            while (MainTaskService.running  && !isInterrupted()) {
                AAMessage message = new AAMessage(inoutstream.readraw(), ssl);
                sessionLive = true;   // the car is talking encrypted -> a ByeBye will be understood
                if (consumeOwnByeByeResponse(message)) continue;   // answer to OUR ByeBye: don't relay
               /* if ( sensorChannel != null  && message.channel==sensorChannel)
                {
                    Log.d(TAG,"Received from car, dropping: "+message);
                  //  return;
                }*/
                // The cluster video session may hijack car->phone traffic on the
                // cluster channel (esp. the video-focus-request); if consumed, don't relay.
                if (other.onCarClusterMessage(message)) continue;
                AAMessage outMsg = translator.process(message);
                if (outMsg != null) other.pushToCar(outMsg);

            }

        } catch (InterruptedException e) {
            Log.d(TAG, "PhoneSimulator interrupted, exiting cleanly");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // A dropped socket must NEVER kill the process. Log and let the thread
            // end; previously this rethrew as RuntimeException -> uncaught FATAL.
            Log.w(TAG, "PhoneSimulator terminating due to: " + e, e);
        } finally {
            ready = false;
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
            try { if (inoutstream != null) inoutstream.close(); } catch (Exception ignored) {}
            other.onSimulatorEnded(this);   // car side gone -> ByeBye gearhead's head unit server
            Log.d(TAG, "PhoneSimulator thread exiting");
        }

    }




}
