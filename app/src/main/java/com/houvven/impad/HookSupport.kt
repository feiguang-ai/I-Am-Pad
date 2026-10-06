package com.houvven.impad

import android.app.Application
import android.content.Context
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import com.highcapable.kavaref.extension.toClassOrNull
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Method
import java.util.Properties

internal class DexMethodCache(
    private val module: XposedModule,
    private val cacheFile: File,
) {

    companion object {
        private const val TAG = "DexMethodCache"
    }

    private var dexkit: DexKitBridge? = null

    private val props: Properties by lazy {
        Properties().apply {
            runCatching {
                if (cacheFile.isFile) cacheFile.inputStream().use { load(it) }
            }
        }
    }

    fun findOrLoad(
        cacheKey: String,
        classLoader: ClassLoader,
        finder: DexKitBridge.() -> DexMethod
    ): Method {
        loadCachedMethod(cacheKey, classLoader)?.let { return it }

        val foundMethod = requireDexkit(classLoader).finder()
        store(cacheKey, foundMethod.serialize())
        return foundMethod.getMethodInstance(classLoader).also {
            module.log(Log.DEBUG, TAG, "Found new method [$cacheKey]: $it")
        }
    }

    private fun loadCachedMethod(cacheKey: String, classLoader: ClassLoader): Method? {
        val serializedMethod =
            props.getProperty(cacheKey)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            DexMethod(serializedMethod).getMethodInstance(classLoader)
        }.onSuccess {
            module.log(Log.DEBUG, TAG, "Loaded cached method [$cacheKey]: $it")
        }.onFailure {
            props.remove(cacheKey)
            module.log(
                Log.WARN,
                TAG,
                "Cached method invalid [$cacheKey], fallback to DexKit: ${it.message}"
            )
        }.getOrNull()
    }

    private fun store(cacheKey: String, serialized: String) {
        runCatching {
            props.setProperty(cacheKey, serialized)
            val parent = cacheFile.parentFile ?: return@runCatching
            parent.mkdirs()
            val tmp = File(parent, "${cacheFile.name}.tmp.${android.os.Process.myPid()}")
            tmp.outputStream().use { props.store(it, null) }
            if (!tmp.renameTo(cacheFile)) {
                tmp.copyTo(cacheFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun requireDexkit(classLoader: ClassLoader): DexKitBridge {
        dexkit?.let { return it }
        System.loadLibrary("dexkit")
        return DexKitBridge.create(classLoader, true).also { dexkit = it }
    }
}

internal fun XposedModule.afterApplicationAttach(
    tag: String = BuildConfig.APPLICATION_ID,
    action: (Context) -> Unit
) {
    val tinkerApplicationClass = "com.tencent.tinker.loader.app.TinkerApplication".toClassOrNull()
    val actionWrapper: (Context) -> Unit = { context ->
        runCatching { action(context) }.onFailure {
            log(Log.ERROR, tag, "Failed to execute afterApplicationAttach hook: ${it.message}")
        }
    }
    val useBaseContextAttached = tinkerApplicationClass != null
    val method = tinkerApplicationClass?.resolve()
        ?.firstMethod { name("onBaseContextAttached") }?.self
        ?: Application::class.resolve().firstMethod { name("onCreate") }.self

    hook(method).intercept { chain ->
        val context = if (useBaseContextAttached) {
            chain.args.firstOrNull() as? Context
        } else {
            chain.thisObject as? Context
        }
        context?.let(actionWrapper)
        return@intercept chain.proceed()
    }
}

internal fun XposedModule.hookToReturn(method: Method, value: Any?) {
    hook(method).intercept { value }
}

internal fun XposedModule.hookAllToReturn(methods: Iterable<Method>, value: Any?) {
    methods.forEach { hookToReturn(it, value) }
}

internal fun XposedModule.simulateTabletProperties() {
    "android.os.SystemProperties".toClass().resolve().method {
        name("get")
        returnType(String::class)
    }.forEach { method ->
        hook(method.self).intercept { chain ->
            if (chain.args.firstOrNull() == "ro.build.characteristics") {
                return@intercept "tablet"
            }
            return@intercept chain.proceed()
        }
    }
}

internal fun simulateTabletModel(
    brand: String,
    model: String,
    manufacturer: String = brand
) {
    android.os.Build::class.resolve().run {
        firstField { name("MANUFACTURER") }.set(manufacturer)
        firstField { name("BRAND") }.set(brand)
        firstField { name("MODEL") }.set(model)
    }
}
