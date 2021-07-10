package com.ichi2.anki.jsaddons;

import android.app.Activity;
import android.content.Context;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ichi2.anki.CollectionHelper;
import com.ichi2.anki.R;

import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;

import java8.util.StringJoiner;
import timber.log.Timber;

import static com.ichi2.anki.web.HttpFetcher.downloadFileToSdCardMethod;

public class NpmPackageDownloader extends DownloadAddonBackgroundTask {
    private Context mContext;
    private DownloadAddonAsyncTaskListener mTaskListener;
    private String addonName;

    public NpmPackageDownloader(Activity activity, Context context, String addonName) {
        super(activity);
        this.mContext = context;
        this.addonName = addonName;
        mTaskListener = (DownloadAddonAsyncTaskListener) context;
    }


    @Override
    public void doInBackground() {
        mTaskListener.addonShowProgressBar();
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            AddonInfo mAddonInfo = mapper.readValue(new URL(mContext.getString(R.string.npmjs_registry, addonName)), AddonInfo.class);

            if (AddonInfo.isValidAnkiDroidAddon(mAddonInfo)) {
                String tarballUrl = mAddonInfo.getDist().get("tarball");

                String downloadFilePath = downloadFileToSdCardMethod(tarballUrl, mContext, "addons", "GET");
                Timber.d("download path %s", downloadFilePath);
                extractAndCopyAddonTgz(downloadFilePath, addonName);

            } else {
                mTaskListener.showToast(mContext.getString(R.string.invalid_js_addon));
            }

        } catch (JsonParseException | JsonMappingException | MalformedURLException e) {
            mTaskListener.showToast(mContext.getString(R.string.invalid_js_addon));
            Timber.d(e.getLocalizedMessage());
        } catch (UnknownHostException e) {
            mTaskListener.showToast(mContext.getString(R.string.network_no_connection));
            Timber.d(e.getLocalizedMessage());
        } catch (NullPointerException | IOException e) {
            mTaskListener.showToast(mContext.getString(R.string.error_occur_downloading_addon));
            Timber.d(e.getLocalizedMessage());
        }
    }


    @Override
    public void onPostExecute() {
        mTaskListener.addonHideProgressBar();
    }

    /**
     * @param tarballPath  path to downloaded js-addon.tgz file
     * @param npmAddonName addon name, e.g ankidroid-js-addon-progress-bar
     *                     extract downloaded .tgz files and copy to AnkiDroid/addons/ folder
     */
    public void extractAndCopyAddonTgz(String tarballPath, String npmAddonName) {
        if (tarballPath == null) {
            //mTaskListener.addonHideProgressBar();
            //mTaskListener.showToast(mContext.getString(R.string.failed_to_extract_addon_package));
            return;
        }

        String currentAnkiDroidDirectory = CollectionHelper.getCurrentAnkiDroidDirectory(mContext);

        // AnkiDroid/addons/js-addons
        // here npmAddonName is id of npm package which may not contain ../ or other bad path
        StringJoiner joinedPath = new StringJoiner("/")
                .add(currentAnkiDroidDirectory)
                .add("addons")
                .add(npmAddonName);

        File addonsDir = new File(joinedPath.toString());
        File tarballFile = new File(tarballPath);

        if (!tarballFile.exists()) {
            return;
        }

        // extracting using library https://github.com/thrau/jarchivelib
        try {
            Archiver archiver = ArchiverFactory.createArchiver(tarballFile);
            archiver.extract(tarballFile, addonsDir);
            Timber.d("js addon .tgz extracted");
        } catch (IOException e) {
            Timber.e(e.getLocalizedMessage());
        } finally {
            if (tarballFile.exists()) {
                tarballFile.delete();
                mTaskListener.showToast(mContext.getString(R.string.addon_installed));
            }
        }
    }
}
