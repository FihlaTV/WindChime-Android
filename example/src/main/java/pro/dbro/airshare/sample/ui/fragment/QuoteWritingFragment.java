package pro.dbro.airshare.sample.ui.fragment;


import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import pro.dbro.airshare.sample.R;

/**
 * A simple {@link Fragment} subclass.
 */
public class QuoteWritingFragment extends Fragment {

    public interface WritingFragmentListener {

        void onShareRequested(String quote, String author);
    }

    private WritingFragmentListener listener;
    private EditText quoteEntry;
    private EditText authorEntry;

    public QuoteWritingFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View root = inflater.inflate(R.layout.fragment_writing, container, false);
        quoteEntry = root.findViewById(R.id.quote_entry);
        authorEntry = root.findViewById(R.id.author_entry);

        root.findViewById(R.id.share_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onShareRequested(quoteEntry.getText().toString(),
                                          authorEntry.getText().toString());
            }
        });

        return root;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        try {
            listener = (WritingFragmentListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString()
                    + " must implement WritingFragmentListener");
        }
    }
}
