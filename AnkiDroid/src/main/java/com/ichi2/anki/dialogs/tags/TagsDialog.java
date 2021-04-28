package com.ichi2.anki.dialogs.tags;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.ichi2.anki.R;
import com.ichi2.anki.UIUtils;
import com.ichi2.anki.analytics.AnalyticsDialogFragment;
import com.ichi2.utils.FilterResultsUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class TagsDialog extends AnalyticsDialogFragment {
    public interface TagsDialogListener {
        void onSelectedTags(List<String> selectedTags, int option);
    }



    /**
     * Enum that define all possible types of TagsDialog
     */
    public enum DialogType {
        /**
         * Adding tag to a single note
         */
        ADD_TAG,
        /**
         * Filter notes by tags
         */
        FILTER_BY_TAG,
        /**
         * A custom study session filtered by tags
         */
        CUSTOM_STUDY_TAGS
    }

    private static final String DIALOG_TYPE_KEY = "dialog_type";
    private static final String CHECKED_TAGS_KEY = "checked_tags";
    private static final String ALL_TAGS_KEY = "all_tags";

    private DialogType mType;
    private TagsList mTags;

    private String mPositiveText;
    private String mDialogTitle;
    private TagsArrayAdapter mTagsArrayAdapter;
    private int mSelectedOption = -1;

    private SearchView mToolbarSearchView;
    private MenuItem mToolbarSearchItem;

    private TextView mNoTagsTextView;
    private RecyclerView mTagsListRecyclerView;

    private MaterialDialog mDialog;

    /**
     * Initializes an instance of {@link TagsDialog} using passed parameters
     *
     * @param type the type of dialog @see {@link DialogType}
     * @param checkedTags tags of the note
     * @param allTags all possible tags in the collection
     * @return Initialized instance of {@link TagsDialog}
     */
    @NonNull
    public static TagsDialog newInstance(@NonNull DialogType type, @NonNull List<String> checkedTags, @NonNull List<String> allTags) {
        TagsDialog t = new TagsDialog();

        Bundle args = new Bundle();
        args.putInt(DIALOG_TYPE_KEY, type.ordinal());
        args.putStringArrayList(CHECKED_TAGS_KEY, new ArrayList<>(checkedTags));
        args.putStringArrayList(ALL_TAGS_KEY, new ArrayList<>(allTags));
        t.setArguments(args);

        return t;
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requireActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        mType = DialogType.values()[requireArguments().getInt(DIALOG_TYPE_KEY)];


        mTags = new TagsList(
                    requireArguments().getStringArrayList(ALL_TAGS_KEY),
                    requireArguments().getStringArrayList(CHECKED_TAGS_KEY));

        setCancelable(true);
    }


    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        @SuppressLint("InflateParams")
        View tagsDialogView = LayoutInflater.from(getActivity()).inflate(R.layout.tags_dialog, null, false);
        mTagsListRecyclerView = tagsDialogView.findViewById(R.id.tags_dialog_tags_list);
        mTagsListRecyclerView.requestFocus();

        RecyclerView.LayoutManager tagsListLayout = new LinearLayoutManager(getActivity());
        mTagsListRecyclerView.setLayoutManager(tagsListLayout);

        mTagsArrayAdapter = new TagsArrayAdapter(mTags);
        mTagsListRecyclerView.setAdapter(mTagsArrayAdapter);

        mNoTagsTextView = tagsDialogView.findViewById(R.id.tags_dialog_no_tags_textview);
        if (mTags.isEmpty()) {
            mNoTagsTextView.setVisibility(View.VISIBLE);
        }
        RadioGroup mOptionsGroup = tagsDialogView.findViewById(R.id.tags_dialog_options_radiogroup);
        for (int i = 0; i < mOptionsGroup.getChildCount(); i++) {
            mOptionsGroup.getChildAt(i).setId(i);
        }
        mOptionsGroup.check(0);

        mSelectedOption = mOptionsGroup.getCheckedRadioButtonId();
        mOptionsGroup.setOnCheckedChangeListener((radioGroup, checkedId) -> mSelectedOption = checkedId);

        if (mType == DialogType.ADD_TAG) {
            mDialogTitle = getResources().getString(R.string.card_details_tags);
            mOptionsGroup.setVisibility(View.GONE);
            mPositiveText = getString(R.string.dialog_ok);
        } else {
            mDialogTitle = getResources().getString(R.string.studyoptions_limit_select_tags);
            mPositiveText = getString(R.string.select);
        }

        adjustToolbar(tagsDialogView);

        MaterialDialog.Builder builder = new MaterialDialog.Builder(requireActivity())
                .positiveText(mPositiveText)
                .negativeText(R.string.dialog_cancel)
                .customView(tagsDialogView, false)
                .onPositive((dialog, which) -> ((TagsDialogListener)requireActivity())
                        .onSelectedTags(mTags.copyOfCheckedTagList(), mSelectedOption));
        mDialog = builder.build();

        mDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        return mDialog;
    }

    private void adjustToolbar(View tagsDialogView) {
        Toolbar mToolbar = tagsDialogView.findViewById(R.id.tags_dialog_toolbar);
        mToolbar.setTitle(mDialogTitle);

        mToolbar.inflateMenu(R.menu.tags_dialog_menu);

        // disallow inputting the 'space' character
        final InputFilter addTagFilter = (source, start, end, dest, dstart, dend) -> {
            for (int i = start; i < end; i++) {
                if (source.charAt(i) == ' ') {
                    return "";
                }
            }
            return null;
        };
        MenuItem mToolbarAddItem = mToolbar.getMenu().findItem(R.id.tags_dialog_action_add);
        mToolbarAddItem.setOnMenuItemClickListener(menuItem -> {
            String query = mToolbarSearchView.getQuery().toString();
            if (mToolbarSearchItem.isActionViewExpanded() && !TextUtils.isEmpty(query)) {
                addTag(query);
                mToolbarSearchView.setQuery("", true);
            } else {
                MaterialDialog.Builder addTagBuilder = new MaterialDialog.Builder(requireActivity())
                        .title(getString(R.string.add_tag))
                        .negativeText(R.string.dialog_cancel)
                        .positiveText(R.string.dialog_ok)
                        .inputType(InputType.TYPE_CLASS_TEXT)
                        .input(R.string.tag_name, R.string.empty_string, (dialog, input) -> addTag(input.toString()));
                final MaterialDialog addTagDialog = addTagBuilder.build();
                EditText inputET = requireDialogInputEditText(addTagDialog);
                inputET.setFilters(new InputFilter[]{addTagFilter});
                addTagDialog.show();
            }
            return true;
        });

        mToolbarSearchItem = mToolbar.getMenu().findItem(R.id.tags_dialog_action_filter);
        mToolbarSearchView = (SearchView) mToolbarSearchItem.getActionView();

        EditText queryET = mToolbarSearchView.findViewById(R.id.search_src_text);
        queryET.setFilters(new InputFilter[]{addTagFilter});

        mToolbarSearchView.setQueryHint(getString(R.string.filter_tags));
        mToolbarSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                mToolbarSearchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                TagsArrayAdapter adapter = (TagsArrayAdapter) mTagsListRecyclerView.getAdapter();
                adapter.getFilter().filter(newText);
                return true;
            }
        });

        MenuItem checkAllItem = mToolbar.getMenu().findItem(R.id.tags_dialog_action_select_all);
        checkAllItem.setOnMenuItemClickListener(menuItem -> {
            final boolean didChange = mTags.toggleAllCheckedStatuses();
            if (didChange) {
                mTagsArrayAdapter.notifyDataSetChanged();
            }
            return true;
        });

        if (mType == DialogType.ADD_TAG) {
            mToolbarSearchView.setQueryHint(getString(R.string.add_new_filter_tags));
        } else {
            mToolbarAddItem.setVisible(false);
        }
    }


    /**
     * A wrapper function around dialog.getInputEditText() to get non null {@link EditText}
     */
    @NonNull
    private EditText requireDialogInputEditText(@NonNull MaterialDialog dialog) {
        EditText editText = dialog.getInputEditText();
        if (editText == null) {
            throw new IllegalStateException("MaterialDialog " + dialog + " does not have an input edit text.");
        }
        return editText;
    }

    public void addTag(String tag) {
        if (!TextUtils.isEmpty(tag)) {
            String feedbackText;
            if (mTags.add(tag)) {
                if (mNoTagsTextView.getVisibility() == View.VISIBLE) {
                    mNoTagsTextView.setVisibility(View.GONE);
                }
                mTags.add(tag);
                mTagsArrayAdapter.sortData();
                feedbackText = getString(R.string.tag_editor_add_feedback, tag, mPositiveText);
            } else {
                feedbackText = getString(R.string.tag_editor_add_feedback_existing, tag);
            }
            mTags.check(tag);
            mTagsArrayAdapter.notifyDataSetChanged();
            // Show a snackbar to let the user know the tag was added successfully
            UIUtils.showSnackbar(getActivity(), feedbackText, false, -1, null,
                    mDialog.getView().findViewById(R.id.tags_dialog_snackbar), null);
        }
    }

    public static class TagsArrayAdapter extends  RecyclerView.Adapter<TagsArrayAdapter.ViewHolder> implements Filterable{
        public static class ViewHolder extends RecyclerView.ViewHolder {
            private final CheckedTextView mTagItemCheckedTextView;
            public ViewHolder(CheckedTextView ctv) {
                super(ctv);
                mTagItemCheckedTextView = ctv;
            }
        }



        /**
         * A reference to the {@link TagsList} passed.
         */
        @NonNull
        private final TagsList mTags;
        /**
         * A subset of all tags in {@link #mTags} satisfying the user's search
         */
        @NonNull
        private List<String> mFilteredList;

        public TagsArrayAdapter(@NonNull TagsList tags) {
            mTags = tags;
            mFilteredList = tags.copyOfAllTagList();
            sortData();
        }

        public void sortData() {
            mTags.sort();
        }

        @NonNull
        @Override
        public TagsArrayAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.tags_item_list_dialog, parent, false);

            ViewHolder vh = new ViewHolder(v.findViewById(R.id.tags_dialog_tag_item));
            vh.mTagItemCheckedTextView.setOnClickListener(view -> {
                CheckedTextView ctv = (CheckedTextView) view;
                ctv.toggle();
                String tag = ctv.getText().toString();
                if (ctv.isChecked()) {
                    mTags.check(tag);
                } else {
                    mTags.uncheck(tag);
                }
            });
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String tag = mFilteredList.get(position);
            holder.mTagItemCheckedTextView.setText(tag);
            holder.mTagItemCheckedTextView.setChecked(mTags.isChecked(tag));
        }

        @Override
        public int getItemCount() {
            return mFilteredList.size();
        }

        @Override
        public Filter getFilter() {
            return new TagsFilter();
        }

        /* Custom Filter class - as seen in http://stackoverflow.com/a/29792313/1332026 */
        private class TagsFilter extends Filter {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                if (constraint.length() == 0) {
                    mFilteredList = mTags.copyOfAllTagList();
                } else {
                    mFilteredList = new ArrayList<>();
                    final String filterPattern = constraint.toString().toLowerCase(Locale.getDefault()).trim();
                    for (String tag : mTags) {
                        if (tag.toLowerCase(Locale.getDefault()).contains(filterPattern)) {
                            mFilteredList.add(tag);
                        }
                    }
                }

                return FilterResultsUtils.fromCollection(mFilteredList);
            }

            @Override
            protected void publishResults(CharSequence charSequence, FilterResults filterResults) {
                sortData();
                notifyDataSetChanged();
            }
        }
    }
}