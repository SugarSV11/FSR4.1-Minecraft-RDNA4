package dev.fsr4mc.core;

/** Lifecycle of one backend instance. */
public enum BackendState { UNPROBED, UNAVAILABLE, AVAILABLE, INITIALIZING, READY, FAILED, DESTROYING }
