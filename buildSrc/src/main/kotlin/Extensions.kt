import org.gradle.api.artifacts.dsl.DependencyHandler

/**
 * Adds an add-on project as a dependency.
 * In this standalone context the dependency is handled by compileOnly in the root build.gradle.kts.
 */
fun DependencyHandler.zapAddOn(addOnId: String) {
    // no-op: compile dependency handled by root build
}
