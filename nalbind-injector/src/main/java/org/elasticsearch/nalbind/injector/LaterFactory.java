package org.elasticsearch.nalbind.injector;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;
import static org.objectweb.asm.Type.getDescriptor;
import static org.objectweb.asm.Type.getInternalName;


public class LaterFactory {

	public static final String CONSTRUCTOR_DESCRIPTOR = "(" + getDescriptor(MutableCallSite.class) + ")V";
	public static final Map<String, MutableCallSite> callSites = new ConcurrentHashMap<>();
	public static final AtomicInteger numCallSites = new AtomicInteger(0);

	static <T> Later<T> generateFor(Class<T> returnClass) {
		ClassWriter cw = new ClassWriter(0);

		// Define the class
		cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, "GeneratedClass", null, getInternalName(MutableLater.class), null);

		// Define the default constructor
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", CONSTRUCTOR_DESCRIPTOR, null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitMethodInsn(INVOKESPECIAL, getInternalName(MutableLater.class), "<init>", CONSTRUCTOR_DESCRIPTOR, false);
		mv.visitInsn(RETURN);
		mv.visitMaxs(99, 99);
		mv.visitEnd();

		int callSiteNum = numCallSites.incrementAndGet();
		String methodName = "callSite+ " + callSiteNum;
		MutableCallSite callSite = newCallSite(MethodType.methodType(returnClass));
		callSites.put(methodName, callSite);

		/* Note that this descriptor returns Object even though we know a more specific
		   return type, and Java allows covariant return types. Turns out that is a
		   language feature that isn't available at the bytecode level. Hopefully
		   this method gets inlined and the jit can see through it to avoid a downcast.
		 */
		mv = cw.visitMethod(ACC_PUBLIC, "value", "()" + getDescriptor(Object.class), null, null);
		mv.visitCode();

		// The invokedynamic instruction
		Handle bootstrapMethodHandle = new Handle(
			Opcodes.H_INVOKESTATIC,

			getInternalName(LaterFactory.class),
			"bootstrap",
			MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class).toMethodDescriptorString(),
			false
		);
		mv.visitInvokeDynamicInsn(methodName, "()" + getDescriptor(returnClass), bootstrapMethodHandle);

		// Return the result
		mv.visitInsn(ARETURN);
		mv.visitMaxs(99, 99); // Computed automatically
		mv.visitEnd();

		cw.visitEnd();

		byte[] bytes = cw.toByteArray();
		Constructor<?> ctor = new CustomClassLoader(LaterFactory.class.getClassLoader())
			.loadThemBytes("GeneratedClass", bytes)
			.getConstructors()[0];
		try {
			//noinspection unchecked
			return (Later<T>) ctor.newInstance(callSite);
		} catch (InstantiationException | IllegalAccessException | VerifyError | InvocationTargetException e) {
			throw new AssertionError("Should be able to instantiate the generated class", e);
		}
	}

	private static MutableCallSite newCallSite(MethodType type) {
		try {
			return new MutableCallSite(lookup()
				.findStatic(LaterFactory.class, "notYetSet", methodType(void.class))
				.asType(type));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	public static void notYetSet() {
		throw new IllegalStateException("Too early!");
	}

	public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type) {
		return requireNonNull(callSites.remove(name), ()->"CallSite not found: \"" + name + "\"");
	}

	private static final class CustomClassLoader extends ClassLoader {
		CustomClassLoader(ClassLoader parentClassLoader) {
			super(parentClassLoader);
		}

		public Class<?> loadThemBytes(String dottyName, byte[] b) {
			return defineClass(dottyName, b, 0, b.length);
		}
	}

}
