package vn.flashsale.auth.application;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) { }
