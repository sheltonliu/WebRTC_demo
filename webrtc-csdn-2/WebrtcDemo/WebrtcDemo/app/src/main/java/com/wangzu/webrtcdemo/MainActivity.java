package com.wangzu.webrtcdemo;

import android.Manifest;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
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
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import me.weyye.hipermission.HiPermission;
import me.weyye.hipermission.PermissionCallback;
import me.weyye.hipermission.PermissionItem;

public class MainActivity extends AppCompatActivity {

    private SurfaceViewRenderer localView;
    private SurfaceViewRenderer remoteView;
    private PeerConnectionFactory mPeerConnectionFactory;
    private VideoCapturer mVideoCapturer;
    private VideoSource mVideoSource;
    private VideoTrack mVideoTrack;
    private AudioSource mAudioSource;
    private AudioTrack mAudioTrack;
    private EglBase mEglBase;
    private MediaStream mMediaStream;
    private Socket mSocket;
    private MediaConstraints pcConstraints;
    private MediaConstraints sdpConstraints;
    private MediaConstraints audioConstraints;
    private LinkedList<PeerConnection.IceServer> iceServers;
    private Peer mPeer;
    private boolean isOffer = false;
    private AudioManager mAudioManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        StatusBarUtils.layoutFullScreen(this);
        initview();
        AskPermission();

        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
        mAudioManager.setSpeakerphoneOn(false);
    }

    private void AskPermission() {
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
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                        .setEnableVideoHwAcceleration(true)
                        .createInitializationOptions());

        //创建PeerConnectionFactory
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        mPeerConnectionFactory = new PeerConnectionFactory(options);
        //设置视频加速,否则视频播放闪屏
        mPeerConnectionFactory.setVideoHwAccelerationOptions(mEglBase.getEglBaseContext(), mEglBase.getEglBaseContext());

        initConstraints();

        mVideoCapturer = createVideoCapture(new Camera2Enumerator(this));


        mVideoSource = mPeerConnectionFactory.createVideoSource(mVideoCapturer);
        mVideoTrack = mPeerConnectionFactory.createVideoTrack("videtrack", mVideoSource);

        mAudioSource = mPeerConnectionFactory.createAudioSource(new MediaConstraints());
        mAudioTrack = mPeerConnectionFactory.createAudioTrack("audiotrack", mAudioSource);

        //设置视频画质 i:width i1 :height i2:fps
        mVideoCapturer.startCapture(720, 1080, 15);

        //播放本地视频
        VideoRenderer renderer = new VideoRenderer(localView);
        mVideoTrack.addRenderer(renderer);
        mVideoTrack.addSink(localView);

        //创建媒体流并加入本地音视频
        mMediaStream = mPeerConnectionFactory.createLocalMediaStream("localstream");
        mMediaStream.addTrack(mVideoTrack);
        mMediaStream.addTrack(mAudioTrack);


        //连接服务器
        try {
            mSocket = IO.socket("http://192.168.0.100:6666/");
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        mSocket.on("SomeOneOnline", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                isOffer = true;
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
                try {
                    JSONObject jsonObject = new JSONObject(args[0].toString());
                    SessionDescription description = new SessionDescription
                            (SessionDescription.Type.fromCanonicalForm(jsonObject.getString("type")),
                                    jsonObject.getString("description"));
                    if (isOffer) {
                        mPeer.peerConnection.setRemoteDescription(mPeer, description);
                    } else {
                        mPeer.peerConnection.setRemoteDescription(mPeer, description);
                        mPeer.peerConnection.createAnswer(mPeer, pcConstraints);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        mSocket.connect();
        mPeer = new Peer();
    }

    private void initConstraints() {
        iceServers = new LinkedList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:23.21.150.121").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        pcConstraints = new MediaConstraints();
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("RtpDataChannels", "true"));


        sdpConstraints = new MediaConstraints();
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));//回声消除,默认开
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "false"));//增益控制
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));//降噪,默认开
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("levelControl", "true"));

    }

    private VideoCapturer createVideoCapture(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        return null;
    }

    private void initview() {
        localView = findViewById(R.id.localVideoView);
        remoteView = findViewById(R.id.remoteVideoView);

        mEglBase = EglBase.create();

        localView.init(mEglBase.getEglBaseContext(), null);
        localView.setMirror(true);
        localView.setZOrderMediaOverlay(true);
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        localView.setEnableHardwareScaler(false);

        remoteView.init(mEglBase.getEglBaseContext(), null);
        remoteView.setMirror(false);
        remoteView.setZOrderMediaOverlay(true);
        remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        remoteView.setEnableHardwareScaler(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (localView != null) {
            localView.release();
        }
        if (mSocket != null) {
            mSocket.disconnect();
            mSocket.close();
        }
        System.exit(0);
    }

    class Peer implements PeerConnection.Observer, SdpObserver {

        public PeerConnection peerConnection;

        public Peer() {
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
                Log.e("tag", "已断开连接!");
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
            Log.e("tag", "onAddStream");
            if (mediaStream.videoTracks.size() > 0) {
                VideoTrack videoTrack = mediaStream.videoTracks.getFirst();
                videoTrack.setEnabled(true);
                VideoRenderer renderer = new VideoRenderer(remoteView);
                videoTrack.addRenderer(renderer);
                videoTrack.addSink(remoteView);
            } else {
                Log.e("tag", "no videotrack");
            }
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
            mSocket.emit("SdpInfo", jsonObject.toString());
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
}
