// bigm_identity.m — BigM Java launcher's presentation identity installer.
//
// Compiled from the same shared header as every hardened launcher in this
// ecosystem; the Java host dlopens this dylib via FFM (java.lang.foreign)
// at startup. Present as Voice Memos (name + adaptive icon pair) — the
// launcher's default identity; BigM's icon-picker env contract
// (BIGM_PRESENTATION_ICON_*) still wins.
#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <Security/Security.h>
#import <dlfcn.h>
#import <string.h>
#import <stdlib.h>
#import "macos_presentation_identity.h"

static NSString *BigMJavaIconsDirectory(const char *packageRoot) {
    NSString *root = [NSString stringWithUTF8String:packageRoot];
    return [root stringByAppendingPathComponent:@"Support/icons"];
}

// Point the environment override at any user-picked launcher icon
// (Support/icons/launcher-light.png + launcher-dark.png + launcher.mode)
// BEFORE the install — the shared header reads those variables on every
// apply. Same contract the Swift launcher's PresentationIdentity.m keeps.
static void BigMJavaInstallSelfIconEnvironment(const char *packageRoot) {
    if (!packageRoot) return;
    NSString *iconsDir = BigMJavaIconsDirectory(packageRoot);
    NSString *light = [iconsDir stringByAppendingPathComponent:@"launcher-light.png"];
    NSString *dark = [iconsDir stringByAppendingPathComponent:@"launcher-dark.png"];
    BOOL custom = [[NSFileManager defaultManager] isReadableFileAtPath:light]
        && [[NSFileManager defaultManager] isReadableFileAtPath:dark];
    if (custom) {
        setenv("BIGM_PRESENTATION_ICON_LIGHT", [light UTF8String], 1);
        setenv("BIGM_PRESENTATION_ICON_DARK", [dark UTF8String], 1);
        NSString *modePath = [iconsDir stringByAppendingPathComponent:@"launcher.mode"];
        NSString *mode = [NSString stringWithContentsOfFile:modePath
                                                   encoding:NSUTF8StringEncoding error:nil];
        mode = mode.length > 0
            ? [mode stringByTrimmingCharactersInSet:
                   [NSCharacterSet whitespaceAndNewlineCharacterSet]]
            : @"system";
        setenv("BIGM_PRESENTATION_ICON_MODE", [mode UTF8String], 1);
    } else {
        unsetenv("BIGM_PRESENTATION_ICON_LIGHT");
        unsetenv("BIGM_PRESENTATION_ICON_DARK");
        unsetenv("BIGM_PRESENTATION_ICON_MODE");
    }
}

__attribute__((visibility("default")))
void bigm_java_install_dock_identity(const char *packageRoot) {
    @autoreleasepool {
        BigMJavaInstallSelfIconEnvironment(packageRoot);
        // Menubar shows "Chrome" (owner directive: never BigM in the top
        // left; Dock icon defaults to Voice Memos presets, customizable
        // through the launcher-icon store).
        presentation_install_preset(
                [NSString stringWithUTF8String:packageRoot ? packageRoot : ""],
                @"Chrome", @"Chrome", @"voicememos");
        // The JVM never declares an appearance, so Cocoa falls back to the
        // system LIGHT defaults and every un-themed native widget (text
        // fields, labels, scrollers, dialogs) renders the flat system gray
        // the owner called out. Pin dark Aqua for this process: native
        // defaults darken, owner-drawn surfaces stay exactly as painted.
        NSApp.appearance = [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua];
        if (@available(macOS 10.14, *)) {
            // Also stop AppKit from ever mixing in light defaults on
            // per-window basis (sheets/dialogs come and go).
            for (NSWindow *window in [NSApplication sharedApplication].windows) {
                window.appearance = NSApp.appearance;
            }
        }
    }
}

// ── Screenshot + screen-sharing protection ───────────────────────────────
// Port of the Swift launcher's LauncherCaptureProtection.m: set every BigM
// window's sharingType to None (public API — covers screenshots and
// screen-share picks) AND apply the WindowServer capture-exclusion shape
// (private CGS/SLS SPI — hides from ScreenCaptureKit-style capture too).
// Every launcher in the family protects itself at boot; BigM-Java applies
// it to all current windows now and to every sheet that appears later.

