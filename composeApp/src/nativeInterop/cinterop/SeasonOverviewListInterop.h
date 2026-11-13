#pragma once

#import <UIKit/UIKit.h>

// Implemented by @_cdecl in iosApp/iosApp/SeasonOverviewListView.swift.
// Kotlin pushes a read-only payload. Swift calls these function pointers back.

typedef void (*NuvioSeasonOverviewKeyAction)(const char *contentKey);
typedef void (*NuvioSeasonOverviewFlagAction)(int flag);
typedef void (*NuvioSeasonOverviewVoidAction)(void);

void NuvioSeasonOverviewBind(
    NuvioSeasonOverviewKeyAction onOpen,
    NuvioSeasonOverviewKeyAction onWatchPrivately,
    NuvioSeasonOverviewKeyAction onInclude,
    NuvioSeasonOverviewFlagAction onSelecting,
    NuvioSeasonOverviewVoidAction onDownload,
    NuvioSeasonOverviewVoidAction onCancel
);
void NuvioSeasonOverviewApply(const char *payload);
UIView * _Nonnull NuvioSeasonOverviewRootView(void);
