import org.elasticsearch.example.module2.Module2ServiceImpl;
import org.elasticsearch.nalbind.api.InjectableSingleton;

module nalbind.module2 {
	exports org.elasticsearch.example.module2.api;
	requires nalbind.api;
	requires nalbind.module1;
	opens org.elasticsearch.example.module2 to nalbind.injector;
	provides InjectableSingleton with Module2ServiceImpl;
}