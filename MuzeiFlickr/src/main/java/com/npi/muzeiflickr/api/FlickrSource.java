/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 * Created by nicolas on 14/02/14.
 * Muzei source
 */

package com.npi.muzeiflickr.api;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.RemoteMuzeiArtSource;
import com.google.android.apps.muzei.api.UserCommand;
import com.npi.muzeiflickr.BuildConfig;
import com.npi.muzeiflickr.R;
import com.npi.muzeiflickr.data.PreferenceKeys;
import com.npi.muzeiflickr.db.Photo;
import com.npi.muzeiflickr.network.FlickrService;
import com.npi.muzeiflickr.ui.activities.SettingsActivity;
import com.npi.muzeiflickr.ui.widgets.FlickrWidget;
import com.npi.muzeiflickr.utils.Utils;

import java.util.ArrayList;
import java.util.List;

import retrofit.Callback;
import retrofit.ErrorHandler;
import retrofit.RequestInterceptor;
import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.Response;

public class FlickrSource extends RemoteMuzeiArtSource {
    private static final String TAG = "FlickrSource";
    private static final String SOURCE_NAME = "FlickrSource";

    public static final String ACTION_CLEAR_SERVICE = "com.npi.muzeiflickr.ACTION_CLEAR_SERVICE";
    public static final String ACTION_REFRESH_FROM_WIDGET = "com.npi.muzeiflickr.NEXT_FROM_WIDGET";
    public static final int DEFAULT_REFRESH_TIME = 7200000;
    private static final int COMMAND_ID_SHARE = 1;
    private static final int COMMAND_ID_PAUSE = 2;
    private static final int COMMAND_ID_RESTART = 3;
    private List<Photo> storedPhotos;

    public FlickrSource() {
        super(SOURCE_NAME);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setUserCommands(BUILTIN_COMMAND_ID_NEXT_ARTWORK);
    }

    /**
     * Muzei ask for an Artwork update
     *
     * @param reason reason for update
     * @throws RetryException
     */
    @Override
    protected void onTryUpdate(int reason) throws RetryException {
        final SharedPreferences settings = getSharedPreferences(SettingsActivity.PREFS_NAME, 0);

        //Avoid refresh if it's paused
        if (settings.getBoolean(PreferenceKeys.PAUSED, false)) {
            manageUserCommands(settings);
            return;
        }

        // Check if we cancel the update due to WIFI connection
        if (settings.getBoolean(PreferenceKeys.WIFI_ONLY, false) && !Utils.isWifiConnected(this)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Refresh avoided: no wifi");
            scheduleUpdate(System.currentTimeMillis() + settings.getInt(PreferenceKeys.REFRESH_TIME, DEFAULT_REFRESH_TIME));
            return;
        }


        //The memory photo cache is empty let's populate it
        if (storedPhotos == null) {
            storedPhotos = Photo.listAll(Photo.class);
        }

        //No photo in the cache, let load some from flickr
        if (storedPhotos == null || storedPhotos.size() == 0) {
            if (BuildConfig.DEBUG) Log.d(TAG, "No photo: retrying");
            requestPhotos();

            throw new RetryException();
        }


        //Get the photo
        Photo photo = storedPhotos.get(0);


        String name = photo.userName.substring(0, 1).toUpperCase() + photo.userName.substring(1);


        //Publick the photo to Muzei
        publishArtwork(new Artwork.Builder()
                .title(photo.title)
                .byline(name)
                .imageUri(Uri.parse(photo.source))
                .token(photo.photoId)
                .viewIntent(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(photo.url)))
                .build());


        SharedPreferences.Editor editor = settings.edit();
        editor.putString(PreferenceKeys.CURRENT_TITLE, photo.title);
        editor.putString(PreferenceKeys.CURRENT_AUTHOR, name);
        editor.putString(PreferenceKeys.CURRENT_URL, photo.url);
        editor.commit();

        //Update widgets
        updateWidgets();

        //Update the cache
        storedPhotos.get(0).delete();
        storedPhotos.remove(0);

