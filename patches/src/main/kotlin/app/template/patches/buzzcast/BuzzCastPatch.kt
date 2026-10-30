package app.template.patches.buzzcast

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.template.patches.shared.Constants.BUZZCAST_COMPATIBILITY
import org.w3c.dom.Element

// ============================================================
// BuzzCast patch — HARDENED version
// ------------------------------------------------------------
// Differences from upstream:
//   1. Drops the getVipSign() hardcode ("vvip1_1") — the original
//      caused BuzzCast to silently exit to the home screen after
//      login, because the server-issued VIP sign no longer matched
//      the local account state and the app treated it as a
//      tamper / integrity failure.
//   2. Every replaceBody call is now safe (uses firstOrNull + safe
//      call), so the patch no longer throws NoSuchElementException
//      if BuzzCast renames/removes a method in a future update.
//   3. BuyVipViewModel / InAppBillingViewModel / UnityAds blocks
//      are wrapped in runCatching so a missing class can't break
//      the whole patch.
//   4. Boolean VIP flags and expiry-time spoofing are kept — these
//      are read locally by the UI and are NOT validated against the
//      server, so they unlock the SVIP UI without triggering the
//      post-login exit.
// ============================================================

private val buzzCastStripAdManifestPatch = resourcePatch(
    name = "Strip ad manifest entries",
    description = "Removes ad permissions and manifest entries.",
    default = true,
) {
    compatibleWith(BUZZCAST_COMPATIBILITY)
    execute {
        document("AndroidManifest.xml").use { doc ->
            val adPermissions = setOf(
                "android.permission.ACCESS_ADSERVICES_AD_ID",
                "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
            )
            for (tag in listOf("uses-permission", "uses-library", "activity", "property")) {
                val nodes = doc.getElementsByTagName(tag)
                for (i in nodes.length - 1 downTo 0) {
                    val node = nodes.item(i) as? Element ?: continue
                    val name = node.getAttribute("android:name")
                    val remove = when (tag) {
                        "uses-permission" -> name in adPermissions
                        "uses-library" -> name == "android.ext.adservices"
                        "activity" -> name.startsWith("com.unity3d.services.ads.") ||
                            name == "com.unity3d.ads.adplayer.FullScreenWebViewDisplay"
                        "property" -> name == "android.adservices.AD_SERVICES_CONFIG"
                        else -> false
                    }
                    if (remove) node.parentNode?.removeChild(node)
                }
            }
        }
    }
}

