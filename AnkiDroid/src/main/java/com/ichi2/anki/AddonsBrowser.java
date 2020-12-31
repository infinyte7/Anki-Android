package com.ichi2.anki;

import android.app.Dialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;

import com.ichi2.anki.widgets.DeckDropDownAdapter;
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

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import timber.log.Timber;

import static com.ichi2.anim.ActivityTransitionAnimation.Direction.RIGHT;

@SuppressWarnings("deprecation")
public class AddonsBrowser extends NavigationDrawerActivity implements DeckDropDownAdapter.SubtitleListener {

    private RecyclerView addonsList;
    @Nullable
    private Menu mActionBarMenu;
    private MenuItem mInstallAddon;
    private MenuItem mGetMoreAddons;

    private long queueId;
    DownloadManager downloadManager;

    private String currentAnkiDroidDirectory;
    private File addonsHomeDir;
    private Dialog downloadDialog;

    // Update if api get updated
    private String AnkiDroidJsAPI = "0.0.1";
    private String AnkiDroidJsAddonKeywords = "ankidroid-js-addon";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Themes.setThemeLegacy(this);
        if (showedActivityFailedScreen(savedInstanceState)) {
            return;
        }
        super.onCreate(savedInstanceState);
        Timber.d("onCreate()");
        setContentView(R.layout.addons_browser);
        initNavigationDrawer(findViewById(android.R.id.content));

        // Add a home button to the actionbar
        getSupportActionBar().setHomeButtonEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        showBackIcon();
        setTitle(getResources().getText(R.string.addons));

        addonsList = (RecyclerView) findViewById(R.id.addons);
        addonsList.setLayoutManager(new LinearLayoutManager(this));
        downloadDialog = new Dialog(this);

        listAddonsFromDir();

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
        mActionBarMenu = menu;
        getMenuInflater().inflate(R.menu.addon_browser, menu);
        mInstallAddon = menu.findItem(R.id.action_install_addon);
        mGetMoreAddons = menu.findItem(R.id.action_get_more_addons);

        mInstallAddon.setOnMenuItemClickListener(item -> {

            downloadDialog.setCanceledOnTouchOutside(true);
            downloadDialog.setContentView(R.layout.addon_install_from_npm);

            EditText downloadEditText = downloadDialog.findViewById(R.id.addon_download_edit_text);
            Button downloadButton = downloadDialog.findViewById(R.id.addon_download_button);

            downloadButton.setOnClickListener(v->{

                showProgressBar();

                String npmAddonName = downloadEditText.getText().toString();

                // if string is:  npm i ankidroid-js-addon-progress-bar
                if (npmAddonName.startsWith("npm i")) {
                    npmAddonName = npmAddonName.replace("npm i", "");
                }

                // if containing space
                npmAddonName = npmAddonName.trim();
                npmAddonName = npmAddonName.replaceAll("\u00A0", "");

                String url = "https://registry.npmjs.org/" + npmAddonName + "/latest";

                //getContentFromUrl(url, ankidroidAddonPackageJson);
                downloadFileFromUrl(url, npmAddonName+".json");

                downloadDialog.dismiss();
            });

            downloadDialog.show();
            return true;
        });

        mGetMoreAddons.setOnMenuItemClickListener(item -> {
            String baseUrl = "https://www.npmjs.com/search?q=keywords:";
            String url = baseUrl + AnkiDroidJsAddonKeywords;
            Uri uri = Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
            return true;
        });

