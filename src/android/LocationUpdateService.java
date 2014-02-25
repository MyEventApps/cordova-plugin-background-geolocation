package com.tenforwardconsulting.cordova.bgloc;

import java.util.Arrays;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONObject;

import com.tenforwardconsulting.cordova.bgloc.data.DAOFactory;
import com.tenforwardconsulting.cordova.bgloc.data.LocationDAO;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.telephony.TelephonyManager;
import android.telephony.CellLocation;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.support.v4.app.NotificationCompat;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.location.Location;
import android.location.Criteria;
//import com.google.android.gms.location.Geofence.Builder;

import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import static android.app.PendingIntent.*;
import static android.telephony.PhoneStateListener.*;

public class LocationUpdateService extends Service implements LocationListener {
    private static final String TAG = "LocationUpdateService";
    private static final String STATIONARY_REGION_ACTION  = "com.tenforwardconsulting.cordova.bgloc.STATIONARY_REGION_ACTION";
    private static final String SINGLE_LOCATION_UPDATE_ACTION   = "com.tenforwardconsulting.cordova.bgloc.SINGLE_LOCATION_UPDATE_ACTION";
    private static long STATIONARY_TIMEOUT = 1000 * 60; //60 * 1000 * 15;

    public static final int NOTIFICATION_ID = 555;
    private PowerManager.WakeLock wakeLock;
    private Location lastLocation;
    private long lastUpdateTime = 0l;
    private BusyTask looper;

    private String authToken = "HypVBMmDxbh76pHpwots";
    private String url = "http://192.168.2.15:3000/users/current_location.json";

    private float stationaryRadius;
    private Location stationaryLocation;
    private Integer desiredAccuracy;
    private Integer distanceFilter;
    private Integer locationTimeout;
    private Boolean isDebugging;

    private ToneGenerator toneGenerator;

    private PendingIntent proximityPI;

    private Notification notification;
    private NotificationManager notificationManager;
    private LocationManager locationManager;
    private ConnectivityManager connectivityManager;

    private Criteria criteria;

    private Boolean isMoving = false;

    public static TelephonyManager p_TelephonyManager = null;
    public static BackgroundPhoneStateListener p_myPhoneStateListener = null;

    @Override
    public IBinder onBind(Intent intent) {
        // TODO Auto-generated method stub
        Log.i(TAG, "OnBind" + intent);
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "OnCreate");
        notificationManager = (NotificationManager)this.getSystemService(NOTIFICATION_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        locationManager = (LocationManager)this.getSystemService(Context.LOCATION_SERVICE);

        connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();

        criteria = new Criteria();
        criteria.setAltitudeRequired(false);
        criteria.setBearingRequired(false);
        criteria.setSpeedRequired(true);
        criteria.setCostAllowed(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);
        if (intent != null) {
            this.authToken = intent.getStringExtra("authToken");
            this.url = intent.getStringExtra("url");
            this.stationaryRadius = Float.parseFloat(intent.getStringExtra("stationaryRadius"));

            distanceFilter = Integer.parseInt(intent.getStringExtra("distanceFilter"));
            if (distanceFilter == null) {
                distanceFilter = 30;
            }
            this.desiredAccuracy = Integer.parseInt(intent.getStringExtra("desiredAccuracy"));
            if (desiredAccuracy == null) {
                desiredAccuracy = 100;
            }
            this.locationTimeout = Integer.parseInt(intent.getStringExtra("locationTimeout"));
            if (locationTimeout == null) {
                locationTimeout = 60;
            }
            this.isDebugging = Boolean.parseBoolean(intent.getStringExtra("isDebugging"));
            if (this.isDebugging == null) {
                this.isDebugging = false;
            }
            if (this.isDebugging) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
            }
            Log.i(TAG, "- stationaryRadius: " + stationaryRadius);
            Log.i(TAG, "- distanceFilter: " + distanceFilter);
            Log.i(TAG, "- desiredAccuracy: " + desiredAccuracy);
            Log.i(TAG, "- locationTimeout: " + locationTimeout);
            Log.i(TAG, "- isDebugging: " + isDebugging);
        }
        Toast.makeText(this, "Background location tracking started", Toast.LENGTH_SHORT).show();

        notification = buildNotification();
        notificationManager.notify(NOTIFICATION_ID, notification);

