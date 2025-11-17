package com.example.capstone_app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String DEVICE_KEY = "JETSON-001";

    // GPS 동적 변수
    private double currentLat = 0.0;
    private double currentLon = 0.0;

    private TextView tvDestination, tvCoordinates, tvNavigation;

    private ApiService apiService;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech mTTS;
    private OkHttpClient mClient;
    private WebSocket mWebSocket;

    private AppState appState = AppState.MENU;

    private String tempDestinationName = "";
    private double tempDestLat, tempDestLon;

    private String currentDestinationName = "";
    private double currentDestLat;
    private double currentDestLon;

    private int confirmTryCount = 0;
    private static final int MAX_CONFIRM_TRY = 3;
    private boolean isListening = false;

    private String lastSpokenMessage = "";
    private long lastTtsTimeMillis = 0L;

    private final Handler sttHandler = new Handler(Looper.getMainLooper());
    private final Handler navigationHandler = new Handler(Looper.getMainLooper());

    private MediaPlayer beepPlayer;
    private boolean isActivityVisible = false;

    // 🔹 내 위치 관련 플래그 추가
    private boolean isLocationSession = false;           // 내 위치 질의 진행 중인지
    private boolean wasNavigatingBeforeLocation = false; // 내 위치 호출 직전에 NAVIGATING 이었는지

    enum AppState {
        MENU,
        LISTENING_DESTINATION,
        CONFIRMING_DESTINATION,
        NAVIGATING
    }

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) initializeTTS();
                else initializeTTSWithoutSTT();
            });

    // ================================
    // onCreate
    // ================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvDestination = findViewById(R.id.tvDestination);
        tvCoordinates = findViewById(R.id.tvCoordinates);
        tvNavigation = findViewById(R.id.tvNavigation);

        tvCoordinates.setText("현재 위치 : GPS 수신 준비 중...");

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.level(HttpLoggingInterceptor.Level.BODY);

        mClient = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BuildConfig.SERVER_URL)
                .client(mClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        apiService = retrofit.create(ApiService.class);

        requestMicPermission();
        startLocationTracking();
    }


    // ================================
    // 위치 수신
    // ================================
    private void startLocationTracking() {
        // 실제 GPS 코드와 연결 필요
        // 현재 테스트 단계에서는 GPS 업데이트 시 아래처럼 갱신
        currentLat = 35.250058;
        currentLon = 128.902743;

        tvCoordinates.setText(String.format(Locale.KOREA,
                "현재 위치: %.6f, %.6f", currentLat, currentLon));
    }

    private void requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            initializeTTS();
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void initializeTTS() {
        mTTS = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                mTTS.setLanguage(Locale.KOREAN);
                mTTS.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        stopListening();
                    }

                    @Override
                    public void onDone(String id) {
                        runOnUiThread(() -> startBeepThenTimedListening(4000));
                    }

                    @Override
                    public void onError(String id) {
                        runOnUiThread(() -> startBeepThenTimedListening(4000));
                    }
                });

                initializeSTT();
                appState = AppState.MENU;
                speakAndShow("길안내를 시작하려면 '길안내'라고 말씀해주세요.");
            }
        });
    }

    private void initializeTTSWithoutSTT() {
        mTTS = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) mTTS.setLanguage(Locale.KOREAN);
        });
    }

    // ================================
    // STT 설정
    // ================================
    private void initializeSTT() {
        resetSpeechRecognizer();
    }

    private void resetSpeechRecognizer() {
        runOnUiThread(() -> {
            isListening = false;

            if (speechRecognizer != null) {
                try {
                    speechRecognizer.destroy();
                } catch (Exception e) {
                    Log.e(TAG, "speechRecognizer.destroy() 오류", e);
                }
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(buildRecognitionListener());
        });
    }

    private void startListening() {
        runOnUiThread(() -> {
            if (speechRecognizer == null || isListening) return;

            isListening = true;
            android.content.Intent intent =
                    new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");

            try {
                speechRecognizer.startListening(intent);
                tvNavigation.setText("🎙️ 음성 인식 중...");
            } catch (Exception e) {
                Log.e(TAG, "startListening() 오류", e);
                isListening = false;
            }
        });
    }

    private void safeRestartListeningWithDelay(long delayMs) {
        sttHandler.removeCallbacksAndMessages(null);
        sttHandler.postDelayed(this::startListening, delayMs);
    }

    private void stopListening() {
        runOnUiThread(() -> {
            if (speechRecognizer == null) {
                isListening = false;
                return;
            }

            try {
                speechRecognizer.stopListening();
            } catch (Exception e) {
                Log.e(TAG, "stopListening() error", e);
            } finally {
                isListening = false;
            }
        });
    }

    // ================================
    // beep + timed listening
    // ================================
    private void startBeepThenTimedListening(long durationMs) {
        if (!isActivityVisible) return;

        sttHandler.removeCallbacksAndMessages(null);

        if (beepPlayer != null) {
            try { beepPlayer.stop(); } catch (Exception ignored) {}
            beepPlayer.release();
            beepPlayer = null;
        }

        beepPlayer = MediaPlayer.create(this, R.raw.beep);

        if (beepPlayer == null) {
            startTimedListening(durationMs);
            return;
        }

        beepPlayer.setOnCompletionListener(mp -> {
            try { mp.release(); } catch (Exception ignored) {}
            beepPlayer = null;
            startTimedListening(durationMs);
        });

        try {
            beepPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "beep 재생 실패", e);
            startTimedListening(durationMs);
        }
    }

    private void startTimedListening(long durationMs) {
        if (!isActivityVisible) return;

        sttHandler.removeCallbacksAndMessages(null);

        startListening();

        sttHandler.postDelayed(() -> {
            if (isListening) stopListening();
        }, durationMs);
    }

    // ================================
    // RecognitionListener
    // ================================
    private RecognitionListener buildRecognitionListener() {
        return new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { tvNavigation.setText("🎙️ 듣는 중..."); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}

            @Override
            public void onError(int error) {
                isListening = false;
                tvNavigation.setText("음성 인식 오류, 다시 시도합니다.");

                switch (error) {
                    case SpeechRecognizer.ERROR_CLIENT:
                        safeRestartListeningWithDelay(500);
                        break;
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        safeRestartListeningWithDelay(400);
                        break;
                    default:
                        safeRestartListeningWithDelay(800);
                        break;
                }
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;

                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list == null || list.isEmpty()) {
                    safeRestartListeningWithDelay(300);
                    return;
                }

                handleSTT(list.get(0));
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        };
    }

    // ================================
    // STT 처리
    // ================================
    private void handleSTT(String text) {
        text = text.trim();

        // STOP 명령
        if (isStopCommand(text)) {
            handleGlobalStop();
            return;
        }

        // 내 위치 명령
        if (isLocationCommand(text)) {
            handleGlobalLocation();
            return;
        }

        switch (appState) {
            case MENU:
                if (isGuideCommand(text)) {
                    appState = AppState.LISTENING_DESTINATION;
                    speakAndShow("어디로 안내해드릴까요?");
                }
                break;

            case LISTENING_DESTINATION:
                if (isGuideCommand(text)) {
                    speakAndShow("이미 길안내 모드입니다. 목적지를 말씀해주세요.");
                    return;
                }
                requestDestination(text);
                break;

            case CONFIRMING_DESTINATION:
                evaluateConfirmation(text);
                break;

            case NAVIGATING:
                Log.d(TAG, "NAVIGATING 상태: " + text);
                break;
        }
    }


    // ================================
    // 명령 인식
    // ================================
    private boolean isGuideCommand(String text) {
        return text.matches(".*(길안내|길 안내|길찾기|네비|내비|네비게이션).*");
    }

    private boolean isStopCommand(String text) {
        return text.matches(".*(중지|그만|멈춰|끝내|종료|스톱).*");
    }

    private boolean isYes(String text) {
        return text.matches(".*(예|네|맞|그래).*");
    }

    private boolean isNo(String text) {
        return text.matches(".*(아니|틀려|노).*");
    }

    private boolean isLocationCommand(String text) {
        return text.matches("^(내 위치|현재 위치|여기 어디|내가 어디|어디야|위치).*$");
    }


    // ================================
    // 목적지 후보 요청
    // ================================
    private void requestDestination(String query) {
        tvNavigation.setText("서버 요청중...");

        Call<DestinationResponse> call = apiService.tuneDestination(
                okhttp3.RequestBody.create(null, query),
                okhttp3.RequestBody.create(null, String.valueOf(currentLat)),
                okhttp3.RequestBody.create(null, String.valueOf(currentLon))
        );

        call.enqueue(new Callback<DestinationResponse>() {
            @Override
            public void onResponse(Call<DestinationResponse> call, Response<DestinationResponse> res) {
                if (!res.isSuccessful() || res.body() == null || res.body().getTuned() == null) {
                    tvNavigation.setText("후보 없음, 다시 말씀해주세요.");
                    appState = AppState.LISTENING_DESTINATION;
                    safeRestartListeningWithDelay(300);
                    return;
                }

                tempDestinationName = res.body().getTuned().getName();
                tempDestLat = res.body().getTuned().getLat();
                tempDestLon = res.body().getTuned().getLon();

                appState = AppState.CONFIRMING_DESTINATION;
                confirmTryCount = 0;

                speakAndShow(tempDestinationName + " 맞습니까? 예 또는 아니오로 답해주세요.");
            }

            @Override
            public void onFailure(Call<DestinationResponse> call, Throwable t) {
                Log.e(TAG, "tuneDestination 실패", t);
                tvNavigation.setText("서버 연결 실패");
                safeRestartListeningWithDelay(500);
            }
        });
    }


    // ================================
    // 목적지 확인
    // ================================
    private void evaluateConfirmation(String text) {
        confirmTryCount++;

        if (isYes(text)) {
            confirmDestination();
            return;
        }

        if (isNo(text)) {
            if (confirmTryCount >= MAX_CONFIRM_TRY) {
                appState = AppState.LISTENING_DESTINATION;
                speakAndShow("다시 목적지를 말씀해주세요.");
            } else speakAndShow("다른 장소인가요? 다시 말씀해주세요.");
            return;
        }

        if (confirmTryCount >= MAX_CONFIRM_TRY) {
            appState = AppState.LISTENING_DESTINATION;
            speakAndShow("인식이 어렵습니다. 목적지를 다시 말해주세요");
        } else {
            speakAndShow("예 또는 아니오로 말씀해주세요.");
        }
    }

    private void confirmDestination() {
        currentDestinationName = tempDestinationName;
        currentDestLat = tempDestLat;
        currentDestLon = tempDestLon;

        tvDestination.setText(currentDestinationName);
        tvCoordinates.setText(String.format(Locale.KOREA,
                "목적지 : %.6f, %.6f", currentDestLat, currentDestLon));

        appState = AppState.NAVIGATING;

        speakAndShow(currentDestinationName + "으로 이동을 시작합니다.");
        connectWebSocket();
    }


    // ================================
    // WebSocket 연결
    // ================================
    private void connectWebSocket() {

        String wsUrl = BuildConfig.SERVER_URL
                .replace("http://","ws://")
                .replace("https://","wss://")
                + "ws/navigation";

        Request request = new Request.Builder().url(wsUrl).build();
        mWebSocket = mClient.newWebSocket(request, new NavigationWebSocketListener(this));

        Log.d(TAG, "🚀 WS 연결 시도 → " + wsUrl);
    }


    // ================================
    // STOP 처리
    // ================================
    private void handleGlobalStop() {
        stopListening();

        if (mTTS != null) {
            try { mTTS.stop(); } catch (Exception ignored) {}
        }

        isLocationSession = false;
        wasNavigatingBeforeLocation = false;

        if (appState == AppState.NAVIGATING) {
            stopNavigationInternal("user stop");
            speakAndShow("안내를 종료했습니다. 다시 시작하려면 '길안내'라고 말해주세요.");
        } else {
            navigationHandler.removeCallbacksAndMessages(null);
            appState = AppState.MENU;
            speakAndShow("모든 안내 종료. 다시 시작하려면 '길안내'라고 말씀해주세요.");
        }
    }


    // ================================
    // 내 위치 피드백 요청
    // ================================
    private void handleGlobalLocation() {
        // 🔹 내 위치 시에는 안내만 잠시 멈추고, 경로안내 자체는 유지
        stopListening();

        // 길안내 중이었는지 기록
        wasNavigatingBeforeLocation = (appState == AppState.NAVIGATING);
        isLocationSession = true;

        // 길안내 중이라도 WebSocket은 유지, 단 타이머성 콜백은 잠시 정리
        navigationHandler.removeCallbacksAndMessages(null);

        speakAndShow("현재 위치 확인중입니다. 잠시만 기다려 주세요.");
        requestPhotoLocation();
    }

    private void requestPhotoLocation() {
        tvNavigation.setText("현재 위치 분석중...");

        Call<PhotoLocationResponse> call = apiService.getPhotoLocation(
                DEVICE_KEY,
                currentLat,
                currentLon
        );

        call.enqueue(new Callback<PhotoLocationResponse>() {
            @Override
            public void onResponse(Call<PhotoLocationResponse> call, Response<PhotoLocationResponse> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    handlePhotoLocationNetworkError(null);
                    return;
                }

                PhotoLocationResponse body = response.body();
                if (body.getStatus() == null || !"success".equalsIgnoreCase(body.getStatus())) {
                    if (wasNavigatingBeforeLocation) {
                        // 길안내 중이었으면 경로 계속
                        speakAndShow("카메라 정보가 부족해 위치 확인이 어렵습니다. 경로 안내를 계속 진행해드릴게요.");
                        // appState는 NAVIGATING 유지
                    } else {
                        speakAndShow("카메라 정보가 부족해 위치 확인이 어렵습니다.");
                        appState = AppState.MENU;
                    }
                    isLocationSession = false;
                    wasNavigatingBeforeLocation = false;
                    return;
                }

                StringBuilder sb = new StringBuilder();

                if (body.getMatchedName() != null) {
                    sb.append("현재 위치는 ")
                            .append(body.getMatchedName())
                            .append(" 근처입니다.");

                    if (body.getAddress() != null)
                        sb.append(" 주소는 ").append(body.getAddress()).append(" 입니다.");
                } else {
                    sb.append("주변 상호를 찾지 못했습니다.");
                }

                if (body.getBestAngleDeg() != null) {
                    double angle = body.getBestAngleDeg();
                    if (angle < -5) sb.append(" 왼쪽 방향이 더 안전합니다.");
                    else if (angle > 5) sb.append(" 오른쪽 방향이 더 안전합니다.");
                    else sb.append(" 정면 방향이 비교적 열려 있습니다.");
                }

                // 🔹 내 위치 이후에도 길안내를 계속해야 하는 경우
                if (wasNavigatingBeforeLocation) {
                    sb.append(" 경로 안내를 계속 진행해드릴게요.");
                    // 상태는 계속 NAVIGATING 유지
                    appState = AppState.NAVIGATING;
                } else {
                    appState = AppState.MENU;
                }

                speakAndShow(sb.toString());

                isLocationSession = false;
                wasNavigatingBeforeLocation = false;
            }

            @Override
            public void onFailure(Call<PhotoLocationResponse> call, Throwable t) {
                handlePhotoLocationNetworkError(t);
            }
        });
    }

    private void handlePhotoLocationNetworkError(Throwable t) {
        if (wasNavigatingBeforeLocation) {
            // 🔹 길안내 중이었으면 경로는 그대로 유지
            speakAndShow("서버 문제로 위치 확인에 실패했습니다. 경로 안내를 계속 진행해드릴게요.");
            appState = AppState.NAVIGATING;
        } else {
            speakAndShow("서버 문제로 위치 확인 실패. 잠시 후 다시 시도해주세요.");
            appState = AppState.MENU;
        }

        isLocationSession = false;
        wasNavigatingBeforeLocation = false;
    }


    // ================================
    // 내부 종료
    // ================================
    private void stopNavigationInternal(String reason) {
        if (mWebSocket != null) {
            try { mWebSocket.close(1000, reason); } catch (Exception ignored) {}
            mWebSocket = null;
        }
        navigationHandler.removeCallbacksAndMessages(null);
        appState = AppState.MENU;
    }


    // ================================
    // 화면 표시 + TTS
    // ================================
    public void speakAndShow(String text) {
        runOnUiThread(() -> tvNavigation.setText(text));

        if (!text.equals(lastSpokenMessage) && mTTS != null) {
            stopListening();
            lastSpokenMessage = text;
            lastTtsTimeMillis = System.currentTimeMillis();
            mTTS.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_" + lastTtsTimeMillis);
        }
    }


    // ================================
    // Activity Lifecycle
    // ================================
    @Override
    protected void onResume() {
        super.onResume();
        isActivityVisible = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityVisible = false;
        sttHandler.removeCallbacksAndMessages(null);
        stopListening();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mWebSocket != null) {
            try { mWebSocket.close(1000, null); } catch (Exception ignored) {}
        }

        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
        }

        if (mTTS != null) {
            mTTS.stop();
            mTTS.shutdown();
        }

        if (beepPlayer != null) {
            try { beepPlayer.stop(); } catch (Exception ignored) {}
            beepPlayer.release();
        }

        sttHandler.removeCallbacksAndMessages(null);
        navigationHandler.removeCallbacksAndMessages(null);
    }
}
