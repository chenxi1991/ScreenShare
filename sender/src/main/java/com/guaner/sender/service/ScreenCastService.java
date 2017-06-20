package com.guaner.sender.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;
import android.view.Surface;


import com.guaner.sender.consts.ActivityServiceMessage;
import com.guaner.sender.consts.ExtraIntent;
import com.guaner.sender.model.MediaPacket;
import com.guaner.sender.model.VideoPacket;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

/**
 * Created by sih on 2017-05-31.
 */
public final class ScreenCastService extends Service {

    private static final int FPS = 30;
    private final String TAG = "ScreenCastService";

    private MediaProjectionManager mediaProjectionManager;
    private Handler handler;
    private Messenger crossProcessMessenger;

    private Socket socket;
    private OutputStream socketOutputStream;

    private MediaProjection mediaProjection;
    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private MediaCodec.BufferInfo videoBufferInfo;
    private MediaCodec encoder;

    private InetAddress remoteHost;
    private int remotePort;

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");

        handler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                Log.i(TAG, "Handler got message. what:" + msg.what);
                switch (msg.what) {
                    case ActivityServiceMessage.CONNECTED:
                    case ActivityServiceMessage.DISCONNECTED:
                        break;
                    case ActivityServiceMessage.STOP:
                        stopScreenCapture();
                        closeSocket();
                        stopSelf();
                        break;
                }
                return false;
            }
        });
        crossProcessMessenger = new Messenger(handler);
        return crossProcessMessenger.getBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        stopScreenCapture();
        closeSocket();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        final String remoteHost = intent.getStringExtra(ExtraIntent.SERVER_HOST.toString());
        remotePort = intent.getIntExtra(ExtraIntent.PORT.toString(), 49152);
        if (remoteHost == null) {
            return START_NOT_STICKY;
        }

        try {
            this.remoteHost = InetAddress.getByName(remoteHost);
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return START_NOT_STICKY;
        }


        final int resultCode = intent.getIntExtra(ExtraIntent.RESULT_CODE.toString(), -1);
        final Intent resultData = intent.getParcelableExtra(ExtraIntent.RESULT_DATA.toString());

        Log.i(TAG, "resultCode: " + resultCode + " serverAddr:" + remoteHost);

        if (resultCode == 0 || resultData == null) {
            return START_NOT_STICKY;
        }

        final String format = "video/avc";
        final int screenWidth = 1280;
        final int screenHeight = 720;
        final int screenDpi = 320;
        final int bitrate = 6144000;

        Log.i(TAG, "Start casting with format:" + format + ", screen:" + screenWidth + "x" + screenHeight + " @ " + screenDpi + " bitrate:" + bitrate);

        if (!createSocket()) {
            Log.e(TAG, "Failed to connect tcp://" + remoteHost + ":" + remotePort);
            return START_NOT_STICKY;
        }
        Log.i(TAG, "TCP Socket created.");

        startScreenCapture(resultCode, resultData, format, screenWidth, screenHeight, screenDpi, bitrate);

        return START_STICKY;
    }

    private void startScreenCapture(int resultCode, Intent resultData, String format, int width, int height, int dpi, int bitrate) {
        this.mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData);

        Log.d(TAG, "startRecording...");

        this.videoBufferInfo = new MediaCodec.BufferInfo();
        MediaFormat mediaFormat = MediaFormat.createVideoFormat(format, width, height);

        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 0);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        try {
            // AVC
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            this.encoder = MediaCodec.createEncoderByType(format);
            this.encoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec codec, int inputBufferId) {
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int outputBufferId, MediaCodec.BufferInfo info) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
                    if (info.size > 0 && outputBuffer != null) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        byte[] b = new byte[outputBuffer.remaining()];
                        outputBuffer.get(b);

                        MediaPacket.Type type = MediaPacket.Type.VIDEO;
                        VideoPacket.Flag flag = VideoPacket.Flag.CONFIG;

                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            flag = VideoPacket.Flag.END;
                        } else if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
                            flag = VideoPacket.Flag.KEY_FRAME;
                        } else if (info.flags == 0) {
                            flag = VideoPacket.Flag.FRAME;
                        }
                        VideoPacket packet = new VideoPacket(type, flag, info.presentationTimeUs, b);
                        sendPacket(packet);
//                                sendData(null, b);
                    }
                    if (encoder != null) {
                        encoder.releaseOutputBuffer(outputBufferId, false);
                    }
                    if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "End of Stream");
                        stopScreenCapture();
                    }
                }

                @Override
                public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                    e.printStackTrace();
                }

                @Override
                public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                    Log.i(TAG, "onOutputFormatChanged. CodecInfo:" + codec.getCodecInfo().toString() + " MediaFormat:" + format.toString());
                }
            });

            this.encoder.configure(mediaFormat
                    , null // surface
                    , null // crypto
                    , MediaCodec.CONFIGURE_FLAG_ENCODE);

            this.inputSurface = this.encoder.createInputSurface();
            this.encoder.start();

        } catch (IOException e) {
            Log.e(TAG, "Failed to initial encoder, e: " + e);
            releaseEncoders();
        }

        this.virtualDisplay = this.mediaProjection.createVirtualDisplay("Recording Display", width, height, dpi, 0, this.inputSurface, null, null);
    }

    private void sendPacket(VideoPacket packet) {
        if (socketOutputStream != null) {
            try {
                socketOutputStream.write(packet.toByteArray());
            } catch (IOException e) {
                e.printStackTrace();
                stopScreenCapture();
            }
        }
    }

    private void stopScreenCapture() {
        releaseEncoders();
        closeSocket();
        if (virtualDisplay == null) {
            return;
        }
        virtualDisplay.release();
        virtualDisplay = null;
    }

    private void releaseEncoders() {

        if (encoder != null) {
            encoder.stop();
            encoder.release();
            encoder = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        videoBufferInfo = null;
    }


    private boolean createSocket() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    socket = new Socket(remoteHost, remotePort);
                    socketOutputStream = socket.getOutputStream();
                } catch (IOException e) {
                    Log.e(TAG, "Socket creation failed - " + e.toString());
                    e.printStackTrace();
                    socket = null;
                    socketOutputStream = null;
                }
            }
        });
        t.start();
        try {
            t.join();
            if (socket != null && socketOutputStream != null) {
                return true;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                socket = null;
                socketOutputStream = null;
            }
        }
    }
}
