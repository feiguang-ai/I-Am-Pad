package com.houvven.impad

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.hasClass
import com.highcapable.kavaref.extension.toClass
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

class HookEntrance : XposedModule() {

    companion object {
        private const val TAG = BuildConfig.APPLICATION_ID
        private const val DEXKIT_PREFS_NAME = "IAMPAD_dexkit"
        private const val QQ_TARGET_MODEL = "23046RP50C"
        private const val XHS_TARGET_MODEL = "23046RP50C"
        private const val QQ_BUGLY_PREFS_NAME = "BUGLY_COMMON_VALUES"
        private const val QQ_PANDORA_CACHE_PATH = "files/mmkv/Pandora"
        private const val QQ_PANDORA_CRC_PATH = "files/mmkv/Pandora.crc"
    }

    private var methodCache: DexMethodCache? = null

    @Suppress("SpellCheckingInspection")
    private val customWeWorkPackages = setOf(
        "com.airchina.wecompro",
        "com.zwfw.YueZhengYi",
        "com.cscec.portal",
        "cn.powerchina.pact"
    )

    private data class PackageRoute(
        val match: (XposedModuleInterface.PackageReadyParam) -> Boolean,
        val handle: (XposedModuleInterface.PackageReadyParam) -> Unit
    )

    private val packageRoutes = listOf(
        PackageRoute(
            match = { it.packageName.contains("com.tencent.mobileqq") },
            handle = { processQQ() }
        ),
        PackageRoute(
            match = { it.packageName.contains("com.tencent.mm") },
            handle = { processWeChat() }
        ),
        PackageRoute(
            match = { it.packageName.contains("com.tencent.wework") },
            handle = { processWeWork() }
        ),
        PackageRoute(
            match = { it.packageName.contains("com.xingin.xhs") },
            handle = { processXhs(it.classLoader) }
        ),
        PackageRoute(
            match = ::isDingTalk,
            handle = { processDingTalk() }
        ),
        PackageRoute(
            match = ::isCustomWeWork,
            handle = { processCustomWeWork() }
        )
    )

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        packageRoutes.firstOrNull { it.match(param) }?.handle?.invoke(param)
    }

    private fun processQQ() {
        simulateTabletModel("Xiaomi", QQ_TARGET_MODEL)
        simulateTabletProperties()
        Application::class.java.resolve().firstMethod {
            name("onCreate")
        }.self.let { method ->
            hook(method).intercept { chain ->
                val context =
                    (chain.args.firstOrNull() as? Context) ?: (chain.thisObject as? Context)
                context?.let(::resetQQModelCacheIfNeeded)
                chain.proceed()
            }
        }
    }

    private fun processWeChat() = afterApplicationAttach { context ->
        hookDexMethodToReturn("checkLoginAsPad_method", context, true) {
            findMethod {
                excludePackages("android", "androidx", "com")
                matcher {
                    modifiers(Modifier.PUBLIC or Modifier.FINAL)
                    paramCount(3)
                    paramTypes(
                        String::class.java,
                        String::class.java,
                        Continuation::class.java
                    )
                    usingStrings(
                        "MicroMsg.CgiCheckLoginAsPad",
                        "/cgi-bin/micromsg-bin/checkloginaspad"
                    )
                }
            }.single().toDexMethod()
        }

        hookDexMethodToReturn("isFoldableDevice_method", context, true) {
            findMethod {
                searchPackages("com.tencent.mm.ui")
                matcher {
                    modifiers(Modifier.PUBLIC or Modifier.STATIC)
                    paramCount(0)
                    usingStrings("royole", "tecno", "ro.os_foldable_screen_support")
                    returnType(Boolean::class.javaPrimitiveType!!)
                }
            }.single().toDexMethod()
        }
    }

    private fun processWeWork() = afterApplicationAttach { context ->
        hookAllToReturn(
            methods = "com.tencent.wework.foundation.impl.WeworkServiceImpl"
                .toClass(context.classLoader)
                .resolve()
                .method {
                    name { it.startsWith("isAndroidPad") }
                    returnType(Boolean::class)
                }.map { it.self },
            value = true
        )
    }

    private fun processDingTalk() = afterApplicationAttach { context ->
        hookDexMethodToReturn("isMultiLoginFoldableDevice_method", context, true) {
            findMethod {
                searchPackages("com.alibaba.android.dingtalkbase.foldable")
                matcher {
                    modifiers(Modifier.STATIC)
                    paramCount(1)
                    paramTypes(Activity::class.java)
                    returnType(Boolean::class.javaPrimitiveType!!)
                    usingStrings("isMultiLoginFoldableDevice")
                }
            }.single().toDexMethod()
        }
    }

    private fun processCustomWeWork() = afterApplicationAttach { context ->
        hookDexMethodToReturn("isPadJudge_method", context, true) {
            findMethod {
                matcher {
                    declaredClass("com.tencent.wework.common.utils.WwUtil")
                    returnType(Boolean::class.javaPrimitiveType!!)
                    paramCount(0)
                    modifiers(Modifier.STATIC)
                    usingStrings(
                        "isPadJudge",
                        "isPadWhiteListFromServer", "isPadBlackListFromServer",
                        "isPadWhiteListFromLocal", "isPadBlackListFromLocal"
                    )
                }
            }.single().toDexMethod()
        }
    }

    private fun processXhs(classLoader: ClassLoader) {
        runCatching {
            // XHS now sends the accurate model to /api/sns/v1/system/device_type and
            // uses the server-side classification for its login/session identity.
            simulateTabletModel("Xiaomi", XHS_TARGET_MODEL)
            simulateTabletProperties()

            "com.xingin.adaptation.device.DeviceInfoContainer".toClass(classLoader).resolve().run {
                val isPadMethods = method { name("isPad") }.map { it.self }
                val isPhoneMethods = method { name("isPhone") }.map { it.self }
                val deviceTypeMethods = method { name("getDeviceType") }.map { it.self }
                val savedDeviceTypeMethods = method { name("getSavedDeviceType") }.map { it.self }
                val predictPadMethods = method { name("predictPad") }.map { it.self }
                val systemTabletMethods = method { name("systemPropertyIsTablet") }.map { it.self }
                val accurateModelMethods = method { name("getDeviceAccurateModel") }.map { it.self }
                val updateCloudDeviceTypeMethods = method {
                    name("updateCloudDeviceType")
                }.map { it.self }
                val hasCloudInfoMethods = method { name("hasCloudDeviceTypeInfo") }.map { it.self }
                val cloudUpdateTimeMethods = method {
                    name("getCloudDeviceTypeUpdateTime")
                }.map { it.self }

                hookAllToReturn(isPadMethods, true)
                hookAllToReturn(isPhoneMethods, false)
                hookAllToReturn(deviceTypeMethods, "pad")
                hookAllToReturn(savedDeviceTypeMethods, "pad")
                hookAllToReturn(predictPadMethods, true)
                hookAllToReturn(systemTabletMethods, true)
                hookAllToReturn(accurateModelMethods, XHS_TARGET_MODEL)
                hookAllToReturn(hasCloudInfoMethods, true)
                hookAllToReturn(cloudUpdateTimeMethods, 0L)
                updateCloudDeviceTypeMethods.forEach { method ->
                    hook(method).intercept { chain ->
                        val requestedType = chain.args.firstOrNull()
                        chain.args[0] = "pad"
                        log(
                            Log.INFO,
                            TAG,
                            "XHS cloud device type: requested=$requestedType, forced=pad"
                        )
                        chain.proceed()
                    }
                }

                log(
                    Log.INFO,
                    TAG,
                    "Installed XHS full pad hooks: isPad=${isPadMethods.size}, " +
                        "isPhone=${isPhoneMethods.size}, getDeviceType=${deviceTypeMethods.size}, " +
                        "getSavedDeviceType=${savedDeviceTypeMethods.size}, " +
                        "predictPad=${predictPadMethods.size}, " +
                        "systemPropertyIsTablet=${systemTabletMethods.size}, " +
                        "getDeviceAccurateModel=${accurateModelMethods.size}, " +
                        "updateCloudDeviceType=${updateCloudDeviceTypeMethods.size}, " +
                        "hasCloudDeviceTypeInfo=${hasCloudInfoMethods.size}, " +
                        "getCloudDeviceTypeUpdateTime=${cloudUpdateTimeMethods.size}, " +
                        "model=$XHS_TARGET_MODEL"
                )
            }
        }.onFailure {
            log(Log.ERROR, TAG, "Failed to install XHS hooks: ${it.stackTraceToString()}")
        }
    }

    private fun hookDexMethodToReturn(
        cacheKey: String,
        context: Context,
        value: Any?,
        finder: DexKitBridge.() -> DexMethod
    ) {
        val classLoader = context.classLoader
        hookToReturn(requireMethodCache(context).findOrLoad(cacheKey, classLoader, finder), value)
    }

    private fun requireMethodCache(context: Context): DexMethodCache {
        methodCache?.let { return it }
        return DexMethodCache(
            module = this,
            prefs = context.getSharedPreferences(DEXKIT_PREFS_NAME, Context.MODE_PRIVATE)
        ).also { methodCache = it }
    }

    private fun resetQQModelCacheIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(QQ_BUGLY_PREFS_NAME, Context.MODE_PRIVATE)
        val storedMode = prefs.getString("model", QQ_TARGET_MODEL)
        if (storedMode == QQ_TARGET_MODEL) return

        log(Log.INFO, TAG, "QQ stored model not match, clear cache")
        val appDataDir = context.applicationInfo.dataDir
        File(appDataDir, QQ_PANDORA_CACHE_PATH).deleteRecursively()
        File(appDataDir, QQ_PANDORA_CRC_PATH).deleteRecursively()
        Process.killProcess(Process.myPid())
    }

    private fun isCustomWeWork(prp: XposedModuleInterface.PackageReadyParam): Boolean = prp.run {
        packageName in customWeWorkPackages || classLoader.hasClass("com.tencent.wework.common.utils.WwUtil")
    }

    private fun isDingTalk(prp: XposedModuleInterface.PackageReadyParam): Boolean = prp.run {
        packageName == "com.alibaba.android.rimet" || classLoader.hasClass("com.alibaba.android.rimet.LauncherApplication")
    }
}
