package pro.dbro.airshare.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by davidbrodsky on 2/22/15.
 */
@SuppressWarnings("WeakerAccess")
public class DataTransferMessage extends SessionMessage {

    public static final String HEADER_TYPE = "datatransfer";

    public static final String HEADER_EXTRA = "extra";

    private ByteBuffer mData;
    private Map<String, Object> mExtraHeaders;

    // <editor-fold desc="Incoming Constructors">

    DataTransferMessage(@NonNull Map<String, Object> headers,
                        @Nullable byte[] body) {

        super((String) headers.get(SessionMessage.HEADER_ID));
        init();
        this.mHeaders = headers;
        mBodyLengthBytes = (int) headers.get(HEADER_BODY_LENGTH);
        mStatus = body == null ? Status.HEADER_ONLY : Status.COMPLETE;

        if (body != null) setBody(body);

        serializeAndCacheHeaders();
    }

    // </editor-fold desc="Incoming Constructors">

    // <editor-fold desc="Outgoing Constructors">

    public static DataTransferMessage createOutgoing(@Nullable Map<String, Object> extraHeaders,
                                                     @Nullable byte[] data) {

        return new DataTransferMessage(data, extraHeaders);
    }

    // To avoid confusion between the incoming constructor which takes a
    // Map of the completely deserialized headers and byte payload, we hide
    // this contstructor behind the static creator 'createOutgoing'
    private DataTransferMessage(@Nullable byte[] data,
                                @Nullable Map<String, Object> extraHeaders) {
        super();

        mExtraHeaders = extraHeaders;
        init();

        if (data != null) {
            setBody(data);
            mBodyLengthBytes = data.length;
        }

        serializeAndCacheHeaders();
    }

    // </editor-fold desc="Outgoing Constructors">

    private void init() {
        mType = HEADER_TYPE;
    }

    @Override
    protected HashMap<String, Object> populateHeaders() {
        HashMap<String, Object> headerMap = super.populateHeaders();
        if (mExtraHeaders != null)
            headerMap.put(HEADER_EXTRA, mExtraHeaders);

        // The following three lines should be deleted
//        headerMap.put(HEADER_TYPE,        type);
//        headerMap.put(HEADER_BODY_LENGTH, bodyLengthBytes);
//        headerMap.put(HEADER_ID,          id);

        return headerMap;
    }

    public void setBody(@NonNull byte[] body) {
        if (mData != null) {
            throw new IllegalStateException("Attempted to set existing message body");
        }

        mData = ByteBuffer.wrap(body);
        mStatus = Status.COMPLETE;
    }

    @Override
    public byte[] getBodyAtOffset(int offset, int length) {

        if (offset > mBodyLengthBytes - 1) return null;

        int bytesToRead = Math.min(length, mBodyLengthBytes - offset);
        byte[] result = new byte[bytesToRead];

        mData.position(offset);
        mData.get(result, 0, bytesToRead);

        return result;
    }
}
