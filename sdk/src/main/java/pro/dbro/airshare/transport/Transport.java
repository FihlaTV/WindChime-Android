package pro.dbro.airshare.transport;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;

/**
 * Created by davidbrodsky on 2/21/15.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public abstract class Transport implements Comparable<Transport> {

    @SuppressWarnings("unused")
    public enum ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    public interface TransportCallback {

        void dataReceivedFromIdentifier(Transport transport,
                                        byte[] data,
                                        String identifier);

        void dataSentToIdentifier(Transport transport,
                                  byte[] data,
                                  String identifier,
                                  Exception exception);

        void identifierUpdated(Transport transport,
                               String identifier,
                               ConnectionStatus status,
                               boolean peerIsHost,
                               Map<String, Object> extraInfo);
    }

    protected String mServiceName;
    protected WeakReference<TransportCallback> mCallback;

    public Transport(String serviceName, TransportCallback callback) {
        mServiceName = serviceName;
        mCallback = new WeakReference<>(callback);
    }

    public void setTransportCallback(TransportCallback callback) {
        mCallback = new WeakReference<>(callback);
    }

    @Nullable
    public TransportCallback getCallback() {
        return mCallback.get();
    }

    public abstract boolean sendData(byte[] data, Set<String> identifier);

    public abstract boolean sendData(byte[] data, String identifier);

    public abstract void advertise();

    public abstract void scanForPeers();

    public abstract void stop();

    /** Return a unique code identifying this transport.
     *  This value must be a valid bit field value that does
     *  not conflict with any existing transports.
     *
     *  see {@link pro.dbro.airshare.transport.wifi.WifiTransport#TRANSPORT_CODE}
     *  see {@link pro.dbro.airshare.transport.ble.BLETransport#TRANSPORT_CODE}
     */
    public abstract int getTransportCode();

    /**
     * @return the Maximum Transmission Unit, in bytes, or 0 if unlimited.
     */
    public abstract int getMtuForIdentifier(String identifier);

    @Override
    public int compareTo (@NonNull Transport another) {
        return getMtuForIdentifier("") - another.getMtuForIdentifier("");
    }

    @Override
    public boolean equals(Object obj) {

        if(obj == this) return true;
        if(obj == null) return false;

        if (getClass().equals(obj.getClass()))
        {
            Transport other = (Transport) obj;
            return getTransportCode() == other.getTransportCode();
        }

        return false;
    }

    @Override
    public int hashCode() {
        return getTransportCode();
    }
}
