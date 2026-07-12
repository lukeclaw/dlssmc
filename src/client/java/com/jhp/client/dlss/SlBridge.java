package com.jhp.client.dlss;

import com.jhp.DLSSmc;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Phase 2 (M4) — FFM (Panama) bridge to NVIDIA Streamline's {@code sl.interposer.dll}.
 *
 * <p><b>Integration mode:</b> "manual hooking" with SL's <i>optional creation proxies</i>
 * (see {@code streamline-sdk-v2.12.0/docs/ProgrammingGuideManualHooking.md} §4.2).
 * Minecraft owns Vulkan device creation; we only reroute {@code vkCreateInstance} /
 * {@code vkCreateDevice} through the interposer's exports so SL adds the instance/device
 * extensions, features and extra command queues DLSS needs, and registers the handles with
 * SL internally (which makes {@code slSetVulkanInfo} unnecessary — guide §5.0). The
 * remaining hookable APIs in {@code sl_hooks.h} (swapchain/present/surface) are only
 * required by Frame Generation, which is out of scope (PRD: DLSS-SR only).</p>
 *
 * <p><b>ABI facts</b> (verified against SDK v2.12.0 headers, 2026-07-11):</p>
 * <pre>
 *   sl::BaseStructure (MSVC x64):  next* @0, StructType GUID @8 (16 B), size_t version @24 → payload @32
 *   sl::Preferences   GUID {1CA10965-BF8E-432B-8DA1-6716D879FB14}, kStructVersion1, sizeof = 144:
 *     bool showConsole @32; LogLevel(u32) @36; wchar_t** pathsToPlugins @40; u32 numPaths @48;
 *     wchar_t* pathToLogsAndData @56; alloc cb @64; release cb @72; log cb @80;
 *     PreferenceFlags(u64) @88; Feature* featuresToLoad @96; u32 numFeatures @104;
 *     u32 applicationId @108; EngineType(u32) @112; char* engineVersion @120;
 *     char* projectId @128; RenderAPI(u32) @136
 *   sl::AdapterInfo   GUID {0677315F-A746-4492-9F42-CB6142C9C3D4}, kStructVersion1, sizeof = 56:
 *     u8* deviceLUID @32; u32 luidSize @40; void* vkPhysicalDevice @48
 *   kSDKVersion = (2&lt;&lt;48)|(12&lt;&lt;32)|(0&lt;&lt;16)|0xfedc; kFeatureDLSS = 0; RenderAPI::eVulkan = 2;
 *   PreferenceFlags: eDisableCLStateTracking=1, eUseManualHooking=4
 *   slInit(const Preferences&amp;, uint64_t) → sl::Result (u32, eOk = 0)
 * </pre>
 *
 * <p>Requires JVM flag {@code --enable-native-access=ALL-UNNAMED} (wired into the loom
 * client run config).</p>
 */
public final class SlBridge {
    private SlBridge() {}

    public static final int SL_OK = 0;
    public static final int K_FEATURE_DLSS = 0;
    // FG-2: frame-generation feature set (sl_core_types.h).
    public static final int K_FEATURE_REFLEX = 3;
    public static final int K_FEATURE_PCL = 4;
    public static final int K_FEATURE_DLSS_G = 1000;
    // FG-4 PCL markers (sl_pcl.h PCLMarker) + Reflex mode (sl_reflex.h ReflexMode)
    public static final int PCL_SIMULATION_START = 0;
    public static final int PCL_SIMULATION_END = 1;
    public static final int PCL_RENDER_SUBMIT_START = 2;
    public static final int PCL_RENDER_SUBMIT_END = 3;
    public static final int PCL_PRESENT_START = 4;
    public static final int PCL_PRESENT_END = 5;
    private static final int REFLEX_MODE_LOW_LATENCY = 1;
    private static final int DLSSG_MODE_OFF = 0;
    private static final int DLSSG_MODE_ON = 1;

    /** (2<<48) | (12<<32) | (0<<16) | kSDKVersionMagic(0xfedc) — SDK v2.12.0. */
    private static final long SDK_VERSION = 0x0002_000C_0000_FEDCL;

    private static final long FLAG_DISABLE_CL_STATE_TRACKING = 1L;      // 1 << 0
    private static final long FLAG_USE_MANUAL_HOOKING = 4L;             // 1 << 2
    /** Required for the slSetTagForFrame API (sl.cpp rejects it with eErrorInvalidIntegration otherwise). */
    private static final long FLAG_USE_FRAME_BASED_RESOURCE_TAGGING = 128L; // 1 << 7

    private enum State { UNINITIALIZED, ACTIVE, FAILED }

