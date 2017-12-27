package com.example.liushuo.mywebrtc;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import de.tavendo.autobahn.WebSocketConnection;
import de.tavendo.autobahn.WebSocketException;
import de.tavendo.autobahn.WebSocketHandler;
import de.tavendo.autobahn.WebSocketOptions;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import me.weyye.hipermission.HiPermission;
import me.weyye.hipermission.PermissionCallback;
import me.weyye.hipermission.PermissionItem;

public class MainActivity extends Activity implements View.OnClickListener {

    private LinearLayout chartTools;
    private TextView switcCamera;
    private TextView loundSperaker;

    private SurfaceViewRenderer localView;
    private SurfaceViewRenderer remoteView;
    private PeerConnectionFactory mPeerConnectionFactory;
    private CameraVideoCapturer mVideoCapturer;
    private VideoTrack mVideoTrack;
    private AudioTrack mAudioTrack;
    private EglBase mEglBase;
    private MediaStream mMediaStream;
    private Socket mSocket;
    private MediaConstraints pcConstraints;
    private MediaConstraints sdpConstraints;
    private LinkedList<PeerConnection.IceServer> iceServers;
    private Peer mPeer;
    private boolean isOffer = false;
    private AudioManager mAudioManager;
    private VideoTrack remoteVideoTrack;

    private WebSocketConnection webSocketConnection;
    //websocket服务端的url,,,ws是协议,和http一样,我写的时候是用的我们公司的服务器所以这里不能贴出来
    private String websocketHost = "ws://10.0.0.218:7397/";
    private WebSocketOptions options = new WebSocketOptions();

    private static final int CREATE_OFFER  = 1;
    private static final int CREATE_ANSWER = 2;
    private int ResponseType = 0;

    private static final String TAG = "MainActivity";

    // List of mandatory application permissions.
    private static final String[] MANDATORY_PERMISSIONS = {"android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.RECORD_AUDIO", "android.permission.INTERNET"
            , "android.permission.CAMERA"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        initview();
        AskPermission();
    }

    private void AskPermission() {


//        // Check for mandatory permissions.
//        for (String permission : MANDATORY_PERMISSIONS) {
//            if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
//                setResult(RESULT_CANCELED);
//                finish();
//                return;
//            }
//        }

        List<PermissionItem> permissionItems = new ArrayList<PermissionItem>();

        permissionItems.add(new PermissionItem(Manifest.permission.CAMERA, "相机", R.drawable.permission_ic_camera));
        permissionItems.add(new PermissionItem(Manifest.permission.WRITE_EXTERNAL_STORAGE, "存储卡", R.drawable.permission_ic_storage));
        permissionItems.add(new PermissionItem(Manifest.permission.RECORD_AUDIO, "录音", R.drawable.permission_ic_micro_phone));
        permissionItems.add(new PermissionItem(Manifest.permission.READ_PHONE_STATE, "手机", R.drawable.permission_ic_phone));

        HiPermission.create(this).permissions(permissionItems)
                .checkMutiPermission(new PermissionCallback() {
                    @Override
                    public void onClose() {

                    }

                    @Override
                    public void onFinish() {
                        init();
                    }

                    @Override
                    public void onDeny(String permission, int position) {

                    }

                    @Override
                    public void onGuarantee(String permission, int position) {

                    }
                });
    }

    private void init() {
        //初始化PeerConnectionFactory
//        PeerConnectionFactory.initialize(
//                PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
//                        .setEnableVideoHwAcceleration(true)
//                        .createInitializationOptions());

        //硬件加速
        PeerConnectionFactory.initializeAndroidGlobals(
                getApplicationContext(), true);

        //创建PeerConnectionFactory
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        mPeerConnectionFactory = new PeerConnectionFactory(options);
        //设置视频Hw加速,否则视频播放闪屏
        mPeerConnectionFactory.setVideoHwAccelerationOptions(mEglBase.getEglBaseContext(), mEglBase.getEglBaseContext());


        initConstraints();

        mVideoCapturer = createVideoCapture(this);

        VideoSource videoSource = mPeerConnectionFactory.createVideoSource(mVideoCapturer);
        mVideoTrack = mPeerConnectionFactory.createVideoTrack("videtrack", videoSource);

        //设置视频画质 i:width i1 :height i2:fps

        mVideoCapturer.startCapture(720, 1280, 30);

        AudioSource audioSource = mPeerConnectionFactory.createAudioSource(new MediaConstraints());
        mAudioTrack = mPeerConnectionFactory.createAudioTrack("audiotrack", audioSource);
        //播放本地视频
        mVideoTrack.addRenderer(new VideoRenderer(localView));

        //创建媒体流并加入本地音视频
        mMediaStream = mPeerConnectionFactory.createLocalMediaStream("localstream");
        mMediaStream.addTrack(mVideoTrack);
        mMediaStream.addTrack(mAudioTrack);


        connectSocketIO();

        connectWebSocket();
    }

