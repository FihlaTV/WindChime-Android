package pro.dbro.airshare.transport.ble;

import java.util.Map;

import pro.dbro.airshare.transport.Transport;

/**
 * Created by davidbrodsky on 2/23/15.
 */
public interface BLETransportCallback {

    enum DeviceType { CENTRAL, PERIPHERAL }

    void dataReceivedFromIdentifier(DeviceType deviceType,
                                    byte[] data,
                                    String identifier);

    void dataSentToIdentifier(DeviceType deviceType,
                              byte[] data,
                              String identifier,
                              Exception e);

    void identifierUpdated(DeviceType deviceType,
                           String identifier,
                           Transport.ConnectionStatus status,
                           Map<String, Object> extraInfo);
}