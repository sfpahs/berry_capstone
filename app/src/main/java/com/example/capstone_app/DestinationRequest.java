package com.example.capstone_app;
public class DestinationRequest {
    private String query;
    private double latitude;
    private double longitude;


    public DestinationRequest(String query, double latitude, double longitude) {
        this.query = query;
        this.latitude = latitude;
        this.longitude = longitude;
    }


    // Getter / Setter 필요하면 추가
}

