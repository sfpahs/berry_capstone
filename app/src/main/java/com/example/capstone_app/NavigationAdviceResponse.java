package com.example.capstone_app;

public class NavigationAdviceResponse {

    private String deviceKey;
    private String mode;
    private Double bestAngleDeg;
    private Double leftBlocked;
    private Double rightBlocked;
    private String advice;
    private String updatedAt;
    private String rawJson;

    public String getDeviceKey() {
        return deviceKey;
    }

    public String getMode() {
        return mode;
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

    public String getAdvice() {
        return advice;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public String getRawJson() {
        return rawJson;
    }
}


