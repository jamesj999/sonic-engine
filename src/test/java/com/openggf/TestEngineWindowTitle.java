package com.openggf;

import com.openggf.version.AppVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestEngineWindowTitle {

	@Test
	public void testBuildWindowTitleUsesResolvedAppVersion() {
		assertEquals("OpenGGF " + AppVersion.get(), Engine.buildWindowTitle());
	}
}
