package pro.dbro.airshare.app;

import pro.dbro.airshare.session.DataTransferMessage;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.session.SessionMessage;
import pro.dbro.airshare.session.SessionMessageScheduler;

/**
 * An OutgoingTransfer wraps an outgoing data transfer.
 *
 * 1. Constructed with a byte[]
 * 2. Sends a DataTransferMessage
 *
 * Created by davidbrodsky on 3/13/15.
 */
@SuppressWarnings("WeakerAccess")
public class OutgoingTransfer extends Transfer implements IncomingMessageListener, MessageDeliveryListener {

    public enum State {

        /** Awaiting data transfer delivery" */
        AWAITING_DATA_ACK,

        /** Transfer completed */
        COMPLETE
    }

    private Peer mRecipient;
    private State mState;

    // <editor-fold desc="Outgoing Constructors">

    public OutgoingTransfer(byte[] data,
                            Peer recipient,
                            SessionMessageScheduler messageSender) {

        init(recipient, messageSender);

        mTransferMessage = DataTransferMessage.createOutgoing(null, data);
        messageSender.sendMessage(mTransferMessage, recipient);

        mState = State.AWAITING_DATA_ACK;
    }


    // </editor-fold desc="Outgoing Constructors">

    private void init(Peer recipient, SessionMessageScheduler sender) {
        mRecipient = recipient;
    }

    public String getTransferId() {
        if (mTransferMessage == null) return null;
        return (String) mTransferMessage.getHeaders().get(SessionMessage.HEADER_ID);
    }

    public Peer getRecipient() {
        return mRecipient;
    }

    @Override
    public boolean onMessageReceived(SessionMessage message, Peer recipient) {
        return false;
    }

    // </editor-fold desc="IncomingMessageInterceptor">

    // <editor-fold desc="MessageDeliveryListener">

    @Override
    public boolean onMessageDelivered(SessionMessage message, Peer recipient, Exception exception) {

        if (mState == State.AWAITING_DATA_ACK && message.equals(mTransferMessage)) {
            mState = State.COMPLETE;
            return false;
        }

        return true;
    }

    // </editor-fold desc="MessageDeliveryListener">

    @Override
    public boolean isComplete() {
        return mState == State.COMPLETE;
    }
}
