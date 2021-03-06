package pro.dbro.airshare.app;

/**
 * Implemented by a Service or other entity to report an Activity is bound, and thus
 * in the foreground. e.g: Useful to determine whether to post message notifications.
 *
 * Created by davidbrodsky on 11/14/14.
 */
@SuppressWarnings({"UnnecessaryInterfaceModifier", "unused"})
public interface ActivityRecevingMessagesIndicator {

    public boolean isActivityReceivingMessages();
}