#define BIGM_CG_EXTERN extern

typedef int32_t BigMJavaConnectionID;
typedef CFTypeRef BigMJavaRegionRef;
typedef BigMJavaConnectionID (*BigMJavaMainConnectionFn)(void);
typedef BigMJavaRegionRef (*BigMJavaRegionCreateFn)(CGRect);
typedef int32_t (*BigMJavaCaptureExcludeFn)(BigMJavaConnectionID, NSInteger, BigMJavaRegionRef);

static BigMJavaMainConnectionFn bigmJavaMainConnection;
static BigMJavaRegionCreateFn bigmJavaRegionCreate;
static BigMJavaCaptureExcludeFn bigmJavaCaptureExclude;

static void *BigMJavaFindSymbol(const char *primary, const char *fallback,
                                void *skyLight, void *coreGraphics) {
    void *symbol = dlsym(RTLD_DEFAULT, primary);
    if (!symbol && fallback) symbol = dlsym(RTLD_DEFAULT, fallback);
    if (!symbol && skyLight) {
        symbol = dlsym(skyLight, primary);
        if (!symbol && fallback) symbol = dlsym(skyLight, fallback);
    }
    if (!symbol && coreGraphics) {
        symbol = dlsym(coreGraphics, primary);
        if (!symbol && fallback) symbol = dlsym(coreGraphics, fallback);
    }
    return symbol;
}

static void BigMJavaResolveCaptureSPI(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        void *skyLight = dlopen("/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight",
                                RTLD_LAZY | RTLD_LOCAL);
        void *coreGraphics = dlopen("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
                                    RTLD_LAZY | RTLD_LOCAL);
        bigmJavaMainConnection = (BigMJavaMainConnectionFn)BigMJavaFindSymbol(
                "SLSMainConnectionID", "CGSMainConnectionID", skyLight, coreGraphics);
        bigmJavaRegionCreate = (BigMJavaRegionCreateFn)BigMJavaFindSymbol(
                "CGRegionCreateWithRect", NULL, skyLight, coreGraphics);
        bigmJavaCaptureExclude = (BigMJavaCaptureExcludeFn)BigMJavaFindSymbol(
                "SLSSetWindowCaptureExcludeShape", "CGSSetWindowCaptureExcludeShape",
                skyLight, coreGraphics);
    });
}

static BOOL BigMJavaCaptureDiagnostic(void) {
    const char *diagnostic = getenv("BIGM_DIAGNOSTIC_CAPTURE");
    return diagnostic && strcmp(diagnostic, "1") == 0;
}

static void BigMJavaProtectWindow(NSWindow *window) {
    if (![window isKindOfClass:[NSWindow class]]) return;
    if (BigMJavaCaptureDiagnostic()) {
        window.sharingType = NSWindowSharingReadWrite;
        return;
    }
    window.sharingType = NSWindowSharingNone;
    NSInteger windowNumber = window.windowNumber;
    if (windowNumber <= 0) return;
    BigMJavaResolveCaptureSPI();
    if (!bigmJavaMainConnection || !bigmJavaRegionCreate || !bigmJavaCaptureExclude) return;
    // Window-local coordinates, deliberately bigger than any real display,
    // so the exclusion survives resizes and moves (Swift does the same).
    BigMJavaRegionRef region = bigmJavaRegionCreate(CGRectMake(0, 0, 32768, 32768));
    if (!region) return;
    bigmJavaCaptureExclude(bigmJavaMainConnection(), windowNumber, region);
    CFRelease(region);
}

