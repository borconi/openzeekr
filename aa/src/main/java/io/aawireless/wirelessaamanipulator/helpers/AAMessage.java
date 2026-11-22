package io.aawireless.wirelessaamanipulator.helpers;

import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_ALL;
import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_CONTROL;
import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_ENCRYPTED;
import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_FIRST;
import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_LAST;
import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_MIDDLE;
import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_SPECIFIC;

import androidx.annotation.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import io.aawireless.wirelessaamanipulator.helpers.ssl.NioSSL;

public class AAMessage {
    public byte channel;
    public byte flag;
    short enc_len;
    int payloadSize;
    int messageID;
    public byte[] data;
    byte encrypted = FLAG_ENCRYPTED;
    byte type = FLAG_SPECIFIC;


    public AAMessage(byte[] read, NioSSL nioSSL) throws IOException {
        if (read == null || read.length < 4) throw new IOException("Frame too short");

        this.channel = read[0];

        byte flagsByte = read[1];
        this.encrypted = (byte) (flagsByte & FLAG_ENCRYPTED);
        this.type      = (byte) (flagsByte & FLAG_CONTROL);
        this.flag      = (byte) (flagsByte & FLAG_ALL);

        int frameSize = ((read[2] & 0xFF) << 8) | (read[3] & 0xFF);
        this.enc_len = (short) frameSize;

        int bodyOffset;


        if (this.flag == FLAG_FIRST) {
            // C++ reads 4-byte BE payloadSize before the frame body
            if (read.length < 8) throw new IOException("FIRST frame too short");
            payloadSize = ((int)(read[4] & 0xFF) << 24)
                    | ((int)(read[5] & 0xFF) << 16)
                    | ((int)(read[6] & 0xFF) << 8)
                    | ((int)(read[7] & 0xFF));

            bodyOffset = 8;
        } else {
            payloadSize = 0;
            bodyOffset = 4;
        }

        if (read.length < bodyOffset + frameSize) {
            throw new IOException("Length mismatch");
        }

        if (this.encrypted == FLAG_ENCRYPTED) {
            data = nioSSL.decrypt(read, bodyOffset, frameSize);
        } else {
            data = Arrays.copyOfRange(read, bodyOffset, bodyOffset + frameSize);
        }

        // Only ALL/FIRST contain messageId at the start (same as your original)
        if (data.length >= 2 && this.flag != FLAG_MIDDLE && this.flag != FLAG_LAST) {
            this.messageID = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            data = Arrays.copyOfRange(data, 2, data.length);
        } else {
            this.messageID = 0;
        }
    }

    public AAMessage(byte[] data){
        this.data=data;
    }
    public AAMessage(byte channel, byte flag, int messageID) {
        this.channel = channel;
        this.flag = flag;
        this.messageID = messageID;
    }

    public AAMessage(byte channel, byte flag, short enc_len, short payload, int messageID, byte[] data) {
        this.channel = channel;
        this.flag = flag;
        this.enc_len = enc_len;
        this.payloadSize = payload;
        this.messageID = messageID;
        this.data = data;
    }

    public AAMessage(byte channel, byte flag, int messageID, byte[] toByteArray) {
        this.channel = channel;
        this.flag = flag;
        this.messageID = messageID;
        this.data = toByteArray;
    }



    public byte getEncrypted() {
        return encrypted;
    }

    public void setEncrypted(byte encrypted) {
        this.encrypted = encrypted;
    }

    public byte getType() {
        return type;
    }

    public void setType(byte type) {
        this.type = type;
    }

    public byte getChannel() {
        return channel;
    }

    public void setChannel(byte channel) {
        this.channel = channel;
    }

    public byte getFlag() {
        return flag;
    }

    public void setFlag(byte flag) {
        this.flag = flag;
    }

    public short getEnc_len() {
        return enc_len;
    }

    public void setEnc_len(short enc_len) {
        this.enc_len = enc_len;
    }

    public int getPayload() {
        return payloadSize;
    }

    public void setPayload(short payload) {
        this.payloadSize = payload;
    }

    public int getMessageID() {
        return messageID;
    }

    public void setMessageID(short messageID) {
        this.messageID = messageID;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }



    public byte[] getFormated(NioSSL ssl) {
        byte [] msgresp;

       try {
           if (this.encrypted == FLAG_ENCRYPTED)
               msgresp = ssl.encrypt(data, (short) messageID, flag);
           else {
               if (messageID == 0)
                   msgresp = data;
               else {
                   ByteBuffer out = ByteBuffer.allocate(2 + data.length);
                   out.putShort((short) messageID);
                   out.put(data);
                   msgresp = out.array();
               }
           }

           int len = msgresp.length + (this.flag == FLAG_FIRST ? 4 : 0)+4;

           ByteBuffer out = ByteBuffer.allocate(len);
           out.put(channel);
           out.put((byte) (flag | encrypted | type));
           out.putShort((short) msgresp.length);
           if (this.flag == FLAG_FIRST)
               out.putInt(payloadSize);
           out.put(msgresp);
           return out.array();
       }
       catch (Exception e){
           e.printStackTrace();
           return null;
       }
    }

    @NonNull
    @Override
    public String toString() {
        return "Channel: " + channel + ", flag: " + flag + ", messageid: " + messageID + ", data: " + Util.bytesToHex(data);
    }


}