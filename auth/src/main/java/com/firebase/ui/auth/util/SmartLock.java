/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firebase.ui.auth.util;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import com.firebase.ui.auth.BuildConfig;
import com.firebase.ui.auth.IdpResponse;
import com.firebase.ui.auth.ui.ActivityHelper;
import com.firebase.ui.auth.ui.ExtraConstants;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.credentials.Credential;
import com.google.android.gms.auth.api.credentials.IdentityProviders;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.Builder;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.firebase.auth.FacebookAuthProvider;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.TwitterAuthProvider;

import static android.app.Activity.RESULT_OK;

public class SmartLock extends Fragment
        implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        ResultCallback<Status> {
    private static final String TAG = "SmartLockFragment";
    private static final int RC_SAVE = 100;
    private static final int RC_UPDATE_SERVICE = 28;

    private AppCompatActivity mActivity;
    private ActivityHelper mActivityHelper;
    private String mName;
    private String mEmail;
    private String mPassword;
    private String mProvider;
    private String mProfilePictureUri;
    private IdpResponse mResponse;
    private GoogleApiClient mCredentialsApiClient;

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        if (mEmail == null) {
            Log.e(TAG, "Unable to save null credential!");
            finish();
            return;
        }

        Credential.Builder builder = new Credential.Builder(mEmail);
        builder.setPassword(mPassword);
        if (mPassword == null) {
            // only password OR provider can be set, not both
            if (mProvider != null) {
                String translatedProvider = null;
                // translate the google.com/facebook.com provider strings into full URIs
                switch (mProvider) {
                    case GoogleAuthProvider.PROVIDER_ID:
                        translatedProvider = IdentityProviders.GOOGLE;
                        break;
                    case FacebookAuthProvider.PROVIDER_ID:
                        translatedProvider = IdentityProviders.FACEBOOK;
                        break;
                    case TwitterAuthProvider.PROVIDER_ID:
                        translatedProvider = IdentityProviders.TWITTER;
                        break;
                    default:
                        Log.e(TAG, "Unable to save null credential!");
                        finish();
                        return;
                }

                builder.setAccountType(translatedProvider);
            }
        }

        if (mName != null) {
            builder.setName(mName);
        }

        if (mProfilePictureUri != null) {
            builder.setProfilePictureUri(Uri.parse(mProfilePictureUri));
        }

        mActivityHelper.getCredentialsApi()
                .save(mCredentialsApiClient, builder.build())
                .setResultCallback(this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Connection suspended with code " + i);
        }
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "connection failed with " + connectionResult.getErrorMessage()
                    + " and code: " + connectionResult.getErrorCode());
        }
        PendingIntent resolution =
                GoogleApiAvailability
                        .getInstance()
                        .getErrorResolutionPendingIntent(mActivity,
                                                         connectionResult.getErrorCode(),
                                                         RC_UPDATE_SERVICE);
        try {
            startIntentSenderForResult(resolution.getIntentSender(),
                                       RC_UPDATE_SERVICE,
                                       null,
                                       0,
                                       0,
                                       0,
                                       null);
        } catch (IntentSender.SendIntentException e) {
            e.printStackTrace();
            finish();
        }
    }


    @Override
    public void onResult(@NonNull Status status) {
        if (status.isSuccess()) {
            finish();
        } else {
            if (status.hasResolution()) {
                // Try to resolve the save request. This will prompt the user if
                // the credential is new.
                try {
                    startIntentSenderForResult(status.getResolution().getIntentSender(),
                                               RC_SAVE,
                                               null,
                                               0,
                                               0,
                                               0,
                                               null);
                } catch (IntentSender.SendIntentException e) {
                    // Could not resolve the request
                    Log.e(TAG, "STATUS: Failed to send resolution.", e);
                    finish();
                }
            } else {
                Log.w(TAG, status.getStatusMessage());
                finish();
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RC_SAVE) {
            if (resultCode == RESULT_OK) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "SAVE: OK");
                }
                finish();
            } else {
                Log.e(TAG, "SAVE: Canceled by user");
                finish();
            }
        } else if (requestCode == RC_UPDATE_SERVICE) {
            if (resultCode == RESULT_OK) {
                Credential credential = new Credential.Builder(mEmail).setPassword(mPassword)
                        .build();
                mActivityHelper.getCredentialsApi()
                        .save(mCredentialsApiClient, credential)
                        .setResultCallback(this);
            } else {
                Log.e(TAG, "SAVE: Canceled by user");
                finish();
            }
        }
    }

    private void finish() {
        Intent resultIntent = new Intent().putExtra(ExtraConstants.EXTRA_IDP_RESPONSE, mResponse);
        mActivity.setResult(RESULT_OK, resultIntent);
        mActivity.finish();
    }

    /**
     * If SmartLock is enabled and Google Play Services is available, save the credentials.
     * Otherwise, finish the calling Activity with RESULT_OK.
     *
     * @param activity     the calling Activity.
     * @param firebaseUser Firebase user to save in Credential.
     * @param password     (optional) password for email credential.
     * @param response     (optional) an {@link IdpResponse} representing the result of signing in.
     */
    public void saveCredentialsOrFinish(AppCompatActivity activity,
                                        ActivityHelper helper,
                                        FirebaseUser firebaseUser,
                                        @Nullable String password,
                                        @Nullable IdpResponse response) {
        mActivity = activity;
        mActivityHelper = helper;
        mName = firebaseUser.getDisplayName();
        mEmail = firebaseUser.getEmail();
        mPassword = password;
        mResponse = response;
        if (response != null) {
            mProvider = response.getProviderType();
        } else {
            mProvider = null;
        }
        mProfilePictureUri = firebaseUser.getPhotoUrl() != null ? firebaseUser.getPhotoUrl()
                .toString() : null;

        // If SmartLock is disabled, finish the Activity
        if (!helper.getFlowParams().smartLockEnabled) {
            finish();
            return;
        }

        // If Play Services is not available, finish the Activity
        if (!PlayServicesHelper.getInstance(activity).isPlayServicesAvailable()) {
            finish();
            return;
        }

        if (!FirebaseAuthWrapperFactory
                .getFirebaseAuthWrapper(helper.getFlowParams().appName)
                .isPlayServicesAvailable(activity)) {
            finish();
            return;
        }

        if (mActivity.isFinishing()) {
            finish();
            return;
        }

        mCredentialsApiClient = new Builder(activity)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Auth.CREDENTIALS_API)
                .enableAutoManage(activity, this)
                .build();
        mCredentialsApiClient.connect();
    }

    @Nullable
    public static SmartLock getInstance(AppCompatActivity activity, String tag) {
        SmartLock result;

        FragmentManager fm = activity.getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();

        Fragment fragment = fm.findFragmentByTag(tag);
        if (fragment == null || !(fragment instanceof SmartLock)) {
            result = new SmartLock();
            try {
                ft.add(result, tag).disallowAddToBackStack().commit();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Can not add fragment", e);
                return null;
            }
        } else {
            result = (SmartLock) fragment;
        }

        return result;
    }
}
