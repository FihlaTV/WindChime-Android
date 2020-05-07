package pro.dbro.airshare.transport.wifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.CountDownTimer;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import pro.dbro.airshare.transport.Transport;
import timber.log.Timber;

/**
 * Wifi Direct Transport. Requires Android 4.0.
 *
 * Proof-of-concept. Not yet ready for use. Supports a single WiFi Direct connection at time
 *
 * Development Note : Need to implement true acknowledgement.
 * {@link pro.dbro.airshare.transport.Transport.TransportCallback#dataSentToIdentifier(Transport, byte[], String, Exception)}
 * reports that the data was written to the Socket, but not that it was received on the other end. Bugs have been witnessed
 * where a client that shuts down the connection immediately after that callback result in the remote peer losing connection
 * before receiving said data.
 *
 * Created by davidbrodsky on 2/21/15.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class WifiTransport extends Transport implements WifiP2pManager.ConnectionInfoListener, WifiP2pManager.ChannelListener {

    private static final boolean VERBOSE = true;

    /** Values to id transport useful in bit fields */
    public static final int TRANSPORT_CODE = 2;

    public static final int DEFAULT_MTU_BYTES = 1024;

    private static final int PORT = 8787;
    private static final int SOCKET_TIMEOUT_MS = 5000;

    private Context mContext;
    private WifiP2pManager mManager;
    private WifiP2pManager.Channel mChannel;

    private Thread mSocketThread;

    private boolean mConnectionDesired = true;
    private boolean mDiscoveringPeers = false;
    private boolean mLocalPrefersToHost = false;
    private boolean mRetryChannel = true;
    private boolean mReceiverRegistered = false;

    private BiMap<String, String> mMacToIpAddress = HashBiMap.create();

    private HashSet<String> mConnectingPeers = new HashSet<>();
    private HashSet<String> mConnectedPeers = new HashSet<>();

    private static int PEER_DISCOVERY_TIMEOUT_MS = 30 * 1000;
    private CountDownTimer mPeerDiscoveryTimer;

    /** Identifier -> Queue of outgoing buffers */
    private final HashMap<String, ArrayDeque<byte[]>> mOutBuffers = new HashMap<>();

    public class DeviceConnectionListener implements WifiP2pManager.ActionListener {

        private WifiP2pDevice mDevice;

        public DeviceConnectionListener(WifiP2pDevice device) {
            mDevice = device;
        }

        @Override
        public void onSuccess() {
            mConnectingPeers.add(mDevice.deviceAddress);
            Timber.d("Connection request initiated");
        }

        @Override
        public void onFailure(int reason) {
            Timber.d("Failed to connect with reason: %s", getDescriptionForActionListenerError(reason));
        }
    }

    public WifiTransport(@NonNull Context context,
                         @NonNull String serviceName,
                         @NonNull TransportCallback callback) {

        super(serviceName, callback);

        mContext = context;
        mManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
    }

    // <editor-fold desc="Transport">

    @Override
    public boolean sendData(byte[] data, Set<String> identifiers) {
        boolean didSendAll = true;

        for (String identifier : identifiers) {
            if (!sendData(data, identifier)) {
                didSendAll = false;
            }
        }

        return didSendAll;
    }

    @Override
    public boolean sendData(@NonNull byte[] data, String identifier) {

        queueOutgoingData(data, identifier);

//        if (isConnectedTo(identifier))
//            return transmitOutgoingDataForConnectedPeer(identifier);

        return true;
    }

    @Override
    public void advertise() {
        mLocalPrefersToHost = true;
        initializeWiFiDirect();
    }

    @Override
    public void scanForPeers() {
        mLocalPrefersToHost = false;
        initializeWiFiDirect();
    }

    @Override
    public void stop() {
        Timber.d("Stopping WiFi");
        if (mReceiverRegistered) {
            mContext.unregisterReceiver(wifiDirectReceiver);
            mReceiverRegistered = false;
        }
        mConnectionDesired = false;
        if (mSocketThread != null) {
            mSocketThread = null;
        }

        if (mDiscoveringPeers)
            mManager.stopPeerDiscovery(mChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Timber.d("Stopped peer discovery");
                }

                @Override
                public void onFailure(int reason) {
                    Timber.w("Failed to stop peer discovery");
                }
            });

        if (mChannel != null) {
            mManager.cancelConnect(mChannel, null);
            mManager.removeGroup(mChannel, null);
        }

        mConnectedPeers.clear();
        mConnectingPeers.clear();

        mOutBuffers.clear();

        mDiscoveringPeers = false;
    }

    @Override
    public int getTransportCode() {
        return TRANSPORT_CODE;
    }

    @Override
    public int getMtuForIdentifier(String identifier) {
//        Integer mtu = central.getMtuForIdentifier(identifier);
//        return (mtu == null ? DEFAULT_MTU_BYTES : mtu ) - 10;
        return DEFAULT_MTU_BYTES;
    }

    // </editor-fold desc="Transport">

    private void cancelConnections() {
        mManager.removeGroup(mChannel, null);
        mManager.cancelConnect(mChannel, null);
    }

    private void initializeWiFiDirect() {
        if (mChannel != null) {
            Timber.w("Channel already present");
        }

        mChannel = mManager.initialize(mContext, Looper.getMainLooper(), this);
        // Clear any previous WiFi-Direct state
        cancelConnections();

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        mContext.registerReceiver(wifiDirectReceiver, filter);
        mReceiverRegistered = true;

        // Begin peer discovery, if appropriate, when Wi-Fi Direct ready
    }

    private void discoverPeers() {
        if (mDiscoveringPeers) {
            Timber.w("Already discovering peers. For WiFi Transport there is no meaning to simultaneously 'scanning' and 'advertising");
            return;
        }

        mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Timber.d("Peer discovery initiated");
                mDiscoveringPeers = true;
                // Restart P2P stack if discovery fails
                mPeerDiscoveryTimer = new CountDownTimer(PEER_DISCOVERY_TIMEOUT_MS, PEER_DISCOVERY_TIMEOUT_MS) {

                    public void onTick(long millisUntilFinished) {
                    }

                    public void onFinish() {
                        Timber.d("Peer Discovery timed out, restarting P2P stack");
                        resetP2PStack();
                    }
                }.start();
            }

            @Override
            public void onFailure(int reason) {
                String reasonDescription = getDescriptionForActionListenerError(reason);
                Timber.e("Peer discovery failed with reason " + reasonDescription);
            }
        });
    }

    public void onWifiDirectReady() {
        // It appears that if only one device enters discovery mode,
        // a connection will never be made. Instead, both devices enter discovery mode
        // but only the client will request connection when a peer is discovered
        //if (!localPrefersToHost*/)
        discoverPeers();
    }

    public void resetP2PStack() {
        stop();
        initializeWiFiDirect();
    }

    /**
     * Queue data for transmission to identifier
     */
    private void queueOutgoingData(byte[] data, String identifier) {
        synchronized (mOutBuffers) {
            ArrayDeque<byte[]> buffers = mOutBuffers.get(identifier);

            if (buffers == null) {
                buffers = new ArrayDeque<>();
                mOutBuffers.put(identifier, buffers);
            }

            int mtu = getMtuForIdentifier(identifier);

            int readIdx = 0;
            while (readIdx < data.length) {

                if (data.length - readIdx > mtu) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(mtu);
                    bos.write(data, readIdx, mtu);
                    buffers.add(bos.toByteArray());
                    readIdx += mtu;
                }
                else {
                    buffers.add(data);
                    break;
                }
            }

            if (VERBOSE) Timber.d("Queued %d outgoing bytes for %s", data.length, identifier);
            mOutBuffers.notify();
        }
    }

    private boolean isConnectedTo(String identifier) {
        return mConnectedPeers.contains(identifier);
    }

    private BroadcastReceiver wifiDirectReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action == null) {
                return;
            }

            final WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            final WifiP2pDeviceList deviceList = intent.getParcelableExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST);
            final WifiP2pGroup p2pGroup = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
            final WifiP2pInfo p2pInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);

            switch(action) {

                case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:

                    // UI update to indicate wifi p2p status.
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        // Wifi Direct mode is enabled
                        Timber.d("Wifi Direct enabled");
                        onWifiDirectReady();
                    } else {
                        Timber.w("Wifi Direct is not enabled");
                    }

                    break;

                case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:

                    // request available peers from the wifi p2p manager. This is an
                    // asynchronous call and the calling activity is notified with a
                    // callback on PeerListListener.onPeersAvailable()
                    if (mManager != null && mDiscoveringPeers) {
                        // TODO: How to handle when multiple P2P peers are available?
                        int numPeers = deviceList != null ? deviceList.getDeviceList().size() : 0;
                        String firstPeerStatus = numPeers > 0 ? "First peer status + " + getDescriptionForDeviceStatus(deviceList.getDeviceList().iterator().next().status) :
                                                                "";
                        Timber.d("Got %d available peers. %s", numPeers, firstPeerStatus);
                        // Only the client should initiate connection
                        if (!mLocalPrefersToHost && numPeers > 0) {
                            WifiP2pDevice connectableDevice = deviceList.getDeviceList().iterator().next();
                            if (connectableDevice.status == WifiP2pDevice.AVAILABLE) {
                                // If the peer status is available, the prior invitation is void
                                mConnectingPeers.remove(connectableDevice.deviceAddress);
                                initiateConnectionToPeer(connectableDevice);
                            }
                        } /*else
                            Timber.d("Local is host so allow client to begin connection");*/
                    } else {
                        Timber.w("Peers changed, but %s", mManager == null ? "manager is null" : "discoveringPeers is false");
                    }

                    break;

                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:

                    Timber.d("Local device is %s of %s P2P group '%s' with %d clients",
                            p2pGroup != null && p2pGroup.isGroupOwner() ? "owner" : "client",
                            p2pInfo != null && p2pInfo.groupFormed ? "formed" : "unformed",
                            p2pGroup != null ? p2pGroup.getNetworkName() : "null",
                            p2pGroup != null ? p2pGroup.getClientList().size() : 0);

                    if (mManager == null) {
                        Timber.d("Connection changed but manager null.");
                        return;
                    }

                    NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

                    if (networkInfo != null && networkInfo.isConnected()) {
                        // we are connected with the other device, request connection
                        // info to find group owner IP
                        Timber.d("Connected to %s", device != null ? device.deviceAddress : "unknown device");
                        if (mDiscoveringPeers) {
                            Timber.d("Connected to %s. Requesting connection info", device != null ? device.deviceAddress : "");
                            mManager.requestConnectionInfo(mChannel, WifiTransport.this);
                        }
                        else {
                            Timber.d("Connection was not requested. Cancelling");
                            cancelConnections();
                        }
                    }
                    else {
                        Timber.d("Network is %s", networkInfo != null ? networkInfo.getState() : "null");
                    }

                    break;

                case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:

                    Timber.d("Local device status %s", device != null ? getDescriptionForDeviceStatus(device.status) : "null");
                    if (device != null && device.status == WifiP2pDevice.CONNECTED) {
                        //noinspection StatementWithEmptyBody
                        if (mDiscoveringPeers) {
//                            Timber.d("Requesting connection info with %s", device.deviceAddress);
//                            manager.requestConnectionInfo(channel, WifiTransport.this);
                        }
                        else {
                            Timber.d("Connection was not requested. Cancelling");
                            cancelConnections();
                        }
                    }

                    break;

                case WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION:

                    boolean started = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, 0) ==
                                      WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;

                    Timber.d("P2P Discovery %s", started ? "started" : "stopped");

                    break;
            }
        }
    };

    private void initiateConnectionToPeer(WifiP2pDevice device) {

        if (!mConnectedPeers.contains(device.deviceAddress) && !mConnectingPeers.contains(device.deviceAddress)) {

            if (mSocketThread != null) {
                // TODO : Check, stop socket if different peer
                Timber.e("Cannot honor request to connect to peer. Socket already open.");
                return;
            }

            final WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = device.deviceAddress;
            config.groupOwnerIntent = mLocalPrefersToHost ? 15 : 0;

            Timber.d("Initiating connection as %s to %s type %s with status %s",
                    mLocalPrefersToHost ? "host" : "client",
                    device.deviceAddress,
                    device.primaryDeviceType,
                    getDescriptionForDeviceStatus(device.status));

            mManager.connect(mChannel, config, new DeviceConnectionListener(device));

        } else {
            Timber.w("Cannot honor request to connect to peer. Already connected");
        }
    }

    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        Timber.d("Got Connection Info");

        if (mSocketThread != null) {
            Timber.w("SocketThread already set. Will not act on connection info");
            return;
        }

        // After a connection we request connection info
        if (info.groupFormed && info.isGroupOwner) {
            Timber.d("This device is the host (group owner)");
            // At this point we want to open a socket and receive data + the client address
            startServerSocket();

        } else if (info.groupFormed) {
            // The other device is the group owner. For now we assume groups of fixed size 2
            Timber.d("Connected to %s (local is client)", info.groupOwnerAddress.getHostAddress());

            if (mConnectingPeers.size() == 1) {
                // We can associate the MAC address we initially discovered
                // with the IP address now available
                String mac = mConnectingPeers.iterator().next();
                mMacToIpAddress.put(mac, info.groupOwnerAddress.getHostAddress());
                Timber.d("associated %s with %s", mac,
                        info.groupOwnerAddress.getHostAddress());
            } else {
                    Iterator<String> iter = mConnectingPeers.iterator();
                    StringBuilder builder = new StringBuilder();
                    while (iter.hasNext()) {
                        builder.append(iter.next());
                        builder.append(", ");
                    }
                    Timber.w("Connecting to %d peers (%s)... cannot associate IP address of just-connected peer", mConnectingPeers.size(), builder.toString());
            }

            startClientSocket(info.groupOwnerAddress);
        } else {
            Timber.w("Connection established but no group formed. Wait for WIFI_P2P_CONNECTION_CHANGED_ACTION");
        }
    }

    public void startClientSocket(final InetAddress address) {
        mSocketThread = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    Socket socket = new Socket();
                    socket.bind(null);
                    Timber.d("Client opening socket to %s", address.getHostAddress());
                    socket.connect((new InetSocketAddress(address, PORT)), SOCKET_TIMEOUT_MS);

                    cancelPeerDiscoveryTimer();

                    Timber.d("Client connected to %s", address.getHostAddress());
                    mConnectedPeers.add(address.getHostAddress());
                    mCallback.get().identifierUpdated(WifiTransport.this, address.getHostAddress(), ConnectionStatus.CONNECTED, true, null);

                    maintainSocket(null, socket, address.getHostAddress());

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        mSocketThread.start();
    }

    public void startServerSocket() {
        mSocketThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {

                    ServerSocket serverSocket = new ServerSocket(PORT);

                    Timber.d("Created Server socket. Waiting for connection");
                    Socket client = serverSocket.accept();

                    cancelPeerDiscoveryTimer();

                    String clientAddress = client.getInetAddress().getHostAddress();
                    Timber.d("Connected to %s (local is server)", clientAddress);

                    mConnectedPeers.add(clientAddress);
                    mCallback.get().identifierUpdated(WifiTransport.this, clientAddress, ConnectionStatus.CONNECTED, false, null);

                    maintainSocket(serverSocket, client, client.getInetAddress().getHostAddress());

                } catch (IOException e) {
                    Timber.e(e, "Failed to read socket inputstream");
                    e.printStackTrace();
                }
            }
        });
        mSocketThread.start();
    }

    /**
     * Maintains the given socket in a read / write loop until
     * {@link #mConnectionDesired} is set false.
     */
    private void maintainSocket(@Nullable ServerSocket serverSocket, Socket socket, String remoteAddress) {
        try {
            mConnectionDesired = true;
            socket.setSoTimeout(50);

            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();

            byte[] buf = new byte[DEFAULT_MTU_BYTES];
            int len;

            while (mConnectionDesired) {

                // Read incoming data
                try {
                    while ((len = inputStream.read(buf)) > 0) {
                        ByteArrayOutputStream os = new ByteArrayOutputStream(len);
                        os.write(buf, 0, len);
                        if (VERBOSE) Timber.d("Got %d bytes from %s", len, remoteAddress);
                        mCallback.get().dataReceivedFromIdentifier(WifiTransport.this, os.toByteArray(), remoteAddress);
                    }
                } catch (SocketTimeoutException e) {
                    // No incoming data received
                    //Timber.d("No incoming data found in timeout period");
                } catch (SocketException e2) {
                    // Socket was closed
                    Timber.d("Socket closed");
                    break;
                }

                ArrayDeque<byte[]> outBuffersForPeer = mOutBuffers.get(remoteAddress);



                // Write outgoing data
                if (outBuffersForPeer != null && outBuffersForPeer.size() > 0) {
                    byte[] buffer;

                    while ((buffer = outBuffersForPeer.poll()) != null) {
                        outputStream.write(buffer);

                        if (VERBOSE) {
                            Timber.d("Wrote %d bytes to %s", buffer.length, remoteAddress);
                        }

                        mCallback.get().dataSentToIdentifier(WifiTransport.this,
                                outBuffersForPeer.poll(), remoteAddress, null);
                    }
                }
            }

            outputStream.close();
            inputStream.close();
            socket.close();
            if (serverSocket != null) serverSocket.close();

            Timber.d("%s closed socket with %s", mConnectionDesired ? "remote" : "local", remoteAddress);

            if (mCallback.get() != null) {
                mCallback.get().identifierUpdated(this, remoteAddress,
                        ConnectionStatus.DISCONNECTED, !mLocalPrefersToHost, null);
            }
        }
        catch (IOException e) {
            Timber.e(e, "Maintain socket exception");
        }
    }

    private static String getDescriptionForDeviceStatus(int status) {
        switch (status) {
            case WifiP2pDevice.CONNECTED:
                return "Connected";

            case WifiP2pDevice.INVITED:
                return "Invited";

            case WifiP2pDevice.FAILED:
                return "Failed";

            case WifiP2pDevice.AVAILABLE:
                return "Available";

            case WifiP2pDevice.UNAVAILABLE:
                return "Unavailable";

            default:
                return "?";
        }
    }

    private static String getDescriptionForActionListenerError(int error) {
        String reasonDescription = null;

        switch (error) {
            case WifiP2pManager.ERROR:
                reasonDescription = "Framework error";
                break;

            case WifiP2pManager.BUSY:
                reasonDescription = "Device busy";
                break;

            case WifiP2pManager.P2P_UNSUPPORTED:
                reasonDescription = "Device does not support WifiP2P";
                break;
        }

        return reasonDescription;
    }

    @Override
    public void onChannelDisconnected() {
        if (mManager != null && !mRetryChannel) {
            Timber.d("Channel lost, retrying...");
            mRetryChannel = true;
            mManager.initialize(mContext, Looper.getMainLooper(), this);
        }
        else {
            Timber.e("Severe! Channel is probably lost premanently. Try Disable/Re-Enable P2P.");
        }
    }

    private void cancelPeerDiscoveryTimer() {
        if (mPeerDiscoveryTimer != null) {
            mPeerDiscoveryTimer.cancel();
            mPeerDiscoveryTimer = null;
        }
    }
}
