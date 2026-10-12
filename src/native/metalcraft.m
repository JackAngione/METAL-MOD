#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <jni.h>
#import <objc/runtime.h>
#import <os/lock.h>
#import <stdatomic.h>
#import <float.h>
#import <math.h>
#import <mach/mach.h>

#define MC_EXPORT __attribute__((visibility("default")))

// Keep the bridge in one translation unit: shared static state, JNI exports and
// optimizer visibility are unchanged. Include order follows native dependencies.
#include "internal/pipeline_cache.inc"
#include "internal/command_stream.inc"
#include "internal/metal_objects.inc"
#include "internal/registry.inc"
#include "jni/device_surface.inc"
#include "jni/transfers.inc"
#include "jni/synchronization.inc"
#include "jni/textures.inc"
#include "jni/pipelines.inc"
#include "jni/render_pass.inc"
#include "jni/command_stream.inc"
#include "jni/compute_pass.inc"
#include "jni/release.inc"
