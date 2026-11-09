#!/usr/bin/env python3
"""Rebuild the dependency-free sample Xcode project; no XcodeGen installation needed."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parent
objects = {}

def oid(name):
    return hashlib.sha256(name.encode()).hexdigest()[:24].upper()

def add(label, isa, **fields):
    key = oid(label)
    objects[key] = dict(isa=isa, **fields)
    return key

def file(name, path, kind, tree="<group>"):
    return add(name, "PBXFileReference", lastKnownFileType=kind, path=path, sourceTree=tree)

app_source = file("app-source", "App/ShareInboxDemoApp.swift", "sourcecode.swift")
ext_source = file("extension-source", "ShareExtension/ShareViewController.swift", "sourcecode.swift")
loader_source = file("loader-source", "../ios-support/Sources/ShareInboxExtension/ShareLoader.swift", "sourcecode.swift")
framework = file("framework", "../shareinbox/build/XCFrameworks/debug/ShareInboxKit.xcframework", "wrapper.xcframework")
app_product = file("app-product", "ShareInboxDemo.app", "wrapper.application", "BUILT_PRODUCTS_DIR")
ext_product = file("extension-product", "ShareInboxExtension.appex", "wrapper.app-extension", "BUILT_PRODUCTS_DIR")
products = add("products", "PBXGroup", children=[app_product, ext_product], name="Products", sourceTree="<group>")
main = add("main", "PBXGroup", children=[app_source, ext_source, loader_source, framework, products], sourceTree="<group>")

def phase(name, kind, refs, **kwargs):
    files = [add(f"{name}-{ref}", "PBXBuildFile", fileRef=ref) for ref in refs]
    return add(name, kind, buildActionMask=2147483647, files=files, runOnlyForDeploymentPostprocessing=0, **kwargs)

def configs(name, settings):
    values = []
    for config in ["Debug", "Release"]:
        merged = dict(settings)
        merged["SWIFT_OPTIMIZATION_LEVEL"] = "-Onone" if config == "Debug" else "-O"
        values.append(add(f"{name}-{config}", "XCBuildConfiguration", buildSettings=merged, name=config))
    return add(f"{name}-config-list", "XCConfigurationList", buildConfigurations=values,
               defaultConfigurationIsVisible=0, defaultConfigurationName="Debug")

common = {
    "ARCHS": "arm64",
    "SDKROOT": "iphoneos", "IPHONEOS_DEPLOYMENT_TARGET": "15.0", "SWIFT_VERSION": "5.0",
    "TARGETED_DEVICE_FAMILY": "1,2", "CLANG_ENABLE_MODULES": "YES", "CODE_SIGN_STYLE": "Automatic",
    "PRODUCT_NAME": "$(TARGET_NAME)", "CURRENT_PROJECT_VERSION": "1", "MARKETING_VERSION": "0.1.0",
    "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator", "ENABLE_USER_SCRIPT_SANDBOXING": "YES",
    "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks"],
}
app_settings = dict(common, PRODUCT_BUNDLE_IDENTIFIER="dev.shareinbox.sample", GENERATE_INFOPLIST_FILE="YES",
                    INFOPLIST_KEY_CFBundleDisplayName="ShareInbox Demo", INFOPLIST_KEY_UILaunchScreen_Generation="YES",
                    INFOPLIST_KEY_UIApplicationSceneManifest_Generation="YES", CODE_SIGN_ENTITLEMENTS="App/App.entitlements")
ext_settings = dict(common, PRODUCT_BUNDLE_IDENTIFIER="dev.shareinbox.sample.ShareExtension", GENERATE_INFOPLIST_FILE="NO",
                    INFOPLIST_FILE="ShareExtension/Info.plist", CODE_SIGN_ENTITLEMENTS="ShareExtension/ShareExtension.entitlements",
                    APPLICATION_EXTENSION_API_ONLY="YES", SKIP_INSTALL="YES")
ext = add("extension", "PBXNativeTarget", name="ShareInboxExtension", productName="ShareInboxExtension",
          productType="com.apple.product-type.app-extension", productReference=ext_product,
          buildConfigurationList=configs("extension", ext_settings), buildRules=[], dependencies=[],
          buildPhases=[phase("extension-sources", "PBXSourcesBuildPhase", [ext_source, loader_source]),
                       phase("extension-frameworks", "PBXFrameworksBuildPhase", [framework])])
proxy = add("extension-proxy", "PBXContainerItemProxy", containerPortal=oid("project"), proxyType=1,
            remoteGlobalIDString=ext, remoteInfo="ShareInboxExtension")
dep = add("extension-dependency", "PBXTargetDependency", target=ext, targetProxy=proxy)
embed_file = add("embed-extension-file", "PBXBuildFile", fileRef=ext_product, settings={"ATTRIBUTES": ["RemoveHeadersOnCopy"]})
embed = add("embed-extension", "PBXCopyFilesBuildPhase", buildActionMask=2147483647, dstPath="", dstSubfolderSpec=13,
            files=[embed_file], name="Embed App Extensions", runOnlyForDeploymentPostprocessing=0)
app = add("app", "PBXNativeTarget", name="ShareInboxDemo", productName="ShareInboxDemo",
          productType="com.apple.product-type.application", productReference=app_product,
          buildConfigurationList=configs("app", app_settings), buildRules=[], dependencies=[dep],
          buildPhases=[phase("app-sources", "PBXSourcesBuildPhase", [app_source]),
                       phase("app-frameworks", "PBXFrameworksBuildPhase", [framework]), embed])
project = add("project", "PBXProject", attributes={"LastUpgradeCheck": "1600", "BuildIndependentTargetsInParallel": "YES"},
              buildConfigurationList=configs("project", {}), compatibilityVersion="Xcode 14.0", developmentRegion="en",
              hasScannedForEncodings=0, knownRegions=["en", "Base"], mainGroup=main, productRefGroup=products,
              projectDirPath="", projectRoot="", targets=[app, ext])

def encode(value, depth=0):
    if isinstance(value, dict):
        return "{\n" + "".join("\t" * (depth + 1) + json.dumps(str(k)) + " = " + encode(v, depth + 1) + ";\n" for k,v in value.items()) + "\t" * depth + "}"
    if isinstance(value, list):
        return "(" + ", ".join(encode(v, depth) for v in value) + ")"
    return json.dumps(str(value))

bundle = root / "ShareInboxDemo.xcodeproj"
bundle.mkdir(exist_ok=True)
(bundle / "project.pbxproj").write_text("// !$*UTF8*$!\n" + encode(dict(archiveVersion=1, classes={}, objectVersion=56, objects=objects, rootObject=project)) + "\n")
schemes = bundle / "xcshareddata" / "xcschemes"
schemes.mkdir(parents=True, exist_ok=True)
ref = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{app}" BuildableName="ShareInboxDemo.app" BlueprintName="ShareInboxDemo" ReferencedContainer="container:ShareInboxDemo.xcodeproj"/>'
(schemes / "ShareInboxDemo.xcscheme").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{ref}</BuildActionEntry></BuildActionEntries></BuildAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></LaunchAction>
  <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></ProfileAction>
  <AnalyzeAction buildConfiguration="Debug"/>
  <ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''')
print(bundle)