@Suppress("unused")
val buzzCastUnlockVipPatch = bytecodePatch(
    name = "Unlock SVIP",
    description = "Unlocks SViP features. Hardened: drops server-validated sign to avoid post-login exit.",
    default = true,
) {
    compatibleWith(BUZZCAST_COMPATIBILITY)
    dependsOn(buzzCastStripAdManifestPatch)

    execute {
        // ----------------------------------------------------------
        // Safe body replacement. Does NOT throw if the class or
        // method is missing in this APK build — it silently skips.
        // ----------------------------------------------------------
        fun replaceBodyIfPresent(
            className: String,
            methodName: String,
            returnType: String,
            smali: String,
        ) {
            val classDef = runCatching { mutableClassDefBy(className) }.getOrNull() ?: return
            classDef.methods
                .firstOrNull { it.name == methodName && it.returnType == returnType }
                ?.apply {
                    removeInstructions(0, implementation!!.instructions.count())
                    addInstructions(0, smali)
                }
        }

        // ==========================================================
        // SVIP unlock — local boolean / integer / expiry flags.
        // These are read by the UI to decide which features to show.
        // They are NOT validated against the server, so spoofing
        // them does NOT trigger the integrity check that caused
        // the post-login exit.
        // ==========================================================
        replaceBodyIfPresent(
            "Lcom/guochao/faceshow/aaspring/beans/UserVipData;",
            "getIsVip", "I",
            "const/4 v0, 0x2\nreturn v0",
        )
        replaceBodyIfPresent(
            "Lcom/guochao/faceshow/aaspring/beans/UserVipData;",
            "getVipLevel", "I",
            "const/4 v0, 0x2\nreturn v0",
        )
        replaceBodyIfPresent(
            "Lcom/guochao/faceshow/bean/UserBean;",
            "getVipLevel", "I",
            "const/4 v0, 0x2\nreturn v0",
        )
        replaceBodyIfPresent(
            "Lcom/guochao/faceshow/aaspring/beans/UserVipData;",
            "getVip", "Ljava/lang/Integer;",
            "const/4 v0, 0x2\ninvoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;\nmove-result-object v0\nreturn-object v0",
        )
        replaceBodyIfPresent(
            "Lcom/guochao/faceshow/aaspring/beans/UserVipData;",
            "getVipExpireTime", "J",
            "const-wide v0, 0x7fffffffffffffffL\nreturn-wide v0",
        )
        replaceBodyIfPresent(
            "Lcom/guochao/faceshow/aaspring/beans/UserVipData;",
            "getThirdEndTime", "J",
            "const-wide v0, 0x7fffffffffffffffL\nreturn-wide v0",
        )

        // ⚠️ INTENTIONALLY REMOVED — see header comment.
        // Hardcoding getVipSign() to "vvip1_1" was the cause of the
        // "exit to home after login" symptom: the app POSTs this
        // sign to the server after login; the server replies that
        // the sign doesn't match the account, and the app calls
        // finishAffinity() -> clean exit to launcher.
        // Keeping it commented out for documentation / rollback.
        //
        // replaceBodyIfPresent(
        //     "Lcom/guochao/faceshow/aaspring/beans/UserVipData;",
        //     "getVipSign", "Ljava/lang/String;",
        //     "const-string v0, \"vvip1_1\"\nreturn-object v0",
        // )

        replaceBodyIfPresent(
            "Lcom/guochao/faceshow/aaspring/beans/UserVipData;",
            "isVip", "Z",
            "const/4 v0, 0x1\nreturn v0",
        )
        replaceBodyIfPresent(
            "Lcom/guochao/faceshow/aaspring/beans/UserVipData;",
            "isOfficial", "Z",
            "const/4 v0, 0x1\nreturn v0",
        )
        replaceBodyIfPresent(
            "Lcom/guochao/faceshow/aaspring/utils/VipUserInfoUtil\$1;",
            "getVipLevel", "I",
            "const/4 v0, 0x2\nreturn v0",
        )
        replaceBodyIfPresent(
            "Lcom/guochao/faceshow/aaspring/utils/VipUserInfoUtil\$1;",
            "isVip", "Z",
            "const/4 v0, 0x1\nreturn v0",
        )
        replaceBodyIfPresent(
            "Lcom/guochao/lib_service_center/live/game/UserVipData1;",
            "getOfficialAccount", "Ljava/lang/Integer;",
            "const/4 v0, 0x1\ninvoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;\nmove-result-object v0\nreturn-object v0",
        )
        replaceBodyIfPresent(
            "Lcom/guochao/faceshow/session/model/UserSessionModel;",
            "getOfficialAccount", "Ljava/lang/Integer;",
            "const/4 v0, 0x1\ninvoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;\nmove-result-object v0\nreturn-object v0",
        )

        // ==========================================================
        // Buy-VIP popup neutralization.
        // The original replaced any void method named "h" or "y" in
        // BuyVipViewModel. Those are obfuscated names that can shift
        // across versions — killing the wrong one (e.g. a lifecycle
        // method) was the second-most-likely cause of clean app exit.
        // Now wrapped in runCatching, and skipped if the class is
        // absent. (Filtering on obfuscated names alone is inherently
        // risky; if this still misbehaves on your version, comment
        // this block out and you keep all the boolean VIP flags.)
        // ==========================================================
        runCatching {
            mutableClassDefBy("Lcom/guochao/faceshow/aaspring/modulars/vip/viewmodel/BuyVipViewModel;")
                .methods
                .filter {
                    it.name in setOf("h", "y") &&
                        it.returnType == "V" &&
                        it.implementation != null
                }
                .forEach { method ->
                    method.removeInstructions(0, method.implementation!!.instructions.count())
                    method.addInstructions(0, "return-void")
                }
        }

        // ==========================================================
        // Google billing stubs. Returning -1 (purchase failed)
        // makes paywalls show "failed" instead of a buy button.
        // Wrapped in runCatching so a missing billing class can't
        // break the whole patch.
        // ==========================================================
        runCatching {
            mutableClassDefBy("Lcom/guochao/faceshow/aaspring/modulars/googlepay/InAppBillingViewModel;")
                .methods
                .filter { method ->
                    method.returnType == "I" &&
                        method.parameters.any { it.type == "Landroidx/fragment/app/FragmentActivity;" } &&
                        method.implementation != null
                }
                .forEach { method ->
                    method.removeInstructions(0, method.implementation!!.instructions.count())
                    method.addInstructions(0, "const/4 v0, -0x1\nreturn v0")
                }
        }
        runCatching {
            mutableClassDefBy("Lcom/guochao/faceshow/aaspring/modulars/googlepay/InAppBillingViewModel;")
                .methods
                .filter { method ->
                    method.returnType == "I" &&
                        method.parameters.any {
                            it.type == "Lcom/android/billingclient/api/ProductDetails;" ||
                                it.type == "Lcom/android/billingclient/api/SkuDetails;"
                        } &&
                        method.implementation != null
                }
                .forEach { method ->
                    method.removeInstructions(0, method.implementation!!.instructions.count())
                    method.addInstructions(0, "const/4 v0, -0x1\nreturn v0")
                }
        }

        // ==========================================================
        // Unity Ads disable. Wrapped in runCatching because some
        // BuzzCast builds strip the Unity SDK entirely.
        // ==========================================================
        runCatching {
            mutableClassDefBy("Lcom/unity3d/ads/UnityAds;")
                .methods
                .filter {
                    it.name in setOf("initialize", "load", "show") &&
                        it.returnType == "V" &&
                        it.implementation != null
                }
                .forEach { method ->
                    method.removeInstructions(0, method.implementation!!.instructions.count())
                    method.addInstructions(0, "return-void")
                }
        }
        replaceBodyIfPresent(
            "Lcom/unity3d/ads/UnityAds;",
            "isSupported", "Z",
            "const/4 v0, 0x0\nreturn v0",
        )
    }
}
