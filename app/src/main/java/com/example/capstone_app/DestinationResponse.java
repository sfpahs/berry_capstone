package com.example.capstone_app;


public class DestinationResponse {
    private Tuned tuned;


    public Tuned getTuned() {
        return tuned;
    }


    public static class Tuned {
        private String name;
        private double lat;
        private double lon;


        public String getName() { return name; }
        public double getLat() { return lat; }
        public double getLon() { return lon; }
    }
}

