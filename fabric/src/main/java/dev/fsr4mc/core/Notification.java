package dev.fsr4mc.core;

public record Notification(String text, boolean error) {
	public static final long DURATION_MS = 2000L;
}
