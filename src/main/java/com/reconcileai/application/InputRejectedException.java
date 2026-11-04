package com.reconcileai.application;

/** 400: user-supplied text was rejected before any model call (e.g. injection tripwire). Zero tokens spent. */
public class InputRejectedException extends RuntimeException {

    public InputRejectedException(String message) {
        super(message);
    }
}
