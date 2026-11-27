// Minimal JUnit-4-compatible runner for tools/verify-core.sh: instantiates each class, runs every
// @org.junit.Test method on a fresh instance, reports failures with the root cause, exits non-zero on failure.
import java.lang.reflect.InvocationTargetException

fun main(args: Array<String>) {
    var passed = 0
    val failures = mutableListOf<String>()
    val t0 = System.nanoTime()
    for (name in args) {
        val cls = Class.forName(name)
        val tests = cls.declaredMethods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.sortedBy { it.name }
        for (m in tests) {
            try {
                m.invoke(cls.getDeclaredConstructor().newInstance())
                passed++
            } catch (e: InvocationTargetException) {
                val c = e.targetException
                failures += "✗ ${cls.simpleName}.${m.name}: ${c::class.java.simpleName}: ${c.message}\n    at ${c.stackTrace.firstOrNull { it.className.startsWith("com.agentkeyboard") }}"
            }
        }
        println("  ${cls.simpleName}: ${tests.size} tests")
    }
    failures.forEach(::println)
    println("${passed} passed, ${failures.size} failed in ${(System.nanoTime() - t0) / 1_000_000} ms")
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}
