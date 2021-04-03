package com.ichi2.anki;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ichi2.anki.widgets.DeckDropDownAdapter;
import com.ichi2.async.Connection;
import com.ichi2.themes.Themes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import androidx.core.text.HtmlCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

import static com.ichi2.anim.ActivityTransitionAnimation.Direction.RIGHT;
import static com.ichi2.anki.AddonsNpmUtility.getPackageJson;
import static com.ichi2.anki.AddonsNpmUtility.isValidAddonPackage;

@SuppressWarnings("deprecation")
public class AddonsBrowser extends NavigationDrawerActivity implements DeckDropDownAdapter.SubtitleListener, AddonsAdapter.OnAddonClickListener {

    private RecyclerView addonsListRecyclerView;
    private MenuItem mInstallAddon;
    private MenuItem mGetMoreAddons;
    private MenuItem mReviewerAddons;
    private MenuItem mNoteEditorAddons;

    private long queueId;
    DownloadManager downloadManager;

    private String currentAnkiDroidDirectory;
    private File addonsHomeDir;
    private Dialog downloadDialog;

    // Update if api get updated
    private String AnkiDroidJsAPI = "0.0.1";
    private String AnkiDroidJsAddonKeywords = "ankidroid-js-addon";

    private List<AddonModel> addonsNames = new ArrayList<AddonModel>();

    private SharedPreferences preferences;

    private String[] addonTypes = {"reviewer", "note_editor"};
    // default list addons in addons browser
    private String listAddonType = addonTypes[0];