        this.setPace(false);

        /**
         * Experimental cell-location-change handler
         *
        p_myPhoneStateListener = new BackgroundPhoneStateListener(this);
        p_TelephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        p_TelephonyManager.listen(p_myPhoneStateListener, LISTEN_CELL_LOCATION);
        *
        */

        //We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    public void onCellLocationChanged(CellLocation cellLocation) {
        Log.i(TAG, "- onCellLocationChanged");
        Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location location = getLastBestLocation((int) stationaryRadius, locationTimeout * 1000);
        if (location != null) {
            Log.i(TAG, "location: " + location.getLatitude() + "," + location.getLongitude() + ", accuracy: " + location.getAccuracy());
        }
    }
    @Override
    public boolean stopService(Intent intent) {
        Log.i(TAG, "Received stop: " + intent);
        cleanUp();
        Toast.makeText(this, "Background location tracking stopped", Toast.LENGTH_SHORT).show();
        return super.stopService(intent);
    }

    private Integer translateDesiredAccuracy(Integer accuracy) {
        switch (accuracy) {
            case 1000:
                accuracy = Criteria.ACCURACY_LOW;
                break;
            case 100:
                accuracy = Criteria.ACCURACY_MEDIUM;
                break;
            case 10:
                accuracy = Criteria.ACCURACY_MEDIUM;
                break;
            case 0:
                accuracy = Criteria.ACCURACY_HIGH;
                break;
            default:
                accuracy = Criteria.ACCURACY_MEDIUM;
        }
        return accuracy;
    }

    private void setPace(Boolean value) {
        Log.i(TAG, "setPace: " + value);
        isMoving = value;

        locationManager.removeUpdates(this);

        Criteria crta = new Criteria();
        crta.setAltitudeRequired(false);
        crta.setBearingRequired(false);
        crta.setSpeedRequired(true);
        crta.setCostAllowed(true);

        if (isMoving) {
            stationaryLocation = null;
            crta.setAccuracy(Criteria.ACCURACY_FINE);
            crta.setHorizontalAccuracy(translateDesiredAccuracy(desiredAccuracy));
            crta.setPowerRequirement(Criteria.POWER_HIGH);
            locationManager.requestLocationUpdates(locationManager.getBestProvider(crta, true), locationTimeout*1000, distanceFilter, this);
        } else {
            stationaryLocation = null;
            crta.setAccuracy(Criteria.ACCURACY_COARSE);
            crta.setHorizontalAccuracy(Criteria.ACCURACY_LOW);
            crta.setPowerRequirement(Criteria.POWER_LOW);

            Location location = this.getLastBestLocation((int) stationaryRadius, locationTimeout * 1000);
            if (location != null) {
                this.startMonitoringStationaryRegion(location);
            }
        }
    }

    /**
     * Returns the most accurate and timely previously detected location.
     * Where the last result is beyond the specified maximum distance or
     * latency a one-off location update is returned via the {@link LocationListener}
     * specified in {@link setChangedLocationListener}.
     * @param minDistance Minimum distance before we require a location update.
     * @param minTime Minimum time required between location updates.
     * @return The most accurate and / or timely previously detected location.
     */
    public Location getLastBestLocation(int minDistance, long minTime) {
        Log.i(TAG, "- fetching last best location");
        Location bestResult = null;
        float bestAccuracy = Float.MAX_VALUE;
        long bestTime = Long.MIN_VALUE;

        // Iterate through all the providers on the system, keeping
        // note of the most accurate result within the acceptable time limit.
        // If no result is found within maxTime, return the newest Location.
        List<String> matchingProviders = locationManager.getAllProviders();
        for (String provider: matchingProviders) {
            Location location = locationManager.getLastKnownLocation(provider);
            if (location != null) {
                float accuracy = location.getAccuracy();
                long time = location.getTime();

                if ((time > minTime && accuracy < bestAccuracy)) {
                    bestResult = location;
                    bestAccuracy = accuracy;
                    bestTime = time;
                }
                else if (time < minTime && bestAccuracy == Float.MAX_VALUE && time > bestTime) {
                    bestResult = location;
                    bestTime = time;
                }
            }
        }
        return bestResult;
    }

    public void onLocationChanged(Location location) {
        Log.d(TAG, "- onLocationChanged: " + location.getLatitude() + "," + location.getLongitude() + ", accuracy: " + location.getAccuracy() + ", isMoving: " + isMoving);
        if (isDebugging) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP);
        }
        if (isMoving) {
            // If user hasn't moved beyond the stationaryRadius within time of STATIONARY_TIMEOUT
            //  assume they've stopped.
            if (lastLocation != null) {
                Log.i(TAG, "- has lastLocation " + lastLocation.distanceTo(location) + "<" + stationaryRadius);
                if (lastLocation.distanceTo(location) < stationaryRadius) {
                    Log.i(TAG, "- lastLocation is within stationaryRadius");
                    if (stationaryLocation == null) {
                        stationaryLocation = lastLocation;
                    }
                    long timeDelta = location.getTime() - stationaryLocation.getTime();
                    Log.i(TAG, "- timeDelta: " + timeDelta + ">" + STATIONARY_TIMEOUT);
                    if (timeDelta > STATIONARY_TIMEOUT) {
                        setPace(false);
                    }
                } else {
                    stationaryLocation = null;
                }
            }
        } else if (stationaryLocation == null) {
            this.startMonitoringStationaryRegion(location);
        }
        lastLocation = location;

        Log.d(TAG, "-------- persistLocation DISABLED");

        // test the measurement to see if it is more accurate than the previous measurement

        //persistLocation(location);

        if (this.isNetworkConnected()) {
            Log.d(TAG, "Scheduling location network post");
            //schedulePostLocations();
        } else {
            Log.d(TAG, "Network unavailable, waiting for now");
        }
    }

    private void startMonitoringStationaryRegion(Location location) {
        Log.i(TAG, "- startMonitoringStationaryRegion (" + location.getLatitude() + "," + location.getLongitude() + ")");
        stationaryLocation = location;

        if (isDebugging) {
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT);
        }

        if (proximityPI != null) {
            locationManager.removeProximityAlert(proximityPI);
        }
        Intent intent = new Intent(STATIONARY_REGION_ACTION);
        proximityPI = PendingIntent.getBroadcast(this, 0, intent, 0);

        locationManager.addProximityAlert(
            location.getLatitude(),
            location.getLongitude(),
            stationaryRadius,
            -1,
            proximityPI
        );

        IntentFilter filter = new IntentFilter(STATIONARY_REGION_ACTION);
        registerReceiver(stationaryRegionReceiver, filter);
    }

    private BroadcastReceiver stationaryRegionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "stationaryRegionReceiver");
            String key = LocationManager.KEY_PROXIMITY_ENTERING;

            Boolean entering = intent.getBooleanExtra(key, false);
            if (entering) {
                Log.d(TAG, "- ENTER");
            }
            else {
                Log.d(TAG, "- EXIT");
                onExitStationaryRegion();
            }
        }
    };

    public void onExitStationaryRegion() {
        if (isDebugging) {
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_CONFIRM);
        }
        if (proximityPI != null) {
            Log.i(TAG, "- proximityPI: " + proximityPI.toString());
            locationManager.removeProximityAlert(proximityPI);
        }
        this.setPace(true);
    }

    public void onProviderDisabled(String provider) {
        // TODO Auto-generated method stub

    }
    public void onProviderEnabled(String provider) {
        // TODO Auto-generated method stub

    }
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // TODO Auto-generated method stub
    }

    private void schedulePostLocations() {
        PostLocationTask task = new LocationUpdateService.PostLocationTask();
        Log.d(TAG, "beforeexecute " +  task.getStatus());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        else
            task.execute();
        Log.d(TAG, "afterexecute " +  task.getStatus());
    }

    private boolean postLocation(com.tenforwardconsulting.cordova.bgloc.data.Location l) {
        if (l == null) {
            Log.w(TAG, "postLocation: null location");
            return false;
        }
        try {
            lastUpdateTime = SystemClock.elapsedRealtime();
            Log.i(TAG, "Posting  native location update: " + l);
            DefaultHttpClient httpClient = new DefaultHttpClient();
            HttpPost request = new HttpPost(url);
            JSONObject params = new JSONObject();
            params.put("auth_token", authToken);

            JSONObject location = new JSONObject();
            location.put("latitude", l.getLatitude());
            location.put("longitude", l.getLongitude());
            location.put("recorded_at", l.getRecordedAt());
            params.put("location", location);


            StringEntity se = new StringEntity(params.toString());
            request.setEntity(se);
            request.setHeader("Accept", "application/json");
            request.setHeader("Content-type", "application/json");
            Log.d(TAG, "Posting to " + request.getURI().toString());
            HttpResponse response = httpClient.execute(request);
            Log.i(TAG, "Response received: " + response.getStatusLine());
            if (response.getStatusLine().getStatusCode() == 200) {
                return true;
            } else {
                return false;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Exception posting location: " + e);
            e.printStackTrace();
            return false;
        }
    }
    private void persistLocation(Location location) {
        LocationDAO dao = DAOFactory.createLocationDAO(this.getApplicationContext());
        com.tenforwardconsulting.cordova.bgloc.data.Location savedLocation = com.tenforwardconsulting.cordova.bgloc.data.Location.fromAndroidLocation(location);

        if (dao.persistLocation(savedLocation)) {
            Log.d(TAG, "Persisted Location: " + savedLocation);
        } else {
            Log.w(TAG, "Failed to persist location");
        }
    }

    private boolean isNetworkConnected() {
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        if (networkInfo != null) {
            Log.d(TAG, "Network found, type = " + networkInfo.getTypeName());
            return networkInfo.isConnected();
        } else {
            Log.d(TAG, "No active network info");
            return false;
        }
    }

    private Notification buildNotification() {
        PackageManager pm = this.getPackageManager();
        Intent notificationIntent = pm.getLaunchIntentForPackage(this.getPackageName());
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        Application application = this.getApplication();
        int backgroundIconId = 0;
        for (String s: Arrays.asList("ic_launcher", "icon", "notification") ) {
            backgroundIconId = application.getResources().getIdentifier(s, "drawable", application.getPackageName());
            if (backgroundIconId != 0) {
                break;
            }
        }

        int appNameId = application.getResources().getIdentifier("app_name", "string", application.getPackageName());

        PendingIntent contentIntent = getActivity(this, 0, notificationIntent, 0);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(backgroundIconId)
                .setContentTitle(this.getString(appNameId))
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .setWhen(System.currentTimeMillis());
        if (lastLocation != null) {
            builder.setContentText("Last location: " + lastLocation.getLatitude() + ", " + lastLocation.getLongitude());
        } else {
            builder.setContentText("Tracking your GPS position");
        }

        return builder.build();
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "------------------------------------------ Destroyed Location update Service");
        cleanUp();
        super.onDestroy();
    }
    private void cleanUp() {
        locationManager.removeUpdates(this);

        // Stationary-region proximity-detector.
        if (proximityPI != null) {
            locationManager.removeProximityAlert(proximityPI);
        }

        notificationManager.cancel(NOTIFICATION_ID);
        wakeLock.release();
        if (looper != null) {
            looper.stop = true;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        this.stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    private class BusyTask extends AsyncTask<String, Integer, Boolean>{
        public boolean stop = false;

        @Override
        protected Boolean doInBackground(String...params) {
            while(!stop) {
                Log.d(TAG, "#timestamp " + System.currentTimeMillis());
                if (lastUpdateTime + 5*60*1000 < SystemClock.elapsedRealtime()) {
                    Log.d(TAG, "5 minutes, forcing update with last location");
                    postLocation(com.tenforwardconsulting.cordova.bgloc.data.Location.fromAndroidLocation(
                            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)));
                }
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            return true;
        }
    }

    private class PostLocationTask extends AsyncTask<Object, Integer, Boolean> {

        @Override
        protected Boolean doInBackground(Object...objects) {
            Log.d(TAG, "Executing PostLocationTask#doInBackground");
            LocationDAO locationDAO = DAOFactory.createLocationDAO(LocationUpdateService.this.getApplicationContext());
            for (com.tenforwardconsulting.cordova.bgloc.data.Location savedLocation : locationDAO.getAllLocations()) {
                Log.d(TAG, "Posting saved location");
                if (postLocation(savedLocation)) {
                    locationDAO.deleteLocation(savedLocation);
                }
            }
            return true;
        }
        @Override
        protected void onPostExecute(Boolean result) {
            Log.d(TAG, "PostLocationTask#onPostExecture");
            notification = buildNotification();
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }
}
