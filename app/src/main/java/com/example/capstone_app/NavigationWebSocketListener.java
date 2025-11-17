package com.example.capstone_app;

import android.util.Log;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class NavigationWebSocketListener extends WebSocketListener {

    private static final String TAG = "NavWebSocket";
    private final MainActivity activity;

    public NavigationWebSocketListener(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        Log.d(TAG, "WebSocket Connected");

        // 여기서는 그냥 연결 성공 로그만 찍고 끝
        activity.runOnUiThread(() -> {
            Log.d(TAG, "WebSocket 연결됨 (UI Thread)");
            // 필요하면 나중에 TextView 업데이트 추가 가능
        });
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        Log.d(TAG, "Received: " + text);

        // 🔥 여기에서 더 이상 activity.handleNavigationMessage(text) 호출 안 함
        activity.runOnUiThread(() -> {
            Log.d(TAG, "수신 메시지(UI Thread): " + text);
            // 나중에 MainActivity 쪽에 파싱 로직 만들면 여기서 호출해도 됨
            // 예) activity.handleJetsonStatus(text);
        });
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        Log.e(TAG, "WebSocket Error", t);
        activity.runOnUiThread(() ->
                Log.e(TAG, "WebSocket onFailure: " + t.getMessage())
        );
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        Log.d(TAG, "WebSocket Closed: " + reason);
    }
}
