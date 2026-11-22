package io.aawireless.wirelessaamanipulator.helpers.streams;

import java.util.Arrays;
public class ByteArrayHandler {

    private byte[] byteArray;
    private int size;

    // Constructor
    public ByteArrayHandler() {
        this.byteArray = new byte[0];
        this.size = 0;
    }

    // Method to append a byte array to the existing byte array
    public void append(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        int newSize = size + data.length;
        byte[] newArray = Arrays.copyOf(byteArray, newSize);
        System.arraycopy(data, 0, newArray, size, data.length);
        byteArray = newArray;
        size = newSize;
    }

    // Method to retrieve exact amount of bytes from the beginning of the byte array, but without removing them
    public byte[] peak(int amount) {
        if (amount <= 0 || amount > size) {
            return null;
        }
        return Arrays.copyOfRange(byteArray, 0, amount);
    }

    // Method to retrieve exact amount of bytes from the beginning of the byte array
    public byte[] retrieve(int amount) {
        if (amount <= 0 || amount > size) {
            throw new IllegalArgumentException("Invalid amount specified.");
        }
        byte[] result = peak(amount);
        byteArray = Arrays.copyOfRange(byteArray, amount, size);
        size -= amount;
        return result;
    }

    // Method to provide information on how many bytes are available
    public int available() {
        return size;
    }

    // Method to clear the byte array
    public void clear() {
        byteArray = new byte[0];
        size = 0;
    }

    // Optional: Method to print the current byte array content (for debugging)
    public void printByteArray() {
        System.out.println(Arrays.toString(byteArray));
    }
}
