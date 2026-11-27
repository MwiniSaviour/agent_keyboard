// Sandbox-only stand-in for junit:junit's annotation, used by tools/verify-core.sh where Maven Central is
// unreachable. Gradle builds use real JUnit 4 and never see this file. Keep the shape source-compatible.
package org.junit

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test
