package pro.dbro.airshare.session;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Date;

/**
 * Created by davidbrodsky on 2/21/15.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class Peer {


    private byte[] mPublicKey;
    private String mAlias;
    private Date mLastSeen;
    private int mRssi;
    protected int mTransports;

    public Peer(byte[] publicKey,
                   String alias,
                   Date lastSeen,
                   int rssi,
                   int transports) {

        mPublicKey = publicKey;
        mAlias = alias;
        mLastSeen = lastSeen;
        mRssi = rssi;
        mTransports = transports;
    }

    public byte[] getPublicKey() {
        return mPublicKey;
    }

    public String getAlias() {
        return mAlias;
    }

    public Date getLastSeen() {
        return mLastSeen;
    }

    public int getRssi() {
        return mRssi;
    }

    public int getTransports() {
        return mTransports;
    }

    public boolean supportsTransportWithCode(int transportCode) {
        return (mTransports & transportCode) == transportCode;
    }

    @NonNull
    @Override
    public String toString() {
        return "Peer{" +
                "publicKey=" + Arrays.toString(mPublicKey) +
                ", alias='" + mAlias + '\'' +
                ", lastSeen=" + mLastSeen +
                ", rssi=" + mRssi +
                '}';
    }

    @Override
    public boolean equals(Object obj) {

        if(obj == this) return true;
        if(obj == null) return false;

        if (getClass().equals(obj.getClass()))
        {
            Peer other = (Peer) obj;
            return Arrays.equals(mPublicKey, other.mPublicKey);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mPublicKey);
    }
}
