//
//  PlayerViewModel.m
//  iPlayClient
//
//  Created by 赫拉 on 2024/3/25.
//

#import "PlayerViewModel.h"

static dispatch_queue_t mpvEventRunloop = nil;

@import MPVKit;


//// MPV_EVENT_QUEUE_OVERFLOW
//void on_mpv_wakeup(void *ctx) {
//    __block PlayerViewModel *self = (__bridge PlayerViewModel *)ctx;
//    @weakify(self);
//    dispatch_async(mpvEventRunloop, ^{
//        while (1) {
//            @strongify(self);
//            if (self.mpv == nil) break;
//            mpv_event *event = mpv_wait_event(self.mpv, 0);
//            if (event->event_id == MPV_EVENT_NONE) break;
//            if (event->event_id == MPV_EVENT_SHUTDOWN) {
//                [self destroy];
//                break;
//            }
//            if (self.mpv) {
//                on_mpv_event(self.mpv, event, self);
//            } else {
//                break;
//            }
//        }
//    });
//}

@interface PlayerViewModel ()
@property (nonatomic, weak) id<VideoPlayerDelegate> delegate;
@property (nonatomic, strong) NSString *subtitleFontName;
@end

@implementation PlayerViewModel

+ (void)load {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        dispatch_queue_attr_t attr = dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_CONCURRENT, QOS_CLASS_USER_INITIATED, -1);
        dispatch_queue_t queue = dispatch_queue_create_with_target("mpv-player-queue", attr, NULL);
        mpvEventRunloop = queue;
    });
}

- (instancetype)initWithLayer:(CAMetalLayer *)layer {
    self = [self init];
    if (self) {
        self.drawable = layer;
    }
    return self;
}

- (void)setDrawable:(id)view {
    if ([view isKindOfClass:CAMetalLayer.class]) {
        mpv_handle *mpv = mpv_create();
        mpv_request_log_messages(mpv, "debug");
        mpv_set_option(mpv, "wid", MPV_FORMAT_INT64, &view);
        mpv_set_option_string(mpv, "subs-match-os-language", "yes");
        mpv_set_option_string(mpv, "subs-fallback", "yes");
        mpv_set_option_string(mpv, "vo", "gpu-next");
        mpv_set_option_string(mpv, "gpu-api", "vulkan");
        mpv_set_option_string(mpv, "hwdec", "videotoolbox");
        if (self.subtitleFontName) {
            const char *cFontName = [self.subtitleFontName cStringUsingEncoding:NSUTF8StringEncoding];
            mpv_set_option_string(self.mpv, "sub-font", cFontName);
        }
        mpv_initialize(mpv);
        self.mpv = mpv;

        mpv_observe_property(self.mpv, 0, "time-pos", MPV_FORMAT_DOUBLE);
        mpv_observe_property(self.mpv, 0, "duration", MPV_FORMAT_DOUBLE);
        mpv_observe_property(self.mpv, 0, "video-params/aspect", MPV_FORMAT_DOUBLE);
        mpv_observe_property(self.mpv, 0, "paused-for-cache", MPV_FORMAT_FLAG);
        mpv_observe_property(self.mpv, 0, "pause", MPV_FORMAT_FLAG);
//        mpv_set_wakeup_callback(self.mpv, on_mpv_wakeup, (__bridge void *)self);
        @weakify(self);
        dispatch_async(mpvEventRunloop, ^{
            while (1) {
                @strongify(self);
                mpv_handle *ctx = self.mpv;
                if (!ctx) break;
                mpv_event *event = mpv_wait_event(ctx, 1);
                if (event->event_id == MPV_EVENT_SHUTDOWN) {
                    [self destroy];
                    break;
                }
                
                if (ctx) {
                    [self handleEvent:event];
                } else {
                    break;
                }
            }
        });
        
    } else {
        NSLog(@"view is not kind of CAMetalLayer");
    }
}

- (void)loadVideo:(NSString *)url {
    if (!self.mpv) return;
    const char *cmd[] = {"loadfile", [url cStringUsingEncoding:NSUTF8StringEncoding], "replace", NULL};
    mpv_command(self.mpv, cmd);
}

- (void)resize:(CGSize)size {
//    int64_t dwidth = 0;
//    int64_t dheight = 0;
//    mpv_get_property(self.mpv, "dwidth", MPV_FORMAT_INT64, &dwidth);
//    mpv_get_property(self.mpv, "dheight", MPV_FORMAT_INT64, &dheight);
//    int64_t scaleX = dwidth / size.width;
//    int64_t scaleY = dheight / size.height;
//    mpv_set_property(self.mpv, "video-scale-x", MPV_FORMAT_DOUBLE, &scaleX);
//    mpv_set_property(self.mpv, "video-scale-y", MPV_FORMAT_DOUBLE, &scaleY);
}

