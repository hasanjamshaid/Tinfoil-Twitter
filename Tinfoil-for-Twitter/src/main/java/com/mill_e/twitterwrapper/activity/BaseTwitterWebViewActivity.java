/*
 * Copyright (C) 2014 James Vento
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mill_e.twitterwrapper.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieSyncManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import com.mill_e.twitterwrapper.R;
import com.mill_e.twitterwrapper.util.Logger;
import com.mill_e.twitterwrapper.util.OrbotHelper;
import com.mill_e.twitterwrapper.util.WebViewProxyUtil;
import com.mill_e.twitterwrapper.webview.TwitterWebChromeClient;
import com.mill_e.twitterwrapper.webview.TwitterWebView;
import com.mill_e.twitterwrapper.webview.TwitterWebViewClient;

import java.io.File;

/**
 * Base activity that uses a {@link com.mill_e.twitterwrapper.webview.TwitterWebView} to load the Facebook
 * site in different formats. Here we can implement all the boilerplate code
 * that has to do with loading the activity as well as lifecycle events.
 * <p/>
 * See {@link #onActivityCreated()}
 * See {@link #onWebViewInit(android.os.Bundle)}
 * See {@link #onResumeActivity()}
 */
public abstract class BaseTwitterWebViewActivity extends Activity implements
        TwitterWebViewClient.WebViewClientListener,
        TwitterWebChromeClient.WebChromeClientListener {

    // Constants
    private final static String LOG_TAG = "BaseTwitterWebViewActivity";
    protected final static int RESULT_CODE_FILE_UPLOAD = 1001;
    protected final static String INIT_URL_MOBILE = "https://m.twitter.com";
    protected final static String INIT_URL_DESKTOP = "https://www.twitter.com";
    protected final static String URL_MOBILE_PAGE_NOTIFICATIONS = "/i/connect";
    protected final static String URL_DESKTOP_PAGE_NOTIFICATIONS = "/i/notifications";
    protected final static String URL_PAGE_MESSAGES = "/messages";

    // URL for Sharing Links
    // u = url & t = title
    protected final static String URL_PAGE_SHARE_LINKS = "/intent/tweet?url=%s&text=\"%s\"";

    // Desktop user agent (Google Chrome's user agent from a MacBook running 10.9.1
    protected static final String USER_AGENT_DESKTOP = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_1) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.63 Safari/537.36";
    // Mobile user agent (Mobile user agent from a Google Nexus S running Android 2.3.3
    protected static final String USER_AGENT_MOBILE_OLD = "Mozilla/5.0 (Linux; U; Android 2.3.3; en-gb; " +
            "Nexus S Build/GRI20) AppleWebKit/533.1 (KHTML, like Gecko) Version/4.0 Mobile Safari/533.1";
    // Mobile user agent (Mobile user agent from a Google Nexus 5 running Android 4.4.2
    protected static final String USER_AGENT_MOBILE = "Mozilla/5.0 (Linux; Android 4.4.2; Nexus 5 Build/KOT49H) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/30.0.0.0 Mobile Safari/537.36";

    // Members
    protected ConnectivityManager mConnectivityManager = null;
    protected CookieSyncManager mCookieSyncManager = null;
    protected TwitterWebView mWebView = null;
    protected ProgressBar mProgressBar = null;
    protected WebSettings mWebSettings = null;
    protected ValueCallback<Uri> mUploadMessage = null;
    private boolean mCreatingActivity = true;

    /**
     * BroadcastReceiver to handle ConnectivityManager.CONNECTIVITY_ACTION intent action.
     */
    private BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        /**
         * {@inheritDoc}
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            // Set the cache mode depending on connection type and availability
            updateCacheMode();
        }
    };

    /**
     * Called when the Activity is created. Make sure the content view
     * is set here.
     */
    protected abstract void onActivityCreated();

    /**
     * Called when we are ready to start restoring or loading
     * data in the {@link com.mill_e.twitterwrapper.webview.TwitterWebView}
     *
     * @param savedInstanceState {@link Bundle}
     */
    protected abstract void onWebViewInit(Bundle savedInstanceState);

    /**
     * Called anything the activity is resumed. Could be used to
     * reload any type of preference.
     */
    protected abstract void onResumeActivity();

    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Create the activity and set the layout
        onActivityCreated();

        mConnectivityManager = (ConnectivityManager) getSystemService(Activity.CONNECTIVITY_SERVICE);

        mWebView = (TwitterWebView) findViewById(R.id.webview);
        mWebView.setCustomContentView((FrameLayout) findViewById(R.id.fullscreen_custom_content));
        mWebView.setWebChromeClientListener(this);
        mWebView.setWebViewClientListener(this);
        mWebSettings = mWebView.getWebSettings();

        mProgressBar = (ProgressBar) findViewById(R.id.progress_bar);

        // Set the database path for this WebView so that
        // HTML5 Storage API works properly
        File directory = getFilesDir();
        if (directory != null) {
            mWebSettings.setDatabaseEnabled(true);
            mWebSettings.setDatabasePath(directory.getAbsolutePath() + "/");
        }

        // Create a CookieSyncManager instance and keep a reference of it
        mCookieSyncManager = CookieSyncManager.createInstance(this);

        // Have the activity open the proper URL
        onWebViewInit(savedInstanceState);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onResume() {
        super.onResume();

        // Pass lifecycle events to the WebView
        mWebView.onResume();

        // Start synchronizing the CookieSyncManager
        mCookieSyncManager.startSync();

        // Set the cache mode depending on connection type and availability
        updateCacheMode();

        // Register a Connectivity action receiver so that we can update the cache mode accordingly
        registerReceiver(mConnectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        // Horrible lifecycle hack
        if (mCreatingActivity) {
            mCreatingActivity = false;
            return;
        }

        // Resume this activity properly, reload preferences, etc.
        onResumeActivity();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPause() {

        // Un-register the connectivity changed receiver
        unregisterReceiver(mConnectivityReceiver);

        // Stop synchronizing the CookieSyncManager
        mCookieSyncManager.stopSync();

        super.onPause();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // Save the state of the WebView as a Bundle to the Instance State
        mWebView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Handle orientation configuration changes
        super.onConfigurationChanged(newConfig);
    }

    /**
     * Set a proxy for the {@link com.mill_e.twitterwrapper.webview.TwitterWebView}
     *
     * @param host {@link String}
     * @param port {@link int}
     */
    protected final void setProxy(String host, int port) {
        if (mWebView != null && !TextUtils.isEmpty(host) && port > 0) {
            WebViewProxyUtil.setProxy(getApplicationContext(), mWebView, host, port);
        }
    }

    /**
     * Restore the state of the {@link com.mill_e.twitterwrapper.webview.TwitterWebView}
     *
     * @param inState {@link Bundle}
     */
    protected void restoreWebView(Bundle inState) {
        if (mWebView != null) {
            mWebView.restoreState(inState);
        }
    }

    /**
     * Set the browser user agent to be used. If the user agent should be forced,
     * make sure the 'force' param is set to true, otherwise the devices' default
     * user agent will be used.
     *
     * @param force  {@link boolean}
     *               true if we should force a custom user agent, false if not.
     *               Note, if this flag is false the default user agent will be
     *               used while disregarding the mobile {@link boolean} parameter
     * @param mobile {@link boolean}
     *               true if we should use a custom user agent for mobile devices,
     *               false if not.
     */
    protected void setUserAgent(boolean force, boolean mobile) {
        if (force && mobile) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                mWebSettings.setUserAgentString(USER_AGENT_MOBILE_OLD);
            } else {
                mWebSettings.setUserAgentString(USER_AGENT_MOBILE);
            }
        } else if (force && !mobile) {
            mWebSettings.setUserAgentString(USER_AGENT_DESKTOP);
        } else {
            mWebSettings.setUserAgentString(null);
        }
    }

    /**
     * Used to load a new URL in the {@link com.mill_e.twitterwrapper.webview.TwitterWebView}
     *
     * @param url {@link String}
     */
    protected void loadNewPage(String url) {
        if (mWebView != null) {
            mWebView.loadUrl(url);
        }
    }

    /**
     * Method used to allow the user to refresh the current page
     */
    protected void refreshCurrentPage() {
        if (mWebView != null) {
            mWebView.reload();
        }
    }

    /**
     * Method used to allow the user to jump to the top of the webview
     */
    protected void jumpToTop() {
        loadNewPage("javascript:window.scrollTo(0,0);");
    }

    /**
     * Used to change the geolocation flag.
     *
     * @param allow {@link boolean} true if the use of
     *              geolocation is allowed, false if not
     */
    protected void setAllowCheckins(boolean allow) {
        if (mWebView != null) {
            mWebView.setAllowGeolocation(allow);
        }
    }

    /**
     * Used to change to change the behaviour of the {@link com.mill_e.twitterwrapper.webview.TwitterWebView}<br/>
     * By default, this {@link com.mill_e.twitterwrapper.webview.TwitterWebView} will only open URLs in which the
     * host is facebook.com, any other links should be sent to the default browser.<br/>
     * However, if the user wants to open the link inside this same webview, he could,
     * so in that case, make sure this flag is set to true.
     *
     * @param allow {@link boolean} true if any domain could be opened
     *              on this webview, false if only facebook domains
     *              are allowed.
     */
    protected void setAllowAnyDomain(boolean allow) {
        if (mWebView != null) {
            mWebView.setAllowAnyDomain(allow);
        }
    }

    /**
     * Allows us to share the page that's currently opened
     * using the ACTION_SEND share intent.
     */
    protected void shareCurrentPage() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, R.string.share_action_subject);
        i.putExtra(Intent.EXTRA_TEXT, mWebView.getUrl());
        startActivity(Intent.createChooser(i, getString(R.string.share_action)));
    }

    /**
     * This is to be used in case we want to force kill the activity.
     * Might not be necessary, but it's here in case we'd like to use it.
     */
    protected void destroyWebView() {
        if (mWebView != null) {
            mWebView.removeAllViews();

            /** Free memory and destroy WebView */
            mWebView.freeMemory();
            mWebView.destroy();
            mWebView = null;
        }
    }

    /**
     * Check whether this device has internet connection or not.
     *
     * @return {@link boolean}
     */
    private boolean checkNetworkConnection() {
        try {
            NetworkInfo networkInfo = mConnectivityManager.getActiveNetworkInfo();
            if (networkInfo != null) {
                return networkInfo.isConnected();
            }
            return false;
        } catch (SecurityException e) {
            // Catch the Security Exception in case the user revokes the ACCESS_NETWORK_STATE permission
            e.printStackTrace();
            // Let's assume the device has internet access
            return true;
        }
    }

    /**
     * Update the cache mode depending on the network connection state of the device.
     */
    private void updateCacheMode() {
        if (checkNetworkConnection()) {
            Logger.d(LOG_TAG, "Setting cache mode to: LOAD_DEFAULT");
            mWebSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        } else {
            Logger.d(LOG_TAG, "Setting cache mode to: LOAD_CACHE_ONLY");
            mWebSettings.setCacheMode(WebSettings.LOAD_CACHE_ONLY);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        switch (requestCode) {
            case OrbotHelper.REQUEST_CODE_START_ORBOT:
                mWebView.reload();
                break;
            case RESULT_CODE_FILE_UPLOAD:
                if (null == mUploadMessage) {
                    return;
                }
                Uri result = intent == null || resultCode != RESULT_OK ? null
                        : intent.getData();
                mUploadMessage.onReceiveValue(result);
                mUploadMessage = null;
                break;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onProgressChanged(WebView view, int progress) {
        Logger.d(LOG_TAG, "onProgressChanged(), progress: " + progress);

        // Posts current progress to the ProgressBar
        mProgressBar.setProgress(progress);

        // Hide the progress bar as soon as it goes over 85%
        if (progress >= 85) {
            mProgressBar.setVisibility(View.GONE);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void showGeolocationDisabledAlert() {
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle(getString(R.string.lbl_dialog_alert));
        alertDialog.setMessage(getString(R.string.txt_checkins_disables));
        alertDialog.setButton(DialogInterface.BUTTON_NEUTRAL,
                getString(R.string.lbl_dialog_ok),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // Don't do anything here, simply close the dialog
                    }
                });
        alertDialog.show();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
        Logger.d(LOG_TAG, "openFileChooser()");
        mUploadMessage = uploadMsg;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(
                Intent.createChooser(i,
                        getString(R.string.upload_file_choose)),
                RESULT_CODE_FILE_UPLOAD);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPageLoadStarted(String url) {
        Logger.d(LOG_TAG, "onPageLoadStarted() -- url: " + url);
        mProgressBar.setVisibility(View.VISIBLE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPageLoadFinished(String url) {
        Logger.d(LOG_TAG, "onPageLoadFinished() -- url: " + url);
        mProgressBar.setVisibility(View.GONE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void openExternalSite(String url) {
        Logger.d(LOG_TAG, "openExternalSite() -- url: " + url);

        // This link is not for a page on my site, launch another Activity
        // that handles this URL
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);

        // Hack: Facebook uses a linker helper, it's blank when coming back to app
        // from an outside link, so let's attempt to go back to avoid this blank page
        if (mWebView.canGoBack()) {
            Logger.d(LOG_TAG, "Attempting to go back to avoid blank page");
            mWebView.goBack();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Override the back button
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mWebView.canGoBack()) {
                // Check to see if there's history to go back to
                mWebView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

}