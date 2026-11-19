package me.aap.fermata.addon.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class XfStartPageTest {

	@Test
	public void startPageEscapesNamesAndKeepsWebLinksOnly() throws Exception {
		Map<String, String> bm = new LinkedHashMap<>();
		bm.put("https://www.example.com/watch?a=1&b=2", "Show <b>\"x\"</b> & 'y'");
		bm.put("javascript:alert(1)", "evil");
		bm.put("https://tv.example.org/", "");
		String html = XfWebSync.startHtml(bm, Arrays.asList(
				new XfWebTabs.Tab("https://a.example.net/p", "Tab <one>"),
				new XfWebTabs.Tab("https://b.example.net/q", "")), 1);

		assertTrue(html.contains("Show &lt;b&gt;&quot;x&quot;&lt;/b&gt; &amp; &#39;y&#39;"));
		assertTrue(html.contains("href=\"https://www.example.com/watch?a=1&amp;b=2\""));
		assertFalse(html.contains("javascript:"));
		assertFalse(html.contains("<b>"));
		assertTrue(html.contains("Tab &lt;one&gt;"));
		assertTrue(html.contains("href=\"https://xf.invalid/tab?i=1\""));
		assertTrue(html.contains("href=\"https://xf.invalid/close?i=0\""));
		assertTrue(html.contains("class=\"o c\" href=\"https://xf.invalid/tab?i=1\""));

		String out = System.getenv("XF_START_OUT");   // to look at the page in a browser
		if (out != null) java.nio.file.Files.write(java.nio.file.Paths.get(out),
				html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void emptyStartPageExplainsHowToAdd() {
		String html = XfWebSync.startHtml(Collections.emptyMap(), Collections.emptyList(), -1);
		assertTrue(html.contains("No bookmarks yet"));
		assertFalse(html.contains("Open pages"));
	}

	@Test
	public void startAddressIsRecognised() {
		assertTrue(XfWebSync.isStartPage("https://xf.invalid/start"));
		assertTrue(XfWebSync.isStartPage("https://xf.invalid/start#x"));
		assertFalse(XfWebSync.isStartPage("https://xf.invalid/started"));
		assertFalse(XfWebTabs.isWeb("https://xf.invalid/tab?i=1"));
		assertTrue(XfWebTabs.isWeb("https://example.com/"));
	}
}
