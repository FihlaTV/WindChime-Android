package pro.dbro.airshare.transport;

/**
 * Created by davidbrodsky on 11/14/14.
 */
public interface ConnectionGovernor {

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean shouldConnectToAddress(String address);
}
