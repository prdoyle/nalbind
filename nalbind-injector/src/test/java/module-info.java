module nalbind.nalbind.test {
	requires nalbind.injector;
	requires nalbind.module2;
	requires org.junit.jupiter.api;
	opens org.elasticsearch.nalbind.test;
}