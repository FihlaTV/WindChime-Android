package pro.dbro.airshare.session;

import android.util.Base64;

import com.google.common.base.Objects;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Representation of network identity. Closely related to {@link pro.dbro.airshare.session.Peer}
 * Created by davidbrodsky on 2/22/15.
 */
@SuppressWarnings("WeakerAccess")
public class IdentityMessage extends SessionMessage {

    public static final String HEADER_TYPE = "identity";

    /** Header keys */
    public static final String HEADER_TRANSPORTS  = "transports";
    public static final String HEADER_PUBKEY      = "pubkey";
    public static final String HEADER_ALIAS       = "alias";

    private Peer mPeer;

    /**
     * Convenience creator for deserialization
     */
    public static IdentityMessage fromHeaders(Map<String, Object> headers) {
        int transports = headers.containsKey(HEADER_TRANSPORTS) ? (int) headers.get(HEADER_TRANSPORTS) : 0;

        Peer peer = new Peer(Base64.decode((String) headers.get(HEADER_PUBKEY), Base64.DEFAULT),
                             (String) headers.get(HEADER_ALIAS),
                             new Date(),
                             -1,
                             transports);

        return new IdentityMessage((String) headers.get(SessionMessage.HEADER_ID),
                                   peer);
    }

    public IdentityMessage(String id, Peer peer) {
        super(id);

        mPeer = peer;

        init();
        serializeAndCacheHeaders();
    }

    /**
     * Constructor for own identity
     * @param peer    peer to provide keypair, alias
     */
    public IdentityMessage(Peer peer) {
        super();

        mPeer = peer;

        init();
        serializeAndCacheHeaders();
    }

    private void init() {
        mType = HEADER_TYPE;
    }

    public Peer getPeer() {
        return mPeer;
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();

        headerMap.put(HEADER_ALIAS, mPeer.getAlias());
        headerMap.put(HEADER_PUBKEY, Base64.encodeToString(mPeer.getPublicKey(), Base64.DEFAULT));
        headerMap.put(HEADER_TRANSPORTS, mPeer.getTransports());

        return headerMap;
    }

    @Override
    public byte[] getBodyAtOffset(int offset, int length) {
        return null;
    }

    @Override
    public int hashCode() {
        // If we only target API 19+, we can move to java.util.Objects.hash
        return Objects.hashCode(mHeaders.get(HEADER_TYPE),
                                mHeaders.get(HEADER_BODY_LENGTH),
                                mHeaders.get(HEADER_ID),
                                mHeaders.get(HEADER_ALIAS),
                                mHeaders.get(HEADER_PUBKEY));
    }

    @Override
    public boolean equals(Object obj) {

        if(obj == this) return true;
        if(obj == null) return false;

        if (getClass().equals(obj.getClass()))
        {
            final IdentityMessage other = (IdentityMessage) obj;

            // If we only target API 19+, we can move to the java.util.Objects.equals
            return super.equals(obj) &&
                    Objects.equal(getHeaders().get(HEADER_PUBKEY),
                            other.getHeaders().get(HEADER_PUBKEY)) &&
                    Objects.equal(getHeaders().get(HEADER_ALIAS),
                            other.getHeaders().get(HEADER_ALIAS));
        }

        return false;
    }
}
