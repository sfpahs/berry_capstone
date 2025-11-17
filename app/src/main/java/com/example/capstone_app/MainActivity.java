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
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;

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
    private static final float RMS_LOG_THRESHOLD = 2.0f;
    private static final long RMS_LOG_INTERVAL_MS = 700L;

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
    private long lastMicLogTimeMillis = 0L;

    private final Handler sttHandler = new Handler(Looper.getMainLooper());
    private final Handler navigationHandler = new Handler(Looper.getMainLooper());

    private MediaPlayer beepPlayer;
    private boolean isActivityVisible = false;

    // 🔹 내 위치 관련 플래그
    private boolean isLocationSession = false;
    private boolean wasNavigatingBeforeLocation = false;

    // 🔹 위치 관련 (추가)
    private FusedLocationProviderClient fused;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;
    private static final long LOCATION_INTERVAL_MS = 3000L; // 3초

    enum AppState {
        MENU,
        LISTENING_DESTINATION,
        CONFIRMING_DESTINATION,
        NAVIGATING
    }

    // 기존 오디오 권한 런처는 유지
    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) initializeTTS();
                else initializeTTSWithoutSTT();
            });

    // 위치 설정 요청 런처(기기 위치 설정이 꺼져 있을 때 유도)
    private final ActivityResultLauncher<IntentSenderRequest> locationSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                startLocationTracking(); // 설정 후 재시도
            });

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
        startLocationTracking(); // 실제 위치 측정으로 변경됨
    }

    // ================================
    // 위치 수신 (실제 GPS, 3초마다)
    // ================================
    private void startLocationTracking() {
        if (!hasLocationPermission()) {
            // 위치 권한이 없다면 요청 (필요 시 COARSE도 함께)
            requestLocationPermission();
            return;
        }

        if (fused == null) {
            fused = LocationServices.getFusedLocationProviderClient(this);
        }

        // 최신 Builder API 사용: 우선순위 + 간격 지정
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS)   // 3초 최소 간격 요청
                .setMaxUpdateDelayMillis(LOCATION_INTERVAL_MS)      // 배치 지연 최소화
                .build(); // 기기/OS에 따라 정확히 3초는 보장되지 않을 수 있음 [전력 최적화]

        // 기기 위치 설정 확인 (GPS/네트워크 위치가 꺼져있을 수 있음)
        LocationSettingsRequest settingsRequest = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .build();
        SettingsClient settingsClient = LocationServices.getSettingsClient(this);
        settingsClient.checkLocationSettings(settingsRequest)
                .addOnSuccessListener(ignored -> {
                    // 콜백 준비
                    if (locationCallback == null) {
                        locationCallback = new LocationCallback() {
                            @Override
                            public void onLocationResult(LocationResult result) {
                                Location loc = result.getLastLocation();
                                if (loc != null) {
                                    currentLat = loc.getLatitude();
                                    currentLon = loc.getLongitude();
                                    tvCoordinates.setText(String.format(
                                            Locale.KOREA, "현재 위치: %.6f, %.6f", currentLat, currentLon));
                                }
                            }
                        };
                    }
                    // 업데이트 시작
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        // TODO: Consider calling
                        //    ActivityCompat#requestPermissions
                        // here to request the missing permissions, and then overriding
                        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                        //                                          int[] grantResults)
                        // to handle the case where the user grants the permission. See the documentation
                        // for ActivityCompat#requestPermissions for more details.
                        return;
                    }
                    fused.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
                })
                .addOnFailureListener(e -> {
                    if (e instanceof ResolvableApiException) {
                        try {
                            IntentSenderRequest req =
                                    new IntentSenderRequest.Builder(((ResolvableApiException) e).getResolution()).build();
                            locationSettingsLauncher.launch(req);
                        } catch (Exception ex) {
                            Log.e(TAG, "Location settings resolution failed", ex);
                        }
                    } else {
                        Log.e(TAG, "Location settings check failed", e);
                    }
                });
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        // 필요 시 복수 권한 런처로 대체 가능
        requestPermissions(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, 1001);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == 1001) {
            if (hasLocationPermission()) {
                startLocationTracking();
            } else {
                tvCoordinates.setText("현재 위치: 권한 필요");
            }
        }
    }
    // ================================
    // 나머지 기존 로직 (변경 없음)
    // ================================

    private void requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            initializeTTS();
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void logState(String message) {
        Log.d(TAG, "[STATE] " + appState.name() + " | " + message);
    }

    private void initializeTTS() {
        mTTS = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                mTTS.setLanguage(Locale.KOREAN);
                mTTS.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { stopListening(); }
                    @Override public void onDone(String id) { runOnUiThread(() -> startBeepThenTimedListening(4000)); }
                    @Override public void onError(String id) { runOnUiThread(() -> startBeepThenTimedListening(4000)); }
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

    private void initializeSTT() {
        resetSpeechRecognizer();
    }

    private void resetSpeechRecognizer() {
        runOnUiThread(() -> {
            isListening = false;
            if (speechRecognizer != null) {
                try { speechRecognizer.destroy(); } catch (Exception e) { Log.e(TAG, "speechRecognizer.destroy() 오류", e); }
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(buildRecognitionListener());
        });
    }

    private void startListening() {
        runOnUiThread(() -> {
            if (speechRecognizer == null || isListening) return;

            isListening = true;
            logState("startListening()");
            android.content.Intent intent = new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
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
            if (speechRecognizer == null) { isListening = false; return; }
            try { speechRecognizer.stopListening(); } catch (Exception e) { Log.e(TAG, "stopListening() error", e); }
            finally { logState("stopListening()"); isListening = false; }
        });
    }

    private void startBeepThenTimedListening(long durationMs) {
        if (!isActivityVisible) return;

        sttHandler.removeCallbacksAndMessages(null);

        if (beepPlayer != null) {
            try { beepPlayer.stop(); } catch (Exception ignored) {}
            beepPlayer.release();
            beepPlayer = null;
        }

        beepPlayer = MediaPlayer.create(this, R.raw.beep);
        if (beepPlayer == null) { startTimedListening(durationMs); return; }

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

    private RecognitionListener buildRecognitionListener() {
        return new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                tvNavigation.setText("🎙️ 듣는 중...");
                logState("onReadyForSpeech");
            }
            @Override public void onBeginningOfSpeech() { logState("onBeginningOfSpeech"); }
            @Override public void onRmsChanged(float rmsdB) {
                if (rmsdB > RMS_LOG_THRESHOLD) {
                    long now = System.currentTimeMillis();
                    if (now - lastMicLogTimeMillis > RMS_LOG_INTERVAL_MS) {
                        lastMicLogTimeMillis = now;
                        Log.d(TAG, "[MIC] RMS=" + rmsdB);
                    }
                }
            }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { logState("onEndOfSpeech"); }
            @Override public void onError(int error) {
                isListening = false;
                tvNavigation.setText("음성 인식 오류, 다시 시도합니다.");
                switch (error) {
                    case SpeechRecognizer.ERROR_CLIENT: safeRestartListeningWithDelay(500); break;
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: safeRestartListeningWithDelay(400); break;
                    default: safeRestartListeningWithDelay(800); break;
                }
            }
            @Override public void onResults(Bundle results) {
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
    // STT 처리 (기존 유지)
    // ================================
    private void handleSTT(String text) {
        text = text.trim();
        logState("handleSTT: " + text);

        if (isStopCommand(text)) { handleGlobalStop(); return; }
        if (isLocationCommand(text)) { handleGlobalLocation(); return; }

        switch (appState) {
            case MENU:
                if (isGuideCommand(text)) {
                    appState = AppState.LISTENING_DESTINATION;
                    logState("STATE → LISTENING_DESTINATION");
                    speakAndShow("어디로 안내해드릴까요?");
                } else {
                    logState("MENU 상태에서 명령 미인식, 재청취");
                    speakAndShow("길안내를 시작하려면 '길안내'라고 말씀해주세요.");
                    safeRestartListeningWithDelay(600);
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
    // 서버 통신 (기존 유지)
    // ================================
    private void requestDestination(String query) {
        tvNavigation.setText("서버 요청중...");
        logState("requestDestination(): " + query);

        Call<DestinationResponse> call = apiService.tuneDestination(
                okhttp3.RequestBody.create(null, query),
                okhttp3.RequestBody.create(null, String.valueOf(currentLat)),
                okhttp3.RequestBody.create(null, String.valueOf(currentLon))
        );

        call.enqueue(new Callback<DestinationResponse>() {
            @Override
            public void onResponse(Call<DestinationResponse> call, Response<DestinationResponse> res) {
                logState("requestDestination() 응답 code=" + res.code());

                if (!res.isSuccessful() || res.body() == null || res.body().getTuned() == null) {
                    logState("requestDestination() → 후보 없음 / 응답 오류");
                    tvNavigation.setText("후보 없음, 다시 말씀해주세요.");
                    appState = AppState.LISTENING_DESTINATION;
                    safeRestartListeningWithDelay(300);
                    return;
                }

                tempDestinationName = res.body().getTuned().getName();
                tempDestLat = res.body().getTuned().getLat();
                tempDestLon = res.body().getTuned().getLon();
                logState("requestDestination() 성공 name=" + tempDestinationName
                        + " lat=" + tempDestLat + " lon=" + tempDestLon);

                appState = AppState.CONFIRMING_DESTINATION;
                logState("STATE → CONFIRMING_DESTINATION 후보=" + tempDestinationName);
                confirmTryCount = 0;

                speakAndShow(tempDestinationName + " 맞습니까? 예 또는 아니오로 답해주세요.");
            }

            @Override
            public void onFailure(Call<DestinationResponse> call, Throwable t) {
                Log.e(TAG, "tuneDestination 실패", t);
                tvNavigation.setText("서버 연결 실패");
                logState("requestDestination() 실패: " + t.getMessage());
                safeRestartListeningWithDelay(500);
            }
        });
    }

    private void evaluateConfirmation(String text) {
        confirmTryCount++;
        logState("evaluateConfirmation(" + text + ") try=" + confirmTryCount);

        if (isYes(text)) { confirmDestination(); return; }

        if (isNo(text)) {
            if (confirmTryCount >= MAX_CONFIRM_TRY) {
                appState = AppState.LISTENING_DESTINATION;
                speakAndShow("다시 목적지를 말씀해주세요.");
            } else {
                speakAndShow("다른 장소인가요? 다시 말씀해주세요.");
            }
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
        logState("STATE → NAVIGATING 확정 목적지=" + currentDestinationName);

        speakAndShow(currentDestinationName + "으로 이동을 시작합니다.");
        connectWebSocket();
    }

    private void connectWebSocket() {
        String wsUrl = BuildConfig.SERVER_URL
                .replace("http://","ws://")
                .replace("https://","wss://")
                + "ws/navigation";

        Request request = new Request.Builder().url(wsUrl).build();
        mWebSocket = mClient.newWebSocket(request, new NavigationWebSocketListener(this));

        Log.d(TAG, "🚀 WS 연결 시도 → " + wsUrl);
        logState("WebSocket connecting → " + wsUrl);
    }

    private void handleGlobalStop() {
        stopListening();
        logState("handleGlobalStop()");

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
            logState("STATE → MENU (global stop)");
            speakAndShow("모든 안내 종료. 다시 시작하려면 '길안내'라고 말씀해주세요.");
        }
    }

    private void handleGlobalLocation() {
        stopListening();
        wasNavigatingBeforeLocation = (appState == AppState.NAVIGATING);
        isLocationSession = true;
        logState("handleGlobalLocation() 호출 | wasNavigating=" + wasNavigatingBeforeLocation);

        navigationHandler.removeCallbacksAndMessages(null);
        speakAndShow("현재 위치 확인중입니다. 잠시만 기다려 주세요.");
        requestPhotoLocation();
    }

    private void requestPhotoLocation() {
        tvNavigation.setText("현재 위치 분석중...");
        logState("requestPhotoLocation() start lat=" + currentLat + " lon=" + currentLon);

        Call<PhotoLocationResponse> call = apiService.getPhotoLocation(
                DEVICE_KEY,
                currentLat,
                currentLon
        );
        Log.d(TAG, "requestPhotoLocation: " + call.request().url().toString());
        call.enqueue(new Callback<PhotoLocationResponse>() {
            @Override
            public void onResponse(Call<PhotoLocationResponse> call, Response<PhotoLocationResponse> response) {
                logState("requestPhotoLocation() 응답 code=" + response.code());

                if (!response.isSuccessful() || response.body() == null) {
                    logState("requestPhotoLocation() → 응답 없음");
                    handlePhotoLocationNetworkError(null);
                    return;
                }

                PhotoLocationResponse body = response.body();
                logState("requestPhotoLocation() body status=" + body.getStatus()
                        + ", matchedName=" + body.getMatchedName()
                        + ", address=" + body.getAddress()
                        + ", bestAngle=" + body.getBestAngleDeg());

                if (body.getStatus() == null || !"success".equalsIgnoreCase(body.getStatus())) {
                    if (wasNavigatingBeforeLocation) {
                        speakAndShow("카메라 정보가 부족해 위치 확인이 어렵습니다. 경로 안내를 계속 진행해드릴게요.");
                    } else {
                        speakAndShow("카메라 정보가 부족해 위치 확인이 어렵습니다.");
                        appState = AppState.MENU;
                        logState("STATE → MENU (위치 분석 실패)");
                    }
                    isLocationSession = false;
                    wasNavigatingBeforeLocation = false;
                    return;
                }

                StringBuilder sb = new StringBuilder();
                if (body.getMatchedName() != null) {
                    sb.append("현재 위치는 ").append(body.getMatchedName()).append(" 근처입니다.");
                    if (body.getAddress() != null) sb.append(" 주소는 ").append(body.getAddress()).append(" 입니다.");
                } else {
                    sb.append("주변 상호를 찾지 못했습니다.");
                }

                if (body.getBestAngleDeg() != null) {
                    double angle = body.getBestAngleDeg();
                    if (angle < -5) sb.append(" 왼쪽 방향이 더 안전합니다.");
                    else if (angle > 5) sb.append(" 오른쪽 방향이 더 안전합니다.");
                    else sb.append(" 정면 방향이 비교적 열려 있습니다.");
                }

                if (wasNavigatingBeforeLocation) {
                    sb.append(" 경로 안내를 계속 진행해드릴게요.");
                    appState = AppState.NAVIGATING;
                    logState("STATE 유지 NAVIGATING (위치 안내 후)");
                } else {
                    appState = AppState.MENU;
                    logState("STATE → MENU (위치 안내 종료)");
                }

                speakAndShow(sb.toString());
                isLocationSession = false;
                wasNavigatingBeforeLocation = false;
                logState("requestPhotoLocation() 완료");
            }

            @Override
            public void onFailure(Call<PhotoLocationResponse> call, Throwable t) {
                logState("requestPhotoLocation() 실패: " + t.getMessage());
                handlePhotoLocationNetworkError(t);
            }
        });
    }

    private void handlePhotoLocationNetworkError(Throwable t) {
        if (wasNavigatingBeforeLocation) {
            speakAndShow("서버 문제로 위치 확인에 실패했습니다. 경로 안내를 계속 진행해드릴게요.");
            appState = AppState.NAVIGATING;
        } else {
            speakAndShow("서버 문제로 위치 확인 실패. 잠시 후 다시 시도해주세요.");
            appState = AppState.MENU;
            logState("STATE → MENU (위치 서버 오류)");
        }

        isLocationSession = false;
        wasNavigatingBeforeLocation = false;
        logState("handlePhotoLocationNetworkError() 완료");
    }

    private void stopNavigationInternal(String reason) {
        if (mWebSocket != null) {
            try { mWebSocket.close(1000, reason); } catch (Exception ignored) {}
            mWebSocket = null;
        }
        navigationHandler.removeCallbacksAndMessages(null);
        appState = AppState.MENU;
        logState("stopNavigationInternal(): " + reason);
    }

    public void speakAndShow(String text) {
        runOnUiThread(() -> tvNavigation.setText(text));
        if (!text.equals(lastSpokenMessage) && mTTS != null) {
            stopListening();
            logState("speakAndShow(): " + text);
            lastSpokenMessage = text;
            lastTtsTimeMillis = System.currentTimeMillis();
            mTTS.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_" + lastTtsTimeMillis);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityVisible = true;
        // 포그라운드 복귀 시 위치 업데이트 재개
        startLocationTracking(); // 3초 주기 요청 재개 [web:114]
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityVisible = false;
        sttHandler.removeCallbacksAndMessages(null);
        stopListening();
        // 백그라운드 전환 시 배터리 절약을 위해 위치 업데이트 중단
        stopLocationUpdatesSafely(); // [web:114]
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mWebSocket != null) { try { mWebSocket.close(1000, null); } catch (Exception ignored) {} }
        if (speechRecognizer != null) { try { speechRecognizer.destroy(); } catch (Exception ignored) {} }
        if (mTTS != null) { mTTS.stop(); mTTS.shutdown(); }
        if (beepPlayer != null) { try { beepPlayer.stop(); } catch (Exception ignored) {} beepPlayer.release(); }
        sttHandler.removeCallbacksAndMessages(null);
        navigationHandler.removeCallbacksAndMessages(null);
        stopLocationUpdatesSafely(); // [web:114]
    }

    private void stopLocationUpdatesSafely() {
        if (fused != null && locationCallback != null) {
            try { fused.removeLocationUpdates(locationCallback); } catch (Exception ignored) {}
        }
    }
}
