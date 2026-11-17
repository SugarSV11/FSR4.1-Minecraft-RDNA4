package dev.fsr4mc.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FgPacingPolicyTest {
	@Test void modeFromConfig() {
		assertEquals(FgPacingPolicy.Mode.ON, FgPacingPolicy.mode(true, false));
		assertEquals(FgPacingPolicy.Mode.ON, FgPacingPolicy.mode(true, true));
		assertEquals(FgPacingPolicy.Mode.AUTO, FgPacingPolicy.mode(false, true));
		assertEquals(FgPacingPolicy.Mode.OFF, FgPacingPolicy.mode(false, false));
	}

	@Test void autoPicksKByPresentMode() {
		var v = FgPacingPolicy.decide(FgPacingPolicy.Mode.AUTO, true, null);
		assertTrue(v.pace()); assertEquals(0.5, v.k(), 1e-9);
		var f = FgPacingPolicy.decide(FgPacingPolicy.Mode.AUTO, false, null);
		assertTrue(f.pace()); assertEquals(1.0, f.k(), 1e-9);
	}

	@Test void offNeverPacesEvenWithEnv() {
		assertFalse(FgPacingPolicy.decide(FgPacingPolicy.Mode.OFF, true, 0.7).pace());
	}

	@Test void envOverridesKAndManualKeepsHistoricalK() {
		assertEquals(0.7, FgPacingPolicy.decide(FgPacingPolicy.Mode.AUTO, true, 0.7).k(), 1e-9);
		assertEquals(1.0, FgPacingPolicy.decide(FgPacingPolicy.Mode.ON, true, null).k(), 1e-9);
	}

	@Test void parseEnvK() {
		assertNull(FgPacingPolicy.parseEnvK(null));
		assertNull(FgPacingPolicy.parseEnvK("abc"));
		assertEquals(1.5, FgPacingPolicy.parseEnvK("9"), 1e-9);
		assertEquals(0.1, FgPacingPolicy.parseEnvK("0"), 1e-9);
	}
}
