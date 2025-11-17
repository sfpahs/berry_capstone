package com.example.capstone_app;

public class PhotoLocationResponse {

    // 서버가 내려주는 값들: {"lon":..., "source":..., "lat":..., "usedText":..., "status":..., "address":..., "matchedName":...}

    private String status;       // "success", "no_frame", "ocr_failed" 등
    private String matchedName;  // 인제대학교 일강원
    private String address;      // 경남 김해시 인제로 197
    private Double lat;          // 매칭된 장소 위도
    private Double lon;          // 매칭된 장소 경도
    private String usedText;     // OCR에서 최종 사용된 텍스트 (예: "일강원")
    private String source;       // "ocr+kakao" 등

    // 나중에 Jetson free-angle 정보까지 붙이면 여기 확장하면 됨 (현재 서버에서 안 내려줘도 null이면 됨)
    private Double bestAngleDeg;
    private Double leftBlocked;
    private Double rightBlocked;

    public String getStatus() {
        return status;
    }

    public String getMatchedName() {
        return matchedName;
    }

    public String getAddress() {
        return address;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLon() {
        return lon;
    }

    public String getUsedText() {
        return usedText;
    }

    public String getSource() {
        return source;
    }

    public Double getBestAngleDeg() {
        return bestAngleDeg;
    }

    public Double getLeftBlocked() {
        return leftBlocked;
    }

    public Double getRightBlocked() {
        return rightBlocked;
    }
}
