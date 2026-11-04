package com.reconcileai.application;

/** 503: the investigation executor queue is full. Back-pressure instead of an unbounded in-memory queue. */
public class CapacityExceededException extends RuntimeException {

    public CapacityExceededException(String message) {
        super(message);
    }
}
