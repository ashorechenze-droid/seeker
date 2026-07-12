package com.simplerag.application.port.in;

import com.simplerag.application.dto.RemoteSendReview;

@FunctionalInterface
public interface RemoteSendAuthorizer {
    boolean authorize(RemoteSendReview review);
}
