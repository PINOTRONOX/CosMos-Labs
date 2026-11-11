plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17); sourceSets.main { kotlin.srcDir("../app/src/main/java"); kotlin.exclude("**/MainActivity.kt", "**/QrScanActivity.kt", "**/DropTheme.kt", "**/DownloadStore.kt", "**/SafStore.kt") } }
application { mainClass.set("ReceiverHarnessKt") }

dependencies { implementation("org.json:json:20240303") }
