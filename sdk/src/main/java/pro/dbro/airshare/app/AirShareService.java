package pro.dbro.airshare.app;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Objects;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import pro.dbro.airshare.crypto.KeyPair;
import pro.dbro.airshare.crypto.SodiumShaker;
import pro.dbro.airshare.session.DataTransferMessage;
import pro.dbro.airshare.session.LocalPeer;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.session.SessionManager;
import pro.dbro.airshare.session.SessionMessage;
import pro.dbro.airshare.transport.Transport;
import timber.log.Timber;

/**
 * Created by davidbrodsky on 11/4/14.
 */
@SuppressWarnings("unused")
public class AirShareService extends Service implements ActivityRecevingMessagesIndicator,
                                                        SessionManager.SessionManagerCallback {

    public interface Callback {

        void onDataReceived(@NonNull AirShareService.ServiceBinder binder,
                            @Nullable byte[] data,
                            @NonNull Peer sender,
                            @Nullable Exception exception);

        void onDataSent(@NonNull AirShareService.ServiceBinder binder,
                        @Nullable byte[] data,
                        @NonNull Peer recipient,
                        @Nullable Exception exception);

        void onPeerStatusUpdated(@NonNull AirShareService.ServiceBinder binder,
                                 @NonNull Peer peer,
                                 @NonNull Transport.ConnectionStatus newStatus,
                                 boolean peerIsHost);

        void onPeerTransportUpdated(@NonNull AirShareService.ServiceBinder binder,
                                    @NonNull Peer peer,
                                    int newTransportCode,
                                    @Nullable Exception exception);

    }

    private SessionManager mSessionManager;
    private Callback mCallback;
    private boolean mActivityRecevingMessages;
    private BiMap<Peer, ArrayDeque<OutgoingTransfer>> mOutPeerTransfers = HashBiMap.create();
    private BiMap<Peer, ArrayDeque<IncomingTransfer>> mInPeerTransfers = HashBiMap.create();
    private Set<IncomingMessageListener> mIncomingMessageListeners = new HashSet<>();
    private Set<MessageDeliveryListener> mMessageDeliveryListeners = new HashSet<>();

    private ServiceBinder mBinder;

    private Looper mBackgroundLooper;
    @SuppressWarnings("FieldCanBeLocal")
    private BackgroundThreadHandler mBackgroundHandler;
    private Handler mForegroundHandler;

    private LocalPeer mLocalPeer;

    /** Handler Messages */
    public static final int ADVERTISE     = 0;
    public static final int SCAN          = 1;
    public static final int SEND_MESSAGE  = 2;
    public static final int SHUTDOWN      = 3;

    @Override
    public void onCreate() {
        Timber.d("onCreate");
        // Start up the thread running the service.  Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block.  We also make it
        // background priority so CPU-intensive work will not disrupt our UI.
        HandlerThread thread = new HandlerThread("AirShareService", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

        // Get the HandlerThread's Looper and use it for our Handler
        mBackgroundLooper = thread.getLooper();
        mBackgroundHandler = new BackgroundThreadHandler(mBackgroundLooper);
        mForegroundHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onDestroy() {
        Timber.d("Service destroyed");
        if (mSessionManager != null) mSessionManager.stop();
        mBackgroundLooper.quit();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (mBinder == null) mBinder = new ServiceBinder();
        Timber.d("Bind service");
        return mBinder;
    }

    @Override
    public int onStartCommand (Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    /** ActivityReceivingMessagesIndicator */
    @Override
    public boolean isActivityReceivingMessages() {
        return mActivityRecevingMessages;
    }

    /** Binder through which Activities can interact with this Service */
    @SuppressWarnings({"unused", "WeakerAccess"})
    public class ServiceBinder extends Binder {

        public void registerLocalUserWithService(String userAlias, String serviceName) {
            KeyPair keyPair = SodiumShaker.generateKeyPair();
            mLocalPeer = new LocalPeer(getApplicationContext(), keyPair, userAlias);

            if (mSessionManager != null) mSessionManager.stop();

            mSessionManager = new SessionManager(AirShareService.this, serviceName, mLocalPeer, AirShareService.this);
        }

        public LocalPeer getLocalPeer() {
            return mLocalPeer;
        }

        public void advertiseLocalUser() {
            if (mSessionManager != null) mSessionManager.advertiseLocalPeer();
        }

        public void scanForOtherUsers() {
            if (mSessionManager != null) mSessionManager.scanForPeers();
        }

        public void stop() {
            if (mSessionManager != null) mSessionManager.stop();
        }

        public void setCallback(Callback callback) {
            AirShareService.this.mCallback = callback;
        }

        public void send(byte[] data, Peer recipient) {
            addOutgoingTransfer(new OutgoingTransfer(data, recipient, mSessionManager));
        }

        /**
         * Request a higher-bandwidth transport be established with the remote peer.
         * Notification of the result of this call is reported by
         * {@link pro.dbro.airshare.app.AirShareService.Callback#onPeerTransportUpdated(pro.dbro.airshare.app.AirShareService.ServiceBinder binder, pro.dbro.airshare.session.Peer, int, Exception)}
         *
         * You can check that a peer supports an additional transport via
         * {@link Peer#supportsTransportWithCode(int)} using a transport code such as
         * {@link pro.dbro.airshare.transport.wifi.WifiTransport#TRANSPORT_CODE}
         *
         * When an upgraded transport is established,
         * it may require the base transport, currently {@link pro.dbro.airshare.transport.ble.BLETransport},
         * be suspended to prevent interference.
         *
         * At this time the only available supplementary transport is
         * {@link pro.dbro.airshare.transport.wifi.WifiTransport}
         * which requires the host application add the following permissions:
         *
         * <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
         * <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
         * <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
         * <uses-permission android:name="android.permission.INTERNET" />
         * <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
         *
         * Note that upgrade transports, such as WiFi, consume considerably more power
         * and should be downgraded as soon as possible via {@link #downgradeTransport()}
         */
        public void requestTransportUpgrade(Peer remotePeer) {
            if (mSessionManager != null) mSessionManager.requestTransportUpgrade(remotePeer);
        }

        /**
         * Stops supplementary transports and returns to exclusive use of the base transport.
         * Currently this is {@link pro.dbro.airshare.transport.ble.BLETransport}
         */
        public void downgradeTransport() {
            if (mSessionManager != null) mSessionManager.downgradeTransport();
        }

        /** Get the current preferred available transport for the given peer
         *  This is generally the available transport with the highest bandwidth
         *
         *  @return either {@link pro.dbro.airshare.transport.wifi.WifiTransport#TRANSPORT_CODE}
         *                 or {@link pro.dbro.airshare.transport.ble.BLETransport#TRANSPORT_CODE},
         *                 or -1 if none available.
         */
        public int getTransportCodeForPeer(Peer remotePeer) {
            return mSessionManager != null ? mSessionManager.getTransportCodeForPeer(remotePeer) : -1;
        }

        /**
         * Set by Activity bound to this Service. If isActive is false, this Service
         * should post incoming messages as Notifications.
         *
         * Note: It seems more appropriate for this to simply be a convenience value for
         * a client application. e.g: The value is set by AirShareFragment and the client
         * application can query the state via {@link #isActivityReceivingMessages()}
         * to avoid manually keeping track of such state themselves.
         */
        public void setActivityReceivingMessages(boolean receivingMessages) {
            mActivityRecevingMessages = receivingMessages;
        }

        public boolean isActivityReceivingMessages() {
            return mActivityRecevingMessages;
        }
    }

    private void addIncomingTransfer(IncomingTransfer transfer) {
        Peer recipient = transfer.getSender();

        mIncomingMessageListeners.add(transfer);
        mMessageDeliveryListeners.add(transfer);

        if (!mInPeerTransfers.containsKey(recipient))
            mInPeerTransfers.put(recipient, new ArrayDeque<IncomingTransfer>());

        ArrayDeque<IncomingTransfer> queue = mInPeerTransfers.get(recipient);
        if (queue != null) queue.add(transfer);
    }

    private void addOutgoingTransfer(OutgoingTransfer transfer) {
        Peer recipient = transfer.getRecipient();

        mIncomingMessageListeners.add(transfer);
        mMessageDeliveryListeners.add(transfer);

        if (!mOutPeerTransfers.containsKey(recipient))
            mOutPeerTransfers.put(recipient, new ArrayDeque<OutgoingTransfer>());

        ArrayDeque<OutgoingTransfer> queue = mOutPeerTransfers.get(recipient);
        if (queue != null) queue.add(transfer);
    }

    /** Handler that processes Messages on a background thread */
    @SuppressWarnings("WeakerAccess")
    private static final class BackgroundThreadHandler extends Handler {
        public BackgroundThreadHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
//            switch (msg.what) {
//                case ADVERTISE:
//                    Log.i(TAG, "handling connect");
//                    sessionManager.advertiseLocalPeer();
//                    break;
//                case SEND_MESSAGEE:
//                    mApp.sendPublicMessageFromPrimaryIdentity((String) msg.obj);
//                    break;
//                case SHUTDOWN:
//                    Log.i(TAG, "handling shutdown");
//                    mApp.makeUnavailable();
//
//                    // Stop the service using the startId, so that we don't stop
//                    // the service in the middle of handling another job
//                    stopSelf(msg.arg1);
//                    break;
//            }
        }
    }

    private @Nullable IncomingTransfer getIncomingTransferForFileTransferMessage(SessionMessage transferMessage,
                                                                                 Peer sender) {

        IncomingTransfer incomingTransfer = null;

        ArrayDeque<IncomingTransfer> queue = mInPeerTransfers.get(sender);

        if (queue != null) {
            for (IncomingTransfer transfer : queue) {
                if (transferMessage instanceof DataTransferMessage) {
                    // If we only target API 19+, we can move to the java.util.Objects.equals
                    if (Objects.equal(transfer.getTransferId(), transferMessage.getHeaders().get(SessionMessage.HEADER_ID))) {
                        incomingTransfer = transfer;
                    }
                }
                else {
                    throw new IllegalStateException("Only DataTransferMessage is supported!");
                }
            }
        }

        return incomingTransfer;
    }

    private @Nullable OutgoingTransfer getOutgoingTransferForFileTransferMessage(SessionMessage transferMessage,
                                                                                 Peer recipient) {

        OutgoingTransfer outgoingTransfer = null;

        ArrayDeque<OutgoingTransfer> queue = mOutPeerTransfers.get(recipient);

        if (queue != null) {
            for (OutgoingTransfer transfer : queue) {
                if (transferMessage instanceof DataTransferMessage) {
                    // If we only target API 19+, we can move to the java.util.Objects.equals
                    if (Objects.equal(transfer.getTransferId(), transferMessage.getHeaders().get(SessionMessage.HEADER_ID))) {
                        outgoingTransfer = transfer;
                    }
                }
                else {
                    throw new IllegalStateException("Only DataTransferMessage is supported!");
                }
            }
        }
        return outgoingTransfer;
    }

    // <editor-fold desc="SessionManagerCallback">

    @Override
    public void peerStatusUpdated(@NonNull final Peer peer, @NonNull final Transport.ConnectionStatus newStatus, final boolean isHost) {

        mForegroundHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCallback != null)
                    mCallback.onPeerStatusUpdated(mBinder, peer, newStatus, isHost);
                else
                    Timber.w("Could not report peer status update, no callback registered");
            }
        });

    }

    @Override
    public void messageReceivingFromPeer(@NonNull SessionMessage message, @NonNull final Peer recipient, final float progress) {
        // currently unused
    }

    @Override
    public void messageReceivedFromPeer(@NonNull SessionMessage message, @NonNull final Peer sender) {
        Timber.d("Got %s message from %s", message.getType(), sender.getAlias());
        Iterator<IncomingMessageListener> iterator = mIncomingMessageListeners.iterator();
        IncomingMessageListener listener;

        while (iterator.hasNext()) {
            listener = iterator.next();

            if (!listener.onMessageReceived(message, sender))
                iterator.remove();

        }

        final IncomingTransfer incomingTransfer;
        if(message.getType().equals(DataTransferMessage.HEADER_TYPE)) {

            incomingTransfer = new IncomingTransfer((DataTransferMessage) message, sender);
            // No action is required for DataTransferMessage. Report complete
            mForegroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mCallback != null)
                        mCallback.onDataReceived(mBinder, incomingTransfer.getBodyBytes(), sender, null);
                }
            });
        }
    }

    @Override
    public void messageSendingToPeer(@NonNull SessionMessage message, @NonNull Peer recipient, float progress) {
        // currently unused
    }

    @Override
    public void messageSentToPeer(@NonNull SessionMessage message, @NonNull final Peer recipient, Exception exception) {
        Timber.d("Sent %s to %s", message.getType(), recipient.getAlias());
        Iterator<MessageDeliveryListener> iterator = mMessageDeliveryListeners.iterator();
        MessageDeliveryListener listener;

        while (iterator.hasNext()) {
            listener = iterator.next();

            if (!listener.onMessageDelivered(message, recipient, exception))
                iterator.remove();
        }

        final OutgoingTransfer outgoingTransfer;
        if (message.getType().equals(DataTransferMessage.HEADER_TYPE)) {
            outgoingTransfer = getOutgoingTransferForFileTransferMessage(message, recipient);
            // No action is required for DataTransferMessage. Report complete
            mForegroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mCallback != null && outgoingTransfer != null) {
                        mCallback.onDataSent(mBinder, outgoingTransfer.getBodyBytes(), recipient, null);
                    }
                }
            });
        }
    }

    @Override
    public void peerTransportUpdated(@NonNull final Peer peer, final int newTransportCode, @Nullable final Exception exception) {
        mForegroundHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCallback != null) mCallback.onPeerTransportUpdated(mBinder, peer, newTransportCode, exception);
            }
        });
    }

    // </editor-fold desc="SessionManagerCallback">
}