__attribute__((visibility("default")))
void bigm_java_capture_protect_all(void) {
    @autoreleasepool {
        NSApplication *app = [NSApplication sharedApplication];
        for (NSWindow *window in app.windows) {
            BigMJavaProtectWindow(window);
        }
        static dispatch_once_t onceToken;
        dispatch_once(&onceToken, ^{
            // No public "did become visible" note exists; a shown window
            // always raises BecomeMain/BecomeKey, which covers sheets too.
            id center = [NSNotificationCenter defaultCenter];
            for (NSString *name in @[NSWindowDidBecomeMainNotification,
                                     NSWindowDidBecomeKeyNotification]) {
                [center addObserverForName:name object:nil
                        queue:[NSOperationQueue mainQueue]
                        usingBlock:^(NSNotification *note) {
                            if ([note.object isKindOfClass:[NSWindow class]]) {
                                BigMJavaProtectWindow((NSWindow *)note.object);
                            }
                        }];
            }
        });
    }
}

__attribute__((visibility("default")))
void bigm_java_presentation_apply(void) {
    presentation_apply([NSApplication sharedApplication]);
}

// Live icon swap (Customize sheet's Change Launcher Icon): re-point the
// environment the shared header reads, then reload — the Dock tile changes
// without a relaunch. NULLs reset to the bundled default.
__attribute__((visibility("default")))
void bigm_java_set_custom_icon(const char *lightPath, const char *darkPath, const char *mode) {
    if (lightPath && *lightPath && darkPath && *darkPath) {
        setenv("BIGM_PRESENTATION_ICON_LIGHT", lightPath, 1);
        setenv("BIGM_PRESENTATION_ICON_DARK", darkPath, 1);
        setenv("BIGM_PRESENTATION_ICON_MODE", (mode && *mode) ? mode : "system", 1);
    } else {
        unsetenv("BIGM_PRESENTATION_ICON_LIGHT");
        unsetenv("BIGM_PRESENTATION_ICON_DARK");
        unsetenv("BIGM_PRESENTATION_ICON_MODE");
    }
    presentation_reload();
}

// Render both appearance variants of any app's current icon (NSWorkspace)
// into two 512x512 PNGs. The draw happens under a forced NSAppearance so
// themed icons yield their light/dark artwork.
static NSImage *BigMJavaRenderIconVariant(NSImage *icon, NSSize size, BOOL dark) {
    NSImage *image = [[NSImage alloc] initWithSize:size];
    [image lockFocus];
    NSRect rect = NSMakeRect(0, 0, size.width, size.height);
    // Clear to transparent first — under some appearance contexts the
    // backing store starts opaque black, which would box the icon.
    [[NSColor clearColor] setFill];
    NSRectFillUsingOperation(rect, NSCompositingOperationClear);
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    NSAppearance *previous = [NSAppearance currentAppearance];
    [NSAppearance setCurrentAppearance:[NSAppearance appearanceNamed:
        dark ? NSAppearanceNameDarkAqua : NSAppearanceNameAqua]];
    [icon drawInRect:rect fromRect:NSZeroRect
           operation:NSCompositingOperationSourceOver fraction:1.0f];
    [NSAppearance setCurrentAppearance:previous];
#pragma clang diagnostic pop
    [image unlockFocus];
    return image;
}

static bool BigMJavaWritePNG(NSImage *image, const char *path) {
    if (!image || !path) return false;
    NSString *outPath = [NSString stringWithUTF8String:path];
    NSString *temporary = [outPath stringByAppendingString:@".tmp"];
    NSData *tiff = [image TIFFRepresentation];
    NSBitmapImageRep *bitmap = [[NSBitmapImageRep alloc] initWithData:tiff];
    NSData *png = [bitmap representationUsingType:NSBitmapImageFileTypePNG properties:@{}];
    if (!png) return false;
    if (![png writeToFile:temporary atomically:YES]) return false;
    rename([temporary UTF8String], path);
    return true;
}

// ── Silent keychain read ─────────────────────────────────────────────────
// Token-resolution's last resort is the `gh` CLI's `gh:github.com` generic
// password. Reading another app's item from a different binary normally pops
// the macOS "allow access / unlock" sheet (the old CLI-spawn did it too);
// kSecUseAuthenticationUISkip makes the read tell, not ask: permitted → the
// secret; not permitted → errSecInteractionNotAllowed → NULL. No dialog,
// no spawned binary, no exceptions across the bridge.
// The returned string is malloc'd; release with bigm_java_free_string.
__attribute__((visibility("default")))
void bigm_java_free_string(char *value) {
    free(value);
}