    private static volatile State state = State.UNINITIALIZED;
    private static String statusLine = "not initialized yet";

    private static MethodHandle hSlInit;
    private static MethodHandle hSlShutdown;
    private static MethodHandle hSlIsFeatureSupported;
    private static MethodHandle hVkCreateInstance;
    private static MethodHandle hVkCreateDevice;
    private static MethodHandle hVkEnumeratePhysicalDevices;
    // FG-3 swapchain routing (SL_INTERCEPT swapchain entry points)
    private static MethodHandle hVkCreateSwapchainKHR;
    private static MethodHandle hVkGetSwapchainImagesKHR;
    private static MethodHandle hVkAcquireNextImageKHR;
    private static MethodHandle hVkQueuePresentKHR;
    private static MethodHandle hVkDestroySwapchainKHR;
    // FG-4 Reflex/PCL (resolved lazily via slGetFeatureFunction)
    private static MethodHandle hReflexSetOptions;
    private static MethodHandle hReflexSleep;
    private static MethodHandle hPclSetMarker;
    private static volatile long currentFrameToken;   // shared per-frame FrameToken* (FG-4)
    private static boolean reflexOptionsSet;
    private static boolean markerWarned;
    // FG-5 DLSS-G (frame generation)
    private static MethodHandle hDlssgSetOptions;
    private static MethodHandle hDlssgGetState;
    private static volatile boolean fgEnabled;
    private static int fgNumFramesToGenerate = 1;
    private static MemorySegment fgViewport;
    private static MemorySegment fgOptions;
    private static MemorySegment fgState;
    // P2-3 per-frame API
    private static MethodHandle hSlGetNewFrameToken;
    private static MethodHandle hSlSetConstants;
    private static MethodHandle hSlSetTagForFrame;
    private static MethodHandle hSlEvaluateFeature;
    private static MethodHandle hSlGetFeatureFunction;
    private static MethodHandle hDlssSetOptions; // resolved lazily via slGetFeatureFunction

    /**
     * True once the VkInstance was actually created through SL's proxy. The whole proxied
     * chain (enumerate → createDevice) must be all-or-nothing: SL's interposer keys its
     * internal state (instance dispatch table, instanceDeviceMap) off the instance it
     * created, so routing later calls through SL without a proxied instance would hit
     * null dispatch entries (the exact cause of the 2026-07-11 EXCEPTION_ACCESS_VIOLATION
     * at pc=0x0: instanceDeviceMap[physicalDevice] was empty because Mojang's
     * vkEnumeratePhysicalDevices bypassed SL — wrapper.cpp:940).
     */
    private static volatile boolean instanceProxied;

    /** True once slInit returned eOk and the interposer proxies are bound. */
    public static boolean isActive() {
        return state == State.ACTIVE;
    }

    /** True if the live VkInstance was created through SL's proxy (gates the whole chain). */
    public static boolean isInstanceProxied() {
        return state == State.ACTIVE && instanceProxied;
    }

    /**
     * Called when any link of the proxied chain fell back to vanilla — the rest of the
     * chain must then also go vanilla, otherwise SL's internal maps are inconsistent and
     * its vkCreateDevice post-create path crashes (see 2026-07-11 post-mortem).
     */
    public static void markChainBroken(String why) {
        if (instanceProxied) {
            instanceProxied = false;
            statusLine += "; PROXY CHAIN BROKEN (" + why + ") — Vulkan continues vanilla, DLSS off";
            DLSSmc.LOGGER.warn("[DLSSmc] Streamline proxy chain broken ({}); continuing vanilla, DLSS unavailable", why);
        }
    }

    /** Human-readable status for the /dlssmc sl command. */
    public static String status() {
        return state + " — " + statusLine;
    }

    /**
     * Idempotent; called lazily from the vkCreateInstance redirect so slInit is guaranteed
     * to run before instance creation regardless of mod-loader init ordering.
     */
    public static synchronized boolean ensureInit() {
        if (state != State.UNINITIALIZED) {
            return state == State.ACTIVE;
        }
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                fail("Streamline requires Windows; os.name=" + System.getProperty("os.name"));
                return false;
            }
            Path slDir = locateSlDir();
            if (slDir == null) {
                fail("sl.interposer.dll not found — set -Ddlssmc.sl.dir=<dir> or keep "
                        + "streamline-sdk-v2.12.0/bin/x64 at the project root");
                return false;
            }
            DLSSmc.LOGGER.info("[DLSSmc] loading Streamline interposer from {}", slDir);

            Arena arena = Arena.global();
            Linker linker = Linker.nativeLinker();
            SymbolLookup sl = SymbolLookup.libraryLookup(slDir.resolve("sl.interposer.dll"), arena);

