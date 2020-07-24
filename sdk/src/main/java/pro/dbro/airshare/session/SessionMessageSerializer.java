package pro.dbro.airshare.session;

import androidx.annotation.Nullable;
import android.util.Pair;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * This class facilitates queuing {@link pro.dbro.airshare.session.SessionMessage}s
 * for sequential serialization
 *
 * Created by davidbrodsky on 3/12/15.
 */
@SuppressWarnings("WeakerAccess")
public class SessionMessageSerializer {

    private static final boolean VERBOSE = false;

    private ArrayList<Pair<Integer, SessionMessage>> mCompletedMessages;
    private ArrayDeque<SessionMessage> mMessages;
    private byte[] mLastChunk;
    private int mMarker;
    private int mSerializeCount;
    private int mAckCount;

    public SessionMessageSerializer(final SessionMessage message) {
        this(new ArrayList<SessionMessage>() {{ add(message); }});
    }

    public SessionMessageSerializer(List<SessionMessage> messages) {
        mMessages = new ArrayDeque<>();
        mMessages.addAll(messages);
        mCompletedMessages = new ArrayList<>();
        mMarker = 0;
        mSerializeCount = 0;
        mAckCount = 0;
    }

    public @Nullable SessionMessage getCurrentMessage() {
        return mMessages.peek();
    }

    public void queueMessage(SessionMessage message) {
        mMessages.offer(message);
    }

    public float getCurrentMessageProgress() {
        if (getCurrentMessage() == null) return 1;

        return ((float) mMarker) / getCurrentMessage().getTotalLengthBytes();
    }

    /**
     * Read up to length bytes of the current outgoing SessionMessage.
     * If length is 0, a fixed memory-safe size will be read.
     *
     * If {@param length} extends beyond the bytes left in the current message,
     * the result will be a byte[] of lesser length containing the completion of the current message.
     *
     * The chunk returned will not advance until a corresponding call to {@link #ackChunkDelivery()}
     */
    public byte[] getNextChunk(int length) {
        if (mLastChunk != null) return mLastChunk;

        if (mMessages.size() == 0) return null;

        SessionMessage message = mMessages.peek();


        length = Math.min(length, 500 * 1024);
        byte[] result = message == null ? null : message.serialize(mMarker, length);

        if (result == null) {
            Timber.d("Completed %s message (%d / %d bytes)", message == null ? "null" : message.getType(),
                    mMarker, message == null ? 0 : message.getTotalLengthBytes());

            mCompletedMessages.add(new Pair<>(mSerializeCount, mMessages.poll()));
            mMarker = 0;

            return getNextChunk(length);
        }
        else {
            mMarker += result.length;
            mSerializeCount++;
            //Timber.d("getNextChunk");
        }

        mLastChunk = result;
        return result;
    }

    /**
     * @return a Pair containing the message delivery progress and the
     * {@link pro.dbro.airshare.session.SessionMessage} corresponding
     * to the chunk being acknowledged. Assumes sequential delivery of chunks returned
     * by {@link #getNextChunk(int)}
     */
    public @Nullable Pair<SessionMessage, Float> ackChunkDelivery() {
        mAckCount++;
        if (VERBOSE) Timber.d("Ack");
        SessionMessage message = null;
        float progress = 0;

        for (Pair<Integer, SessionMessage> messagePair : mCompletedMessages) {
            if (messagePair.first >= mAckCount) {
                message = messagePair.second;
                progress = ((float) mAckCount) / messagePair.first;
                if (VERBOSE) Timber.d("ackChunkDelivery reporting prev msg progress %f", progress);
                break;
            }
        }

        if (message == null) {
            message = mMessages.peek();
            progress = getCurrentMessageProgress();
            if (VERBOSE) Timber.d("ackChunkDelivery reporting current progress %f", progress);
        }

        if (message == null) return null; // Acknowledgements have fallen out of sync!

        mLastChunk = null;
        return new Pair<>(message, progress);
    }

}
