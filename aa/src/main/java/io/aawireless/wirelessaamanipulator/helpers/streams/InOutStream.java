package io.aawireless.wirelessaamanipulator.helpers.streams;


import static io.aawireless.wirelessaamanipulator.helpers.Constants.FLAG_FIRST;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import io.aawireless.wirelessaamanipulator.helpers.Util;

import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;



public class InOutStream  {

    private FileDescriptor fd;
    private FileInputStream instream=null;
    private FileOutputStream outstrem=null;
    private String endpointID;

    private final BlockingQueue<byte[]> freeInputBuffers = new ArrayBlockingQueue<byte[]>(1024);

    DataInputStream inputStream;
    OutputStream outputStream;

    public String getNamedstring() {
        return namedstring;
    }

    public void setNamedstring(String namedstring) {
        this.namedstring = namedstring;

    }

    String namedstring;

    private boolean hasMore = false;

    public static InOutStream currentStream = null;
    private static ByteArrayHandler quehandler = new ByteArrayHandler();

    Context context;
    Socket socket;






    public InOutStream(ParcelFileDescriptor pfd) {

             fd = pfd.getFileDescriptor();
             instream = new FileInputStream(fd);
             outstrem = new FileOutputStream(fd);


    }

    public InOutStream(Socket socket) {
        this.socket = socket;
        try {
            socket.setSoTimeout(15000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setReuseAddress(true);

            this.inputStream = new DataInputStream(socket.getInputStream());
            this.outputStream = socket.getOutputStream();
            Log.d("InOutStream","Created new in/out with sockets, outpustream: "+this.outputStream);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        currentStream = this;
    }




    public synchronized void write(final byte[] data) {
        write(data, 0);
    }

    /**
     * Orderly close: half-close the output first (peer gets a FIN after anything we already
     * wrote, e.g. a ByeByeRequest, rather than a reset), then close everything. Idempotent.
     */
    public void close() {
        if (socket != null) {
            try { if (!socket.isClosed() && !socket.isOutputShutdown()) socket.shutdownOutput(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
        try { if (outstrem != null) outstrem.close(); } catch (Exception ignored) {}
        try { if (instream != null) instream.close(); } catch (Exception ignored) {}
    }

    public void write(byte[] data, int i) {
        if (data == null)
            return;


        try {
                if (outstrem != null)  //if we are on USB mode
                {
                    outstrem.write(data);
                    return;
                }

                outputStream.write(data);

                outputStream.flush();

        } catch (Exception e) {
            //  Intent intent = new Intent(context, TransporterService.class);
            //   context.stopService(intent);
        }
    }

    private void setMore() {
        byte[] buf = quehandler.peak(4);
        if (buf == null) {
            hasMore = false;
            return;
        }
        short enc_len = (short) ((buf[2] & 0xFF) << 8 | (buf[3] & 0xFF));
        if (buf[1] == 9 || buf[1] == 1)
            enc_len += 4;
        if (quehandler.available() >= enc_len + 4)
            hasMore = true;
        else
            hasMore = false;
    }

    public byte[] getNext() throws Exception, IOException, InterruptedException {

        if (hasMore) {
            byte[] buf = quehandler.peak(4);
            short enc_len = (short) ((buf[2] & 0xFF) << 8 | (buf[3] & 0xFF));
            if (buf[1] == 9 || buf[1] == 1)
                enc_len += 4;

            byte[] resp = quehandler.retrieve(enc_len + 4);
            setMore();
            return resp;
        } else {
            read();
            setMore();
            return getNext();
        }
    }


    public byte[] readraw() throws Exception, IOException, InterruptedException {

            if (instream != null)
                return getNext();


            byte[] hdr = new byte[4];
            readFullyTraced(hdr, 0, 4, null);
            // Unsigned: a short wrapped lengths >= 0x8000 negative, and the fixed 16 KiB
            // buffer overflowed on a FIRST frame with enc_len > 16376.
            int enc_len = ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
            if ((hdr[1] & 0x03) == FLAG_FIRST)
                enc_len += 4;

            byte[] buf = new byte[4 + enc_len];
            System.arraycopy(hdr, 0, buf, 0, 4);
            readFullyTraced(buf, 4, enc_len, hdr);

            if (framesRead < 3) {   // the first frames on a link are what differ between cars
                Log.d("InOutStream", namedstring + " frame#" + framesRead + " hdr=" + Util.bytesToHex(hdr)
                        + " len=" + buf.length + " head=" + Util.bytesToHex(Arrays.copyOf(buf, Math.min(buf.length, 48))));
            }
            framesRead++;
            return buf;

    }

    private int framesRead = 0;

    /**
     * readFully that, when it fails (timeout / EOF), logs what DID arrive: the frame header and
     * the partial body. A bare SocketTimeoutException can't tell "peer silent" from "peer sent
     * something we mis-framed".
     */
    private void readFullyTraced(byte[] b, int off, int len, byte[] hdr) throws IOException {
        int got = 0;
        try {
            while (got < len) {
                int n = inputStream.read(b, off + got, len - got);
                if (n < 0) throw new java.io.EOFException("end of stream");
                got += n;
            }
        } catch (IOException e) {
            String what = hdr == null ? "header" : "body";
            Log.w("InOutStream", namedstring + ": " + e + " reading " + what + " (frame#" + framesRead
                    + ") got " + got + "/" + len
                    + (hdr != null ? " hdr=" + Util.bytesToHex(hdr) : "")
                    + " bytes=" + Util.bytesToHex(Arrays.copyOfRange(b, off, off + Math.min(got, 64))));
            throw e;
        }
    }


    public void read() throws Exception, IOException, InterruptedException {


            byte[] buf = new byte[4*16384];
            int got = instream.read(buf);
            quehandler.append(Arrays.copyOf(buf, got));

    }


}