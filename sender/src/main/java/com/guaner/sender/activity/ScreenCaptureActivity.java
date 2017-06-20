package com.guaner.sender.activity;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.guaner.sender.R;
import com.guaner.sender.consts.ActivityServiceMessage;
import com.guaner.sender.consts.ExtraIntent;
import com.guaner.sender.service.ScreenCastService;


public class ScreenCaptureActivity extends Activity {

    private static final String TAG = "ScreenCaptureActivity";

    private final int REMOTE_SERVER_PORT = 49152;

    private static final String PREFERENCE_KEY = "default";
    private static final String PREFERENCE_SERVER_HOST = "server_host";

    private static final String STATE_RESULT_CODE = "result_code";
    private static final String STATE_RESULT_DATA = "result_data";

    private static final int ACTIVITY_RESULT_REQUEST_MEDIA_PROJECTION = 300;

    private int stateResultCode;
    private Intent stateResultData;

    private Context context;
    private Messenger messenger;

    private MediaProjectionManager mediaProjectionManager;
    private ServiceConnection serviceConnection;
    private Messenger serviceMessenger;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            this.stateResultCode = savedInstanceState.getInt(STATE_RESULT_CODE);
            this.stateResultData = savedInstanceState.getParcelable(STATE_RESULT_DATA);
        }

        this.context = this;
        this.mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        this.messenger = new Messenger(new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                Log.i(TAG, "Handler got message : " + msg.what);
                return false;
            }
        }));

        this.serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, name + " service is connected.");

                serviceMessenger = new Messenger(service);
                Message msg = Message.obtain(null, ActivityServiceMessage.CONNECTED);
                msg.replyTo = messenger;
                try {
                    serviceMessenger.send(msg);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to send message due to:" + e.toString());
                    e.printStackTrace();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, name + " service is disconnected.");
                serviceMessenger = null;
            }
        };

        final EditText editTextServerHost = (EditText) findViewById(R.id.editText_server_host);
        final Button startButton = (Button) findViewById(R.id.button_start);

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "Start button clicked.");

                final String serverHost = editTextServerHost.getText().toString();
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putString(PREFERENCE_SERVER_HOST, serverHost).apply();
                startCaptureScreen();
            }
        });

        final Button stopButton = (Button) findViewById(R.id.button_stop);
        stopButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                stopScreenCapture();
            }
        });

        editTextServerHost.setText(context.getSharedPreferences(PREFERENCE_KEY, 0).getString(PREFERENCE_SERVER_HOST, ""));

        startService();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (stateResultData != null) {
            outState.putInt(STATE_RESULT_CODE, stateResultCode);
            outState.putParcelable(STATE_RESULT_DATA, stateResultData);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ACTIVITY_RESULT_REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                Log.i(TAG, "User didn't allow.");
            } else {
                Log.d(TAG, "Starting screen capture");
                stateResultCode = resultCode;
                stateResultData = data;
                startCaptureScreen();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService();
    }

    private void unbindService() {
        if (serviceMessenger != null) {
            try {
                Message msg = Message.obtain(null, ActivityServiceMessage.DISCONNECTED);
                msg.replyTo = messenger;
                serviceMessenger.send(msg);
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to send unregister message to service, e: " + e.toString());
                e.printStackTrace();
            }
            unbindService(serviceConnection);
        }
    }

    private void startService() {
        final EditText editTextServerHost = (EditText) findViewById(R.id.editText_server_host);
        final String serverHost = editTextServerHost.getText().toString();

        Log.i(TAG, "Starting cast service");

        final Intent intent = new Intent(this, ScreenCastService.class);

        if (stateResultCode != 0 && stateResultData != null) {
            intent.putExtra(ExtraIntent.SERVER_HOST.toString(),serverHost);
            intent.putExtra(ExtraIntent.RESULT_CODE.toString(), stateResultCode);
            intent.putExtra(ExtraIntent.RESULT_DATA.toString(), stateResultData);
            intent.putExtra(ExtraIntent.PORT.toString(), REMOTE_SERVER_PORT);
        }

        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void startCaptureScreen() {
        if (stateResultCode != 0 && stateResultData != null) {
            startService();
        } else {
            Log.d(TAG, "Requesting confirmation");
            startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(), ACTIVITY_RESULT_REQUEST_MEDIA_PROJECTION);
        }
    }

    private void stopScreenCapture() {
        if (serviceMessenger == null) {
            return;
        }
        Message msg = Message.obtain(null, ActivityServiceMessage.STOP);
        msg.replyTo = messenger;
        try {
            serviceMessenger.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException:" + e.toString());
            e.printStackTrace();
        }
    }
}
