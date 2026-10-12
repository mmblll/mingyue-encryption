plugins { base }

val cryptoCompiler = configurations.create("cryptoCompiler")
dependencies {
    add(cryptoCompiler.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.21")
    add(cryptoCompiler.name, "org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    add(cryptoCompiler.name, "org.jetbrains.kotlin:kotlin-script-runtime:2.3.21")
    add(cryptoCompiler.name, "org.jetbrains.kotlin:kotlin-reflect:2.2.21")
    add(cryptoCompiler.name, "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0")
    add(cryptoCompiler.name, "org.jetbrains:annotations:13.0")
}
val checkClasses = layout.buildDirectory.dir("crypto-checks/classes")
val checkData = layout.buildDirectory.dir("crypto-checks/data")
val checkSources = listOf(
    "app/src/main/java/com/mingyue/app/VaultCrypto.kt",
    "app/src/main/java/com/mingyue/app/VaultPng.kt",
    "tests/CryptoChecks.kt"
)
val compileCryptoChecks = tasks.register<JavaExec>("compileCryptoChecks") {
    group = "verification"
    classpath = cryptoCompiler
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    inputs.files(checkSources)
    outputs.dir(checkClasses)
    doFirst {
        setArgs(listOf("-no-stdlib", "-no-reflect", "-classpath", cryptoCompiler.asPath,
            "-d", checkClasses.get().asFile.absolutePath) + checkSources.map { file(it).absolutePath })
    }
}
tasks.register<JavaExec>("cryptoChecks") {
    group = "verification"
    description = "Synthetic encryption, PNG, integrity and password tests; no user files."
    dependsOn(compileCryptoChecks)
    classpath = files(checkClasses) + cryptoCompiler
    mainClass.set("com.mingyue.app.CryptoChecksKt")
    maxHeapSize = "64m"
    jvmArgs("-Djava.awt.headless=true")
    args(checkData.get().asFile.absolutePath)
    doLast {
        val target = checkData.get().asFile.canonicalFile
        require(target.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        target.deleteRecursively()
    }
}
