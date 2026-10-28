// BRouter routing engine (MIT), merged from the upstream modules
// brouter-core, -mapaccess, -util, -expressions and -codec.
// See UPSTREAM-COMMIT.txt for the exact revision.
plugins { id("java-library") }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
