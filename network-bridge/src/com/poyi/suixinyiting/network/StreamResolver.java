package com.poyi.suixinyiting.network;

public interface StreamResolver {
    StreamVariant resolve(long songId, String preferredLevel) throws Exception;
}
