package pro.dbro.airshare.app;

import pro.dbro.airshare.session.DataTransferMessage;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.session.SessionMessage;

/**
 * Facilitates responding to incoming transfer requests that require user acceptance to proceed.
 *
 * 1. Constructed with a complete DataTransferMessage
 *
 * Created by davidbrodsky on 3/13/15.
 */
@SuppressWarnings("WeakerAccess")
public class IncomingTransfer extends Transfer implements IncomingMessageListener, MessageDeliveryListener {

    private Peer mSender;

    // <editor-fold desc="Incoming Constructors">

    public IncomingTransfer(DataTransferMessage dataMessage, Peer sender) {

        mSender = sender;
        mTransferMessage = dataMessage;
    }

    // </editor-fold desc="Incoming Constructors">

    public String getTransferId() {
        return (String) mTransferMessage.getHeaders().get(SessionMessage.HEADER_ID);
    }

    public Peer getSender() {
        return mSender;
    }

    public boolean isComplete() {
        return true;
    }

    // <editor-fold desc="IncomingMessageInterceptor">

    @Override
    public boolean onMessageReceived(SessionMessage message, Peer recipient) {
        return false;
    }

    // </editor-fold desc="IncomingMessageInterceptor">

    // <editor-fold desc="MessageDeliveryListener">

    @Override
    public boolean onMessageDelivered(SessionMessage message, Peer recipient, Exception exception) {
        return false;
    }

    // </editor-fold desc="MessageDeliveryListener">
}
