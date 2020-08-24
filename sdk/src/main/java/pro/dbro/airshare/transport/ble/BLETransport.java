package pro.dbro.airshare.transport.ble;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import pro.dbro.airshare.transport.Transport;
import timber.log.Timber;

/**
 * Bluetooth Low Energy Transport. Requires Android 5.0.
 *
 * Note that only the Central device reports device connection events to {@link #mCallback}
 * in this implementation.
 * See {@link #identifierUpdated(pro.dbro.airshare.transport.ble.BLETransportCallback.DeviceType, String, pro.dbro.airshare.transport.Transport.ConnectionStatus, java.util.Map)}
 *
 * Created by davidbrodsky on 2/21/15.
 *
 *
 * Every Identifier gets a ByteBuffer that all outgoing data gets copied to, and
 * is read from in DEFAULT_MTU_BYTES increments for the actual sendData call.
 *
 * *** THOUGHTS ***
 *
 * Need to have buffering at SessionManager to throttle data sent from Session
 * to Transport to match data being sent from Transport
 *
 * Error recovery
 *
 * When an error happens on a Transport write we need to inform the SessionManager for resume
 *
 * What happens when the two devices fall out of sync. What happens when partial data is transferred.
 * How to re-establish Session at new offset
 */
public class BLETransport extends Transport implements BLETransportCallback {

    public static final int DEFAULT_MTU_BYTES = 155;

    public static final int TRANSPORT_CODE = 1;

    private final UUID mServiceUuid;
    private final UUID mDataUuid = UUID.fromString("72A7700C-859D-4317-9E35-D7F5A93005B1");

    /** Identifier -> Queue of outgoing buffers */
    private HashMap<String, ArrayDeque<byte[]>> mOutBuffers = new HashMap<>();

    private final BluetoothGattCharacteristic mDataCharacteristic
            = new BluetoothGattCharacteristic(mDataUuid,
                                              BluetoothGattCharacteristic.PROPERTY_READ |
                                              BluetoothGattCharacteristic.PROPERTY_WRITE |
                                              BluetoothGattCharacteristic.PROPERTY_INDICATE,

                                              BluetoothGattCharacteristic.PERMISSION_READ |
                                              BluetoothGattCharacteristic.PERMISSION_WRITE);

    private BLECentral mCentral;
    private BLEPeripheral mPeripheral;

    public BLETransport(@NonNull Context context,
                        @NonNull String serviceName,
                        @NonNull Transport.TransportCallback callback) {

        super(serviceName, callback);

        mServiceUuid = generateUUIDFromString(serviceName);

        mDataCharacteristic.addDescriptor(new BluetoothGattDescriptor(BLECentral.CLIENT_CHARACTERISTIC_CONFIG,
                                                                     BluetoothGattDescriptor.PERMISSION_WRITE |
                                                                             BluetoothGattDescriptor.PERMISSION_READ));

        mCentral = new BLECentral(context, mServiceUuid);
        mCentral.setTransportCallback(this);
        mCentral.requestNotifyOnCharacteristic(mDataCharacteristic);

        if (isLollipop()) {
            mPeripheral = new BLEPeripheral(context, mServiceUuid);
            mPeripheral.setTransportCallback(this);
            mPeripheral.addCharacteristic(mDataCharacteristic);
        }
    }

    private UUID generateUUIDFromString(String input) {
        String hexString = new String(Hex.encodeHex(DigestUtils.sha256(input)));
        StringBuilder uuid = new StringBuilder();
        // UUID has 32 hex 'digits'
        uuid.insert(0, hexString.substring(0, 32));

        uuid.insert(8, '-');
        uuid.insert(13,'-');
        uuid.insert(18,'-');
        uuid.insert(23,'-');
        Timber.d("Using UUID %s for string %s", uuid.toString(), input);
        return UUID.fromString(uuid.toString());
    }

    // <editor-fold desc="Transport">

    /**
     * Send data to the given identifiers. If identifier is unavailable data will be queued.
     * TODO: Callbacks to {@link pro.dbro.airshare.transport.Transport.TransportCallback#dataSentToIdentifier(pro.dbro.airshare.transport.Transport, byte[], String, Exception)}
     * should occur per data-sized chunk, not for each MTU-sized transmit.
     */
    @Override
    public boolean sendData(byte[] data, Set<String> identifiers) {
        boolean didSendAll = true;

        for (String identifier : identifiers) {
            boolean didSend = sendData(data, identifier);

            if (!didSend) didSendAll = false;
        }
        return didSendAll;
    }