        scheduleUpdate(System.currentTimeMillis() + settings.getInt(PreferenceKeys.REFRESH_TIME, DEFAULT_REFRESH_TIME));
        //No photo left, let's load some more
        if (storedPhotos.size() == 0) {
            requestPhotos();
        }
        manageUserCommands(settings);
    }

    private void manageUserCommands(SharedPreferences settings) {
        List<UserCommand> commands = new ArrayList<UserCommand>();
        commands.add(new UserCommand(COMMAND_ID_SHARE, getString(R.string.share)));
        commands.add(new UserCommand(BUILTIN_COMMAND_ID_NEXT_ARTWORK,""));
        if (settings.getBoolean(PreferenceKeys.PAUSED, false)) {
            commands.add(new UserCommand(COMMAND_ID_RESTART, getString(R.string.restart)));
        } else {
            commands.add(new UserCommand(COMMAND_ID_PAUSE, getString(R.string.pause)));
        }
        setUserCommands(commands);
    }

    @Override
    protected void onCustomCommand(int id) {
        super.onCustomCommand(id);
        final SharedPreferences settings = getSharedPreferences(SettingsActivity.PREFS_NAME, 0);
        SharedPreferences.Editor editor = settings.edit();
        switch (id) {
            case COMMAND_ID_PAUSE:
                editor.putBoolean(PreferenceKeys.PAUSED, true);
                editor.commit();
                break;
            case COMMAND_ID_RESTART:
                editor.putBoolean(PreferenceKeys.PAUSED, false);
                editor.commit();
                scheduleUpdate(System.currentTimeMillis() + settings.getInt(PreferenceKeys.REFRESH_TIME, DEFAULT_REFRESH_TIME));
                break;
            case COMMAND_ID_SHARE:
                Artwork currentArtwork = getCurrentArtwork();
                if (currentArtwork == null) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(FlickrSource.this,
                                    getString(R.string.no_artwork),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }

                String detailUrl = (currentArtwork.getViewIntent().getDataString());
                String artist = currentArtwork.getByline()
                        .replaceFirst("\\.\\s*($|\\n).*", "").trim();

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, "My Android wallpaper is '"
                        + currentArtwork.getTitle().trim()
                        + "' by " + artist
                        + ". \nShared with Flickr for Muzei\n\n"
                        + detailUrl);
                shareIntent = Intent.createChooser(shareIntent, "Share Flickr photo");
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(shareIntent);
                break;

        }
        manageUserCommands(settings);

    }

    private void updateWidgets() {
        Intent intent = new Intent(this, FlickrWidget.class);
        intent.setAction("android.appwidget.action.APPWIDGET_UPDATE");
        int ids[] = AppWidgetManager.getInstance(getApplication()).getAppWidgetIds(new ComponentName(getApplication(), FlickrWidget.class));
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        sendBroadcast(intent);
    }


    /**
     * Load photos from flickr
     */
    private void requestPhotos() {

        final SharedPreferences settings = getSharedPreferences(SettingsActivity.PREFS_NAME, 0);


        if (BuildConfig.DEBUG) Log.d(TAG, "Start service");

        RestAdapter restAdapter = new RestAdapter.Builder()
                .setLogLevel(RestAdapter.LogLevel.FULL)
                .setServer("http://api.flickr.com/services/rest")
                .setRequestInterceptor(new RequestInterceptor() {
                    @Override
                    public void intercept(RequestInterceptor.RequestFacade request) {

                        //Change the request depending on the mode
                        int mode = settings.getInt(PreferenceKeys.MODE, 0);

                        switch (mode) {
                            case 0:

                                String search = settings.getString(PreferenceKeys.SEARCH_TERM, "landscape");
                                if (TextUtils.isEmpty(search)) search = "landscape";
                                if (BuildConfig.DEBUG) Log.d(TAG, "Request: " + search);
                                request.addQueryParam("text", search);

                                break;

                            case 1:

                                String user = settings.getString(PreferenceKeys.USER_ID, "");
                                request.addQueryParam("user_id", user);

                                break;

                        }


                    }
                })
                .setErrorHandler(new ErrorHandler() {
                    @Override
                    public Throwable handleError(RetrofitError retrofitError) {
                        //Issue with update. Let's wait for the next time
                        scheduleUpdate(System.currentTimeMillis() + settings.getInt(PreferenceKeys.REFRESH_TIME, DEFAULT_REFRESH_TIME));
                        return retrofitError;
                    }
                })
                .build();


        final FlickrService service = restAdapter.create(FlickrService.class);
        //Request the correct page
        final int page = settings.getInt(PreferenceKeys.CURRENT_PAGE, -1) + 1;
        if (BuildConfig.DEBUG) Log.d(TAG, "Requesting page: " + page);

        FlickrService.PhotosResponse response = null;
        int mode = settings.getInt(PreferenceKeys.MODE, 0);

        Callback<FlickrService.PhotosResponse> photosResponseCallback = new Callback<FlickrService.PhotosResponse>() {
            @Override
            public void success(FlickrService.PhotosResponse photosResponse, Response response) {
                if (response == null || photosResponse.photos.photo == null || photosResponse.photos == null) {
                    Log.w(TAG, "Unable to get the photo list");
                    return;
                }

                //No photo
                if (page == 1 && photosResponse.photos.photo.size() < 1) {
                    SharedPreferences.Editor editor = settings.edit();
                    editor.putInt(PreferenceKeys.MODE, 0);
                    editor.putString(PreferenceKeys.SEARCH_TERM, "");
                    editor.commit();
                    Log.w(TAG, "No photo in search");
                    return;
                }

                if (BuildConfig.DEBUG) Log.d(TAG, "Stored page: " + page + "/" + photosResponse.photos.pages);
                SharedPreferences.Editor editor = settings.edit();
                if (page >= photosResponse.photos.pages) {
                    editor.putInt(PreferenceKeys.CURRENT_PAGE, 0);
                } else {
                    editor.putInt(PreferenceKeys.CURRENT_PAGE, page);

                }


                if (BuildConfig.DEBUG) Log.d(TAG, "Stored page: " + page);


                editor.commit();

                //Store photos
                for (final FlickrService.Photo photo : photosResponse.photos.photo) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Getting infos for photo: " + photo.id);


                    service.getSize(photo.id, new Callback<FlickrService.SizeResponse>() {
                        @Override
                        public void success(FlickrService.SizeResponse responseSize, Response response) {
                            if (responseSize == null || responseSize.sizes == null) {
                                Log.w(TAG, "Unable to get the infos for photo");
                                return;
                            }

                            //Get the largest size limited to screen height to avoid too much loading
                            int currentSizeHeight = 0;
                            FlickrService.Size largestSize = null;
                            for (FlickrService.Size size : responseSize.sizes.size) {
                                if (size.height > currentSizeHeight && size.height < Utils.getScreenHeight(FlickrSource.this)) {
                                    currentSizeHeight = size.height;
                                    largestSize = size;
                                }
                            }

                            if (largestSize != null) {

                                FlickrService.UserResponse responseUser = null;
                                //Request user info (for the title)
                                final FlickrService.Size finalLargestSize = largestSize;
                                service.getUser(photo.owner, new Callback<FlickrService.UserResponse>() {
                                        @Override
                                        public void success(FlickrService.UserResponse responseUser, Response response) {
                                            if (responseUser == null || responseUser.person == null) {
                                                Log.w(TAG, "Unable to get the infos for user");
                                                return;
                                            }

                                            String name = "";
                                            if (responseUser.person.realname != null) {
                                                name = responseUser.person.realname._content;
                                            }
                                            if (TextUtils.isEmpty(name) && responseUser.person.username != null) {
                                                name = responseUser.person.username._content;
                                            }


                                            //Add the photo
                                            Photo photoEntity = new Photo(FlickrSource.this);
                                            photoEntity.userName = name;
                                            photoEntity.url = "http://www.flickr.com/photos/" + photo.owner + "/" + photo.id;
                                            photoEntity.source = finalLargestSize.source;
                                            photoEntity.title = photo.title;
                                            photoEntity.photoId = photo.id;

                                            if (storedPhotos == null) {
                                                storedPhotos = Photo.listAll(Photo.class);
                                            }
                                            if (storedPhotos == null) {
                                                storedPhotos = new ArrayList<Photo>();
                                            }
                                            storedPhotos.add(photoEntity);
                                            photoEntity.save();
                                        }

                                        @Override
                                        public void failure(RetrofitError retrofitError) {

                                        }
                                    });



                            }
                        }

                        @Override
                        public void failure(RetrofitError retrofitError) {

                        }
                    });


                }
            }

            @Override
            public void failure(RetrofitError retrofitError) {

            }
        };

        switch (mode) {
            case 0:
                service.getPopularPhotos(page, photosResponseCallback);

                break;
            case 1:
                service.getPopularPhotosByUser(page, photosResponseCallback);
                break;
        }




    }


    @Override
    protected void onHandleIntent(Intent intent) {


        if (intent == null) {
            super.onHandleIntent(intent);
            return;
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Handle intent: " + intent.getAction());

        String action = intent.getAction();
        if (ACTION_CLEAR_SERVICE.equals(action) || ACTION_REFRESH_FROM_WIDGET.equals(action)) {
            scheduleUpdate(System.currentTimeMillis() + 1000);
            return;

        }

        super.onHandleIntent(intent);
    }


    @Override
    protected void onDisabled() {
        if (BuildConfig.DEBUG) Log.d(TAG, "onDisabled");
        final SharedPreferences settings = getSharedPreferences(SettingsActivity.PREFS_NAME, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean(PreferenceKeys.IS_SUSCRIBER_ENABLED, false);
        editor.commit();

        updateWidgets();
        manageUserCommands(settings);

        super.onDisabled();
    }

    @Override
    protected void onEnabled() {
        if (BuildConfig.DEBUG) Log.d(TAG, "onEnabled");
        final SharedPreferences settings = getSharedPreferences(SettingsActivity.PREFS_NAME, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean(PreferenceKeys.IS_SUSCRIBER_ENABLED, true);

        editor.commit();

        updateWidgets();

        super.onEnabled();
    }

}

