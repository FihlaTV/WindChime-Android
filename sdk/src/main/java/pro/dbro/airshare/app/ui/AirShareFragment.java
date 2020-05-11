package pro.dbro.airshare.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import pro.dbro.airshare.app.AirShareService;
import pro.dbro.airshare.transport.ble.BleUtil;
import timber.log.Timber;

/**
 * Convenience fragment for interacting with AirShare. Handles connecting to AirShare Service,
 * prompting user to enable Bluetooth etc.
 *
 * Implementation classes
 * must implement {@link pro.dbro.airshare.app.ui.AirShareFragment.Callback}
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class AirShareFragment extends Fragment implements ServiceConnection {

    public interface Callback {

        /**
         * Indicates AirShare is ready
         */
        void onServiceReady(@NonNull AirShareService.ServiceBinder serviceBinder);

        /**
         * Indicates the AirShare service is finished.
         * This would occur if the user declined to enable required resources like Bluetooth
         */
        void onFinished(@Nullable Exception exception);

    }

    protected static final String ARG_USERNAME = "uname";
    protected static final String ARG_SERVICENAME = "sname";

    private Callback mCallback;
    private String mUsername;
    private String mServicename;
    private AirShareService.ServiceBinder mServiceBinder;
    private boolean mDidIssueServiceUnbind = false;
    private boolean mServiceBound = false;  // Are we bound to the ChatService?
    private boolean mBluetoothReceiverRegistered = false; // Are we registered for Bluetooth status broadcasts?
    private boolean mOperateInBackground = false;

    private AlertDialog mBluetoothEnableDialog;

    public static AirShareFragment newInstance(String username, String serviceName, Callback callback) {

        Bundle bundle = new Bundle();
        bundle.putString(ARG_USERNAME, username);
        bundle.putString(ARG_SERVICENAME, serviceName);

        AirShareFragment fragment = new AirShareFragment();
        fragment.setArguments(bundle);
        fragment.setAirShareCallback(callback);
        return fragment;
    }

    public AirShareFragment() {
        super();
    }

    public void setAirShareCallback(Callback callback) {
        mCallback = callback;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Retain our instance across Activity re-creations unless added to back stack
        setRetainInstance(true);
        mServiceBound = false; // onServiceDisconnected may not be called before fragment destroyed

        Bundle args = getArguments();

        if (args != null) {
            mUsername = args.getString(ARG_USERNAME);
            mServicename = args.getString(ARG_SERVICENAME);
        }

        if (mUsername == null || mServicename == null)
            throw new IllegalStateException("username and servicename cannot be null");
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!mServiceBound) {
            mDidIssueServiceUnbind = false;
            startAndBindToService();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mServiceBinder != null) {
            mServiceBinder.setActivityReceivingMessages(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mServiceBinder != null) {
            mServiceBinder.setActivityReceivingMessages(false);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mServiceBound && !mDidIssueServiceUnbind) {
            Timber.d("Unbinding service. %s", shouldServiceContinueInBackground() ? "service will continue in bg" : "service will be closed");
            mDidIssueServiceUnbind = true;
            unBindService();
            unregisterBroadcastReceiver();

            if (!shouldServiceContinueInBackground())
                stopService();
        }
    }

    /**
     * @return whether the AirShareService should remain active after {@link #onStop()}
     * if false, the service will be re-started on {@link #onStart()}
     */
    public boolean shouldServiceContinueInBackground() {
        return mOperateInBackground;
    }

    public void setShouldServiceContinueInBackground(boolean shouldContinueInBackground) {
        mOperateInBackground = shouldContinueInBackground;
    }

    public void stopService() {
        Timber.d("Stopping service");
        Activity host = getActivity();

        if (host != null) {
            host.stopService(new Intent(host, AirShareService.class));
        }
    }

    private void startAndBindToService() {
        Timber.d("Starting service");
        Activity host = getActivity();

        if (host != null) {
            Intent intent = new Intent(host, AirShareService.class);
            host.startService(intent);
            host.bindService(intent, this, 0);
        }
    }

    private void unBindService() {
        Activity host = getActivity();

        if (host != null) {
            host.unbindService(this);
        }
    }

    private final BroadcastReceiver mBluetoothBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        break;
                    case BluetoothAdapter.STATE_ON:
                        if (mBluetoothEnableDialog != null && mBluetoothEnableDialog.isShowing()) {
                            mBluetoothEnableDialog.dismiss();
                        }
                        Timber.d("Bluetooth enabled");
                        checkDevicePreconditions();
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        break;
                }
            }
        }
    };

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        mServiceBinder = (AirShareService.ServiceBinder) iBinder;
        mServiceBound = true;
        Timber.d("Bound to service");
        checkDevicePreconditions();

        mServiceBinder.setActivityReceivingMessages(true);
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        Timber.d("Unbound from service");
        mServiceBinder = null;
        mServiceBound = false;
    }

    private void checkDevicePreconditions() {
        if (!BleUtil.isBluetoothEnabled(getActivity())) {
            // Bluetooth is not Enabled.
            // await result in OnActivityResult
            registerBroadcastReceiver();
            showEnableBluetoothDialog();
        }
        else {
            // Bluetooth Enabled, Register primary identity
            mServiceBinder.registerLocalUserWithService(mUsername, mServicename);

            if (mCallback != null) mCallback.onServiceReady(mServiceBinder);
        }
    }

    private void registerBroadcastReceiver() {
        Activity host = getActivity();

        if (host != null) {
            host.registerReceiver(mBluetoothBroadcastReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            mBluetoothReceiverRegistered = true;
        }
    }

    private void unregisterBroadcastReceiver() {
        Activity host = getActivity();

        if (host != null && mBluetoothReceiverRegistered) {
            host.unregisterReceiver(mBluetoothBroadcastReceiver);
            mBluetoothReceiverRegistered = false;
        }
    }

    private void showEnableBluetoothDialog() {
        final Activity host = getActivity();

        if (host == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("Enable Bluetooth")
                .setMessage("This app requires Bluetooth on to function. May we enable Bluetooth?")
                .setPositiveButton("Enable Bluetooth", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        mBluetoothEnableDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        mBluetoothEnableDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                        ((TextView) mBluetoothEnableDialog.findViewById(android.R.id.message)).setText("Enabling...");
                        BleUtil.getManager(host).getAdapter().enable();
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        if (mCallback != null)
                            mCallback.onFinished(new UnsupportedOperationException("User declined to enable Bluetooth"));
                    }
                });
        builder.setCancelable(false);
        mBluetoothEnableDialog = builder.create();

        mBluetoothEnableDialog.show();
    }
}
