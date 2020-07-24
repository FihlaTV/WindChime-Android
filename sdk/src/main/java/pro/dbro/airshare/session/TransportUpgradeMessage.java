package pro.dbro.airshare.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by davidbrodsky on 2/22/15.
 */
@SuppressWarnings("WeakerAccess")
public class TransportUpgradeMessage extends SessionMessage {

    public static final String HEADER_TYPE = "transport-upgrade";

    public static final String HEADER_TRANSPORT_CODE = "transport-code";

    private int mTransportCode;

    // <editor-fold desc="Incoming Constructors">

    TransportUpgradeMessage(@NonNull Map<String, Object> headers) {

        super((String) headers.get(SessionMessage.HEADER_ID));

        init();

        mTransportCode = (int) headers.get(HEADER_TRANSPORT_CODE);
        mHeaders = headers;
        mBodyLengthBytes = (int) headers.get(HEADER_BODY_LENGTH);
        mStatus = Status.COMPLETE;

        serializeAndCacheHeaders();
    }

    // </editor-fold desc="Incoming Constructors">

    // <editor-fold desc="Outgoing Constructors">

    public TransportUpgradeMessage(int transportCode) {
        super();

        init();

        mTransportCode = transportCode;

        serializeAndCacheHeaders();
    }

    // </editor-fold desc="Outgoing Constructors">

    public int getTransportCode() {
        return mTransportCode;
    }

    private void init() {
        mType = HEADER_TYPE;
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();

        headerMap.put(HEADER_TRANSPORT_CODE, mTransportCode);

        return headerMap;
    }

    @Nullable
    @Override
    public byte[] getBodyAtOffset(int offset, int length) {
        return null;
    }
}
