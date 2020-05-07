package pro.dbro.airshare.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

import pro.dbro.airshare.R;
import pro.dbro.airshare.app.AirShareService;
import pro.dbro.airshare.app.adapter.PeerAdapter;
import pro.dbro.airshare.session.Peer;
import pro.dbro.airshare.transport.Transport;

/**
 * A Fragment that supports discovering peers and sending or receiving data to/from them.
 *
 * The three static creators instantiate the Fragment in one of three modes:
 *
 * <ul>
 *     <li> SEND : The Fragment is created with a username, service name and data payload that will
 *          be sent to a peer the user selects. Completion will be indicated by the callback method
 *          {@link pro.dbro.airshare.app.ui.PeerFragment.PeerFragmentListener#onFinished(Exception)}
 *     </li>
 *
 *     <li> RECEIVE : The Fragment is created with a username and service name and will await transfer
 *          from a sending peer. Completion will be indicated by the callback method
 *          {@link pro.dbro.airshare.app.ui.PeerFragment.PeerFragmentListener#onFinished(Exception)}
 *     </li>
 *
 *     <li> BOTH : The Fragment is created with a username and service name and will await transfer
 *          from a sending peer and request data to send when a receiving peer is selected.
 *          Completion will only be indicated in case of error by the callback method
 *          {@link pro.dbro.airshare.app.ui.PeerFragment.PeerFragmentListener#onFinished(Exception)}
 *     </li>
 * </ul>
 *
 * An Activity that hosts PeerFragment must implement
 * {@link pro.dbro.airshare.app.ui.PeerFragment.PeerFragmentListener}
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class PeerFragment extends AirShareFragment implements AirShareService.Callback,
                                                              AirShareFragment.Callback {

    /** Bundle parameters */
    // AirShareFragment provides username, servicename
    private static final String ARG_MODE    = "mode";
    private static final String ARG_PAYLOAD = "payload";

    public enum Mode { SEND, RECEIVE, BOTH }

    public interface PeerFragmentListener {

        /**
         * A transfer was received from a peer.
         * Called when mode is {@link Mode#RECEIVE} or {@link Mode#BOTH}
         */
        void onDataReceived(@NonNull PeerFragment fragment,
                            @Nullable byte[] payload,
                            @NonNull Peer sender);

        /**
         * A transfer was sent to a peer.
         * Called when mode is {@link Mode#SEND} or {@link Mode#BOTH}
         */
        void onDataSent(@NonNull PeerFragment fragment,
                        @Nullable byte[] data,
                        @NonNull Peer recipient);

        /**
         * The user selected recipient to receive data. Provide that data in a call
         * to {@link #sendDataToPeer(byte[], pro.dbro.airshare.session.Peer)}
         * Called when mode is {@link Mode#BOTH}
         */
        void onDataRequestedForPeer(@NonNull PeerFragment fragment,
                                    @NonNull Peer recipient);

        /**
         * The fragment is complete and should be removed by the host Activity.
         *
         * If exception is null, the fragment has completed it's requested operation,
         * else an error occurred.
         */
        void onFinished(@NonNull PeerFragment fragment,
                        @Nullable Exception exception);

    }

    private ViewGroup mEmptyContainer;
    private PeerAdapter mPeerAdapter;
    private PeerFragmentListener mCallback;
    private AirShareService.ServiceBinder mServiceBinder;

    private Mode mMode;

    private byte[] mPayload;

    public static PeerFragment toSend(@NonNull byte[] toSend,
                                      @NonNull String username,
                                      @NonNull String serviceName) {
        Bundle bundle = new Bundle();
        bundle.putSerializable(ARG_PAYLOAD, toSend);
        return init(bundle, Mode.SEND, username, serviceName);
    }

    public static PeerFragment toReceive(@NonNull String username,
                                         @NonNull String serviceName) {
        return init(null, Mode.RECEIVE, username, serviceName);
    }

    public static PeerFragment toSendAndReceive(@NonNull String username,
                                                @NonNull String serviceName) {
        return init(null, Mode.BOTH, username, serviceName);
    }

    private static PeerFragment init(@Nullable Bundle bundle,
                                     @NonNull Mode mode,
                                     @NonNull String username,
                                     @NonNull String serviceName) {

        if (bundle == null) bundle = new Bundle();

        bundle.putSerializable(ARG_MODE, mode);
        bundle.putString(AirShareFragment.ARG_USERNAME, username);
        bundle.putString(AirShareFragment.ARG_SERVICENAME, serviceName);

        PeerFragment fragment = new PeerFragment();
        fragment.setArguments(bundle);
        return fragment;
    }

    public PeerFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setAirShareCallback(this);
        if (getArguments() != null) {
            mMode = (Mode) getArguments().getSerializable(ARG_MODE);
            mPayload = (byte[]) getArguments().getSerializable(ARG_PAYLOAD);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        Context context = getActivity();
        View root = inflater.inflate(R.layout.fragment_peer, container, false);
        mPeerAdapter = new PeerAdapter(context, new ArrayList<Peer>());
        mEmptyContainer = root.findViewById(R.id.empty_container);
        RecyclerView recyclerView = root.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(mPeerAdapter);

        mPeerAdapter.setOnPeerViewClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPeerSelected((Peer) v.getTag());
            }
        });
        return root;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        try {
            mCallback = (PeerFragmentListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString()
                    + " must implement PeerFragmentListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mCallback = null;
    }

    /**
     * Send data to recipient. Used when mode is {@link Mode#BOTH}.
     *
     * Should be called by a client in response to the PeerFragmentCallback method:
     * {@link pro.dbro.airshare.app.ui.PeerFragment.PeerFragmentListener#onDataRequestedForPeer(PeerFragment, Peer)}
     */
    public void sendDataToPeer(byte[] data, Peer recipient) {
        mServiceBinder.send(data, recipient);
    }

    /** An available peer was selected from {@link pro.dbro.airshare.app.adapter.PeerAdapter} */
    public void onPeerSelected(Peer peer) {
        switch (mMode) {
            case SEND:

                mServiceBinder.send(mPayload, peer);
                break;

            case BOTH:

                mCallback.onDataRequestedForPeer(this, peer);
                break;

            case RECEIVE:
                // do nothing
                break;
        }
    }

    @Override
    public void onDataRecevied(@NonNull AirShareService.ServiceBinder binder, byte[] data, @NonNull Peer sender, Exception exception) {
        if (mCallback == null) return; // Fragment was detached but not destroyed

        mCallback.onDataReceived(this, data, sender);

        if (mMode == Mode.RECEIVE)
            mCallback.onFinished(this, null);
    }

    @Override
    public void onDataSent(@NonNull AirShareService.ServiceBinder binder, byte[] data, @NonNull Peer recipient, Exception exception) {
        if (mCallback == null) return; // Fragment was detached but not destroyed
        mCallback.onDataSent(this, data, recipient);

        if (mMode == Mode.SEND)
            mCallback.onFinished(this, null);
    }

    @Override
    public void onPeerStatusUpdated(@NonNull AirShareService.ServiceBinder binder, @NonNull Peer peer, @NonNull Transport.ConnectionStatus newStatus, boolean peerIsHost) {
        switch (newStatus) {
            case CONNECTED:
                mPeerAdapter.notifyPeerAdded(peer);

                mEmptyContainer.setVisibility(View.GONE);
                break;

            case DISCONNECTED:
                mPeerAdapter.notifyPeerRemoved(peer);
                if (mPeerAdapter.getItemCount() == 0) {
                    mEmptyContainer.setVisibility(View.VISIBLE);
                }
                break;
        }
    }

    @Override
    public void onPeerTransportUpdated(@NonNull AirShareService.ServiceBinder binder, @NonNull Peer peer, int newTransportCode, @Nullable Exception exception) {
        // do nothing for now
    }

    @Override
    public void onServiceReady(@NonNull AirShareService.ServiceBinder serviceBinder) {
        this.mServiceBinder = serviceBinder;
        this.mServiceBinder.setCallback(this);

        switch (mMode) {
            case SEND:
                this.mServiceBinder.scanForOtherUsers();
                break;

            case RECEIVE:
                this.mServiceBinder.advertiseLocalUser();
                break;

            case BOTH:
                this.mServiceBinder.scanForOtherUsers();
                this.mServiceBinder.advertiseLocalUser();
        }
    }

    @Override
    public void onFinished(Exception e) {
        if (mCallback == null) return; // Fragment was detached but not destroyed
        mCallback.onFinished(this, e);
    }

}
