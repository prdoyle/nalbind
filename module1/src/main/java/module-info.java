import org.elasticsearch.example.module1.Module1ServiceImpl;
import org.elasticsearch.nalbind.api.InjectableSingleton;

module nalbind.module1 {
	exports org.elasticsearch.example.module1.api;
	requires nalbind.api;
	opens org.elasticsearch.example.module1 to nalbind.injector;
	provides InjectableSingleton with Module1ServiceImpl;
}