    /**
     * 连接WebSocket
     */
    private void connectWebSocket() {
        webSocketConnection = new WebSocketConnection();
        try{
            webSocketConnection.connect(websocketHost, new WebSocketHandler(){
                @Override
                public void onOpen() {
                    Log.d(TAG, "websocket...open...");
                }

                @Override
                public void onClose(int code, String reason) {
                }

                @Override
                public void onTextMessage(String payload) {
                    if (payload.startsWith("{\"type\":\"offer\"")){
                        Log.d(TAG, "收到offer... ");
                        //应该发送answer信令
                        ResponseType = CREATE_ANSWER;
                        //自己设置远端
                        if (mPeer == null) {
                            mPeer = new Peer();
                        }
                        try {
                            JSONObject jsonObject = new JSONObject(payload);
                            SessionDescription description = new SessionDescription
                                    (SessionDescription.Type.fromCanonicalForm(jsonObject.getString("type")),
                                            jsonObject.getString("description"));
                            mPeer.peerConnection.setRemoteDescription(mPeer, description);
                            mPeer.peerConnection.createAnswer(mPeer, sdpConstraints);
//                            if (!isOffer) {
//                                mPeer.peerConnection.createAnswer(mPeer, sdpConstraints);
//                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }, options);
        }catch (WebSocketException e) {
            e.printStackTrace();
        }

    }

    public void sendMsg(String s) {
        Log.d(TAG, "sendMsg = " + s);
        if (!TextUtils.isEmpty(s)){
            if (webSocketConnection != null) {
                webSocketConnection.sendTextMessage(s);
            }
        }
    }

    /**
     * socket-io连接服务器
     */
    private void connectSocketIO() {
        //连接服务器
        try {
            mSocket = IO.socket("http://10.0.0.102:6667/");
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        mSocket.on("SomeOneOnline", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d(TAG, "client...receive...SomeOneOnline...");
                isOffer = true;
                if (mPeer == null) {
                    mPeer = new Peer();
                }
                mPeer.peerConnection.createOffer(mPeer, sdpConstraints);
            }
        }).on("IceInfo", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    JSONObject jsonObject = new JSONObject(args[0].toString());
                    IceCandidate candidate = null;
                    candidate = new IceCandidate(
                            jsonObject.getString("id"),
                            jsonObject.getInt("label"),
                            jsonObject.getString("candidate")
                    );
                    mPeer.peerConnection.addIceCandidate(candidate);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }).on("SdpInfo", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                if (mPeer == null) {
                    mPeer = new Peer();
                }
                try {
                    JSONObject jsonObject = new JSONObject(args[0].toString());
                    SessionDescription description = new SessionDescription
                            (SessionDescription.Type.fromCanonicalForm(jsonObject.getString("type")),
                                    jsonObject.getString("description"));
                    mPeer.peerConnection.setRemoteDescription(mPeer, description);
                    if (!isOffer) {
                        mPeer.peerConnection.createAnswer(mPeer, sdpConstraints);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        mSocket.connect();
    }

    private void initConstraints() {
        iceServers = new LinkedList<>();
//        iceServers.add(PeerConnection.IceServer.builder("stun:23.21.150.121").createIceServer());
//        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        iceServers.add(new PeerConnection.IceServer("stun:47.254.26.83:3478", "ning", "123456"));


        pcConstraints = new MediaConstraints();
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("RtpDataChannels", "true"));


        sdpConstraints = new MediaConstraints();
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

    }


    private CameraVideoCapturer createVideoCapture(Context context) {
        CameraEnumerator enumerator;
        if (Camera2Enumerator.isSupported(context)) {
            enumerator = new Camera2Enumerator(context);
        } else {
            enumerator = new Camera1Enumerator(true);
        }
        final String[] deviceNames = enumerator.getDeviceNames();

        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                CameraVideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                CameraVideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        return null;
    }

    private void initview() {

        chartTools = findViewById(R.id.charttools_layout);
        switcCamera = findViewById(R.id.switch_camera_tv);
        loundSperaker = findViewById(R.id.loundspeaker_tv);

        switcCamera.setOnClickListener(this);
        loundSperaker.setOnClickListener(this);

        localView = findViewById(R.id.localVideoView);
        remoteView = findViewById(R.id.remoteVideoView);

        //创建EglBase对象
        mEglBase = EglBase.create();

        //初始化localView
        localView.init(mEglBase.getEglBaseContext(), null);
        localView.setKeepScreenOn(true);
        localView.setMirror(true);
        localView.setZOrderMediaOverlay(true);
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        localView.setEnableHardwareScaler(false);

        //初始化remoteView
        remoteView.init(mEglBase.getEglBaseContext(), null);
        remoteView.setMirror(false);
        remoteView.setZOrderMediaOverlay(true);
        remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        remoteView.setEnableHardwareScaler(false);

        //关闭扬声器
        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        assert mAudioManager != null;
        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
        mAudioManager.setSpeakerphoneOn(false);
    }

    @Override
    protected void onDestroy() {
        if(webSocketConnection != null){
            webSocketConnection.disconnect();
        }
        if (mSocket != null) {
            mSocket.disconnect();
        }
        if (mVideoCapturer != null) {
            try {
                mVideoCapturer.stopCapture();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (mPeer != null) {
            mPeer.peerConnection.close();
            mPeer = null;
        }
        if (mVideoTrack != null) {
            mVideoTrack.dispose();
        }
        if (mAudioTrack != null) {
            mAudioTrack.dispose();
        }
        super.onDestroy();
    }

    class Peer implements PeerConnection.Observer, SdpObserver {

        PeerConnection peerConnection;

        Peer() {
            peerConnection = mPeerConnectionFactory.createPeerConnection(iceServers, pcConstraints, this);
            peerConnection.addStream(mMediaStream);
        }

        // PeerConnection.Observer

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {

        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                remoteVideoTrack.dispose();
                remoteView.clearImage();
                mPeer = null;
                isOffer = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "已断开连接!", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {

        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("label", iceCandidate.sdpMLineIndex);
                jsonObject.put("id", iceCandidate.sdpMid);
                jsonObject.put("candidate", iceCandidate.sdp);
                mSocket.emit("IceInfo", jsonObject.toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            remoteVideoTrack = mediaStream.videoTracks.get(0);
            remoteVideoTrack.addRenderer(new VideoRenderer(remoteView));
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {

        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {

        }

        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {

        }

        //    SdpObserver

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            peerConnection.setLocalDescription(this, sessionDescription);
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("type", sessionDescription.type.canonicalForm());
                jsonObject.put("description", sessionDescription.description);
            } catch (JSONException e) {
                e.printStackTrace();
            }
//            mSocket.emit("SdpInfo", jsonObject.toString());

            if (ResponseType == CREATE_ANSWER){
                //然后发送自己的sdp
                String str = "003"+"13511111111";
                try {
                    JSONObject sendJson = new JSONObject();
                    sendJson.put("sdp", sessionDescription.description);
                    sendJson.put("type", "answer");

                    str = str+ sendJson.toString();
                    sendMsg(str);
                    Log.d(TAG, "发送sdp... ");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onSetSuccess() {

        }

        @Override
        public void onCreateFailure(String s) {

        }

        @Override
        public void onSetFailure(String s) {

        }
    }

    //监听音量键控制视频通话音量
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return true;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_UP:
                toggleChartTools();
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.switch_camera_tv:
                mVideoCapturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                    @Override
                    public void onCameraSwitchDone(boolean b) {
                        //切换摄像头完成

                    }

                    @Override
                    public void onCameraSwitchError(String s) {
                        //切换摄像头错误
                    }
                });
                break;

            case R.id.loundspeaker_tv:
                if (mAudioManager.isSpeakerphoneOn()) {
                    mAudioManager.setSpeakerphoneOn(false);
                    Toast.makeText(this, "扬声器已关闭", Toast.LENGTH_SHORT).show();
                } else {
                    mAudioManager.setSpeakerphoneOn(true);
                    Toast.makeText(this, "扬声器已打开", Toast.LENGTH_SHORT).show();
                }
                break;
        }

    }

    private void toggleChartTools() {
        if (chartTools.isShown()) {
            chartTools.setVisibility(View.INVISIBLE);
        } else {
            chartTools.setVisibility(View.VISIBLE);
        }
    }
}
