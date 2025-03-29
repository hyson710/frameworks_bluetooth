package com.openvela.bluetooth;

import static androidx.core.content.ContextCompat.getSystemService;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import com.openvela.bluetooth.callback.BluetoothStateCallback;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothSpp {
    private final String TAG = "BluetoothSpp";
    private int mSppRole;
    private final int SPP_ROLE_UNKNOWN = 0;
    private final int SPP_ROLE_SERVER = 1;
    private final int SPP_ROLE_CLIENT = 2;

    private int mSppRxState;
    private final int SPP_RX_STATE_IDLE = 0;
    private final int SPP_RX_STATE_RECEIVING = 1;
    private final int SPP_RX_STATE_WAIT4ACK = 1;
    private int mSppTxState;
    private final int SPP_TX_STATE_IDLE = 0;
    private final int SPP_TX_STATE_SENDING = 0;
    String mSppUuid;
    public static final int MESSAGE_SPP_LOGGING = 1;
    private BluetoothAdapter bluetoothAdapter;

    // It's used for Client or Server, for Server role, it means accepted socket
    private BluetoothSocket mSocket;
    // It's used only for Server
    private BluetoothServerSocket mServerSocket;

    // It's used for reading
    private BufferedInputStream mInputStream;

    // it's used for writing
    private BufferedOutputStream mOutputStream;
    // To Update UI
    private Handler mHandler;
    // Thread to sending data
    TxThread mTxThread;

    // For Tput calculation
    private int mTotalSize;
    private long mStartTime;
    private long mStopTime;
    // SPP index
    private int mIndex;


    public BluetoothSpp(int index, Handler handler) {
        mIndex = index;
        mHandler = handler;

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Device doesn't support Bluetooth");
            return;
        }

        mSppRole = SPP_ROLE_UNKNOWN;
    }

    public void register(String uuid) {
        if (mSppRole != SPP_ROLE_UNKNOWN) {
            showLogs("Unexpected register, current role = " + mSppRole);
            return;
        }

        mSppRole = SPP_ROLE_SERVER;
        mSppUuid = uuid;

        try {
            Log.d(TAG, "mServerSocket = " + mServerSocket + ", uuid = " + uuid);

            // Option#1: listenUsingInsecureRfcommWithServiceRecord()
            // Option#2: listenUsingRfcommWithServiceRecord()
            if (mServerSocket == null) {
                Log.d(TAG, "before listenUsingInsecureRfcommWithServiceRecord");
                mServerSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord("Vela BTS SPP Server", UUID.fromString(uuid));
                Log.d(TAG, "after listenUsingInsecureRfcommWithServiceRecord, server = " + mServerSocket);
            }

            // Show logs on UI
            String str = "Registered Socket Server on Port/SCN = " + mServerSocket.getPsm() + "\r\n";
            showLogs(str);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // Start AcceptThread to wait for a new connection from other clients
        AcceptThread thread = new AcceptThread();
        thread.start();
    }

    public void unregister() {
        if (mSppRole != SPP_ROLE_SERVER) {
            showLogs("Unexpected unregister, current role = " + mSppRole);
            return;
        }

        // TODO:
    }

    public void connect(String bdAddr, String uuid) {
        if (mSppRole != SPP_ROLE_UNKNOWN) {
            showLogs("Unexpected connect, current role = " + mSppRole);
            return;
        }

        mSppRole = SPP_ROLE_CLIENT;

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(bdAddr);

        try {
            Log.d(TAG, "onClick: Connect uuidSpp = " + uuid);
            Log.d(TAG, "onClick: before createInsecureRfcommSocketToServiceRecord");
            mSocket = device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(uuid));
            Log.d(TAG, "onClick: after createInsecureRfcommSocketToServiceRecord, socket = " + mSocket);
            if (null == mSocket) {
                Log.e(TAG, "Failed to create socket");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Start ConnectThread
        ConnectThread thread = new ConnectThread();
        thread.start();
        Log.i(TAG, "onClick: Connected Socket to" + bdAddr);
    }

    public void disconnect() {
        if (null != mOutputStream) try {
            mOutputStream.flush();
            mOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mOutputStream = null;

        if (null != mInputStream) try {
            mInputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mInputStream = null;

        if (null != mSocket) try {
            mSocket.getOutputStream().close();
            mSocket.getInputStream().close();
            mSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mSocket = null;

        mSppRole = SPP_ROLE_UNKNOWN;
    }

    public void send(String msgToSend) {
        // TODO: Check whether it's busy now
        if ((mSppTxState != SPP_TX_STATE_IDLE) || (mSppRxState != SPP_RX_STATE_IDLE)) {
            showLogs("Unexpected send: it's busy now, mSppTxState = " + mSppTxState + ", mSppRxState = " + mSppRxState);
            return;
        }

        if ((null == mSocket) || (null == mOutputStream)) {
              return;
        }

        int num = 0;
        if (msgToSend.startsWith("VelaTest:")) { // Start Tput testing
            mSppTxState = SPP_TX_STATE_SENDING;

            num = Integer.parseInt(msgToSend.substring(9));

            /* Option #1: when num = 12888, the time consuming is about 468ms
            for (int i = 0; i <= num; i++) {
                msgToSend = msgToSend + String.valueOf(i);
            } */

            // Option #2:
            int tmp = num;
            int begin = 0;
            int end = 10;
            int countDigits = 1;
            int numOfChar = msgToSend.length();
            Log.d (TAG, "numOfChar init = " + numOfChar);
            do {
                if (num >= end)
                    numOfChar += (end - begin)  * countDigits;
                else if ((num >= begin) && (num < end))
                    numOfChar += (num - begin + 1) * countDigits;
                else
                    Log.e(TAG, "wrong case!!!");

                countDigits++;
                begin = end;
                end = 10 * begin;
                tmp /= 10;
            } while (tmp != 0);

            Log.d (TAG, "numOfChar = " + numOfChar);

            msgToSend = "START:" + numOfChar;

            // Delay the sending in another Thread, after receiving ACK from PEER
            mTxThread = new TxThread(num);
        }

        writeStr(msgToSend);

        // Show logs on UI
        String str = "Sent: size = " + msgToSend.length() + " Bytes: \"" + msgToSend + "\"\r\n";
        showLogs(str);
    }

    // AcceptThread is used by Server to listen a connection from other clients
    private class AcceptThread extends Thread {
        public void run() {
            Log.d(TAG, "AcceptThread: started");

            while (true) {
                try {
                    // It's a blocking operation, so we shall do it in a background thread
                    Log.d(TAG, "before server.accept()");
                    mSocket = mServerSocket.accept();
                    Log.d(TAG, "after server.accept(), acceptSocket = " + mSocket);
                } catch (IOException e) {
                    e.printStackTrace();
                    //return;
                }

                if (null != mSocket) {
                    Log.i(TAG, "Accepted one connection...");

                    try {
                        mInputStream = new BufferedInputStream(mSocket.getInputStream());
                        mOutputStream = new BufferedOutputStream(mSocket.getOutputStream());

                        // Start receiving data
                        RxThread thread = new RxThread();
                        thread.start();

                        // Only accept one connection!!!

                        // Show logs on UI
                        String str = "Server: accepted one socket connection and started reading \r\n";
                        showLogs(str);
                        break;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    // ConnectThread is used by Client to connect other Servers
    private class ConnectThread extends Thread {
        public void run() {
            Log.d(TAG, "ConnectThread: started");

            // Create RFCOMM socket, as a Client
            try {
                // It's a blocking operation, so we shall do it in background
                Log.d(TAG, "onClick: before BluetoothSocket::connect");
                mSocket.connect();
                Log.d(TAG, "onClick: after BluetoothSocket::connect");

                Log.d(TAG, "onClick: before BluetoothSocket::getInputStream");
                InputStream iStream = mSocket.getInputStream();
                Log.e(TAG, "onClick: after BluetoothSocket::getInputStream, iStream = " + iStream);
                if (null == iStream) {
                    Log.e(TAG, "Failed to getInputStream");
                    return;
                }
                mInputStream = new BufferedInputStream(iStream);

                Log.d(TAG, "onClick: before BluetoothSocket::getOutputStream");
                OutputStream oStream = mSocket.getOutputStream();
                Log.e(TAG, "onClick: after BluetoothSocket::getOutputStream, iStream = " + oStream);
                if (null == oStream) {
                    Log.e(TAG, "Failed to getOutputStream");
                    return;
                }
                mOutputStream = new BufferedOutputStream(oStream);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            // Start receiving data
            RxThread thread = new RxThread();
            thread.start();

            // Show logs on UI
            String str = "Client: Connected one server and started reading\r\n";
            showLogs(str);
        }
    }

    // RxThread is used by Client or Server to Receive data (in the background) after connection
    private class RxThread extends Thread {
        public void run() {
            int readSize = 0;
            byte[] buffer = new byte[1000];
            String readStr;
            int totalSizeToReceive = 0;
            int totalReceived = 0;
            long duration = 0;
            String str;

            Log.d(TAG, "RxThread: started");

            try {
                while ((readSize = mInputStream.read(buffer, 0, buffer.length)) != -1) {
                    Log.d(TAG, "Received: readSize = " + readSize + ", buffer = " + buffer);
                    readStr = new String(buffer, 0, readSize, "UTF-8");

                    if (readStr.startsWith("START:")) {
                        showLogs("Received \"START:\"\r\n");
                        if ((mSppTxState != SPP_TX_STATE_IDLE) || (mSppRxState != SPP_RX_STATE_IDLE)) {
                            showLogs("SPP is busy for sending now, ignore Rx Tput test request");
                        }
                        totalSizeToReceive = Integer.parseInt(readStr.substring(6));

                        // Write ACK to remote
                        writeStr("START_ACK");

                        mSppRxState = SPP_RX_STATE_RECEIVING;
                        mTotalSize = totalSizeToReceive;
                        mStartTime = System.currentTimeMillis();
                        totalReceived = 0;
                        continue;
                    } else if (readStr.startsWith("START_ACK")) {
                        showLogs("Received \"START_ACK\"\r\n");
                        if (mSppTxState == SPP_TX_STATE_SENDING) {
                            // Received ACK, continue to send data in another thread
                            mTxThread.start();
                        }
                        continue;
                    } else if (readStr.startsWith("EOF")) {
                        showLogs("Received \"EOF\"\r\n");
                        // Calculate Tput
                        mStopTime = System.currentTimeMillis();
                        duration = mStopTime - mStartTime;

                        str = "Sent Total " + String.valueOf(mTotalSize) + " Bytes";
                        if (duration > 0)
                            str += ", Duration: " + duration + " ms, Average Tput = " + mTotalSize/duration + " kB/s";
                        str += "\r\n";

                        // Send message to UI
                        showLogs(str);

                        mStartTime = 0;
                        mStopTime = 0;
                        mTotalSize = 0;
                        mSppTxState = SPP_TX_STATE_IDLE;
                        continue;
                    }

                    Log.d(TAG, "Continue to handle string: totalReceived = " + totalReceived + ", readSize = " + readSize
                            + ", totalSizeToReceive = " + totalSizeToReceive);
                    if (mSppRxState == SPP_RX_STATE_RECEIVING) {
                        totalReceived += readSize;
                        Log.d(TAG, "Updated totalReceived = " + totalReceived + ", readSize = " + readSize);

                        if (totalReceived >= totalSizeToReceive) {
                            mStopTime = System.currentTimeMillis();
                            duration = mStopTime - mStartTime;

                            str = "Received Total " + String.valueOf(totalReceived) + " Bytes)";
                            if (duration > 0)
                                str += ", Duration: " + duration + " ms, Average Tput = " + mTotalSize/duration + " kB/s";
                            str += "\r\n";

                            showLogs(str);

                            // Tell remote to stop sending and calculate Tput on remote side
                            writeStr("EOF");

                            mStartTime = 0;
                            mStopTime = 0;
                            mTotalSize = 0;
                            mSppRxState = SPP_RX_STATE_IDLE;
                        }
                    } else {
                        str = "Received " + String.valueOf(readSize) + " Bytes: " + readStr +"\r\n";

                        // Send message to UI
                        showLogs(str);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                //return;
            }

            if (mSppRole == SPP_ROLE_SERVER) {
                showLogs("Socket unexpectedly disconnected, restart Accept Thread again");
                disconnect();
                register(mSppUuid);
            }
        }
    }

    // TxThread is used by Client or Server to Send data (in the background) after connection
    private class TxThread extends Thread {
        int mNumToSend;

        public TxThread(int num) {
            mNumToSend = num;
        }

        public void run() {
            String msgToSend = "VelaTest:" + mNumToSend;
            int totalSize = 0;
            int start = 0;
            int last = 0;

            Log.d(TAG, "TxThread: started");

            if (mSppTxState != SPP_TX_STATE_SENDING)
                return;

            // Building string to be sent:
            long testTimeStart = System.currentTimeMillis();
            showLogs("Preparing data to be sent ...\r\n");

            /* Option#1: low efficiency!
            for (int i = 0; i <= mNumToSend; i++) {
                msgToSend = msgToSend + String.valueOf(i);
            }
             */

            // Option#2: Higher efficiency, but not thread-safety useage.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i <= mNumToSend; i++) {
                sb.append(i);
            }
            msgToSend += sb.toString();

            long testTimeStop = System.currentTimeMillis();
            long duration = testTimeStop - testTimeStart;
            showLogs("Done. Time to generation string = " + duration + " ms\r\n");

            totalSize = msgToSend.length();

            mTotalSize = totalSize;
            mStartTime = System.currentTimeMillis();

            start = 0;
            int step = 1000;
            while (true) {
                last = start + step;
                if (last > totalSize) {
                    writeStr(msgToSend.substring(start));
                } else {
                    writeStr(msgToSend.substring(start, last));
                }

                start = last;
                if (start >= totalSize)
                    break;
            }

            // Show logs on UI
            String str = "Sent: size = " + totalSize + "Bytes\r\n";
            showLogs(str);
        }
    }

    // To show logs on UI
    private void showLogs(String str) {
        if (mSppRole == SPP_ROLE_CLIENT)
            str = "Client(" + mIndex + "): " + str;
        else if (mSppRole == SPP_ROLE_SERVER)
            str = "Server(" + mIndex + "): " + str;

        Bundle bData = new Bundle();
        bData.putString("log", str);

        Message msg = mHandler.obtainMessage();
        msg.what = BluetoothSpp.MESSAGE_SPP_LOGGING;
        msg.setData(bData);
        mHandler.sendMessage(msg);
        Log.i(TAG, "showLogs: " + str);
    }

    private void writeStr(String msgToSend) {
        try {
            mOutputStream.write(msgToSend.getBytes());
            //mOutputStream.write('\r');
            //mOutputStream.write('\n');
            mOutputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