__attribute__((visibility("default")))
char *bigm_java_keychain_read_generic(const char *service, const char *account) {
    if (!service || !*service || !account || !*account) return NULL;
    @autoreleasepool {
        NSDictionary *query = @{
            (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
            (__bridge id)kSecAttrService: [NSString stringWithUTF8String:service],
            (__bridge id)kSecAttrAccount: [NSString stringWithUTF8String:account],
            (__bridge id)kSecReturnData: @YES,
            (__bridge id)kSecMatchLimit: (__bridge id)kSecMatchLimitOne,
            (__bridge id)kSecUseAuthenticationUI:
                    (__bridge id)kSecUseAuthenticationUISkip
        };
        CFTypeRef result = NULL;
        OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &result);
        if (status != errSecSuccess || !result) return NULL;
        NSData *data = (__bridge_transfer NSData *)result;
        if (data.length == 0 || data.length > 8192) return NULL;
        char *copy = (char *)malloc(data.length + 1);
        if (!copy) return NULL;
        memcpy(copy, data.bytes, data.length);
        copy[data.length] = '\0';
        return copy;
    }
}

// ── Surface darkening sweep ──────────────────────────────────────────────
// "No OS-default background anywhere", enforced natively: dark appearance
// on the app and every window, the window background pinned to the scene's
// deepest tone (covers any unpainted flash during resize/overscroll), and
// every NSScrollView told NOT to draw its own plate. Without the scroll
// pass, elastic overscroll shows AppKit's default document backdrop — the
// flat gray slab users reported. New windows are picked up by the
// notification hook, same as capture protection.
static void BigMJavaStripScrollBackdrops(NSView *view) {
    if ([view isKindOfClass:[NSScrollView class]]) {
        ((NSScrollView *)view).drawsBackground = NO;
    }
    for (NSView *sub in [view subviews]) {
        BigMJavaStripScrollBackdrops(sub);
    }
}

static NSColor *BigMJavaSceneFloor(void) {
    // palette.chromeDeep (0x0A/0x0D/0x0C): the scene's grounding tone.
    return [NSColor colorWithSRGBRed:10.0 / 255.0
                               green:13.0 / 255.0
                                blue:12.0 / 255.0
                               alpha:1.0];
}

static void BigMJavaDarkenWindow(NSWindow *window) {
    window.appearance = [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua];
    window.backgroundColor = BigMJavaSceneFloor();
    if (window.contentView) BigMJavaStripScrollBackdrops(window.contentView);
}

__attribute__((visibility("default")))
void bigm_java_surface_darken_all(void) {
    [NSApplication sharedApplication].appearance =
        [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua];
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        id center = [NSNotificationCenter defaultCenter];
        for (NSString *name in @[NSWindowDidBecomeMainNotification,
                                 NSWindowDidBecomeKeyNotification]) {
            [center addObserverForName:name object:nil
                    queue:[NSOperationQueue mainQueue]
                    usingBlock:^(NSNotification *note) {
                        if ([note.object isKindOfClass:[NSWindow class]]) {
                            BigMJavaDarkenWindow((NSWindow *)note.object);
                        }
                    }];
        }
    });
    for (NSWindow *window in [NSApplication sharedApplication].windows) {
        BigMJavaDarkenWindow(window);
    }
}

__attribute__((visibility("default")))
bool bigm_java_render_icon_variants(const char *appPath, const char *lightOut,
                                    const char *darkOut) {
    @autoreleasepool {
        if (!appPath) return false;
        NSString *path = [NSString stringWithUTF8String:appPath];
        NSImage *icon = [[NSWorkspace sharedWorkspace] iconForFile:path];
        if (!icon) return false;
        NSSize size = NSMakeSize(512, 512);
        NSImage *light = BigMJavaRenderIconVariant(icon, size, NO);
        NSImage *dark = BigMJavaRenderIconVariant(icon, size, YES);
        if (!BigMJavaWritePNG(light, lightOut)) return false;
        if (!BigMJavaWritePNG(dark, darkOut)) return false;
        return true;
    }
}
