package vn.flashsale.auth.domain;

public record ServiceClient(String clientId, String secretHash, boolean enabled) { }