            hSlInit = linker.downcallHandle(find(sl, "slInit"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
            hSlShutdown = linker.downcallHandle(find(sl, "slShutdown"),
                    FunctionDescriptor.of(JAVA_INT));
            hSlIsFeatureSupported = linker.downcallHandle(find(sl, "slIsFeatureSupported"),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
            hVkCreateInstance = linker.downcallHandle(find(sl, "vkCreateInstance"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            hVkCreateDevice = linker.downcallHandle(find(sl, "vkCreateDevice"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            hVkEnumeratePhysicalDevices = linker.downcallHandle(find(sl, "vkEnumeratePhysicalDevices"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            hVkCreateSwapchainKHR = linker.downcallHandle(find(sl, "vkCreateSwapchainKHR"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            hVkGetSwapchainImagesKHR = linker.downcallHandle(find(sl, "vkGetSwapchainImagesKHR"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
            hVkAcquireNextImageKHR = linker.downcallHandle(find(sl, "vkAcquireNextImageKHR"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
            hVkQueuePresentKHR = linker.downcallHandle(find(sl, "vkQueuePresentKHR"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            hVkDestroySwapchainKHR = linker.downcallHandle(find(sl, "vkDestroySwapchainKHR"),
                    FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
            // Per-frame API (P2-3). C++ references are pointers at the ABI level.
            hSlGetNewFrameToken = linker.downcallHandle(find(sl, "slGetNewFrameToken"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));           // (FrameToken*&, const uint32_t*)
            hSlSetConstants = linker.downcallHandle(find(sl, "slSetConstants"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));  // (Constants&, FrameToken&, ViewportHandle&)
            hSlSetTagForFrame = linker.downcallHandle(find(sl, "slSetTagForFrame"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)); // (token, viewport, ResourceTag*, num, CommandBuffer*)
            hSlEvaluateFeature = linker.downcallHandle(find(sl, "slEvaluateFeature"),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)); // (feature, token, BaseStructure**, num, cmdBuf)
            hSlGetFeatureFunction = linker.downcallHandle(find(sl, "slGetFeatureFunction"),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)); // (feature, const char*, void*&)

            MemorySegment prefs = buildPreferences(arena, slDir);
            int r = (int) hSlInit.invokeExact(prefs, SDK_VERSION);
            if (r != SL_OK) {
                fail("slInit failed: sl::Result=" + r);
                return false;
            }
            state = State.ACTIVE;
            statusLine = "slInit OK (SDK 2.12.0, manual hooking, feature=DLSS) from " + slDir;
            DLSSmc.LOGGER.info("[DLSSmc] {}", statusLine);
            return true;
        } catch (Throwable t) {
            fail("exception during Streamline init: " + t);
            DLSSmc.LOGGER.error("[DLSSmc] Streamline init exception", t);
            return false;
        }
    }

    /**
     * SL's vkCreateInstance proxy. Only call when {@link #isActive()}; raw addresses come
     * from LWJGL ({@code createInfo.address()}, {@code memAddress(pInstance)}).
     */
    public static int vkCreateInstance(long createInfoAddr, long pInstanceAddr) throws Throwable {
        int r = (int) hVkCreateInstance.invokeExact(
                MemorySegment.ofAddress(createInfoAddr), MemorySegment.NULL,
                MemorySegment.ofAddress(pInstanceAddr));
        if (r == 0) { // VK_SUCCESS — SL now owns this instance's dispatch state
            instanceProxied = true;
        }
        return r;
    }

    /**
     * SL's vkEnumeratePhysicalDevices hook. MUST be used when the instance is proxied:
     * it populates SL's instanceDeviceMap (interposer wrapper.cpp:982), which
     * vkCreateDevice reads after creation (wrapper.cpp:940) to rebuild its instance
     * dispatch. Bypassing it leaves a null instance → null dispatch → native crash at
     * pc=0 during SL plugin init.
     */
    public static int vkEnumeratePhysicalDevices(long instanceAddr, long pCountAddr, long pDevicesAddr)
            throws Throwable {
        // invokeExact matches on the *static* type of each argument — a ternary here gets
        // typed as Object and throws WrongMethodTypeException, so use a typed local.
        MemorySegment devices = pDevicesAddr == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(pDevicesAddr);
        return (int) hVkEnumeratePhysicalDevices.invokeExact(
                MemorySegment.ofAddress(instanceAddr), MemorySegment.ofAddress(pCountAddr), devices);
    }

    /** SL's vkCreateDevice proxy (adds DLSS extensions/features/queues, registers device). */
    public static int vkCreateDevice(long physicalDeviceAddr, long createInfoAddr, long pDeviceAddr)
            throws Throwable {
        return (int) hVkCreateDevice.invokeExact(
                MemorySegment.ofAddress(physicalDeviceAddr), MemorySegment.ofAddress(createInfoAddr),
                MemorySegment.NULL, MemorySegment.ofAddress(pDeviceAddr));
    }

    // ---- FG-3 swapchain proxies (route Mojang's KHRSwapchain calls through SL) -----------
    public static int vkCreateSwapchainKHR(long deviceAddr, long pCreateInfoAddr,
            long pAllocatorAddr, long pSwapchainAddr) throws Throwable {
        MemorySegment alloc = pAllocatorAddr == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(pAllocatorAddr);
        return (int) hVkCreateSwapchainKHR.invokeExact(
                MemorySegment.ofAddress(deviceAddr), MemorySegment.ofAddress(pCreateInfoAddr),
                alloc, MemorySegment.ofAddress(pSwapchainAddr));
    }

    public static int vkGetSwapchainImagesKHR(long deviceAddr, long swapchain,
            long pCountAddr, long pImagesAddr) throws Throwable {
        MemorySegment images = pImagesAddr == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(pImagesAddr);
        return (int) hVkGetSwapchainImagesKHR.invokeExact(
                MemorySegment.ofAddress(deviceAddr), swapchain, MemorySegment.ofAddress(pCountAddr), images);
    }

    public static int vkAcquireNextImageKHR(long deviceAddr, long swapchain, long timeout,
            long semaphore, long fence, long pImageIndexAddr) throws Throwable {
        return (int) hVkAcquireNextImageKHR.invokeExact(
                MemorySegment.ofAddress(deviceAddr), swapchain, timeout, semaphore, fence,
                MemorySegment.ofAddress(pImageIndexAddr));
    }

    public static int vkQueuePresentKHR(long queueAddr, long pPresentInfoAddr) throws Throwable {
        return (int) hVkQueuePresentKHR.invokeExact(
                MemorySegment.ofAddress(queueAddr), MemorySegment.ofAddress(pPresentInfoAddr));
    }

    public static void vkDestroySwapchainKHR(long deviceAddr, long swapchain, long pAllocatorAddr)
            throws Throwable {
        MemorySegment alloc = pAllocatorAddr == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(pAllocatorAddr);
        hVkDestroySwapchainKHR.invokeExact(MemorySegment.ofAddress(deviceAddr), swapchain, alloc);
    }

    /** Logs whether DLSS-SR is supported on the given VkPhysicalDevice (V-5 evidence). */
    public static void logDlssSupport(long vkPhysicalDeviceAddr) {
        if (state != State.ACTIVE) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(56, 8);
            writeHeader(info,
                    0x0677315f, (short) 0xa746, (short) 0x4492,
                    new byte[] { (byte) 0x9f, 0x42, (byte) 0xcb, 0x61, 0x42, (byte) 0xc9, (byte) 0xc3, (byte) 0xd4 },
                    1);
            info.set(ADDRESS, 32, MemorySegment.NULL);                              // deviceLUID
            info.set(JAVA_INT, 40, 0);                                              // luidSize
            info.set(ADDRESS, 48, MemorySegment.ofAddress(vkPhysicalDeviceAddr));   // vkPhysicalDevice
            // FG-2: check every loaded feature so Gate C confirms DLSS-G availability.
            int[] checkIds = { K_FEATURE_DLSS, K_FEATURE_DLSS_G, K_FEATURE_REFLEX, K_FEATURE_PCL };
            String[] checkNames = { "kFeatureDLSS", "kFeatureDLSS_G", "kFeatureReflex", "kFeaturePCL" };
            for (int i = 0; i < checkIds.length; i++) {
                int featureId = checkIds[i];
                int r = (int) hSlIsFeatureSupported.invokeExact(featureId, info);
                if (r == SL_OK) {
                    DLSSmc.LOGGER.info("[DLSSmc] slIsFeatureSupported({}): SUPPORTED", checkNames[i]);
                    if (featureId == K_FEATURE_DLSS) {
                        statusLine += "; DLSS SUPPORTED on this adapter";
                    }
                } else {
                    DLSSmc.LOGGER.warn("[DLSSmc] slIsFeatureSupported({}) -> sl::Result={} "
                            + "(sl_result.h; 2=driver out of date, 4/5=no supported adapter)", checkNames[i], r);
                    if (featureId == K_FEATURE_DLSS) {
                        statusLine += "; DLSS NOT supported: sl::Result=" + r;
                    }
                }
            }
        } catch (Throwable t) {
            DLSSmc.LOGGER.error("[DLSSmc] slIsFeatureSupported call failed", t);
        }
    }

    // ---- P2-3 per-frame wrappers -------------------------------------------------------

    /** Returns FrameToken* (native address) or 0 on failure. SL owns the token memory. */
    public static long getNewFrameToken() throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pToken = a.allocate(ADDRESS);
            int r = (int) hSlGetNewFrameToken.invokeExact(pToken, MemorySegment.NULL);
            return r == SL_OK ? pToken.get(JAVA_LONG, 0) : 0L;
        }
    }

    public static int setConstants(MemorySegment constants, long frameToken, MemorySegment viewport)
            throws Throwable {
        MemorySegment token = MemorySegment.ofAddress(frameToken);
        return (int) hSlSetConstants.invokeExact(constants, token, viewport);
    }

    /** tags = contiguous array of ResourceTag structs (64 B stride). */
    public static int setTagForFrame(long frameToken, MemorySegment viewport, MemorySegment tags,
            int numTags, long vkCommandBuffer) throws Throwable {
        MemorySegment token = MemorySegment.ofAddress(frameToken);
        MemorySegment cb = MemorySegment.ofAddress(vkCommandBuffer);
        return (int) hSlSetTagForFrame.invokeExact(token, viewport, tags, numTags, cb);
    }

    /** inputs = BaseStructure** (array of struct pointers; at minimum the ViewportHandle). */
    public static int evaluateDlss(long frameToken, MemorySegment inputs, int numInputs,
            long vkCommandBuffer) throws Throwable {
        MemorySegment token = MemorySegment.ofAddress(frameToken);
        MemorySegment cb = MemorySegment.ofAddress(vkCommandBuffer);
        return (int) hSlEvaluateFeature.invokeExact(K_FEATURE_DLSS, token, inputs, numInputs, cb);
    }

    /**
     * slDLSSSetOptions via slGetFeatureFunction — only callable after the device exists
     * (sl.h: feature functions require slSetD3DDevice/slSetVulkanInfo/creation proxies).
     */
    public static int dlssSetOptions(MemorySegment viewport, MemorySegment options) throws Throwable {
        if (hDlssSetOptions == null) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment name = a.allocateFrom("slDLSSSetOptions");
                MemorySegment pFn = a.allocate(ADDRESS);
                int r = (int) hSlGetFeatureFunction.invokeExact(K_FEATURE_DLSS, name, pFn);
                if (r != SL_OK) {
                    DLSSmc.LOGGER.error("[DLSSmc] slGetFeatureFunction(slDLSSSetOptions) -> sl::Result={}", r);
                    return r;
                }
                hDlssSetOptions = Linker.nativeLinker().downcallHandle(
                        MemorySegment.ofAddress(pFn.get(JAVA_LONG, 0)),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)); // (ViewportHandle&, DLSSOptions&)
            }
        }
        return (int) hDlssSetOptions.invokeExact(viewport, options);
    }

    // ---- FG-4 frame token unification + Reflex/PCL markers -----------------------------

    /** The one FrameToken* shared by markers, constants and evaluate this frame (0 if none). */
    public static long currentFrameToken() {
        return currentFrameToken;
    }

    /**
     * Frame start (MinecraftMixin.runTick HEAD): mint ONE shared token, set Reflex
     * low-latency once, sleep, and drop eSimulationStart. FG hard-requires the present
     * marker's frame index to equal the Constants token index; sharing one token per frame
     * guarantees it (guide: "common constants cannot be found for frame N").
     */
    public static void frameBegin() {
        // FG-4 machinery (shared token, Reflex sleep, PCL markers) is ONLY needed for frame
        // generation. Running it every frame perturbs DLSS-SR temporal accumulation (Reflex
        // re-pacing the CPU frame -> motion smearing), so gate it on FG being enabled. With
        // FG off, DlssEvaluator falls back to getNewFrameToken() = exact M5 behaviour.
        if (!isInstanceProxied() || !fgEnabled) {
            currentFrameToken = 0L;
            return;
        }
        try {
            long token = getNewFrameToken();
            currentFrameToken = token;
            if (token == 0L) {
                return;
            }
            if (!reflexOptionsSet) {
                reflexOptionsSet = true;
                reflexSetLowLatency();
            }
            reflexSleep(token);
            pclSetMarker(PCL_SIMULATION_START, token);
        } catch (Throwable t) {
            DLSSmc.LOGGER.error("[DLSSmc] FG-4 frameBegin failed", t);
        }
    }

    /** No-throw PCL marker on the current shared token; no-op if SL inactive or no token. */
    public static void mark(int marker) {
        long token = currentFrameToken;
        if (token == 0L || !isInstanceProxied()) {
            return;
        }
        try {
            pclSetMarker(marker, token);
        } catch (Throwable t) {
            if (!markerWarned) {
                markerWarned = true;
                DLSSmc.LOGGER.error("[DLSSmc] slPCLSetMarker(marker={}) failed (logged once)", marker, t);
            }
        }
    }

    private static int reflexSleep(long token) throws Throwable {
        if (hReflexSleep == null) {
            hReflexSleep = resolveFeatureFn(K_FEATURE_REFLEX, "slReflexSleep",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            if (hReflexSleep == null) {
                return -1;
            }
        }
        return (int) hReflexSleep.invokeExact(MemorySegment.ofAddress(token));
    }

    private static int pclSetMarker(int marker, long token) throws Throwable {
        if (hPclSetMarker == null) {
            hPclSetMarker = resolveFeatureFn(K_FEATURE_PCL, "slPCLSetMarker",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
            if (hPclSetMarker == null) {
                return -1;
            }
        }
        return (int) hPclSetMarker.invokeExact(marker, MemorySegment.ofAddress(token));
    }

    private static void reflexSetLowLatency() throws Throwable {
        if (hReflexSetOptions == null) {
            hReflexSetOptions = resolveFeatureFn(K_FEATURE_REFLEX, "slReflexSetOptions",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            if (hReflexSetOptions == null) {
                return;
            }
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment opts = a.allocate(48, 8);
            writeHeader(opts, 0xf03af81a, (short) 0x6d0b, (short) 0x4902,
                    new byte[] { (byte) 0xa6, 0x51, (byte) 0xc4, (byte) 0x96, 0x5e, 0x21, 0x54, 0x34 }, 1);
            opts.set(JAVA_INT, 32, REFLEX_MODE_LOW_LATENCY);   // mode = eLowLatency
            int r = (int) hReflexSetOptions.invokeExact(opts);
            DLSSmc.LOGGER.info("[DLSSmc] slReflexSetOptions(eLowLatency) -> sl::Result={}", r);
        }
    }

    /** Resolve an SL feature function pointer (slGetFeatureFunction) into a downcall handle. */
    private static MethodHandle resolveFeatureFn(int feature, String name, FunctionDescriptor fd)
            throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment nm = a.allocateFrom(name);
            MemorySegment pFn = a.allocate(ADDRESS);
            int r = (int) hSlGetFeatureFunction.invokeExact(feature, nm, pFn);
            if (r != SL_OK) {
                DLSSmc.LOGGER.error("[DLSSmc] slGetFeatureFunction({}) -> sl::Result={}", name, r);
                return null;
            }
            return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(pFn.get(JAVA_LONG, 0)), fd);
        }
    }

    // ---- FG-5 DLSS-G frame generation --------------------------------------------------

    public static boolean isFrameGenEnabled() {
        return fgEnabled;
    }

    private static void ensureFgStructs() {
        if (fgViewport != null) {
            return;
        }
        Arena g = Arena.global();
        fgViewport = g.allocate(40, 8);
        writeHeader(fgViewport, 0x171b6435, (short) 0x9b3c, (short) 0x4fc8,
                new byte[] { (byte) 0x99, (byte) 0x94, (byte) 0xfb, (byte) 0xe5, 0x25, 0x69, (byte) 0xaa, (byte) 0xa4 }, 1);
        fgViewport.set(JAVA_INT, 32, 0);                                    // viewport id 0
        fgOptions = g.allocate(120, 8);                                     // DLSSGOptions v5
        writeHeader(fgOptions, 0xfac5f1cb, (short) 0x2dfd, (short) 0x4f36,
                new byte[] { (byte) 0xa1, (byte) 0xe6, 0x3a, (byte) 0x9e, (byte) 0x86, 0x52, 0x56, (byte) 0xc5 }, 5);
        fgState = g.allocate(88, 8);                                        // DLSSGState v4
        writeHeader(fgState, 0xcc8ac8e1, (short) 0xa179, (short) 0x44f5,
                new byte[] { (byte) 0x97, (byte) 0xfa, (byte) 0xe7, 0x41, 0x12, (byte) 0xf9, (byte) 0xbc, 0x61 }, 4);
    }

    /** Enable/disable DLSS-G. Returns sl::Result of slDLSSGSetOptions, or -1 if unavailable. */
    public static int setFrameGeneration(boolean on) {
        if (!isInstanceProxied()) {
            return -1;
        }
        try {
            ensureFgStructs();
            if (hDlssgSetOptions == null) {
                hDlssgSetOptions = resolveFeatureFn(K_FEATURE_DLSS_G, "slDLSSGSetOptions",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
                if (hDlssgSetOptions == null) {
                    return -1;
                }
            }
            fgOptions.set(JAVA_INT, 32, on ? DLSSG_MODE_ON : DLSSG_MODE_OFF);   // mode
            fgOptions.set(JAVA_INT, 36, fgNumFramesToGenerate);                 // numFramesToGenerate
            int r = (int) hDlssgSetOptions.invokeExact(fgViewport, fgOptions);
            if (r == SL_OK) {
                fgEnabled = on;
            }
            DLSSmc.LOGGER.info("[DLSSmc] slDLSSGSetOptions(mode={}, n={}) -> sl::Result={}",
                    on ? "eOn" : "eOff", fgNumFramesToGenerate, r);
            return r;
        } catch (Throwable t) {
            DLSSmc.LOGGER.error("[DLSSmc] setFrameGeneration failed", t);
            return -1;
        }
    }

    /** Human-readable DLSS-G state via slDLSSGGetState (/dlssmc fg readout). */
    public static String frameGenState() {
        if (!isInstanceProxied()) {
            return "SL inactive";
        }
        try {
            ensureFgStructs();
            if (hDlssgGetState == null) {
                hDlssgGetState = resolveFeatureFn(K_FEATURE_DLSS_G, "slDLSSGGetState",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
                if (hDlssgGetState == null) {
                    return "slDLSSGGetState unavailable";
                }
            }
            int r = (int) hDlssgGetState.invokeExact(fgViewport, fgState, MemorySegment.NULL);
            if (r != SL_OK) {
                return "slDLSSGGetState -> sl::Result=" + r;
            }
            long vram = fgState.get(JAVA_LONG, 32);
            int status = fgState.get(JAVA_INT, 40);
            int minWH = fgState.get(JAVA_INT, 44);
            int presented = fgState.get(JAVA_INT, 48);
            int maxFrames = fgState.get(JAVA_INT, 52);
            return String.format("status=0x%X (%s), maxGen=%d, minWH=%d, presentedSinceLast=%d, ~VRAM=%.1fMB",
                    status, dlssgStatusString(status), maxFrames, minWH, presented, vram / (1024.0 * 1024.0));
        } catch (Throwable t) {
            DLSSmc.LOGGER.error("[DLSSmc] frameGenState failed", t);
            return "exception: " + t;
        }
    }

    private static String dlssgStatusString(int status) {
        if (status == 0) {
            return "eOk";
        }
        StringBuilder sb = new StringBuilder();
        if ((status & (1 << 0)) != 0) { sb.append("ResolutionTooLow "); }
        if ((status & (1 << 1)) != 0) { sb.append("ReflexNotDetected "); }
        if ((status & (1 << 2)) != 0) { sb.append("HDRFormatNotSupported "); }
        if ((status & (1 << 3)) != 0) { sb.append("CommonConstantsInvalid "); }
        if ((status & (1 << 4)) != 0) { sb.append("GetCurrentBackBufferIndexNotCalled "); }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------------------------

    private static void fail(String why) {
        state = State.FAILED;
        statusLine = why + " — falling back to vanilla Vulkan (no DLSS)";
        DLSSmc.LOGGER.warn("[DLSSmc] {}", statusLine);
    }

    private static MemorySegment find(SymbolLookup sl, String name) {
        return sl.find(name).orElseThrow(
                () -> new IllegalStateException("sl.interposer.dll missing export: " + name));
    }

    private static Path locateSlDir() {
        String override = System.getProperty("dlssmc.sl.dir");
        if (override != null) {
            Path p = Path.of(override);
            return Files.isRegularFile(p.resolve("sl.interposer.dll")) ? p : null;
        }
        // Dev layout: game dir is <repo>/run, SDK sits at <repo>/streamline-sdk-v2.12.0.
        Path base = FabricLoader.getInstance().getGameDir().toAbsolutePath();
        for (int i = 0; i < 6 && base != null; i++, base = base.getParent()) {
            Path cand = base.resolve("streamline-sdk-v2.12.0").resolve("bin").resolve("x64");
            if (Files.isRegularFile(cand.resolve("sl.interposer.dll"))) {
                return cand;
            }
        }
        return null;
    }

    /** BaseStructure header: next=null, StructType GUID, structVersion. */
    private static void writeHeader(MemorySegment s, int d1, short d2, short d3, byte[] d4, long version) {
        s.set(ADDRESS, 0, MemorySegment.NULL);
        s.set(JAVA_INT, 8, d1);
        s.set(JAVA_SHORT, 12, d2);
        s.set(JAVA_SHORT, 14, d3);
        MemorySegment.copy(d4, 0, s, JAVA_BYTE, 16, 8);
        s.set(JAVA_LONG, 24, version);
    }

    private static MemorySegment buildPreferences(Arena arena, Path slDir) throws Exception {
        // Log callback upcall — keep alive forever (global arena).
        MethodHandle logMh = MethodHandles.lookup().findStatic(SlBridge.class, "onSlLog",
                MethodType.methodType(void.class, int.class, MemorySegment.class));
        MemorySegment logStub = Linker.nativeLinker().upcallStub(logMh,
                FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS), arena);

        // Plugin search path (wchar_t**) — same dir as the interposer.
        MemorySegment pathW = wideString(arena, slDir.toAbsolutePath().toString());
        MemorySegment paths = arena.allocate(ADDRESS);
        paths.set(ADDRESS, 0, pathW);

        // FG-2: load DLSS-SR + Reflex + PCL + DLSS-G. SL's vkCreateDevice proxy adds
        // each feature's extra device extensions/features/queues automatically.
        int[] featureIds = { K_FEATURE_DLSS, K_FEATURE_REFLEX, K_FEATURE_PCL, K_FEATURE_DLSS_G };
        MemorySegment features = arena.allocate((long) JAVA_INT.byteSize() * featureIds.length,
                JAVA_INT.byteAlignment());
        for (int i = 0; i < featureIds.length; i++) {
            features.setAtIndex(JAVA_INT, i, featureIds[i]);
        }

        MemorySegment prefs = arena.allocate(144, 8);
        writeHeader(prefs,
                0x1ca10965, (short) 0xbf8e, (short) 0x432b,
                new byte[] { (byte) 0x8d, (byte) 0xa1, 0x67, 0x16, (byte) 0xd8, 0x79, (byte) 0xfb, 0x14 },
                1);
        prefs.set(JAVA_BYTE, 32, (byte) 0);                       // showConsole = false
        prefs.set(JAVA_INT, 36, 1);                               // logLevel = eDefault
        prefs.set(ADDRESS, 40, paths);                            // pathsToPlugins
        prefs.set(JAVA_INT, 48, 1);                               // numPathsToPlugins
        prefs.set(ADDRESS, 56, MemorySegment.NULL);               // pathToLogsAndData (callback only)
        prefs.set(ADDRESS, 64, MemorySegment.NULL);               // allocateCallback
        prefs.set(ADDRESS, 72, MemorySegment.NULL);               // releaseCallback
        prefs.set(ADDRESS, 80, logStub);                          // logMessageCallback
        prefs.set(JAVA_LONG, 88, FLAG_DISABLE_CL_STATE_TRACKING | FLAG_USE_MANUAL_HOOKING
                | FLAG_USE_FRAME_BASED_RESOURCE_TAGGING);
        prefs.set(ADDRESS, 96, features);                         // featuresToLoad = {DLSS,Reflex,PCL,DLSS_G}
        prefs.set(JAVA_INT, 104, featureIds.length);              // numFeaturesToLoad = 4 (FG-2)
        prefs.set(JAVA_INT, 108, 0);                              // applicationId (using projectId instead)
        prefs.set(JAVA_INT, 112, 0);                              // engine = eCustom
        prefs.set(ADDRESS, 120, arena.allocateFrom("26.3-snapshot-3"));   // engineVersion
        prefs.set(ADDRESS, 128, arena.allocateFrom("8b5c176a-4b28-4a66-9b02-d7c4a1e5f00d")); // projectId (dev GUID)
        prefs.set(JAVA_INT, 136, 2);                              // renderAPI = eVulkan
        return prefs;
    }

    /** Upcall target for SL's PFun_LogMessageCallback — never let an exception escape. */
    @SuppressWarnings("unused")
    private static void onSlLog(int type, MemorySegment msg) {
        try {
            String text = msg.reinterpret(Long.MAX_VALUE).getString(0).stripTrailing();
            switch (type) {
                case 1 -> DLSSmc.LOGGER.warn("[Streamline] {}", text);
                case 2 -> DLSSmc.LOGGER.error("[Streamline] {}", text);
                default -> DLSSmc.LOGGER.info("[Streamline] {}", text);
            }
        } catch (Throwable ignored) {
            // Upcall exceptions would crash the VM — swallow.
        }
    }

    private static MemorySegment wideString(Arena arena, String s) {
        MemorySegment seg = arena.allocate((s.length() + 1) * 2L, 2);
        for (int i = 0; i < s.length(); i++) {
            seg.set(JAVA_CHAR, i * 2L, s.charAt(i));
        }
        seg.set(JAVA_CHAR, s.length() * 2L, '\0');
        return seg;
    }
}
