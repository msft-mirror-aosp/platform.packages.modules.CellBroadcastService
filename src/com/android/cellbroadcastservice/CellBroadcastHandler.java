/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.cellbroadcastservice;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Telephony;
import android.provider.Telephony.CellBroadcasts;
import android.telephony.CbGeoUtils.Geometry;
import android.telephony.CbGeoUtils.LatLng;
import android.telephony.CellBroadcastIntents;
import android.telephony.Rlog;
import android.telephony.SmsCbMessage;
import android.telephony.SubscriptionManager;
import android.telephony.cdma.CdmaSmsCbProgramData;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.LocalLog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Dispatch new Cell Broadcasts to receivers. Acquires a private wakelock until the broadcast
 * completes and our result receiver is called.
 */
public class CellBroadcastHandler extends WakeLockStateMachine {
    private static final String EXTRA_MESSAGE = "message";

    /**
     * To disable cell broadcast duplicate detection for debugging purposes
     * <code>adb shell am broadcast -a com.android.cellbroadcastservice.action.DUPLICATE_DETECTION
     * --ez enable false</code>
     *
     * To enable cell broadcast duplicate detection for debugging purposes
     * <code>adb shell am broadcast -a com.android.cellbroadcastservice.action.DUPLICATE_DETECTION
     * --ez enable true</code>
     */
    private static final String ACTION_DUPLICATE_DETECTION =
            "com.android.cellbroadcastservice.action.DUPLICATE_DETECTION";

    /**
     * The extra for cell broadcast duplicate detection enable/disable
     */
    private static final String EXTRA_ENABLE = "enable";

    private final LocalLog mLocalLog = new LocalLog(100);

    private static final boolean IS_DEBUGGABLE = SystemProperties.getInt("ro.debuggable", 0) == 1;

    /** Uses to request the location update. */
    public final LocationRequester mLocationRequester;

    /** Timestamp of last airplane mode on */
    protected long mLastAirplaneModeTime = 0;

    /** Resource cache */
    private final Map<Integer, Resources> mResourcesCache = new HashMap<>();

    /** Whether performing duplicate detection or not. Note this is for debugging purposes only. */
    private boolean mEnableDuplicateDetection = true;

    /**
     * Service category equivalent map. The key is the GSM service category, the value is the CDMA
     * service category.
     */
    private final Map<Integer, Integer> mServiceCategoryCrossRATMap;

    private CellBroadcastHandler(Context context) {
        this("CellBroadcastHandler", context, Looper.myLooper());
    }

    @VisibleForTesting
    public CellBroadcastHandler(String debugTag, Context context, Looper looper) {
        super(debugTag, context, looper);
        mLocationRequester = new LocationRequester(
                context,
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE),
                getHandler().getLooper());

        // Adding GSM / CDMA service category mapping.
        mServiceCategoryCrossRATMap = Stream.of(new Integer[][] {
                // Presidential alert
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL_LANGUAGE,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT},

                // Extreme alert
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_EXTREME_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED_LANGUAGE,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_EXTREME_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_EXTREME_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY_LANGUAGE,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_EXTREME_THREAT},

