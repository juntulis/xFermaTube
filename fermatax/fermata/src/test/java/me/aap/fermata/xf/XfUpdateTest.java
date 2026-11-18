package me.aap.fermata.xf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XfUpdateTest {
	@Test
	public void comparesReleaseNames() {
		assertTrue(XfUpdate.compare("v1.0.4", "1.0.3") > 0);
		assertTrue(XfUpdate.compare("v1.0.3", "1.0.3-beta.4") > 0);
		assertTrue(XfUpdate.compare("v1.0.3-beta.4", "1.0.3") < 0);
		assertTrue(XfUpdate.compare("v1.0.3-beta.4", "1.0.3-beta.3") > 0);
		assertTrue(XfUpdate.compare("v1.1", "1.0.9") > 0);
		assertTrue(XfUpdate.compare("v1.0.10", "1.0.9") > 0);
		assertEquals(0, XfUpdate.compare("v1.0.3", "1.0.3"));
		assertTrue(XfUpdate.compare("v1.0.2", "1.0.3") < 0);
		assertTrue(XfUpdate.compare("v1.0.4+build.1", "1.0.3") > 0);
	}
}
