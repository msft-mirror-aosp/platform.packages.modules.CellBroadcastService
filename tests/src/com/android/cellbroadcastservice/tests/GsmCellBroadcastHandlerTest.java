/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.cellbroadcastservice.tests;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.location.Location;
import android.location.LocationRequest;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SmsCbCmasInfo;
import android.telephony.SmsCbMessage;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.text.format.DateUtils;

import com.android.cellbroadcastservice.CellBroadcastHandler;
import com.android.cellbroadcastservice.CellBroadcastProvider;
import com.android.cellbroadcastservice.GsmCellBroadcastHandler;
import com.android.cellbroadcastservice.SmsCbConstants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.Map;
import java.util.function.Consumer;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class GsmCellBroadcastHandlerTest extends CellBroadcastServiceTestBase {

    private GsmCellBroadcastHandler mGsmCellBroadcastHandler;

    private TestableLooper mTestableLooper;

    @Mock
    private Map<Integer, Resources> mMockedResourcesCache;

    private class CellBroadcastContentProvider extends MockContentProvider {
        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                            String sortOrder) {

            // Assume the message was received 2 hours ago.
            long receivedTime = System.currentTimeMillis() - DateUtils.HOUR_IN_MILLIS * 2;
            long locationCheckTime = receivedTime;

            if (uri.compareTo(Telephony.CellBroadcasts.CONTENT_URI) == 0
                    && Long.parseLong(selectionArgs[selectionArgs.length - 1]) <= receivedTime) {
                MatrixCursor mc = new MatrixCursor(CellBroadcastProvider.QUERY_COLUMNS);

                mc.addRow(new Object[]{
                        1,              // _ID
                        0,              // SLOT_INDEX
                        1,              // SUBSCRIPTION_ID
                        0,              // GEOGRAPHICAL_SCOPE
                        "311480",       // PLMN
                        0,              // LAC
                        0,              // CID
                        1234,           // SERIAL_NUMBER
                        SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL,
                        "en",           // LANGUAGE_CODE
                        1,              // DATA_CODING_SCHEME
                        "Test Message", // MESSAGE_BODY
                        1,              // MESSAGE_FORMAT
                        3,              // MESSAGE_PRIORITY
                        0,              // ETWS_WARNING_TYPE
                        0,              // ETWS_IS_PRIMARY
                        SmsCbCmasInfo.CMAS_CLASS_PRESIDENTIAL_LEVEL_ALERT, // CMAS_MESSAGE_CLASS
                        0,              // CMAS_CATEGORY
                        0,              // CMAS_RESPONSE_TYPE
                        0,              // CMAS_SEVERITY
                        0,              // CMAS_URGENCY
                        0,              // CMAS_CERTAINTY
                        receivedTime,   // RECEIVED_TIME
                        locationCheckTime, // LOCATION_CHECK_TIME
                        false,          // MESSAGE_BROADCASTED
                        true,           // MESSAGE_DISPLAYED
                        "",             // GEOMETRIES
                        5,              // MAXIMUM_WAIT_TIME
                });

                return mc;
            }

            return null;
        }

        @Override
        public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
            return 1;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

    }

    private class SettingsProvider extends MockContentProvider {
        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            return null;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                            String sortOrder) {
            return null;
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mTestableLooper = TestableLooper.get(GsmCellBroadcastHandlerTest.this);

        mGsmCellBroadcastHandler = new GsmCellBroadcastHandler(mMockedContext,
                mTestableLooper.getLooper());
        mGsmCellBroadcastHandler.start();

        ((MockContentResolver) mMockedContext.getContentResolver()).addProvider(
                Telephony.CellBroadcasts.CONTENT_URI.getAuthority(),
                new CellBroadcastContentProvider());
        ((MockContentResolver) mMockedContext.getContentResolver()).addProvider(
                Settings.AUTHORITY, new SettingsProvider());
        doReturn(true).when(mMockedResourcesCache).containsKey(anyInt());
        doReturn(mMockedResources).when(mMockedResourcesCache).get(anyInt());
        replaceInstance(CellBroadcastHandler.class, "mResourcesCache",
                mGsmCellBroadcastHandler, mMockedResourcesCache);
        putResources(com.android.cellbroadcastservice.R.integer.message_expiration_time, 86400000);
        putResources(com.android.cellbroadcastservice.R.array
                .additional_cell_broadcast_receiver_packages, new String[]{});
        putResources(com.android.cellbroadcastservice.R.array.area_info_channels, new int[]{});
        putResources(
                com.android.cellbroadcastservice.R.array.config_area_info_receiver_packages,
                new String[]{"fake.inforeceiver.pkg"});
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testTriggerMessage() throws Exception {
        final byte[] pdu = hexStringToBytes("0001113001010010C0111204D2");
        mGsmCellBroadcastHandler.onGsmCellBroadcastSms(0, pdu);
        mTestableLooper.processAllMessages();

        ArgumentCaptor<Consumer<Location>> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(mMockedLocationManager).getCurrentLocation(
                any(LocationRequest.class), any(), any(), consumerCaptor.capture());

        Consumer<Location> consumer = consumerCaptor.getValue();
        consumer.accept(Mockito.mock(Location.class));

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockedContext).sendOrderedBroadcast(intentCaptor.capture(), any(),
                (Bundle) any(), any(), any(), anyInt(), any(), any());
        Intent intent = intentCaptor.getValue();
        assertEquals(Telephony.Sms.Intents.ACTION_SMS_EMERGENCY_CB_RECEIVED, intent.getAction());
        SmsCbMessage msg = intent.getParcelableExtra("message");

        assertEquals(SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL,
                msg.getServiceCategory());
        assertEquals(1234, msg.getSerialNumber());
        assertEquals("Test Message", msg.getMessageBody());
    }

    @Test
    @SmallTest
    public void testAirplaneModeReset() {
        putResources(com.android.cellbroadcastservice.R.bool.reset_on_power_cycle_or_airplane_mode,
                true);
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", true);
        // Send fake airplane mode on event.
        sendBroadcast(intent);

        final byte[] pdu = hexStringToBytes("0001113001010010C0111204D2");
        mGsmCellBroadcastHandler.onGsmCellBroadcastSms(0, pdu);
        mTestableLooper.processAllMessages();

        verify(mMockedContext, never()).sendOrderedBroadcast(any(), anyString(), anyString(),
                any(), any(), anyInt(), any(), any());
    }

    @Test
    @SmallTest
    public void testGeofencingAlertOutOfPolygon() {
        final byte[] pdu = hexStringToBytes("01111D7090010254747A0E4ACF416110B538A582DE6650906AA28"
                + "2AE6979995D9ECF41C576597E2EBBC77950905D96D3D3EE33689A9FD3CB6D1708CA2E87E76550FAE"
                + "C7ECBCB203ABA0C6A97E7F3F0B9EC02C15CB5769A5D0652A030FB1ECECF5D5076393C2F83C8E9B9B"
                + "C7C0ECBC9203A3A3D07B5CBF379F85C06E16030580D660BB662B51A0D57CC3500000000000000000"
                + "0000000000000000000000000000000000000000000000000003021002078B53B6CA4B84B53988A4"
                + "B86B53958A4C2DB53B54A4C28B53B6CA4B840100CFF");
        mGsmCellBroadcastHandler.onGsmCellBroadcastSms(0, pdu);
        mTestableLooper.processAllMessages();

        ArgumentCaptor<Consumer<Location>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(mMockedLocationManager).getCurrentLocation(
                any(LocationRequest.class), any(), any(), captor.capture());

        Consumer<Location> consumer = captor.getValue();
        consumer.accept(Mockito.mock(Location.class));

        verify(mMockedContext, never()).sendOrderedBroadcast(any(), anyString(), anyString(),
                any(), any(), anyInt(), any(), any());
    }
}
