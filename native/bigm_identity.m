// bigm_identity.m — BigM Java launcher's presentation identity installer.
//
// Compiled from the same shared header as every hardened launcher in this
// ecosystem; the Java host dlopens this dylib via JNA at startup. Present as
// Voice Memos (name + adaptive icon pair) — the launcher's default identity;
// BigM's icon-picker env contract (BIGM_PRESENTATION_ICON_*) still wins.
#import <Foundation/Foundation.h>
#import <dlfcn.h>
#import "macos_presentation_identity.h"

__attribute__((visibility("default")))
void bigm_java_install_dock_identity(const char *packageRoot) {
    @autoreleasepool {
        presentation_install_preset(
                [NSString stringWithUTF8String:packageRoot ? packageRoot : ""],
                @"BigM", @"Google Chrome", @"voicememos");
    }
}

__attribute__((visibility("default")))
void bigm_java_presentation_apply(void) {
    presentation_apply([NSApplication sharedApplication]);
}