        return super.onCreateOptionsMenu(menu);
    }

    /* list addons with valid packge.json, i.e contains ankidroid_js_api = 0.0.1, keywords='ankidroid-js-addon'
       and non empty string.
       Then that addon will available for enable/disable
     */
    public void listAddonsFromDir() {
        currentAnkiDroidDirectory = CollectionHelper.getCurrentAnkiDroidDirectory(this);
        addonsHomeDir = new File(currentAnkiDroidDirectory, "addons" );

        boolean success = true;
        if (!addonsHomeDir.exists()) {
            success = addonsHomeDir.mkdirs();
        }

        List<AddonModel> addonsNames = new ArrayList<AddonModel>();

        if (success) {
            File directory = new File(String.valueOf(addonsHomeDir));
            File[] files = directory.listFiles();
            for (File file : files) {
                Timber.d("Addons:%s", file.getName());

                // Read package.json from
                // AnkiDroid/addons/ankidroid-addon-../package/package.json
                File addonsPackageDir = new File(file, "package");
                File addonsPackageJson = new File(addonsPackageDir, "package.json");

                AddonModel addonModel;
                if (isValidAddonPackage(addonsPackageJson)) {

                    JSONObject jsonObject = packageJsonReader(addonsPackageJson);
                    String addonName = jsonObject.optString("name", "");
                    String addonVersion = jsonObject.optString("version", "");
                    String addonDev = jsonObject.optString("author", "");
                    String addonAnkiDroidAPI = jsonObject.optString("ankidroid_js_api", "");
                    String addonHomepage = jsonObject.optString("homepage", "");

                    addonModel = new AddonModel(addonName, addonVersion, addonDev, addonAnkiDroidAPI, addonHomepage);
                    addonsNames.add(addonModel);
                }
            }

            addonsList.setAdapter(new AddonsAdapter(addonsNames));
            hideProgressBar();
        } else {
            UIUtils.showThemedToast(this, getString(R.string.error_listing_addons), true);
        }
    }

    // download package.json and addon.tgz file from url
    public void downloadFileFromUrl(String url, String fileName) {
        downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle(fileName);

        File downloadFile = new File(Environment.DIRECTORY_DOWNLOADS + fileName);

        // remove old file
        if (downloadFile.exists()) {
            downloadFile.delete();
        }

        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

        queueId = downloadManager.enqueue(request);

        registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }


    BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(queueId);

                Cursor cursor = downloadManager.query(query);

                if (cursor.moveToFirst()) {
                    int columIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);

                    if (DownloadManager.STATUS_SUCCESSFUL == cursor.getInt(columIndex)) {
                        String uri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_TITLE));

                        String tarballLink;

                        if (uri.endsWith(".json")) {

                            tarballLink = getNpmTarball(uri);
                            // tarball contains valid url for download
                            if (!tarballLink.isEmpty()) {
                                downloadFileFromUrl(tarballLink, uri.replace(".json", "") + ".tgz");
                            }

                        } else if (uri.endsWith(".tgz")) {
                            extractTarball(uri);
                        }

                        UIUtils.showThemedToast(context, uri, true);
                    }
                }

            } else {
                UIUtils.showThemedToast(context, getString(R.string.error_downloading_file), false);
            }
        }
    };

    // extract ankidroid-js-addon....tgz file and copy it AnkiDroid/addons folder
    private void extractTarball(String uri) {
        File addonsDir = new File(addonsHomeDir, uri.replace(".tgz", ""));

        String tarballPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + Uri.parse(uri).getPath();
        File tarballFile = new File(tarballPath);

        // extracting using library https://github.com/thrau/jarchivelib
        try {
            Archiver archiver = ArchiverFactory.createArchiver(new File(tarballPath));
            archiver.extract(tarballFile, addonsDir);

            // remove after extract
            if (tarballFile.exists()) {
                tarballFile.delete();
            }

            listAddonsFromDir();
            hideProgressBar();

            UIUtils.showThemedToast(this, "Addons downloaded to addons folder", false);
        } catch (IOException e) {
            Timber.e(e);
        }
    }

    // get tarball info from package.json of ankidroid-js-addon...
    private String getNpmTarball(String uri) {
        String tarballUrl = "";
        File packageJsonFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Uri.parse(uri).getPath());

        if (isValidAddonPackage(packageJsonFile)) {
            try {
                JSONObject jsonObject  = packageJsonReader(packageJsonFile);
                JSONObject dist = jsonObject.getJSONObject("dist");
                tarballUrl = dist.get("tarball").toString();
                Timber.d("tarball link %s", dist.get("tarball"));

                // remove after getting tarball link
                if (packageJsonFile.exists()) {
                    packageJsonFile.delete();
                }
            } catch (JSONException e) {
                Timber.e(e.getLocalizedMessage());
            }
        }

        return tarballUrl;
    }

    // read package.json file of ankidroid-js-addon...
    public static JSONObject packageJsonReader(File addonsFiles) {
        JSONObject jsonObject = null;
        try {
            FileReader fileReader = new FileReader(addonsFiles);
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            StringBuilder stringBuilder = new StringBuilder();
            String line = bufferedReader.readLine();
            while (line != null){
                stringBuilder.append(line).append("\n");
                line = bufferedReader.readLine();
            }
            bufferedReader.close();

            String response = stringBuilder.toString();
            jsonObject  = new JSONObject(response);
            
        } catch (IOException | JSONException e) {
            Timber.e(e.getLocalizedMessage());
        }
        return jsonObject;
    }

    // is package.json of ankidroid-js-addon... contains valid ankidroid_js_api==0.0.1 and keywords 'ankidroid-js-addon'
    public boolean isValidAddonPackage(File addonsPackageJson) {

        if (addonsPackageJson.exists()) {

            JSONObject jsonObject  = packageJsonReader(addonsPackageJson);

            String addonName = jsonObject.optString("name", "");
            String addonVersion = jsonObject.optString("version", "");
            String addonDev = jsonObject.optString("author", "");
            String addonAnkiDroidAPI = jsonObject.optString("ankidroid_js_api", "");
            String addonHomepage = jsonObject.optString("homepage", "");
            boolean jsAddonKeywordsPresent = false;

            try {
                JSONArray keywords = jsonObject.getJSONArray("keywords");
                for (int j = 0; j < keywords.length(); j++) {
                    String addonKeyword = keywords.getString(j);
                    if (addonKeyword.equals(AnkiDroidJsAddonKeywords)) {
                        jsAddonKeywordsPresent = true;
                        break;
                    }
                }
                Timber.d("keywords %s", keywords.toString());
            } catch (JSONException e) {
                Timber.e(e.getLocalizedMessage());
            }

            // if ankidroid_js_api == 0.0.1 (current js api version) and package.json contains ankidroid-js-addon keywords
            if (addonAnkiDroidAPI.equals(AnkiDroidJsAPI) && jsAddonKeywordsPresent) {
                // if other strings are non empty
                if (!addonName.isEmpty() && !addonVersion.isEmpty() && !addonDev.isEmpty() && !addonHomepage.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}