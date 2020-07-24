package pro.dbro.airshare.session;

import android.content.Context;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import timber.log.Timber;

/**
 * This class facilitates deserializing a {@link pro.dbro.airshare.session.SessionMessage} from
 * in-order data streams.
 *
 * After construction, call {@link #dataReceived(byte[])} as data arrives and await
 * notification of deserialization events via the
 * {@link pro.dbro.airshare.session.SessionMessageDeserializer.SessionMessageDeserializerCallback}
 * passed to the constructor.
 *
 * This class assumes contiguous serialized SessionMessage chunks will be delivered in-order and
 * that any discontinuities in the data stream will be reported by the client of this class via
 * {@link #reset(boolean)}. A call to {@link #reset(boolean)} with true argument will result
 * in the loss of any partially accumulated SessionMessage.
 *
 * Created by davidbrodsky on 2/24/15.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class SessionMessageDeserializer {

    public interface SessionMessageDeserializerCallback {

        void onHeaderReady(SessionMessageDeserializer receiver, SessionMessage message);

        void onBodyProgress(SessionMessageDeserializer receiver, SessionMessage message, float progress);

        void onComplete(SessionMessageDeserializer receiver, SessionMessage message, Exception e);
    }

    /** Bodies over this size will be stored on disk */
    private static final int BODY_SIZE_CUTOFF_BYTES = 2 * 1000 * 1000; // 2 MB

    private Context mContext;
    private ByteBuffer mBuffer;
    private SessionMessageDeserializerCallback mCallback;
    private File mBodyFile;
    private OutputStream mBodyStream;
    private ByteBuffer mHeaderLengthBuffer;
    private SessionMessage mSessionMessage;

    private boolean mGotVersion;
    private boolean mGotHeaderLength;
    private boolean mGotHeader;
    private boolean mGotBody;
    private boolean mGotBodyBoundary;

    private int mHeaderLength;
    private int mBodyLength;
    private int mBodyBytesReceived;
    private int mBufferOffset;

    public SessionMessageDeserializer(Context context, SessionMessageDeserializerCallback callback) {
        mBuffer = ByteBuffer.allocate(5 * 1000);
        mCallback = callback;
        mContext = context;

        init();
    }

    /**
     * Reset the state of the receiver in preparation for a new SessionMessage.
     *
     * @param clear whether to delete unprocessed data in {@link #mBuffer}. If the data stream
     *              is interrupted and not resumable we'd want to do this. If we want to
     *              process the next message in stream, we do not.
     * losing any
     * partially accumulated SessionMessage. Call this if the incoming data stream is interrupted
     * and not expected to be immediately resumed.
     * e.g: the source of incoming data becomes unavailable.
     */
    public void reset(boolean clear) {
        mGotVersion = false;
        mGotHeaderLength = false;
        mGotHeader = false;
        mGotBody = false;
        mGotBodyBoundary = false;

        mHeaderLength = 0;
        mBodyLength = 0;
        mBodyBytesReceived = 0;

        if (clear) {
            mBufferOffset = 0;
            mBuffer.clear();

            mBodyFile = null;

            if (mBodyStream != null) {
                try {
                    mBodyStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mBodyStream = null;
            }
        }
    }

    /**
     * Process sequential chunk of a serialized {@link pro.dbro.airshare.session.SessionMessage}
     *
     * This method will call {@link #reset(boolean)} internally if data provided completes a SessionMessage.
     *
     * @param data sequential chunk of a serialized {@link pro.dbro.airshare.session.SessionMessage}
     */
    public void dataReceived(byte[] data) {
        if (data.length > mBuffer.capacity() - mBuffer.position())
            resizeBuffer(data.length);

        try {

            /* Write incoming data to memory buffer if accumulated bytes received (since construction
             * or call to {@link #reset()}) indicates we are still receiving the SessionMessage prefix
             * or header. If accumulated bytes received indicates we are receiving body, write to body OutputStream
             */
            if (mGotHeaderLength && getMessageIndex() >= getPrefixAndHeaderLengthBytes()) {

                if (mBodyLength > BODY_SIZE_CUTOFF_BYTES) {

                    if (mBodyStream == null) prepareBodyOutputStream();

                    mBodyStream.write(data);
                } else
                    mBuffer.put(data);

                mBodyBytesReceived += Math.min(mBodyLength - mBodyBytesReceived, data.length);

                if (mCallback != null && mBodyLength > 0)
                    mCallback.onBodyProgress(this, mSessionMessage, mBodyBytesReceived / (float) mBodyLength);
            }
            else {
                mBuffer.put(data);
            }

        } catch (IOException e) {
            Timber.e(e, "Failed to write data to body outputStream");
        }

        processData(data.length);


        if (!mGotBody && mGotHeader) {
            Timber.d(String.format(Locale.US, "Read %d / %d body bytes", mBodyBytesReceived, mBodyLength));
        }
    }

    /** @return the current index into the message currently being deserialized */
    private int getMessageIndex() {
        return mBuffer.position() - mBufferOffset;
    }

    private void processData(int bytesJustReceived) {
        Timber.d("Received %d bytes", bytesJustReceived);
        int dataBytesProcessed = 0;

        /* Deserialize SessionMessage Header version byte, if not yet done since construction
         * or last call to {@link #reset()}
         */
        if (!mGotVersion && getMessageIndex() >= SessionMessage.HEADER_VERSION_BYTES) {
            // Get version int from first byte
            // Check we can deserialize this version
            int version = new BigInteger(new byte[]{mBuffer.get(mBufferOffset)}).intValue();
            Timber.d("Deserialized header version %d at idx %d", version, mBufferOffset);
            if (version != SessionMessage.CURRENT_HEADER_VERSION) {
                Timber.e("Unknown SessionMessage version");
                if (mCallback != null)
                    mCallback.onComplete(this, null, new UnsupportedOperationException("Unknown SessionMessage version " + version));
                return;
            }
            dataBytesProcessed += SessionMessage.HEADER_VERSION_BYTES;
            mGotVersion = true;
        }

        /* Deserialize SessionMessage Header length bytes, if not yet done since construction
         * or last call to {@link #reset()}
         */
        if (!mGotHeaderLength && getMessageIndex() >= SessionMessage.HEADER_VERSION_BYTES +
                                                     SessionMessage.HEADER_LENGTH_BYTES) {

            // Get header length and store. Deserialize header when possible
            byte[] headerLengthBytes = new byte[SessionMessage.HEADER_LENGTH_BYTES];
            int originalPosition = mBuffer.position();
            mBuffer.position(mBufferOffset + dataBytesProcessed);
            mBuffer.get(headerLengthBytes, 0, headerLengthBytes.length);
            mBuffer.position(originalPosition);

            mHeaderLengthBuffer.clear();
            mHeaderLengthBuffer.put(headerLengthBytes);
            mHeaderLengthBuffer.rewind();

            mHeaderLength = mHeaderLengthBuffer.getInt();
            Timber.d("Deserialized header length " + mHeaderLength);
            mGotHeaderLength = true;
            dataBytesProcessed += SessionMessage.HEADER_LENGTH_BYTES;
        }

        /* Deserialize SessionMessage Header content, if not yet done since construction
         * or last call to {@link #reset()}
         */
        if (!mGotHeader && mGotHeaderLength && getMessageIndex() >= getPrefixAndHeaderLengthBytes()) {

            byte[] headerString = new byte[mHeaderLength];
            int originalBufferPosition = mBuffer.position();
            mBuffer.position(mBufferOffset + SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES);
            mBuffer.get(headerString, 0, mHeaderLength);
            mBuffer.position(originalBufferPosition);

            try {
                //noinspection CharsetObjectCanBeUsed
                JSONObject jsonHeader = new JSONObject(new String(headerString, "UTF-8"));

                HashMap<String, Object> headers = toMap(jsonHeader);

                mBodyLength = (int) headers.get(SessionMessage.HEADER_BODY_LENGTH);
                mSessionMessage = sessionMessageFromHeaders(headers);

                Timber.d(String.format(Locale.US, "Deserialized %s header indicating body length %d",
                        headers.get(SessionMessage.HEADER_TYPE), mBodyLength));

                if (mSessionMessage != null && mCallback != null) {
                    mCallback.onHeaderReady(this, mSessionMessage);
                }
            }
            catch (JSONException | UnsupportedEncodingException e) {
                // TODO : We should reset or otherwise abort this message
                e.printStackTrace();
            }

            mGotHeader = true;
            dataBytesProcessed += mHeaderLength;
        }
//        else if (!gotHeader)
//            Timber.d(String.format("Got %d / %d header bytes", buffer.position(), SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES + headerLength));

        /* If the boundary between header content and body content occurred within {@link data},
         * update {@link #mBodyBytesReceived} appropriately. Additionally, if this SessionMessage
         * requires off-memory body storage we remove body data from {@link mBuffer} and insert it into
         * the {@link mBodyStream}. Must be performed before determining body completion.
         *
         * Performed at most once per SessionMessage
         */
        if (!mGotBodyBoundary &&
                mGotHeader &&
                mBodyLength > 0 &&
                getMessageIndex() >= getPrefixAndHeaderLengthBytes()) {

            try {
                int bodyBytesJustReceived = bytesJustReceived - dataBytesProcessed;

                if (mBodyLength > BODY_SIZE_CUTOFF_BYTES) {

                    if (mBodyStream == null) prepareBodyOutputStream();

                    byte[] bodyBytes = new byte[bodyBytesJustReceived];
                    mBuffer.position(mBuffer.position() - bodyBytesJustReceived);
                    mBuffer.get(bodyBytes, 0, bodyBytesJustReceived);
                    mBuffer.position(mBuffer.position() - bodyBytesJustReceived);
                    mBodyStream.write(bodyBytes, 0, bodyBytesJustReceived);
                }

                mBodyBytesReceived += bodyBytesJustReceived;

                if (mCallback != null && mBodyLength > 0)
                    mCallback.onBodyProgress(this, mSessionMessage, mBodyBytesReceived / (float) mBodyLength);

                Timber.d(String.format(Locale.US, "Splitting received data between header (%d bytes) and body (%d bytes)", dataBytesProcessed, bodyBytesJustReceived));
                mGotBodyBoundary = true;

            } catch (IOException e) {
                Timber.d(e, "IOException");
            }
        }

        /* Construct and deliver complete SessionMessage if deserialized header and body are received */
        if (mGotHeader && !mGotBody && mBodyBytesReceived == mBodyLength) {

            Timber.d("Got body!");
            // Construct appropriate SessionMessage or child object
            if (mBodyLength > BODY_SIZE_CUTOFF_BYTES) {

                if (mSessionMessage instanceof DataTransferMessage) {
                    // TODO This shouldn't happen. We should enforce an upper limit on DataTransferMessage
                    throw new UnsupportedOperationException("Cannot have a disk-backed DataTransferMessage");
                }
            } else {
                byte[] body = new byte[mBodyLength];
                int originalPos = mBuffer.position();
                mBuffer.position(mBufferOffset + getPrefixAndHeaderLengthBytes());
                mBuffer.get(body, 0, mBodyLength);
                mBuffer.position(originalPos);

                if (mSessionMessage instanceof DataTransferMessage) {
                    ((DataTransferMessage) mSessionMessage).setBody(body);
                }
                dataBytesProcessed += mBodyLength;
            }

            if (mCallback != null) mCallback.onComplete(this, mSessionMessage, null);

            mGotBody = true;

            // Prepare for next incoming message
            mBufferOffset += (getPrefixAndHeaderLengthBytes() + mBodyLength); // The next message begins at this offset. We can't simply use dataBytesProcessed because this message may have been processed over prior calls to this method
            Timber.d("Message complete. Buffer offset %d, dataBytes processed %d", mBufferOffset, dataBytesProcessed);
            reset(false);
            if (dataBytesProcessed < bytesJustReceived) {
                Timber.d("%d / %d bytes deserialized in complete msg. Proceeding to next msg", dataBytesProcessed, bytesJustReceived);
                processData(bytesJustReceived - dataBytesProcessed);
            }
        }
    }

    private void init() {
        mHeaderLengthBuffer = ByteBuffer.allocate(Integer.SIZE / 8).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void resizeBuffer(int minLength) {
        int curLen = mBuffer.capacity();
        int curOccupied = mBuffer.position();
        int newLen = Math.max(minLength, (int) (curLen * 1.5));
        ByteBuffer newBuffer = ByteBuffer.allocate(newLen);
        mBuffer.limit(mBuffer.position());
        mBuffer.position(0);
        newBuffer.put(mBuffer);
        mBuffer = newBuffer;
        Timber.d("Buffer resized from %d (%d used) to %d. %d bytes avail", curLen, curOccupied, newLen, mBuffer.capacity() - mBuffer.position());
    }

    private void prepareBodyOutputStream() {
        mBodyFile = new File(mContext.getExternalFilesDir(null), UUID.randomUUID().toString().replace("-","") + ".body");
        try {
            mBodyStream = new FileOutputStream(mBodyFile);
        } catch (FileNotFoundException e) {
            String msg = "Failed to open body File: " + mBodyFile.getAbsolutePath();
            Timber.e(e, msg);
            throw new IllegalArgumentException(msg);
        }
    }

    private float getCurrentMessageProgress() {
        if (mBodyLength == 0) return 0;
        return mBodyBytesReceived / (float) mBodyLength;
    }

    /**
     * @return the number of bytes which the prefix and header occupy
     * This method only returns a valid value if {@link #mGotHeaderLength} is {@code true}
     */
    private int getPrefixAndHeaderLengthBytes() {
        return SessionMessage.HEADER_VERSION_BYTES + SessionMessage.HEADER_LENGTH_BYTES + mHeaderLength;
    }

    private static @Nullable SessionMessage sessionMessageFromHeaders(HashMap<String, Object> headers) {
        final String headerType = (String) headers.get(SessionMessage.HEADER_TYPE);

        if (headerType == null) {
            throw new IllegalArgumentException("headers map must have 'type' entry");
        }

        switch(headerType) {
            case IdentityMessage.HEADER_TYPE:
                return IdentityMessage.fromHeaders(headers);

            case TransportUpgradeMessage.HEADER_TYPE:
                return new TransportUpgradeMessage(headers);

            case DataTransferMessage.HEADER_TYPE:
                return new DataTransferMessage(headers, null);

            default:
                Timber.w("Unable to deserialize %s message", headerType);
                return null;

        }
    }

    // <editor-fold desc="JSON Utils">

    public static HashMap<String, Object> toMap(JSONObject object) throws JSONException {
        HashMap<String, Object> map = new HashMap<>();
        Iterator keys = object.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            map.put(key, fromJson(object.get(key)));
        }
        return map;
    }

    private static Object fromJson(Object json) throws JSONException {
        if (json == JSONObject.NULL) {
            return null;
        } else if (json instanceof JSONObject) {
            return toMap((JSONObject) json);
        } else if (json instanceof JSONArray) {
            return toList((JSONArray) json);
        } else {
            return json;
        }
    }

    public static List toList(JSONArray array) throws JSONException {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            list.add(fromJson(array.get(i)));
        }
        return list;
    }

    // </editor-fold desc="JSON Utils">
}
