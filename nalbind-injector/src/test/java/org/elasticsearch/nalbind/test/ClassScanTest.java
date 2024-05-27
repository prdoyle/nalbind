package org.elasticsearch.nalbind.test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.stream.Stream;

public class ClassScanTest {

//	@Test
	void scanForPackageURLs() throws IOException {
		Enumeration<URL> resources = getClass().getClassLoader().getResources(getClass().getPackageName().replace('.','/'));
		resources.asIterator().forEachRemaining(url -> {
			try (Stream<Path> paths = Files.walk(Paths.get(url.toURI()))) {
				paths.forEach(System.out::println);
			} catch (IOException | URISyntaxException e) {
				throw new IllegalStateException(e);
			}
		});
	}

}
