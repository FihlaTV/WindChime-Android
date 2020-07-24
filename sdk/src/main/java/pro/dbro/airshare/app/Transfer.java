package pro.dbro.airshare.app;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

import pro.dbro.airshare.session.DataTransferMessage;
import pro.dbro.airshare.session.SessionMessage;

/**
 * Created by davidbrodsky on 3/14/15.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public abstract class Transfer {

    protected SessionMessage mTransferMessage;

    public abstract boolean isComplete();

    public @Nullable InputStream getBody() {
        if (mTransferMessage instanceof DataTransferMessage) {
            return new ByteArrayInputStream(getBodyBytes());
        }
        else {
            throw new IllegalStateException("Only DataTransferMessage is supported!");
        }
    }

    public @Nullable byte[] getBodyBytes() {
        if (mTransferMessage == null) return null;

        byte[] body;

        if (mTransferMessage instanceof DataTransferMessage) {
            body = mTransferMessage.getBodyAtOffset(0, mTransferMessage.getBodyLengthBytes());
        }
        else {
            throw new IllegalStateException("Only DataTransferMessage is supported!");
        }

        return body;
    }

    public @Nullable Map<String, Object> getHeaderExtras() {
        if (mTransferMessage == null || !(mTransferMessage instanceof DataTransferMessage)) {
            return null;
        }

        //noinspection unchecked
        return (Map<String, Object>) mTransferMessage.getHeaders().get(DataTransferMessage.HEADER_EXTRA);
    }
}
