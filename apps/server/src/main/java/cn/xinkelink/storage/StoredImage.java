package cn.xinkelink.storage;

import java.net.URI;

/** url may be signed or public; expiresAt is added when the provider is selected. */
public record StoredImage(String key, URI url, String contentType, long size) {}
