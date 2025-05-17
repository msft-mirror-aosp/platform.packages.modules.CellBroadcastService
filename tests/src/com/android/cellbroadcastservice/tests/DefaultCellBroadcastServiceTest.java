/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.PowerManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.ServiceTestCase;
import android.test.mock.MockContentResolver;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.cellbroadcastservice.DefaultCellBroadcastService;
import com.android.modules.utils.build.SdkLevel;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DefaultCellBroadcastServiceTest extends ServiceTestCase<DefaultCellBroadcastService> {

    @Mock
    private Context mMockedContext;

    @Mock
    private ApplicationInfo mApplicationInfo;
    @Mock
    private Resources mMockedResources;

    @Mock
    private SubscriptionManager mMockedSubscriptionManager;

    @Mock
    private TelephonyManager mMockedTelephonyManager;

    @Mock
    private LocationManager mMockedLocationManager;

    @Mock
    private PackageManager mMockedPackageManager;

    @Mock
    private SharedPreferences mSharedPreference;

    @Mock
    private SharedPreferences.Editor mEditor;
    private final MockContentResolver mMockedContentResolver = new MockContentResolver();

    private final Multimap<String, BroadcastReceiver> mBroadcastReceiversByAction =
            ArrayListMultimap.create();

    private static final int FAKE_SUBID = 1;

    public DefaultCellBroadcastServiceTest() {
        super(DefaultCellBroadcastService.class);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        setContext(mMockedContext);
        doReturn(mApplicationInfo).when(mMockedContext).getApplicationInfo();
        doReturn(mMockedContext).when(mMockedContext).getApplicationContext();
        doReturn(mMockedContext).when(mMockedContext).createConfigurationContext(any());
        doReturn(mMockedContentResolver).when(mMockedContext).getContentResolver();
        doReturn(mSharedPreference).when(mMockedContext).getSharedPreferences(
                anyString(), anyInt());
        doReturn(mEditor).when(mSharedPreference).edit();
        doReturn(false).when(mSharedPreference).getBoolean(anyString(), anyBoolean());
        doReturn(mMockedResources).when(mMockedContext).getResources();
        Configuration config = new Configuration();
        doReturn(config).when(mMockedResources).getConfiguration();

        // Can't directly mock power manager because it's final.
        PowerManager powerManager = new PowerManager(mMockedContext, mock(IPowerManager.class),
                mock(IThermalService.class),
                new Handler(TestableLooper.get(DefaultCellBroadcastServiceTest.this).getLooper()));
        doReturn(powerManager).when(mMockedContext).getSystemService(Context.POWER_SERVICE);
        doReturn(mMockedTelephonyManager).when(mMockedContext)
                .getSystemService(Context.TELEPHONY_SERVICE);
        doReturn(Context.TELEPHONY_SERVICE).when(mMockedContext)
                .getSystemServiceName(TelephonyManager.class);
        doReturn(mMockedSubscriptionManager).when(mMockedContext)
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        doReturn(Context.TELEPHONY_SUBSCRIPTION_SERVICE).when(mMockedContext).getSystemServiceName(
                SubscriptionManager.class);
        doReturn(mMockedLocationManager).when(mMockedContext)
                .getSystemService(Context.LOCATION_SERVICE);
        doReturn(true).when(mMockedLocationManager)
                .isLocationEnabled();
        doReturn(mMockedPackageManager).when(mMockedContext)
                .getPackageManager();
        doReturn(mMockedContext).when(mMockedContext).createContextAsUser(any(), anyInt());
        doReturn(new int[]{FAKE_SUBID}).when(mMockedSubscriptionManager)
                .getSubscriptionIds(anyInt());
        doReturn(mMockedTelephonyManager).when(mMockedTelephonyManager)
                .createForSubscriptionId(anyInt());
        Answer<Intent> registerReceiverAnswer = invocation -> {
            BroadcastReceiver receiver = invocation.getArgument(0);
            IntentFilter intentFilter = invocation.getArgument(1);
            for (int i = 0; i < intentFilter.countActions(); i++) {
                mBroadcastReceiversByAction.put(intentFilter.getAction(i), receiver);
            }
            return null;
        };
        doAnswer(registerReceiverAnswer).when(mMockedContext).registerReceiver(
                any(BroadcastReceiver.class), any(IntentFilter.class), any(int.class));
        doAnswer(registerReceiverAnswer).when(mMockedContext).registerReceiver(
                any(BroadcastReceiver.class), any(IntentFilter.class),
                any(), any(), any(int.class));
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    void sendBroadcast(Intent intent) {
        if (mBroadcastReceiversByAction.containsKey(intent.getAction())) {
            for (BroadcastReceiver receiver : mBroadcastReceiversByAction.get(intent.getAction())) {
                receiver.onReceive(mMockedContext, intent);
            }
        }
    }

    @Test
    public void testUserSwitchEvent() {
        if (!SdkLevel.isAtLeastT()) {
            return;
        }
        Intent intentStart = new Intent(mMockedContext, DefaultCellBroadcastService.class);
        startService(intentStart);

        ArgumentCaptor<IntentFilter> captor = ArgumentCaptor.forClass(IntentFilter.class);
        verify(mMockedContext, times(3)).registerReceiver(
                any(), captor.capture(), anyInt());
        assertEquals(Intent.ACTION_USER_SWITCHED, captor.getAllValues().get(2).getAction(0));

        Intent intent = new Intent(Intent.ACTION_USER_SWITCHED);
        // Send fake user switch event.
        sendBroadcast(intent);
        ArgumentCaptor<Intent> captorIntent = ArgumentCaptor.forClass(Intent.class);
        ArgumentCaptor<String> capturePermission = ArgumentCaptor.forClass(String.class);

        verify(mContext, times(1)).sendBroadcastAsUser(
                captorIntent.capture(), any(), capturePermission.capture());
        assertEquals(DefaultCellBroadcastService.ACTION_CELLBROADCAST_USER_SWITCHED,
                captorIntent.getValue().getAction());
        assertEquals(DefaultCellBroadcastService.CBR_MODULE_PERMISSION,
                capturePermission.getValue());
    }

    @Test
    @Override
    public void testServiceTestCaseSetUpProperly() throws Exception {
        super.testServiceTestCaseSetUpProperly();
    }
}
