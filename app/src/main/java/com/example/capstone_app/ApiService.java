package com.example.capstone_app;

import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.Field;

public interface ApiService {

    // ✅ 목적지 튜닝 (기존 그대로 유지)
    @Multipart
    @POST("/api/destinations/tune")
    Call<DestinationResponse> tuneDestination(
            @Part("query") RequestBody query,
            @Part("lat") RequestBody lat,
            @Part("lon") RequestBody lon
    );

    // ✅ Jetson 마지막 프레임 + 앱 좌표 기반 현재 위치 피드백
    // 서버가 @RequestParam String deviceKey, double lat, double lon 으로 받는 형태에 맞춤
    @FormUrlEncoded
    @POST("/api/photo-location/from-jetson")
    Call<PhotoLocationResponse> getPhotoLocation(
            @Field("deviceKey") String deviceKey,
            @Field("lat") double lat,
            @Field("lon") double lon
    );
}