    private LinearLayout addonsMessageLayout;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return;
        }
        super.onCreate(savedInstanceState);
        Timber.d("onCreate()");
        setContentView(R.layout.addons_browser);
        initNavigationDrawer(findViewById(android.R.id.content));

        addonsMessageLayout = findViewById(R.id.addons_download_message_ll);
        addonsMessageLayout.setOnClickListener(v -> {
            openUrl(Uri.parse(getResources().getString(R.string.ankidroid_js_addon_npm_search_url)));
        });

        // Add a home button to the actionbar
        getSupportActionBar().setHomeButtonEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        showBackIcon();

        addonsListRecyclerView = findViewById(R.id.addons);
        addonsListRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(this, DividerItemDecoration.VERTICAL);
        addonsListRecyclerView.addItemDecoration(dividerItemDecoration);

        downloadDialog = new Dialog(this);

        currentAnkiDroidDirectory = CollectionHelper.getCurrentAnkiDroidDirectory(this);
        addonsHomeDir = new File(currentAnkiDroidDirectory, "addons");

        preferences = AnkiDroidApp.getSharedPrefs(this);

        // default list reviewer addons
        getSupportActionBar().setTitle(getString(R.string.reviewer_addons));
        listAddonsFromDir(addonTypes[0]);
    }


    @Override
    public String getSubtitleText() {
        return getResources().getString(R.string.addons);
    }


    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finishWithAnimation(RIGHT);

        if (downloadDialog.isShowing()) {
            downloadDialog.dismiss();
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.addon_browser, menu);
        mInstallAddon = menu.findItem(R.id.action_install_addon);
        mGetMoreAddons = menu.findItem(R.id.action_get_more_addons);
        mReviewerAddons = menu.findItem(R.id.action_addon_reviewer);
        mNoteEditorAddons = menu.findItem(R.id.action_addon_note_editor);

        mReviewerAddons.setOnMenuItemClickListener(item -> {
            getSupportActionBar().setTitle(getString(R.string.reviewer_addons));
            listAddonsFromDir(addonTypes[0]);
            return true;
        });

        mNoteEditorAddons.setOnMenuItemClickListener(item -> {
            getSupportActionBar().setTitle(getString(R.string.note_editor_addons));
            listAddonsFromDir(addonTypes[1]);
            return true;
        });

        mInstallAddon.setOnMenuItemClickListener(item -> {

            downloadDialog.setCanceledOnTouchOutside(true);
            downloadDialog.setContentView(R.layout.addon_install_from_npm);

            EditText downloadEditText = downloadDialog.findViewById(R.id.addon_download_edit_text);
            Button downloadButton = downloadDialog.findViewById(R.id.addon_download_button);

            downloadButton.setOnClickListener(v -> {
                String npmAddonName = downloadEditText.getText().toString();

                // if string is:  npm i ankidroid-js-addon-progress-bar
                if (npmAddonName.startsWith("npm i")) {
                    npmAddonName = npmAddonName.replace("npm i", "");
                }

                // if containing space
                npmAddonName = npmAddonName.trim();
                npmAddonName = npmAddonName.replaceAll("\u00A0", "");

                UIUtils.showThemedToast(this, getString(R.string.downloading_addon), true);
                AddonsNpmUtility.getPackageJson(npmAddonName, this);

                downloadDialog.dismiss();

            });

            downloadDialog.show();
            return true;
        });

        mGetMoreAddons.setOnMenuItemClickListener(item -> {
            openUrl(Uri.parse(getResources().getString(R.string.ankidroid_js_addon_npm_search_url)));
            return true;
        });

        return super.onCreateOptionsMenu(menu);
    }


    /*
       list addons with valid package.json, i.e contains
       ankidroid_js_api = 0.0.1
       keywords='ankidroid-js-addon'
       and non empty string.
       Then that addon will available for enable/disable
     */
    public void listAddonsFromDir(String addonType) {
        boolean success = true;
        if (!addonsHomeDir.exists()) {
            success = addonsHomeDir.mkdirs();
        }

        if (success) {

            // reset for new view create
            addonsNames.clear();

            File directory = new File(String.valueOf(addonsHomeDir));
            File[] files = directory.listFiles();
            for (File file : files) {
                Timber.d("Addons:%s", file.getName());

                // Read package.json from
                // AnkiDroid/addons/ankidroid-addon-../package/package.json
                File addonsPackageDir = new File(file, "package");
                File addonsPackageJson = new File(addonsPackageDir, "package.json");

                JSONObject jsonObject = AddonsNpmUtility.packageJsonReader(addonsPackageJson);

                AddonModel addonModel;
                if (isValidAddonPackage(jsonObject)) {

                    String addonName = jsonObject.optString("name", "");
                    String addonVersion = jsonObject.optString("version", "");
                    String addonDev = jsonObject.optString("author", "");
                    String addonAnkiDroidAPI = jsonObject.optString("ankidroid_js_api", "");
                    String addonHomepage = jsonObject.optString("homepage", "");
                    String typeOfAddon = jsonObject.optString("addon_type", "");

                    if (addonType.equals(typeOfAddon)) {
                        addonModel = new AddonModel(addonName, addonVersion, addonDev, addonAnkiDroidAPI, addonHomepage, addonType);
                        addonsNames.add(addonModel);
                    }
                }
            }

            addonsListRecyclerView.setAdapter(new AddonsAdapter(addonsNames, this));

            if (addonsNames.isEmpty()) {
                addonsMessageLayout.setVisibility(View.VISIBLE);
            } else {
                addonsMessageLayout.setVisibility(View.GONE);
            }

        } else {
            UIUtils.showThemedToast(this, getString(R.string.error_listing_addons), true);
        }
        hideProgressBar();
    }


    /*
      Show info about clicked addons with update and delete options
     */
    @Override
    public void onAddonClick(int position) {
        AddonModel addonModel = addonsNames.get(position);

        Dialog infoDialog = new Dialog(this);
        infoDialog.setCanceledOnTouchOutside(true);
        infoDialog.setContentView(R.layout.addon_info_popup);

        TextView name = infoDialog.findViewById(R.id.popup_addon_name_info);
        TextView ver = infoDialog.findViewById(R.id.popup_addon_version_info);
        TextView dev = infoDialog.findViewById(R.id.popup_addon_dev_info);
        TextView jsApi = infoDialog.findViewById(R.id.popup_js_api_info);
        TextView homepage = infoDialog.findViewById(R.id.popup_addon_homepage_info);
        Button buttonDelete = infoDialog.findViewById(R.id.btn_addon_delete);
        Button buttonUpdate = infoDialog.findViewById(R.id.btn_addon_update);

        name.setText(addonModel.getName());
        ver.setText(addonModel.getVersion());
        dev.setText(addonModel.getDeveloper());
        jsApi.setText(addonModel.getJsApiVersion());

        String link = "<a href='" + addonModel.getHomepage() + "'>" + addonModel.getHomepage() + "</a>";
        homepage.setClickable(true);
        homepage.setText(HtmlCompat.fromHtml(link, HtmlCompat.FROM_HTML_MODE_LEGACY));

        buttonDelete.setOnClickListener(v -> {

            AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
            alertBuilder.setTitle(addonModel.getName());
            alertBuilder.setMessage(getString(R.string.confirm_remove_addon, addonModel.getName()));
            alertBuilder.setCancelable(true);

            alertBuilder.setPositiveButton(
                    getString(R.string.dialog_ok),
                    (dialog, id) -> {
                        // remove the js addon folder
                        File dir = new File(addonsHomeDir, addonModel.getName());
                        deleteDirectory(dir);

                        // remove enabled status
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.remove(addonModel.getType() + "_addon:" + addonModel.getName());
                        editor.apply();

                        addonsNames.remove(position);
                        Objects.requireNonNull(addonsListRecyclerView.getAdapter()).notifyItemRemoved(position);
                        addonsListRecyclerView.getAdapter().notifyItemRangeChanged(position, addonsNames.size());
                        addonsListRecyclerView.getAdapter().notifyDataSetChanged();
                    });

            alertBuilder.setNegativeButton(
                    getString(R.string.dialog_cancel),
                    (dialog, id) -> dialog.cancel());

            AlertDialog deleteAlert = alertBuilder.create();
            deleteAlert.show();
            infoDialog.dismiss();
        });

        buttonUpdate.setOnClickListener(v -> {
            UIUtils.showThemedToast(this, getString(R.string.checking_addon_update), false);
            getPackageJson(addonModel.getName(), this);
        });

        infoDialog.show();
    }


    public static void deleteDirectory(File dir) {
        if (!dir.isDirectory()) {
            return;
        }

        String[] children = dir.list();
        for (String s : children) {
            File child = new File(dir, s);

            if (child.isDirectory()) {
                deleteDirectory(child);
            }
            child.delete();
        }
        dir.delete();
    }


    /**
     * @return content of index.js file for every enabled addons in script tag.
     * get enabled js addon contents from AnkiDroid/addons/...
     * This function will be called in AbstractFlashcardViewer
     */
    public static String getEnabledAddonsContent(Context context) {
        StringBuilder content = new StringBuilder();
        String mainJsFile = null;

        String currentAnkiDroidDirectory = CollectionHelper.getCurrentAnkiDroidDirectory(context);
        File addonsHomeDir = new File(currentAnkiDroidDirectory, "addons");
        SharedPreferences preferences = AnkiDroidApp.getSharedPrefs(context);

        Map<String, ?> keys = preferences.getAll();

        for (Map.Entry<String, ?> entry : keys.entrySet()) {
            Timber.d("map values %s: %s", entry.getKey(), entry.getValue());

            // get enabled status of addons in SharedPreferences with key containing 'addon', read only enabled addons
            if (!entry.getKey().contains("reviewer_addon") && entry.getValue().equals(true)) {
                continue;
            }

            /*
              split value and get latter part as it stored in SharedPreferences
              e.g: reviewer_addon:ankidroid-js-addon-12345...  -->  ankidroid-js-addon-12345...
              It is same as folder name for the addon.
             */
            try {
                String addonDirName = String.valueOf(entry.getKey().split(":")[1]);
                File finalAddonPath = new File(addonsHomeDir, addonDirName);

                if (!finalAddonPath.exists()) {
                    continue;
                }

                //Read text from file, index.js is starting point for the addon
                File addonsPackageDir = new File(finalAddonPath, "package");
                File addonPackageJson = new File(addonsPackageDir, "package.json");

                /*
                  get {'main': 'index.js'} from package.json file
                  main point to index.js file by default, according to info in package.json it may point to other .js file
                 */
                if (addonPackageJson.exists()) {
                    org.json.JSONObject jsonObject = AddonsNpmUtility.packageJsonReader(addonPackageJson);
                    if (jsonObject == null) {
                        // return empty
                        return "";
                    }
                    mainJsFile = jsonObject.optString("main", "");
                }

                // read index.js file or file pointed by "main"
                File addonsContentFile = new File(addonsPackageDir, mainJsFile);
                if (addonsContentFile.exists()) {
                    // wrap index.js content in script tag for each enabled addons
                    content.append("<script>");
                    content.append("\n");

                    BufferedReader br = new BufferedReader(new FileReader(addonsContentFile));
                    String line;

                    while ((line = br.readLine()) != null) {
                        content.append(line);
                        content.append('\n');
                    }
                    br.close();

                    content.append("</script>");
                    content.append("\n");

                    Timber.d("addon content path %s", addonsContentFile);
                }

            } catch (ArrayIndexOutOfBoundsException | IOException e) {
                Timber.w(e, "AbstractFlashcardViewer::IOException");
            }
        }

        return content.toString();
    }
}