                // Severe alert
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED_LANGUAGE,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY_LANGUAGE,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED_LANGUAGE,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY_LANGUAGE,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED_LANGUAGE,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY_LANGUAGE,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_SEVERE_THREAT},

                // Amber alert
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_CHILD_ABDUCTION_EMERGENCY,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_CHILD_ABDUCTION_EMERGENCY},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_CHILD_ABDUCTION_EMERGENCY_LANGUAGE,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_CHILD_ABDUCTION_EMERGENCY},

                // Monthly test alert
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_REQUIRED_MONTHLY_TEST,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_TEST_MESSAGE},
                { SmsCbConstants.MESSAGE_ID_CMAS_ALERT_REQUIRED_MONTHLY_TEST_LANGUAGE,
                        CdmaSmsCbProgramData.CATEGORY_CMAS_TEST_MESSAGE},
        }).collect(Collectors.toMap(data -> data[0], data -> data[1]));

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        if (IS_DEBUGGABLE) {
            intentFilter.addAction(ACTION_DUPLICATE_DETECTION);
        }
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        switch (intent.getAction()) {
                            case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                                boolean airplaneModeOn = intent.getBooleanExtra("state", false);
                                if (airplaneModeOn) {
                                    mLastAirplaneModeTime = System.currentTimeMillis();
                                    log("Airplane mode on.");
                                }
                                break;
                            case ACTION_DUPLICATE_DETECTION:
                                mEnableDuplicateDetection = intent.getBooleanExtra(EXTRA_ENABLE,
                                        true);
                                break;
                        }

                    }
                }, intentFilter);
    }

    /**
     * Create a new CellBroadcastHandler.
     * @param context the context to use for dispatching Intents
     * @return the new handler
     */
    public static CellBroadcastHandler makeCellBroadcastHandler(Context context) {
        CellBroadcastHandler handler = new CellBroadcastHandler(context);
        handler.start();
        return handler;
    }

    /**
     * Handle Cell Broadcast messages from {@code CdmaInboundSmsHandler}.
     * 3GPP-format Cell Broadcast messages sent from radio are handled in the subclass.
     *
     * @param message the message to process
     * @return true if need to wait for geo-fencing or an ordered broadcast was sent.
     */
    @Override
    protected boolean handleSmsMessage(Message message) {
        if (message.obj instanceof SmsCbMessage) {
            if (!isDuplicate((SmsCbMessage) message.obj)) {
                handleBroadcastSms((SmsCbMessage) message.obj);
                return true;
            }
            return false;
        } else {
            loge("handleMessage got object of type: " + message.obj.getClass().getName());
            return false;
        }
    }

    /**
     * Dispatch a Cell Broadcast message to listeners.
     * @param message the Cell Broadcast to broadcast
     */
    protected void handleBroadcastSms(SmsCbMessage message) {
        int slotIndex = message.getSlotIndex();

        // TODO: Database inserting can be time consuming, therefore this should be changed to
        // asynchronous.
        ContentValues cv = message.getContentValues();
        Uri uri = mContext.getContentResolver().insert(CellBroadcasts.CONTENT_URI, cv);

        if (message.needGeoFencingCheck()) {
            if (DBG) {
                log("Request location update for geo-fencing. serialNumber = "
                        + message.getSerialNumber());
            }

            requestLocationUpdate(location -> {
                if (location == null) {
                    // Broadcast the message directly if the location is not available.
                    broadcastMessage(message, uri, slotIndex);
                } else {
                    performGeoFencing(message, uri, message.getGeometries(), location, slotIndex);
                }
            }, message.getMaximumWaitingDuration());
        } else {
            if (DBG) {
                log("Broadcast the message directly because no geo-fencing required, "
                        + "serialNumber = " + message.getSerialNumber()
                        + " needGeoFencing = " + message.needGeoFencingCheck());
            }
            broadcastMessage(message, uri, slotIndex);
        }
    }

    /**
     * Check if the message is a duplicate
     *
     * @param message Cell broadcast message
     * @return {@code true} if this message is a duplicate
     */
    @VisibleForTesting
    public boolean isDuplicate(SmsCbMessage message) {
        if (!mEnableDuplicateDetection) {
            log("Duplicate detection was disabled for debugging purposes.");
            return false;
        }

        // Find the cell broadcast message identify by the message identifier and serial number
        // and is not broadcasted.
        String where = CellBroadcasts.RECEIVED_TIME + ">?";

        int slotIndex = message.getSlotIndex();
        SubscriptionManager subMgr = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        int[] subIds = subMgr.getSubscriptionIds(slotIndex);
        Resources res;
        if (subIds != null) {
            res = getResources(subIds[0]);
        } else {
            res = getResources(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        }

        // Only consider cell broadcast messages received within certain period.
        // By default it's 24 hours.
        long expirationDuration = res.getInteger(R.integer.message_expiration_time);
        long dupCheckTime = System.currentTimeMillis() - expirationDuration;

        // Some carriers require reset duplication detection after airplane mode or reboot.
        if (res.getBoolean(R.bool.reset_on_power_cycle_or_airplane_mode)) {
            dupCheckTime = Long.max(dupCheckTime, mLastAirplaneModeTime);
            dupCheckTime = Long.max(dupCheckTime,
                    System.currentTimeMillis() - SystemClock.elapsedRealtime());
        }

        List<SmsCbMessage> cbMessages = new ArrayList<>();

        try (Cursor cursor = mContext.getContentResolver().query(CellBroadcasts.CONTENT_URI,
                CellBroadcastProvider.QUERY_COLUMNS,
                where,
                new String[] {Long.toString(dupCheckTime)},
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    cbMessages.add(SmsCbMessage.createFromCursor(cursor));
                }
            }
        }

        boolean compareMessageBody = res.getBoolean(R.bool.duplicate_compare_body);

        log("Found " + cbMessages.size() + " messages since "
                + DateFormat.getDateTimeInstance().format(dupCheckTime));
        for (SmsCbMessage messageToCheck : cbMessages) {
            // If messages are from different slots, then we only compare the message body.
            if (message.getSlotIndex() != messageToCheck.getSlotIndex()) {
                if (TextUtils.equals(message.getMessageBody(), messageToCheck.getMessageBody())) {
                    log("Duplicate message detected from different slot. " + message);
                    return true;
                }
            } else {
                // Check serial number if message is from the same carrier.
                if (message.getSerialNumber() != messageToCheck.getSerialNumber()) {
                    // Not a dup. Check next one.
                    log("Serial number check. Not a dup. " + messageToCheck);
                    continue;
                }

                // ETWS primary / secondary should be treated differently.
                if (message.isEtwsMessage() && messageToCheck.isEtwsMessage()
                        && message.getEtwsWarningInfo().isPrimary()
                        != messageToCheck.getEtwsWarningInfo().isPrimary()) {
                    // Not a dup. Check next one.
                    log("ETWS primary check. Not a dup. " + messageToCheck);
                    continue;
                }

                // Check if the message category is different. Some carriers send cell broadcast
                // messages on different techs (i.e. GSM / CDMA), so we need to compare service
                // category cross techs.
                if (message.getServiceCategory() != messageToCheck.getServiceCategory()
                        && !Objects.equals(mServiceCategoryCrossRATMap.get(
                                message.getServiceCategory()), messageToCheck.getServiceCategory())
                        && !Objects.equals(mServiceCategoryCrossRATMap.get(
                                messageToCheck.getServiceCategory()),
                        message.getServiceCategory())) {
                    // Not a dup. Check next one.
                    log("Service category check. Not a dup. " + messageToCheck);
                    continue;
                }

                // Compare message body if needed.
                if (!compareMessageBody || TextUtils.equals(
                        message.getMessageBody(), messageToCheck.getMessageBody())) {
                    log("Duplicate message detected. " + message);
                    return true;
                }
            }
        }

        log("Not a duplicate message. " + message);
        return false;
    }

    /**
     * Perform a geo-fencing check for {@code message}. Broadcast the {@code message} if the
     * {@code location} is inside the {@code broadcastArea}.
     * @param message the message need to geo-fencing check
     * @param uri the message's uri
     * @param broadcastArea the broadcast area of the message
     * @param location current location
     */
    protected void performGeoFencing(SmsCbMessage message, Uri uri, List<Geometry> broadcastArea,
            LatLng location, int slotIndex) {

        if (DBG) {
            logd("Perform geo-fencing check for message identifier = "
                    + message.getServiceCategory()
                    + " serialNumber = " + message.getSerialNumber());
        }

        for (Geometry geo : broadcastArea) {
            if (geo.contains(location)) {
                broadcastMessage(message, uri, slotIndex);
                return;
            }
        }

        if (DBG) {
            logd("Device location is outside the broadcast area "
                    + CbGeoUtils.encodeGeometriesToString(broadcastArea));
        }

        sendMessage(EVENT_BROADCAST_NOT_REQUIRED);
    }

    /**
     * Request a single location update.
     * @param callback a callback will be called when the location is available.
     * @param maximumWaitTimeSec the maximum wait time of this request. If location is not updated
     * within the maximum wait time, {@code callback#onLocationUpadte(null)} will be called.
     */
    protected void requestLocationUpdate(LocationUpdateCallback callback, int maximumWaitTimeSec) {
        mLocationRequester.requestLocationUpdate(callback, maximumWaitTimeSec);
    }

    /**
     * Get the subscription ID for a phone ID, or INVALID_SUBSCRIPTION_ID if the phone does not
     * have an active sub
     * @param phoneId the phoneId to use
     * @return the associated sub id
     */
    protected static int getSubIdForPhone(Context context, int phoneId) {
        SubscriptionManager subMan =
                (SubscriptionManager) context.getSystemService(
                        Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        int[] subIds = subMan.getSubscriptionIds(phoneId);
        if (subIds != null) {
            return subIds[0];
        } else {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
    }

    /**
     * Put the phone ID and sub ID into an intent as extras.
     */
    public static void putPhoneIdAndSubIdExtra(Context context, Intent intent, int phoneId) {
        int subId = getSubIdForPhone(context, phoneId);
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            intent.putExtra("subscription", subId);
            intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        }
        intent.putExtra("phone", phoneId);
        intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, phoneId);
    }

    /**
     * Broadcast the {@code message} to the applications.
     * @param message a message need to broadcast
     * @param messageUri message's uri
     */
    protected void broadcastMessage(@NonNull SmsCbMessage message, @Nullable Uri messageUri,
            int slotIndex) {
        String receiverPermission;
        String appOp;
        String msg;
        Intent intent;
        if (message.isEmergencyMessage()) {
            msg = "Dispatching emergency SMS CB, SmsCbMessage is: " + message;
            log(msg);
            mLocalLog.log(msg);
            intent = new Intent(Telephony.Sms.Intents.ACTION_SMS_EMERGENCY_CB_RECEIVED);
            //Emergency alerts need to be delivered with high priority
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            receiverPermission = Manifest.permission.RECEIVE_EMERGENCY_BROADCAST;
            appOp = AppOpsManager.OPSTR_RECEIVE_EMERGENCY_BROADCAST;

            intent.putExtra(EXTRA_MESSAGE, message);
            putPhoneIdAndSubIdExtra(mContext, intent, slotIndex);

            if (IS_DEBUGGABLE) {
                // Send additional broadcast intent to the specified package. This is only for sl4a
                // automation tests.
                String[] testPkgs = mContext.getResources().getStringArray(
                        R.array.config_testCellBroadcastReceiverPkgs);
                if (testPkgs != null) {
                    Intent additionalIntent = new Intent(intent);
                    for (String pkg : testPkgs) {
                        additionalIntent.setPackage(pkg);
                        mContext.createContextAsUser(UserHandle.ALL, 0).sendOrderedBroadcast(
                                additionalIntent, receiverPermission, appOp, null,
                                getHandler(), Activity.RESULT_OK, null, null);
                    }
                }
            }

            String[] pkgs = mContext.getResources().getStringArray(
                    R.array.config_defaultCellBroadcastReceiverPkgs);
            if (pkgs != null) {
                mReceiverCount.addAndGet(pkgs.length);
                for (String pkg : pkgs) {
                    // Explicitly send the intent to all the configured cell broadcast receivers.
                    intent.setPackage(pkg);
                    mContext.createContextAsUser(UserHandle.ALL, 0).sendOrderedBroadcast(
                            intent, receiverPermission, appOp, mReceiver, getHandler(),
                            Activity.RESULT_OK, null, null);
                }
            }
        } else {
            msg = "Dispatching SMS CB, SmsCbMessage is: " + message;
            log(msg);
            mLocalLog.log(msg);
            // Send implicit intent since there are various 3rd party carrier apps listen to
            // this intent.
            intent = new Intent(Telephony.Sms.Intents.SMS_CB_RECEIVED_ACTION);
            receiverPermission = Manifest.permission.RECEIVE_SMS;
            appOp = AppOpsManager.OPSTR_RECEIVE_SMS;

            intent.putExtra(EXTRA_MESSAGE, message);
            putPhoneIdAndSubIdExtra(mContext, intent, slotIndex);

            mReceiverCount.incrementAndGet();
            CellBroadcastIntents.sendOrderedBroadcastForBackgroundReceivers(
                    mContext, UserHandle.ALL, intent,
                    receiverPermission, appOp, mReceiver, getHandler(), Activity.RESULT_OK,
                    null, null);
        }

        if (messageUri != null) {
            ContentValues cv = new ContentValues();
            cv.put(CellBroadcasts.MESSAGE_BROADCASTED, 1);
            mContext.getContentResolver().update(CellBroadcasts.CONTENT_URI, cv,
                    CellBroadcasts._ID + "=?", new String[] {messageUri.getLastPathSegment()});
        }
    }

    /**
     * Get the device resource based on SIM
     *
     * @param subId Subscription index
     *
     * @return The resource
     */
    public @NonNull Resources getResources(int subId) {
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID
                || !SubscriptionManager.isValidSubscriptionId(subId)) {
            return mContext.getResources();
        }

        if (mResourcesCache.containsKey(subId)) {
            return mResourcesCache.get(subId);
        }

        Resources res = SubscriptionManager.getResourcesForSubId(mContext, subId);
        mResourcesCache.put(subId, res);

        return res;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("CellBroadcastHandler:");
        mLocalLog.dump(fd, pw, args);
        pw.flush();
    }

    /** The callback interface of a location request. */
    public interface LocationUpdateCallback {
        /**
         * Call when the location update is available.
         * @param location a location in (latitude, longitude) format, or {@code null} if the
         * location service is not available.
         */
        void onLocationUpdate(@Nullable LatLng location);
    }

    private static final class LocationRequester {
        private static final String TAG = LocationRequester.class.getSimpleName();

        /**
         * Use as the default maximum wait time if the cell broadcast doesn't specify the value.
         * Most of the location request should be responded within 20 seconds.
         */
        private static final int DEFAULT_MAXIMUM_WAIT_TIME_SEC = 20;

        /**
         * Trigger this event when the {@link LocationManager} is not responded within the given
         * time.
         */
        private static final int EVENT_LOCATION_REQUEST_TIMEOUT = 1;

        /** Request a single location update. */
        private static final int EVENT_REQUEST_LOCATION_UPDATE = 2;

        /**
         * Request location update from network or gps location provider. Network provider will be
         * used if available, otherwise use the gps provider.
         */
        private static final List<String> LOCATION_PROVIDERS = Arrays.asList(
                LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER);

        private final LocationManager mLocationManager;
        private final Looper mLooper;
        private final List<LocationUpdateCallback> mCallbacks;
        private final Context mContext;
        private Handler mLocationHandler;

        LocationRequester(Context context, LocationManager locationManager, Looper looper) {
            mLocationManager = locationManager;
            mLooper = looper;
            mCallbacks = new ArrayList<>();
            mContext = context;
            mLocationHandler = new LocationHandler(looper);
        }

        /**
         * Request a single location update. If the location is not available, a callback with
         * {@code null} location will be called immediately.
         *
         * @param callback a callback to the response when the location is available
         * @param maximumWaitTimeSec the maximum wait time of this request. If location is not
         * updated within the maximum wait time, {@code callback#onLocationUpadte(null)} will be
         * called.
         */
        void requestLocationUpdate(@NonNull LocationUpdateCallback callback,
                int maximumWaitTimeSec) {
            mLocationHandler.obtainMessage(EVENT_REQUEST_LOCATION_UPDATE, maximumWaitTimeSec,
                    0 /* arg2 */, callback).sendToTarget();
        }

        private void onLocationUpdate(@Nullable LatLng location) {
            for (LocationUpdateCallback callback : mCallbacks) {
                callback.onLocationUpdate(location);
            }
            mCallbacks.clear();
        }

        private void requestLocationUpdateInternal(@NonNull LocationUpdateCallback callback,
                int maximumWaitTimeSec) {
            if (DBG) Rlog.d(TAG, "requestLocationUpdate");
            if (!isLocationServiceAvailable()) {
                if (DBG) {
                    Rlog.d(TAG, "Can't request location update because of no location permission");
                }
                callback.onLocationUpdate(null);
                return;
            }

            if (!mLocationHandler.hasMessages(EVENT_LOCATION_REQUEST_TIMEOUT)) {
                if (maximumWaitTimeSec == SmsCbMessage.MAXIMUM_WAIT_TIME_NOT_SET) {
                    maximumWaitTimeSec = DEFAULT_MAXIMUM_WAIT_TIME_SEC;
                }
                mLocationHandler.sendMessageDelayed(
                        mLocationHandler.obtainMessage(EVENT_LOCATION_REQUEST_TIMEOUT),
                        maximumWaitTimeSec * DateUtils.SECOND_IN_MILLIS);
            }

            mCallbacks.add(callback);

            for (String provider : LOCATION_PROVIDERS) {
                if (mLocationManager.isProviderEnabled(provider)) {
                    mLocationManager.requestSingleUpdate(provider, mLocationListener, mLooper);
                    break;
                }
            }
        }

        private boolean isLocationServiceAvailable() {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    && !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return false;
            for (String provider : LOCATION_PROVIDERS) {
                if (mLocationManager.isProviderEnabled(provider)) return true;
            }
            return false;
        }

        private boolean hasPermission(String permission) {
            return mContext.checkPermission(permission, Process.myPid(), Process.myUid())
                    == PackageManager.PERMISSION_GRANTED;
        }

        private final LocationListener mLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                mLocationHandler.removeMessages(EVENT_LOCATION_REQUEST_TIMEOUT);
                onLocationUpdate(new LatLng(location.getLatitude(), location.getLongitude()));
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };

        private final class LocationHandler extends Handler {
            LocationHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case EVENT_LOCATION_REQUEST_TIMEOUT:
                        if (DBG) Rlog.d(TAG, "location request timeout");
                        onLocationUpdate(null);
                        break;
                    case EVENT_REQUEST_LOCATION_UPDATE:
                        requestLocationUpdateInternal((LocationUpdateCallback) msg.obj, msg.arg1);
                        break;
                    default:
                        Rlog.e(TAG, "Unsupported message type " + msg.what);
                }
            }
        }
    }
}
