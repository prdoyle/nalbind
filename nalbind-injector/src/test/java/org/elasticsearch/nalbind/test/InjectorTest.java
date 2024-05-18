package org.elasticsearch.nalbind.test;

import java.io.IOException;
import org.elasticsearch.example.module2.api.Module2Service;
import org.elasticsearch.nalbind.api.InjectableSingleton;
import org.elasticsearch.nalbind.injector.Injector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InjectorTest {

	@Test
	void test() {
		Injector injector = Injector.withFullLayerScan(getClass().getModule().getLayer());
		Module2Service module2Service = injector.getInstance(Module2Service.class);
		assertEquals("Module1Service: Hello from Module1ServiceImpl", module2Service.statusReport());
	}

	@Test
	void testDetectAllSPI() throws IOException {
		for (var m: Module2Service.class.getModule().getLayer().modules()) {
			System.out.println("Module: " + m);
			for (var p: m.getDescriptor().provides()) {
				if (InjectableSingleton.class.getName().equals(p.service())) {
					System.out.println("\tProvider: " + p);
				}
			}
		}
		Iterable<java.net.URL> i = getClass().getClassLoader().getResources("module-info.class")::asIterator;
		for (var r: i) {
			System.out.println("Resource: " + r);
		}
//		var services = Module2Service.class.getClassLoader().getResources("META-INF/services/");
//		var list = new ArrayList<>();
//		services.asIterator().forEachRemaining(list::add);
//		System.out.println("Services: " + list);
	}
}