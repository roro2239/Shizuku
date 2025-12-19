package moe.shizuku.manager.starter

import moe.shizuku.manager.application
import java.io.File

object Starter {

    private val packageName = application.packageName
    private val starterFile = File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String = starterFile.absolutePath

    val adbCommand = "`pm path $packageName | grep base | sed 's/package://;s|base.apk|lib/arm64/libshizuku.so|'`"

    val internalCommand = "$userCommand --apk=${application.applicationInfo.sourceDir}"
}