    @Override
    public boolean sendData(@NonNull byte[] data, String identifier) {

        queueOutgoingData(data, identifier);

        if (isConnectedTo(identifier))
            return transmitOutgoingDataForConnectedPeer(identifier);

        return false;
    }

    @Override
    public void advertise() {
        if (isLollipop() && !mPeripheral.isAdvertising()) mPeripheral.start();
    }

    @Override
    public void scanForPeers() {
        if (!mCentral.isScanning()) mCentral.start();
    }

    @Override
    public void stop() {
        if (isLollipop() && mPeripheral.isAdvertising()) mPeripheral.stop();
        if (mCentral.isScanning())       mCentral.stop();
    }

    @Override
    public int getTransportCode() {
        return TRANSPORT_CODE;
    }

    @Override
    public int getMtuForIdentifier(String identifier) {
        Integer mtu = mCentral.getMtuForIdentifier(identifier);
        return (mtu == null ? DEFAULT_MTU_BYTES : mtu ) - 10;
    }

    // </editor-fold desc="Transport">

    // <editor-fold desc="BLETransportCallback">

    @Override
    public void dataReceivedFromIdentifier(DeviceType deviceType, byte[] data, String identifier) {
        if (mCallback.get() != null)
            mCallback.get().dataReceivedFromIdentifier(this, data, identifier);
    }

    @Override
    public void dataSentToIdentifier(DeviceType deviceType, byte[] data, String identifier, Exception exception) {
        Timber.d("Got receipt for %d sent bytes", data.length);

        if (mCallback.get() != null)
            mCallback.get().dataSentToIdentifier(this, data, identifier, exception);
    }

    @Override
    public void identifierUpdated(DeviceType deviceType,
                                  String identifier,
                                  ConnectionStatus status,
                                  Map<String, Object> extraInfo) {

        Timber.d("%s status: %s", identifier, status.toString());
        if (mCallback.get() != null) {

                mCallback.get().identifierUpdated(this,
                                                 identifier,
                                                 status,
                                                 deviceType == DeviceType.CENTRAL,  // If the central reported connection, the remote peer is the host
                                                 extraInfo);
        }

        if (status == ConnectionStatus.CONNECTED)
            transmitOutgoingDataForConnectedPeer(identifier);
    }

    // </editor-fold desc="BLETransportCallback">

    /**
     * Queue data for transmission to identifier
     */
    private void queueOutgoingData(byte[] data, String identifier) {
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
                Timber.d("Adding %d byte chunk to queue", bos.size());
                buffers.add(bos.toByteArray());
                readIdx += mtu;
            } else {
                Timber.d("Adding %d byte chunk to queue", data.length);
                buffers.add(data);
                break;
            }
        }
    }

    // TODO: Don't think the boolean return type is meaningful here as partial success can't be handled
    private boolean transmitOutgoingDataForConnectedPeer(String identifier) {
        ArrayDeque<byte[]> buffer = mOutBuffers.get(identifier);

        if (buffer == null) {
            return false;
        }

        byte[] toSend;
        boolean didSendAll = true;

        while ((toSend = buffer.peek()) != null) {
            boolean didSend = false;
            if (mCentral.isConnectedTo(identifier)) {
                didSend = mCentral.write(toSend, mDataCharacteristic.getUuid(), identifier);
            }
            else if (isLollipop() && mPeripheral.isConnectedTo(identifier)) {
                didSend = mPeripheral.indicate(toSend, mDataCharacteristic.getUuid(), identifier);
            }

            if (didSend) {
                Timber.d("Sent %d byte chunk to %s. %d more chunks in queue", toSend.length, identifier, buffer.size() - 1);

                buffer.poll();
            }
            else {
                Timber.w("Failed to send %d bytes to %s", toSend.length, identifier);
                didSendAll = false;

                break;
            }

            break; // For now, only attempt one data chunk at a time. Wait delivery before proceeding
        }

        return didSendAll;
    }

    private boolean isConnectedTo(String identifier) {
        return mCentral.isConnectedTo(identifier) || (isLollipop() && mPeripheral.isConnectedTo(identifier));
    }

    private static boolean isLollipop() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }
}