- (void)play {
    if (!self.mpv) return;
    const char *cmd[] = {"play", NULL};
    mpv_command(self.mpv, cmd);
}

- (void)stop {
    if (!self.mpv) return;
    const char *cmd[] = {"stop", NULL};
    mpv_command(self.mpv, cmd);
}

- (void)volumeUp:(CGFloat)percent {
    if (!self.mpv) return;
    double volume;
    mpv_get_property(self.mpv, "volume", MPV_FORMAT_DOUBLE, &volume);
    volume += 100 * percent;
    if (volume > 100) return;
    mpv_set_property(self.mpv, "volume", MPV_FORMAT_DOUBLE, &volume);
}

- (void)volumeDown:(CGFloat)percent {
    if (!self.mpv) return;
    double volume;
    mpv_get_property(self.mpv, "volume", MPV_FORMAT_DOUBLE, &volume);
    volume -= 100 * percent;
    if (volume > 100) return;
    mpv_set_property(self.mpv, "volume", MPV_FORMAT_DOUBLE, &volume);
}

- (void)jumpBackward:(NSUInteger)seconds {
    if (!self.mpv) return;
    const char* pos = [@(-seconds).stringValue cStringUsingEncoding:NSUTF8StringEncoding];
    const char *cmd[] = {"seek", pos, "relative", NULL};
    mpv_command(self.mpv, cmd);
}

- (void)jumpForward:(NSUInteger)seconds {
    if (!self.mpv) return;
    const char* pos = [@(seconds).stringValue cStringUsingEncoding:NSUTF8StringEncoding];
    const char *cmd[] = {"seek", pos, "relative", NULL};
    mpv_command(self.mpv, cmd);
}

- (void)seek:(NSUInteger)timeSeconds {
    if (!self.mpv) return;
    const char* pos = [@(timeSeconds).stringValue cStringUsingEncoding:NSUTF8StringEncoding];
    const char *cmd[] = {"seek", pos, "absolute+keyframes", NULL};
    mpv_command(self.mpv, cmd);
}

- (void)pause {
    if (!self.mpv) return;
    const char *cmd[] = {"cycle", "pause", NULL};
    mpv_command(self.mpv, cmd);
}

- (void)setSubtitleFont:(NSString *)fontName {
    _subtitleFontName = fontName;
    if (!self.mpv) return;
    const char *cFontName = [fontName cStringUsingEncoding:NSUTF8StringEncoding];
    mpv_set_option_string(self.mpv, "sub-font", cFontName);
}

- (void)resume {
    if (!self.mpv) return;
    int flag = 0;
    mpv_set_property(self.mpv, "pause", MPV_FORMAT_FLAG, &flag);
}

- (void)keepaspect {
    if (!self.mpv) return;
    int flag = -1;
    mpv_set_property(self.mpv, "keepaspect", MPV_FORMAT_FLAG, &flag);
}

- (void)quit {
    if (!self.mpv) return;
    mpv_unobserve_property(self.mpv, 0);
    const char *cmd[] = {"quit", NULL};
    mpv_command(self.mpv, cmd);
}

- (void)destroy {
    if (_mpv) {
        mpv_set_option_string(_mpv, "vo", "null");
        mpv_destroy(_mpv);
        _mpv = nil;
    }
}

- (void)onProgressUpdate:(double)time {
    [self.delegate onPlayEvent:PlayEventTypeOnProgress data:@{
        @"time": @(time)
    }];
}

- (void)onDurationUpdate:(double)time {
    self.duration = time;
    [self.delegate onPlayEvent:PlayEventTypeDuration data:@{
        @"duration": @(time)
    }];
}

- (void)onPlaystateUpdate:(PlayEventType)type
                    state:(int)state {
    self.isPlaying = state == 0;
    [self.delegate onPlayEvent:type data:@{
        @"state": @(state)
    }];
}

- (void)handleEvent:(mpv_event *)event {
    if (event->event_id == MPV_EVENT_PROPERTY_CHANGE) {
        mpv_event_property *prop = event->data;
        if (strcmp(prop->name, "time-pos") == 0) {
            if (prop->format == MPV_FORMAT_DOUBLE) {
                [self onProgressUpdate:*(double *)prop->data];
            }
        } else if (strcmp(prop->name, "duration") == 0) {
            if (prop->format == MPV_FORMAT_DOUBLE) {
                [self onDurationUpdate:*(double *)prop->data];
            }
        } else if (strcmp(prop->name, "pause") == 0) {
            if (prop->format == MPV_FORMAT_FLAG) {
                [self onPlaystateUpdate:PlayEventTypeOnPause state:*(int *)prop->data];
            }
        } else if (strcmp(prop->name, "paused-for-cache") == 0) {
            if (prop->format == MPV_FORMAT_FLAG) {
                [self onPlaystateUpdate:PlayEventTypeOnPauseForCache state:*(int *)prop->data];
            }
        }
    }
}

@end
