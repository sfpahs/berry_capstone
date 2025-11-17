package com.example.capstone_app;

public class PhotoLocationRequest {

    private double lat;
    private double lon;
    private String deviceKey;

    public PhotoLocationRequest(double lat, double lon, String deviceKey) {
        this.lat = lat;
        this.lon = lon;
        this.deviceKey = deviceKey;
    }

    // Gson 등을 위한 기본 생성자 (필요 없으면 제거 가능)
    public PhotoLocationRequest() {
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public String getDeviceKey() {
        return deviceKey;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }

    public void setDeviceKey(String deviceKey) {
        this.deviceKey = deviceKey;
    }
}
